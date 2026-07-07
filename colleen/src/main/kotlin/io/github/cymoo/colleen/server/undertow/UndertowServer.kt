package io.github.cymoo.colleen.server.undertow

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.server.WebServer
import io.github.cymoo.colleen.ws.WsConfig
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.GracefulShutdownHandler
import io.undertow.server.handlers.RequestLimitingHandler
import io.undertow.util.HttpString
import io.undertow.util.Methods
import org.xnio.Options
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

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
 * - WebSocket upgrade delegation
 */
class UndertowServer(private val config: ServerConfig, private val wsConfig: WsConfig) : WebServer {
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
     *
     * Exposed as a separate [Lazy] to allow checking [Lazy.isInitialized]
     * during shutdown without accidentally triggering initialization.
     */
    private val lazySseExecutor = lazy {
        if (config.useVirtualThreads) {
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
    private val sseExecutor: ExecutorService by lazySseExecutor

    /**
     * WebSocket handler responsible for all WS lifecycle management.
     */
    private val wsHandler = UndertowWsHandler(config, wsConfig)

    /**
     * Active SSE connections, tracked so graceful shutdown can signal them to
     * close (symmetric with WebSocket handling). Without this, long-lived SSE
     * handlers would only learn about shutdown via thread interruption after
     * the executor timeout.
     */
    private val activeSseConnections: MutableSet<SseConnection> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

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
     * 2. Wait for in-flight HTTP requests to complete
     * 3. Shut down virtual executor (no new dispatch possible after step 2)
     * 4. Wait for virtual threads (best-effort)
     * 5. Gracefully close all active WebSocket connections
     * 6. Shut down WebSocket infrastructure (ping scheduler, executor)
     * 7. Shut down SSE executor (if initialized)
     * 8. Stop Undertow itself
     *
     * Note: virtualExecutor.shutdown() MUST be called AFTER
     * gracefulShutdownHandler.awaitShutdown() completes.
     * Otherwise, in-flight requests accepted before step 1 may
     * fail with RejectedExecutionException when they attempt to
     * dispatch to the already-shut-down executor.
     */
    override fun stop() {
        logger.info("Shutting down server...")

        // 1. Stop accepting new HTTP requests
        gracefulShutdownHandler.shutdown()

        // 2. Wait for in-flight HTTP handlers to complete
        val httpCompleted = gracefulShutdownHandler.awaitShutdown(config.shutdownTimeout)
        if (!httpCompleted) {
            logger.warn("HTTP shutdown timeout exceeded")
        }

        // 3. Shut down virtual executor (should be idle after step 2)
        virtualExecutor?.shutdown()

        // 4. Brief wait as a safety net — normally returns immediately
        virtualExecutor?.let {
            if (!it.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.warn("Virtual threads did not terminate, forcing shutdown")
                it.shutdownNow()
            }
        }

        // 5. Gracefully close all active WebSocket connections
        wsHandler.closeAllConnections()

        // 6. Shut down WebSocket infrastructure
        wsHandler.shutdown(config.shutdownTimeout)

        // 7. Signal all active SSE connections to close, then shut down the executor.
        // Closing first lets handler loops (`while (!conn.isClosed) ...`) exit
        // cooperatively instead of waiting for shutdownNow() interruption.
        //
        // Each close() runs on its own virtual thread with a bounded wait:
        // close() contends with in-flight blocking writes (see issue #25), so a
        // single stuck client must not be able to hang stop(). Connections that
        // miss the deadline are torn down by Undertow's stop below.
        val sseCloseThreads = ArrayList(activeSseConnections).map { conn ->
            Thread.startVirtualThread {
                runCatching { conn.close(SseCloseReason.ServerShutdown) }
            }
        }
        val sseCloseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        for (thread in sseCloseThreads) {
            val remainingMs = (sseCloseDeadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            runCatching { thread.join(remainingMs) }
        }
        if (lazySseExecutor.isInitialized()) {
            sseExecutor.shutdown()
            if (!sseExecutor.awaitTermination(config.shutdownTimeout, TimeUnit.MILLISECONDS)) {
                logger.warn("SSE executor did not terminate, forcing shutdown")
                sseExecutor.shutdownNow()
            }
        }

        // 8. Stop Undertow
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
            // IDLE_TIMEOUT is an Int option; clamp to avoid silent overflow for
            // extreme Long configs (> ~24.8 days)
            setServerOption(UndertowOptions.IDLE_TIMEOUT, config.idleTimeout.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            setServerOption(UndertowOptions.MAX_ENTITY_SIZE, config.maxRequestSize)

            // Socket options
            setSocketOption(Options.TCP_NODELAY, true)

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

                // Request adaptation happens before the framework's error handling
                // is available; malformed ingress data (e.g. a path containing ".."
                // segments) must be rejected here as 400, not surface as a 500.
                val request = try {
                    UndertowRequestAdapter.adapt(exchange, config)
                } catch (e: IllegalArgumentException) {
                    logger.debug("Rejected malformed request: {}", e.message)
                    writeBadRequest(exchange)
                    return@dispatchToWorker
                }

                // The framework handles Exceptions itself; anything escaping here
                // (Errors, direct Throwable subclasses) must still be logged and
                // answered, otherwise the exchange would hang until timeout.
                val response = try {
                    requestHandler(request)
                } catch (e: Throwable) {
                    logger.error("Unhandled throwable escaped request handling", e)
                    writeInternalError(exchange)
                    if (e is Error) throw e
                    return@dispatchToWorker
                }

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

        // RFC 9110 §9.3.2: a HEAD response carries the same headers as GET
        // (including Content-Length) but MUST NOT include a body. This covers
        // both explicit HEAD handlers and HEAD served from a GET route.
        val isHead = exchange.requestMethod.equals(Methods.HEAD)

        when (body) {
            is RawResponseBody.Empty -> {
                exchange.endExchange()
            }

            is RawResponseBody.Bytes -> {
                // The length is known; set Content-Length explicitly, otherwise
                // Undertow falls back to chunked encoding for bodies larger than
                // its internal buffer (8KB).
                if (!response.headers.has("Content-Length")) {
                    exchange.setResponseContentLength(body.bytes.size.toLong())
                }
                if (isHead) {
                    exchange.endExchange()
                } else {
                    exchange.outputStream.use { it.write(body.bytes) }
                }
            }

            is RawResponseBody.Stream -> {
                if (isHead) {
                    runCatching { body.input.close() }
                    exchange.endExchange()
                } else {
                    body.input.use { input ->
                        exchange.outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            is RawResponseBody.Sse -> {
                if (isHead) {
                    // Headers only — the long-lived SSE handler must not run
                    exchange.endExchange()
                } else {
                    handleSseResponse(body.handler, exchange)
                }
            }

            is RawResponseBody.WebSocket -> {
                wsHandler.handleUpgrade(
                    body.handler, body.pathParams, body.queryParams,
                    body.app, body.states, body.headers, exchange
                )
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
    private fun handleSseResponse(handler: java.util.function.Consumer<SseConnection>, exchange: HttpServerExchange) {
        val writer = UndertowSseWriter(exchange)
        val connection = SseConnection(writer)

        activeSseConnections.add(connection)
        connection.onClose { activeSseConnections.remove(connection) }

        sseExecutor.execute {
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

    private fun writeInternalError(exchange: HttpServerExchange) {
        if (exchange.isResponseStarted) {
            return
        }
        exchange.statusCode = 500
        exchange.outputStream.bufferedWriter().use {
            it.write("Internal server error")
        }
    }

    private fun writeBadRequest(exchange: HttpServerExchange) {
        if (exchange.isResponseStarted) {
            return
        }
        exchange.statusCode = 400
        exchange.outputStream.bufferedWriter().use {
            it.write("Bad Request")
        }
    }
}
