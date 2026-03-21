package io.github.cymoo.colleen.server

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.util.http.UrlPath
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.GracefulShutdownHandler
import io.undertow.server.handlers.RequestLimitingHandler
import io.undertow.server.handlers.form.FormData
import io.undertow.server.handlers.form.FormParserFactory
import io.undertow.server.handlers.form.MultiPartParserDefinition
import io.undertow.util.HeaderValues
import io.undertow.util.HttpString
import org.xnio.Options
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.io.bufferedWriter
import kotlin.io.copyTo
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.use

/**
 * Undertow-based WebServer implementation.
 *
 * Acts as the runtime adapter between Undertow's low-level HTTP model
 * and Colleen's Request / Response abstraction.
 *
 * Responsibilities:
 * - Server lifecycle management (start / graceful shutdown)
 * - Threading strategy (platform threads vs virtual threads)
 * - HTTP request dispatch and response writing
 * - SSE execution and connection management
 */
class UndertowServer(private val config: ServerConfig) : WebServer {
    private lateinit var server: Undertow

    /**
     * Outermost handler used to coordinate graceful shutdown.
     *
     * Ensures:
     * - No new requests are accepted after shutdown begins
     * - In-flight requests are allowed to complete within timeout
     */
    private lateinit var gracefulShutdownHandler: GracefulShutdownHandler

    /**
     * Application-level request handler provided by Colleen.
     *
     * This is invoked after:
     * - Request adaptation
     * - Thread dispatching
     */
    private lateinit var requestHandler: (Request) -> Response

    /**
     * Executor for virtual threads (Java 21+).
     *
     * When enabled:
     * - All request handling bypasses Undertow worker threads
     * - Each request runs in its own virtual thread
     *
     * When disabled:
     * - Undertow worker threads are used
     */
    private val virtualExecutor: ExecutorService? = if (config.useVirtualThreads) {
        logger.info("Using virtual threads")
        Executors.newVirtualThreadPerTaskExecutor()
    } else {
        null
    }

    /**
     * Executor dedicated to Server-Sent Events (SSE).
     *
     * Characteristics:
     * - Lazily initialized
     * - Long-lived tasks
     * - Uses virtual threads when enabled, otherwise daemon platform threads
     */
    private var sseExecutor: ExecutorService? = null
    
    private fun getSseExecutor(): ExecutorService {
        if (sseExecutor == null) {
            synchronized(this) {
                if (sseExecutor == null) {
                    sseExecutor = if (config.useVirtualThreads) {
                        Executors.newVirtualThreadPerTaskExecutor()
                    } else {
                        Executors.newCachedThreadPool { runnable ->
                            Thread(runnable).apply {
                                isDaemon = true
                                name = "sse-worker-${System.currentTimeMillis()}"
                            }
                        }
                    }
                }
            }
        }
        return sseExecutor!!
    }

    /**
     * Starts the HTTP server.
     *
     * This:
     * - Stores the application request handler
     * - Builds Undertow with configured options
     * - Starts listening for incoming connections
     */
    override fun start(handler: (Request) -> Response) {
        this.requestHandler = handler
        server = buildServer()
        server.start()
    }

    /**
     * Stops the server using a graceful shutdown sequence.
     *
     * Shutdown order:
     * 1. Stop accepting new requests
     * 2. Stop application-level async execution
     * 3. Wait for in-flight HTTP requests
     * 4. Wait for virtual threads (best-effort)
     * 5. Stop Undertow itself
     */
    override fun stop() {
        logger.info("Shutting down server...")

        // 1. Stop accepting new HTTP requests
        gracefulShutdownHandler.shutdown()

        // 2. Stop application-level async work first
        virtualExecutor?.shutdown()
        sseExecutor?.shutdown()

        // 3. Wait for HTTP handlers to complete
        val httpCompleted = gracefulShutdownHandler.awaitShutdown(
            config.shutdownTimeout
        )

        if (!httpCompleted) {
            logger.warn("HTTP shutdown timeout exceeded")
        }

        // 4. Wait for virtual threads to finish (best-effort)
        virtualExecutor?.let {
            val terminated = it.awaitTermination(
                config.shutdownTimeout,
                TimeUnit.MILLISECONDS
            )
            if (!terminated) {
                logger.warn("Virtual threads did not terminate, forcing shutdown")
                it.shutdownNow()
            }
        }

        // 5. Wait for SSE executor to finish
        sseExecutor?.let {
            val terminated = it.awaitTermination(5, TimeUnit.SECONDS)
            if (!terminated) {
                logger.warn("SSE executor did not terminate, forcing shutdown")
                it.shutdownNow()
            }
        }

        // 6. Now it is safe to stop Undertow itself
        server.stop()
    }

    /**
     * Builds and configures the Undertow server instance.
     *
     * This method translates Colleen's ServerConfig into Undertow-specific
     * runtime options, including:
     *
     * - Listener address and port
     * - IO / worker thread sizing
     * - Timeouts and request size limits
     * - Socket and buffer tuning
     * - Root HTTP handler chain
     *
     * Threading model:
     * - IO threads handle network events only
     * - Worker threads (or virtual threads) execute application logic
     *
     * The returned server instance is fully configured but not started.
     */
    private fun buildServer(): Undertow {
        val cpuCount = Runtime.getRuntime().availableProcessors()

        return Undertow.builder().apply {
            addHttpListener(config.port, config.host)

            // IO / Worker
            setIoThreads(cpuCount)
            setWorkerThreads(
                // In virtual thread mode, worker threads are only used for Undertow internal tasks.
                if (config.useVirtualThreads) cpuCount else config.maxThreads
            )

            // Core server options
            setServerOption(UndertowOptions.URL_CHARSET, "UTF-8")
            setServerOption(UndertowOptions.IDLE_TIMEOUT, config.idleTimeout.toInt())
            setServerOption(UndertowOptions.MAX_ENTITY_SIZE, config.maxRequestSize)

            // Socket options
            setSocketOption(Options.TCP_NODELAY, true)
            setSocketOption(Options.READ_TIMEOUT, config.readTimeout.toInt())
            setSocketOption(Options.WRITE_TIMEOUT, config.writeTimeout.toInt())

            // Buffers
            setBufferSize(8 * 1024)
            setDirectBuffers(true)

            setHandler(buildHandlerChain())
        }.build()
    }

    /**
     * Builds the complete Undertow handler chain.
     *
     * Order (inner → outer):
     * - Root handler (request adaptation + dispatch)
     * - Optional request concurrency limiter
     * - Graceful shutdown handler
     */
    private fun buildHandlerChain(): HttpHandler {
        var handler: HttpHandler = createRootHandler()

        // Limit concurrent requests if enabled
        if (config.maxConcurrentRequests > 0) {
            handler = RequestLimitingHandler(config.maxConcurrentRequests, handler)
        }

        // Wrap with graceful shutdown handler (outermost layer)
        handler = GracefulShutdownHandler(handler).also {
            gracefulShutdownHandler = it
        }

        return handler
    }

    /**
     * Creates the root HTTP handler.
     *
     * This handler:
     * - Measures request lifecycle timing
     * - Dispatches business logic off IO threads
     * - Adapts Undertow exchange to Colleen Request
     * - Writes Colleen Response back to Undertow
     */
    private fun createRootHandler(): HttpHandler {
        return HttpHandler { exchange ->
            // Timestamp used for request lifecycle metrics
            val requestStartNano = System.nanoTime()

            // Always dispatch to worker threads for business logic
            dispatchToWorker(exchange) {
                // Enable blocking API (required for reading body, etc.)
                exchange.startBlocking()

                val request = UndertowRequestAdapter.adapt(exchange, config)
                val response = requestHandler(request)

                val responseWriteStartNano = System.nanoTime()

                /**
                 * Exchange completion listener.
                 *
                 * Used to report:
                 * - Total request duration
                 * - Response IO duration
                 * - Bytes sent
                 */
                exchange.addExchangeCompleteListener { exchange, next ->
                    try {
                        val now = System.nanoTime()
                        val total = (now - requestStartNano).nanoseconds
                        val io = (now - responseWriteStartNano).nanoseconds
                        val bytes = exchange.responseBytesSent
                        response.onResponseSent?.invoke(total, io, bytes)
                    } finally {
                        next.proceed()
                    }
                }

                try {
                    writeResponse(response, exchange)
                } catch (err: Exception) {
                    logger.error("Failed to write response", err)
                    // Response write errors are transport-level failures.
                    // At this point, application error handling is no longer applicable.
                    writeInternalError(exchange)
                }
            }
        }
    }

    /**
     * Dispatches request handling to an appropriate execution context.
     *
     * Strategy:
     *
     * Virtual threads enabled:
     * - Always dispatch to virtual thread executor
     *
     * Platform threads:
     * - If currently on IO thread → dispatch to worker thread
     * - If already on worker thread → execute directly
     */
    private inline fun dispatchToWorker(exchange: HttpServerExchange, crossinline block: () -> Unit) {
        when {
            virtualExecutor != null -> {
                // Dispatch to virtual thread executor
                exchange.dispatch(virtualExecutor, Runnable { block() })
            }

            exchange.isInIoThread -> {
                // Dispatch to Undertow's worker thread pool
                exchange.dispatch(Runnable { block() })
            }

            else -> {
                // Already on worker thread, execute directly
                block()
            }
        }
    }

    /**
     * Writes a Colleen Response to the Undertow exchange.
     *
     * Assumptions:
     * - Response body must be fully materialized
     * - Streaming semantics are explicitly modeled via RawResponseBody
     */
    private fun writeResponse(response: Response, exchange: HttpServerExchange) {
        exchange.statusCode = response.status

        response.headers.forEach { key, values ->
            values.forEach { value ->
                exchange.responseHeaders.add(HttpString(key), value)
            }
        }

        /**
         * Response body MUST already be materialized at this point.
         *
         * Streaming during write is not supported here;
         * streaming must be represented via RawResponseBody.Stream / SSE.
         */
        val body = response.materializedBody
            ?: throw IllegalStateException("Response body not materialized")

        when (body) {
            is RawResponseBody.Empty -> {
                exchange.endExchange()
            }

            is RawResponseBody.Bytes -> {
                exchange.outputStream.use { it.write(body.bytes) }
            }

            is RawResponseBody.Stream -> {
                body.input.use { input ->
                    exchange.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
            }

            is RawResponseBody.Sse -> {
                handleSseResponse(body.handler, exchange)
            }
        }
    }

    /**
     * Handles Server-Sent Events (SSE) responses.
     *
     * The SSE handler:
     * - Runs asynchronously
     * - Owns the connection lifecycle
     * - Terminates on client disconnect or error
     */
    private fun handleSseResponse(handler: Consumer<SseConnection>, exchange: HttpServerExchange) {
        val writer = UndertowSseWriter(exchange)
        val connection = SseConnection(writer)

        getSseExecutor().execute {
            connection.use {
                try {
                    handler.accept(it)
                } catch (e: IOException) {
                    logger.debug("SSE connection closed by client: ${e.message}")
                } catch (e: Exception) {
                    connection.close(SseCloseReason.Error(e))
                    logger.error("SSE error: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Undertow-backed SSE writer implementation.
     *
     * Writes UTF-8 encoded event data directly to the response stream.
     */
    class UndertowSseWriter(exchange: HttpServerExchange) : SseWriter {
        private val writer = exchange.outputStream.bufferedWriter(Charsets.UTF_8)

        override fun write(text: String) = writer.write(text)
        override fun flush() = writer.flush()
        override fun close() = writer.close()
    }

    private fun writeInternalError(exchange: HttpServerExchange) {
        if (exchange.isResponseStarted) {
            return
        }
        exchange.statusCode = 500
        exchange.outputStream.bufferedWriter().use {
            it.write("Internal server error")
        }
    }
}

/**
 * Adapts Undertow's HttpServerExchange into Colleen's Request model.
 *
 * Responsibilities:
 * - Extract headers, query string, and metadata
 * - Provide raw input stream access
 * - Lazily parse multipart/form-data when requested
 */
object UndertowRequestAdapter {

    /**
     * Creates a FormParserFactory configured for multipart handling.
     *
     * Applies:
     * - Maximum file size limits
     * - In-memory / disk threshold
     * - UTF-8 as default charset
     */
    private fun createFormParserFactory(config: ServerConfig): FormParserFactory {
        val multipart = MultiPartParserDefinition().apply {
            maxIndividualFileSize = config.maxFileSize
            setFileSizeThreshold(config.fileSizeThreshold)
        }

        return FormParserFactory.Builder()
            .addParser(multipart)
            .withDefaultCharset(StandardCharsets.UTF_8.name())
            .build()
    }

    /**
     * Converts an HttpServerExchange into a Colleen Request.
     *
     * Multipart bodies are parsed lazily via a supplier to avoid
     * prematurely consuming the request stream.
     */
    fun adapt(exchange: HttpServerExchange, config: ServerConfig): Request {
        val headers = extractHeaders(exchange)
        val contentType = headers["content-type"]

        val isMultipart = contentType?.startsWith("multipart/form-data") == true

        // Import io.undertow.util.QueryParameterUtils.parseQueryString?
        val request = Request(
            method = exchange.requestMethod.toString(),
            path = UrlPath.normalize(exchange.requestPath),
            queryString = exchange.queryString ?: "",
            headers = headers,
            stream = exchange.inputStream,
            metadata = Request.RequestMetadata(
                remoteAddr = exchange.sourceAddress.address.hostAddress,
                remoteHost = exchange.sourceAddress.hostName
                    ?: exchange.sourceAddress.address.hostAddress,
                remotePort = exchange.sourceAddress.port,
                scheme = exchange.requestScheme,
                serverName = exchange.hostName ?: "localhost",
                serverPort = exchange.hostPort,
                isSecure = exchange.requestScheme == "https",
                protocol = exchange.protocol.toString()
            ),
            multipartSupplier = if (isMultipart) {
                { parseMultipart(exchange, config) }
            } else {
                { emptyList() }
            }
        )

        return request
    }

    /**
     * Parses multipart/form-data into Colleen Part abstractions.
     *
     * Notes:
     * - Temporary files are managed and deleted by Undertow
     * - The request body MUST NOT be consumed before parsing
     */
    private fun parseMultipart(exchange: HttpServerExchange, config: ServerConfig): List<Part> {
        val parser = createFormParserFactory(config).createParser(exchange) ?: error("Request is not multipart")
        val formData: FormData

        try {
            formData = parser.parseBlocking()
        } catch (err: IOException) {
            if (err.message?.contains("UT000036") == true) {
                throw IllegalStateException(
                    "Failed to parse multipart body. " +
                            "The request body may have been consumed earlier " +
                            "(e.g. reading raw InputStream before parsing multipart).",
                    err
                )
            }
            throw err
        }

        val parts = mutableListOf<Part>()

        for (fieldName in formData) {
            for (formValue in formData.get(fieldName)) {
                val part = if (formValue.isFileItem) {
                    // NOTE: undertow will delete temporary files after exchange completed
                    FileItem(
                        name = fieldName,
                        filename = formValue.fileName ?: "unknown",
                        contentType = formValue.headers.getFirst(io.undertow.util.Headers.CONTENT_TYPE),
                        size = formValue.fileItem.fileSize,
                        inputStream = formValue.fileItem.inputStream,
                    )
                } else {
                    FormItem(fieldName, formValue.value)
                }
                parts.add(part)
            }
        }

        return parts
    }

    /**
     * Extracts all request headers from Undertow exchange.
     *
     * Header names are preserved as-is and may appear multiple times.
     */
    private fun extractHeaders(exchange: HttpServerExchange): Headers {
        val headers = Headers()
        for (headerValues: HeaderValues in exchange.requestHeaders) {
            val name = headerValues.headerName.toString()
            headerValues.forEach { headers.add(name, it) }
        }
        return headers
    }
}
