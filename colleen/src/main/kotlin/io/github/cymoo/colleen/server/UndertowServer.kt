package io.github.cymoo.colleen.server

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.util.http.UrlPath
import io.github.cymoo.colleen.ws.WsChannel
import io.github.cymoo.colleen.ws.WsCloseReason
import io.github.cymoo.colleen.ws.WsConnection
import io.github.cymoo.colleen.ws.WsMessage
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
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.WebSocketProtocolHandshakeHandler
import io.undertow.websockets.core.*
import io.undertow.websockets.spi.WebSocketHttpExchange
import org.xnio.ChannelListener
import org.xnio.Options
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
class UndertowServer(private val config: ServerConfig, private val wsConfig: io.github.cymoo.colleen.ws.WsConfig) : WebServer {
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

    // ========================================================================
    // WebSocket Infrastructure
    // ========================================================================

    /**
     * Executor for WebSocket message dispatch.
     *
     * Messages are dispatched from IO threads to this executor via per-connection
     * [OrderedExecutor] wrappers to ensure sequential processing per connection.
     *
     * Lazily initialized on first WebSocket connection.
     */
    private val lazyWsExecutor = lazy {
        if (config.useVirtualThreads) {
            Executors.newVirtualThreadPerTaskExecutor()
        } else {
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable).apply {
                    isDaemon = true
                    name = "ws-worker-${threadCounter.incrementAndGet()}"
                }
            }
        }
    }
    private val wsExecutor: ExecutorService by lazyWsExecutor
    private val threadCounter = AtomicInteger(0)

    /**
     * Tracks all active WebSocket connections for graceful shutdown.
     */
    private val activeWsConnections: MutableSet<WsConnection> = ConcurrentHashMap.newKeySet()

    /**
     * Tracks the number of active WebSocket connections for connection limiting.
     */
    private val wsConnectionCount = AtomicInteger(0)

    /**
     * Scheduler for WebSocket ping/pong heartbeat.
     *
     * Sends periodic ping frames and detects dead connections.
     * Only initialized when [io.github.cymoo.colleen.ws.WsConfig.pingIntervalMs] > 0.
     */
    private val lazyPingScheduler = lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable).apply {
                isDaemon = true
                name = "ws-ping-scheduler"
            }
        }
    }
    private val pingScheduler: ScheduledExecutorService by lazyPingScheduler

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
     * 6. Shut down WebSocket ping scheduler (if initialized)
     * 7. Shut down WebSocket executor (if initialized)
     * 8. Shut down SSE executor (if initialized)
     * 9. Stop Undertow itself
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
        val snapshot = ArrayList(activeWsConnections)
        snapshot.forEach { conn ->
            runCatching { conn.close(WsCloseReason.Normal) }
        }
        activeWsConnections.clear()

        // 6. Shut down WebSocket ping scheduler
        if (lazyPingScheduler.isInitialized()) {
            pingScheduler.shutdown()
            if (!pingScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                pingScheduler.shutdownNow()
            }
        }

        // 7. Shut down WebSocket executor
        if (lazyWsExecutor.isInitialized()) {
            wsExecutor.shutdown()
            if (!wsExecutor.awaitTermination(config.shutdownTimeout, TimeUnit.MILLISECONDS)) {
                logger.warn("WebSocket executor did not terminate, forcing shutdown")
                wsExecutor.shutdownNow()
            }
        }

        // 8. Gracefully shut down SSE executor if initialized
        if (lazySseExecutor.isInitialized()) {
            sseExecutor.shutdown()
            if (!sseExecutor.awaitTermination(config.shutdownTimeout, TimeUnit.MILLISECONDS)) {
                logger.warn("SSE executor did not terminate, forcing shutdown")
                sseExecutor.shutdownNow()
            }
        }

        // 9. Stop Undertow
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

            is RawResponseBody.WebSocket -> {
                handleWebSocketUpgrade(body.handler, body.pathParams, body.queryParams, body.app, body.states, body.headers, exchange)
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

    // ========================================================================
    // WebSocket Handling
    // ========================================================================

    /**
     * Handles a WebSocket upgrade request.
     *
     * Uses Undertow's WebSocketProtocolHandshakeHandler to perform the
     * HTTP→WebSocket upgrade, then bridges the Undertow WebSocketChannel
     * to Colleen's WsConnection abstraction.
     *
     * Features:
     * - Connection limit enforcement
     * - Active connection tracking for graceful shutdown
     * - Per-connection ordered message dispatch (off IO thread)
     * - Ping/pong heartbeat for dead connection detection
     * - Proper 1009 close code for oversized messages
     */
    private fun handleWebSocketUpgrade(
        handler: Consumer<WsConnection>,
        pathParams: Map<String, String>,
        queryParams: Map<String, List<String>>,
        app: Colleen?,
        states: Map<String, Any?>,
        headers: Headers,
        exchange: HttpServerExchange,
    ) {
        val callback = WebSocketConnectionCallback { _: WebSocketHttpExchange, wsChannel: WebSocketChannel ->
            // Check connection limit
            val current = wsConnectionCount.incrementAndGet()
            if (wsConfig.maxConnections > 0 && current > wsConfig.maxConnections) {
                wsConnectionCount.decrementAndGet()
                runCatching {
                    WebSockets.sendCloseBlocking(1013, "Try Again Later", wsChannel)
                }
                return@WebSocketConnectionCallback
            }

            // Apply WS configuration
            wsChannel.idleTimeout = wsConfig.idleTimeoutMs

            // Create the Colleen WsChannel adapter
            val channel = UndertowWsChannel(wsChannel)
            val connection = WsConnection(channel, pathParams, queryParams, app, states.toMutableMap(), headers)

            // Track connection for graceful shutdown
            activeWsConnections.add(connection)

            // Create per-connection ordered executor for message dispatch
            val orderedDispatcher = OrderedExecutor(wsExecutor)

            // Invoke user handler to set up callbacks
            try {
                handler.accept(connection)
            } catch (e: Exception) {
                logger.error("WebSocket handler setup failed: ${e.message}", e)
                connection.close(WsCloseReason.Error(e))
                return@WebSocketConnectionCallback
            }

            // Schedule ping/pong heartbeat
            var pingFuture: ScheduledFuture<*>? = null
            if (wsConfig.pingIntervalMs > 0) {
                val lastPong = AtomicLong(System.currentTimeMillis())

                pingFuture = pingScheduler.scheduleAtFixedRate({
                    try {
                        if (connection.isClosed) return@scheduleAtFixedRate
                        val sinceLastPong = System.currentTimeMillis() - lastPong.get()
                        if (sinceLastPong > wsConfig.pingIntervalMs + wsConfig.pingTimeoutMs) {
                            // Pong timeout — close the dead connection
                            connection.close(WsCloseReason.Protocol(1001, "Ping timeout"))
                            return@scheduleAtFixedRate
                        }
                        WebSockets.sendPing(ByteBuffer.allocate(0), wsChannel, null)
                    } catch (_: Exception) {
                        // Connection already closed; task will be cancelled by onClose
                    }
                }, wsConfig.pingIntervalMs, wsConfig.pingIntervalMs, TimeUnit.MILLISECONDS)

                // Register pong listener to track last pong time
                wsChannel.receiveSetter.set(object : AbstractReceiveListener() {
                    override fun getMaxTextBufferSize(): Long = wsConfig.maxMessageSizeBytes
                    override fun getMaxBinaryBufferSize(): Long = wsConfig.maxMessageSizeBytes

                    override fun onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage) {
                        val msg = WsMessage.Text(message.data)
                        orderedDispatcher.execute { connection.dispatchMessage(msg) }
                    }

                    override fun onFullBinaryMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                        val data = message.data
                        try {
                            val bytes = extractBinaryData(data)
                            val msg = WsMessage.Binary(bytes)
                            orderedDispatcher.execute { connection.dispatchMessage(msg) }
                        } finally {
                            data.free()
                        }
                    }

                    override fun onCloseMessage(cm: CloseMessage, channel: WebSocketChannel) {
                        val reason = if (cm.code == CloseMessage.NORMAL_CLOSURE) {
                            WsCloseReason.Normal
                        } else {
                            WsCloseReason.Protocol(cm.code, cm.reason ?: "")
                        }
                        connection.close(reason)
                    }

                    override fun onError(channel: WebSocketChannel, error: Throwable) {
                        connection.dispatchError(error)
                        if (error is IOException) {
                            connection.close(WsCloseReason.ClientDisconnected)
                        } else {
                            connection.close(WsCloseReason.Error(error))
                        }
                    }

                    override fun onFullPongMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                        lastPong.set(System.currentTimeMillis())
                        message.data.free()
                    }


                })
            } else {
                // No ping/pong — simpler receive listener
                val maxMsgSize = wsConfig.maxMessageSizeBytes
                wsChannel.receiveSetter.set(object : AbstractReceiveListener() {
                    override fun getMaxTextBufferSize(): Long = maxMsgSize
                    override fun getMaxBinaryBufferSize(): Long = maxMsgSize

                    override fun onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage) {
                        val msg = WsMessage.Text(message.data)
                        orderedDispatcher.execute { connection.dispatchMessage(msg) }
                    }

                    override fun onFullBinaryMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                        val data = message.data
                        try {
                            val bytes = extractBinaryData(data)
                            val msg = WsMessage.Binary(bytes)
                            orderedDispatcher.execute { connection.dispatchMessage(msg) }
                        } finally {
                            data.free()
                        }
                    }

                    override fun onCloseMessage(cm: CloseMessage, channel: WebSocketChannel) {
                        val reason = if (cm.code == CloseMessage.NORMAL_CLOSURE) {
                            WsCloseReason.Normal
                        } else {
                            WsCloseReason.Protocol(cm.code, cm.reason ?: "")
                        }
                        connection.close(reason)
                    }

                    override fun onError(channel: WebSocketChannel, error: Throwable) {
                        connection.dispatchError(error)
                        if (error is IOException) {
                            connection.close(WsCloseReason.ClientDisconnected)
                        } else {
                            connection.close(WsCloseReason.Error(error))
                        }
                    }


                })
            }

            // Register close listener for cleanup
            val capturedPingFuture = pingFuture
            connection.onClose {
                capturedPingFuture?.cancel(false)
                activeWsConnections.remove(connection)
                wsConnectionCount.decrementAndGet()
            }

            wsChannel.addCloseTask(ChannelListener {
                connection.close(WsCloseReason.ClientDisconnected)
            })

            // Start receiving messages
            wsChannel.resumeReceives()
        }

        val handshakeHandler = WebSocketProtocolHandshakeHandler(callback)

        // Perform the upgrade handshake
        handshakeHandler.handleRequest(exchange)
    }

    /**
     * Extracts binary data from a pooled buffer message into a byte array.
     */
    private fun extractBinaryData(data: org.xnio.Pooled<Array<ByteBuffer>>): ByteArray {
        val pooled = data.resource
        val totalSize = pooled.sumOf { it.remaining() }
        val bytes = ByteArray(totalSize)
        var offset = 0
        for (buf in pooled) {
            val len = buf.remaining()
            buf.get(bytes, offset, len)
            offset += len
        }
        return bytes
    }

    /**
     * Executor that processes tasks sequentially in submission order.
     *
     * Tasks are dispatched to a shared delegate executor, but execution is serialized:
     * at most one task runs at a time for a given [OrderedExecutor] instance.
     * This ensures per-connection message ordering while offloading work from IO threads.
     */
    private class OrderedExecutor(private val delegate: Executor) : Executor {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        private val running = AtomicBoolean(false)

        override fun execute(task: Runnable) {
            queue.add(task)
            tryDrain()
        }

        private fun tryDrain() {
            if (running.compareAndSet(false, true)) {
                try {
                    delegate.execute {
                        try {
                            while (true) {
                                val task = queue.poll() ?: break
                                try {
                                    task.run()
                                } catch (_: Exception) {
                                    // Task-level errors are handled by the task itself
                                }
                            }
                        } finally {
                            running.set(false)
                            // Re-check in case a task was added after our last poll()
                            if (queue.isNotEmpty()) {
                                tryDrain()
                            }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // Executor shut down; release lock so tasks can be drained later if possible
                    running.set(false)
                }
            }
        }
    }

    /**
     * Undertow-backed WebSocket channel implementation.
     *
     * Bridges Undertow's async WebSocketChannel to Colleen's blocking WsChannel interface.
     */
    private class UndertowWsChannel(private val channel: WebSocketChannel) : WsChannel {

        override fun sendText(text: String) {
            WebSockets.sendTextBlocking(text, channel)
        }

        override fun sendBinary(data: ByteBuffer) {
            WebSockets.sendBinaryBlocking(data, channel)
        }

        override fun close(code: Int, reason: String) {
            runCatching { WebSockets.sendCloseBlocking(code, reason, channel) }
        }

        override fun close() {
            close(1000, "")
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
     * Cached FormParserFactory instances keyed by relevant config values.
     *
     * Since ServerConfig is typically stable for the server lifetime,
     * this effectively caches a single factory instance and avoids
     * recreating it on every multipart request.
     */
    private data class FormParserCacheKey(val maxFileSize: Long, val fileSizeThreshold: Long)

    private val formParserFactoryCache = ConcurrentHashMap<FormParserCacheKey, FormParserFactory>()

    /**
     * Returns a cached FormParserFactory configured for multipart handling.
     *
     * Applies:
     * - Maximum file size limits
     * - In-memory / disk threshold
     * - UTF-8 as default charset
     */
    private fun getFormParserFactory(config: ServerConfig): FormParserFactory {
        val key = FormParserCacheKey(config.maxFileSize, config.fileSizeThreshold)
        return formParserFactoryCache.computeIfAbsent(key) {
            val multipart = MultiPartParserDefinition().apply {
                maxIndividualFileSize = config.maxFileSize
                setFileSizeThreshold(config.fileSizeThreshold)
            }

            FormParserFactory.Builder()
                .addParser(multipart)
                .withDefaultCharset(StandardCharsets.UTF_8.name())
                .build()
        }
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
            serverInfo = Request.ServerInfo(
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
        val parser = getFormParserFactory(config).createParser(exchange) ?: error("Request is not multipart")
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
                    FilePart(
                        name = fieldName,
                        filename = formValue.fileName ?: "unknown",
                        contentType = formValue.headers.getFirst(io.undertow.util.Headers.CONTENT_TYPE),
                        size = formValue.fileItem.fileSize,
                        inputStream = formValue.fileItem.inputStream,
                    )
                } else {
                    FormField(fieldName, formValue.value)
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
