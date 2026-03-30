package io.github.cymoo.colleen.server.undertow

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.ServerConfig
import io.github.cymoo.colleen.logger
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.ws.WsCloseReason
import io.github.cymoo.colleen.ws.WsConfig
import io.github.cymoo.colleen.ws.WsConnection
import io.github.cymoo.colleen.ws.WsMessage
import io.undertow.server.HttpServerExchange
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.WebSocketProtocolHandshakeHandler
import io.undertow.websockets.core.*
import io.undertow.websockets.spi.WebSocketHttpExchange
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * Handles WebSocket upgrade requests and manages WebSocket connections.
 *
 * Responsibilities:
 * - Connection limit enforcement (pre-handshake, returns HTTP 503)
 * - Active connection tracking for graceful shutdown
 * - Per-connection ordered message dispatch (off IO thread)
 * - Ping/pong heartbeat for dead connection detection
 * - RFC 6455 compliant ping response
 * - Proper 1009 close code for oversized messages
 */
internal class UndertowWsHandler(
    private val config: ServerConfig,
    private val wsConfig: WsConfig,
) {
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
     * Only initialized when [WsConfig.pingIntervalMs] > 0.
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
     * Handles a WebSocket upgrade request.
     *
     * Uses Undertow's WebSocketProtocolHandshakeHandler to perform the
     * HTTP→WebSocket upgrade, then bridges the Undertow WebSocketChannel
     * to Colleen's WsConnection abstraction.
     */
    fun handleUpgrade(
        handler: Consumer<WsConnection>,
        pathParams: Map<String, String>,
        queryParams: Map<String, List<String>>,
        app: Colleen?,
        states: Map<String, Any?>,
        headers: Headers,
        exchange: HttpServerExchange,
    ) {
        val callback = WebSocketConnectionCallback { _: WebSocketHttpExchange, wsChannel: WebSocketChannel ->
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
            val lastPong: AtomicLong? = if (wsConfig.pingIntervalMs > 0) {
                val pongTracker = AtomicLong(System.currentTimeMillis())

                pingFuture = pingScheduler.scheduleAtFixedRate({
                    try {
                        if (connection.isClosed) return@scheduleAtFixedRate
                        val sinceLastPong = System.currentTimeMillis() - pongTracker.get()
                        if (sinceLastPong > wsConfig.pingIntervalMs + wsConfig.pingTimeoutMs) {
                            // Pong timeout — close the dead connection
                            connection.close(WsCloseReason.Protocol(1001, "Ping timeout"))
                            return@scheduleAtFixedRate
                        }
                        WebSockets.sendPing(ByteBuffer.allocate(0), wsChannel, null)
                    } catch (_: Exception) {
                        // Connection already closed; task will be canceled by onClose
                    }
                }, wsConfig.pingIntervalMs, wsConfig.pingIntervalMs, TimeUnit.MILLISECONDS)

                pongTracker
            } else {
                null
            }

            // Unified receive listener for all cases (ping enabled or disabled)
            wsChannel.receiveSetter.set(object : AbstractReceiveListener() {
                override fun getMaxTextBufferSize(): Long = wsConfig.maxMessageSizeBytes
                override fun getMaxBinaryBufferSize(): Long = wsConfig.maxMessageSizeBytes

                override fun onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage) {
                    val msg = WsMessage.Text(message.data)
                    orderedDispatcher.execute { connection.dispatchMessage(msg) }
                }

                override fun onFullBinaryMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                    val bytes = extractBinaryData(message)
                    val msg = WsMessage.Binary(bytes)
                    orderedDispatcher.execute { connection.dispatchMessage(msg) }
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
                    if (isMessageTooLarge(error)) {
                        runCatching { WebSockets.sendCloseBlocking(1009, "Message Too Big", channel) }
                        connection.close(WsCloseReason.Protocol(1009, "Message Too Big"))
                    } else if (error is IOException) {
                        connection.close(WsCloseReason.ClientDisconnected)
                    } else {
                        connection.dispatchError(error)
                        connection.close(WsCloseReason.Error(error))
                    }
                }

                override fun onFullPongMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                    lastPong?.set(System.currentTimeMillis())
                    message.data.free()
                }

                override fun onFullPingMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                    val data = message.data
                    try {
                        WebSockets.sendPongBlocking(
                            data.resource.firstOrNull() ?: ByteBuffer.allocate(0),
                            channel
                        )
                    } finally {
                        data.free()
                    }
                }
            })

            // Register close listener for cleanup
            val capturedPingFuture = pingFuture
            connection.onClose {
                capturedPingFuture?.cancel(false)
                activeWsConnections.remove(connection)
                wsConnectionCount.decrementAndGet()
            }

            wsChannel.addCloseTask {
                connection.close(WsCloseReason.ClientDisconnected)
            }

            // Start receiving messages
            wsChannel.resumeReceives()
        }

        // Subclass to enforce connection limits before the WebSocket handshake.
        // This rejects excess connections at the HTTP level (503) instead of
        // completing the upgrade and then sending a WS close frame.
        val handshakeHandler = object : WebSocketProtocolHandshakeHandler(callback) {
            override fun handleRequest(exchange: HttpServerExchange) {
                if (wsConfig.maxConnections > 0) {
                    val current = wsConnectionCount.incrementAndGet()
                    if (current > wsConfig.maxConnections) {
                        wsConnectionCount.decrementAndGet()
                        exchange.statusCode = 503
                        exchange.responseSender.send("Try Again Later")
                        return
                    }
                }
                super.handleRequest(exchange)
            }
        }

        // Perform the upgrade handshake
        handshakeHandler.handleRequest(exchange)
    }

    /**
     * Gracefully closes all active WebSocket connections.
     */
    fun closeAllConnections() {
        val snapshot = ArrayList(activeWsConnections)
        snapshot.forEach { conn ->
            runCatching { conn.close(WsCloseReason.Normal) }
        }
        activeWsConnections.clear()
    }

    /**
     * Shuts down all WebSocket infrastructure.
     *
     * @param timeout shutdown timeout in milliseconds
     */
    fun shutdown(timeout: Long) {
        // Shut down WebSocket ping scheduler
        if (lazyPingScheduler.isInitialized()) {
            pingScheduler.shutdown()
            if (!pingScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                pingScheduler.shutdownNow()
            }
        }

        // Shut down WebSocket executor
        if (lazyWsExecutor.isInitialized()) {
            wsExecutor.shutdown()
            if (!wsExecutor.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                logger.warn("WebSocket executor did not terminate, forcing shutdown")
                wsExecutor.shutdownNow()
            }
        }
    }

    /**
     * Extracts binary data from a pooled buffer message into a byte array.
     */
    @Suppress("DEPRECATION")
    private fun extractBinaryData(message: BufferedBinaryMessage): ByteArray {
        val data = message.data
        return try {
            val merged = WebSockets.mergeBuffers(*data.resource)
            if (merged.hasArray() && merged.arrayOffset() == 0 && merged.remaining() == merged.array().size) {
                merged.array()
            } else {
                ByteArray(merged.remaining()).also { merged.get(it) }
            }
        } finally {
            data.free()
        }
    }

    /**
     * Detects whether a WebSocket error was caused by an oversized message.
     *
     * Undertow uses error codes like UT002004 for text messages and
     * UT002005 for binary messages that exceed the configured buffer size.
     */
    private fun isMessageTooLarge(error: Throwable): Boolean {
        val message = error.message ?: return false
        return message.contains("UT002004") || message.contains("UT002005")
    }
}
