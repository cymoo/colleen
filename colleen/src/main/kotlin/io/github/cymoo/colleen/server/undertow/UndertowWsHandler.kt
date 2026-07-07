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
     * Tracks all active WebSocket connections for graceful shutdown and connection limiting.
     *
     * This is the single source of truth for the active connection count.
     * [ConcurrentHashMap.newKeySet] is used for O(1) add/remove/contains with safe concurrent access.
     */
    private val activeWsConnections: MutableSet<WsConnection> = ConcurrentHashMap.newKeySet()

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
            wsChannel.idleTimeout = wsConfig.idleTimeoutMs

            val connection = createConnection(wsChannel, pathParams, queryParams, app, states, headers)
            val orderedDispatcher = OrderedExecutor(wsExecutor)

            // Abort if the user-supplied handler throws during setup.
            if (!invokeUserHandler(handler, connection)) return@WebSocketConnectionCallback

            val (pingFuture, lastPong) = setupHeartbeat(wsChannel, connection)
            setupReceiveListener(wsChannel, connection, orderedDispatcher, lastPong)
            registerCloseHandlers(wsChannel, connection, pingFuture)

            wsChannel.resumeReceives()
        }

        createHandshakeHandler(callback).handleRequest(exchange)
    }

    /**
     * Gracefully closes all active WebSocket connections.
     */
    fun closeAllConnections() {
        val snapshot = ArrayList(activeWsConnections)
        snapshot.forEach { conn ->
            runCatching { conn.close(WsCloseReason.GoingAway) }
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
     * Creates a [WsConnection] for the newly upgraded channel and registers it
     * for graceful shutdown tracking.
     */
    private fun createConnection(
        wsChannel: WebSocketChannel,
        pathParams: Map<String, String>,
        queryParams: Map<String, List<String>>,
        app: Colleen?,
        states: Map<String, Any?>,
        headers: Headers,
    ): WsConnection {
        val channel = UndertowWsChannel(wsChannel)
        val connection = WsConnection(channel, pathParams, queryParams, app, states.toMutableMap(), headers)
        activeWsConnections.add(connection)
        // Register the tracking-removal callback IMMEDIATELY after adding.
        // If it were registered later (e.g. in registerCloseHandlers) and the user
        // handler threw during setup, the early close() would never remove the
        // connection — permanently leaking a maxConnections slot.
        connection.onClose {
            activeWsConnections.remove(connection)
        }
        return connection
    }

    /**
     * Invokes the user-supplied handler to register message/error/close callbacks.
     *
     * Returns `false` if the handler throws, in which case the connection is
     * closed immediately and the caller should abort further setup.
     */
    private fun invokeUserHandler(handler: Consumer<WsConnection>, connection: WsConnection): Boolean {
        return try {
            handler.accept(connection)
            true
        } catch (e: Exception) {
            logger.error("WebSocket handler setup failed: ${e.message}", e)
            connection.close(WsCloseReason.Error(e))
            false
        }
    }

    /**
     * Holds the scheduled ping task and the timestamp of the last received pong.
     * Both are `null` when heartbeats are disabled ([WsConfig.pingIntervalMs] <= 0).
     */
    private data class HeartbeatResult(
        val pingFuture: ScheduledFuture<*>?,
        val lastPong: AtomicLong?,
    )

    /**
     * Starts the ping/pong heartbeat for the given connection.
     *
     * Every [WsConfig.pingIntervalMs] ms a ping frame is sent. If no pong is
     * received within [WsConfig.pingTimeoutMs] ms after the last ping, the
     * connection is treated as dead and closed with [WsCloseReason.GoingAway].
     *
     * Returns [HeartbeatResult] with nulls if heartbeats are disabled.
     */
    private fun setupHeartbeat(wsChannel: WebSocketChannel, connection: WsConnection): HeartbeatResult {
        if (wsConfig.pingIntervalMs <= 0) return HeartbeatResult(null, null)

        // nanoTime: heartbeat timing must use a monotonic clock — wall-clock
        // (currentTimeMillis) jumps on NTP adjustment and could kill healthy
        // connections or keep dead ones alive.
        val lastPong = AtomicLong(System.nanoTime())

        val pingFuture = pingScheduler.scheduleAtFixedRate({
            try {
                if (connection.isClosed) return@scheduleAtFixedRate

                val sinceLastPong = (System.nanoTime() - lastPong.get()) / 1_000_000
                if (sinceLastPong > wsConfig.pingIntervalMs + wsConfig.pingTimeoutMs) {
                    // No pong received within the allowed window — treat as dead.
                    connection.close(WsCloseReason.GoingAway)
                    return@scheduleAtFixedRate
                }
                WebSockets.sendPing(ByteBuffer.allocate(0), wsChannel, null)
            } catch (_: Exception) {
                // Connection already closed; the task will be canceled by onClose.
            }
        }, wsConfig.pingIntervalMs, wsConfig.pingIntervalMs, TimeUnit.MILLISECONDS)

        return HeartbeatResult(pingFuture, lastPong)
    }

    /**
     * Attaches an [AbstractReceiveListener] to [wsChannel] that bridges Undertow
     * WebSocket events to Colleen's [WsConnection] abstraction.
     *
     * All callbacks are dispatched through [dispatcher] to ensure ordered,
     * sequential processing per connection off the IO thread.
     *
     * [lastPong] is updated on every received pong frame; pass `null` to skip
     * pong tracking when heartbeats are disabled.
     */
    private fun setupReceiveListener(
        wsChannel: WebSocketChannel,
        connection: WsConnection,
        dispatcher: OrderedExecutor,
        lastPong: AtomicLong?,
    ) {
        wsChannel.receiveSetter.set(object : AbstractReceiveListener() {
            override fun getMaxTextBufferSize(): Long = wsConfig.maxMessageSizeBytes
            override fun getMaxBinaryBufferSize(): Long = wsConfig.maxMessageSizeBytes

            override fun onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage) {
                dispatcher.execute { connection.dispatchMessage(WsMessage.Text(message.data)) }
            }

            override fun onFullBinaryMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                dispatcher.execute { connection.dispatchMessage(WsMessage.Binary(extractBinaryData(message))) }
            }

            override fun onCloseMessage(cm: CloseMessage, channel: WebSocketChannel) {
                // Map Undertow close codes to Colleen's close reason hierarchy per RFC 6455.
                val reason = when (cm.code) {
                    CloseMessage.NORMAL_CLOSURE -> WsCloseReason.Normal
                    CloseMessage.GOING_AWAY -> WsCloseReason.GoingAway
                    CloseMessage.MSG_TOO_BIG -> WsCloseReason.MessageTooBig
                    else -> WsCloseReason.Protocol(cm.code, cm.reason ?: "")
                }
                dispatcher.execute { connection.close(reason) }
            }

            override fun onError(channel: WebSocketChannel, error: Throwable) {
                dispatcher.execute {
                    when {
                        // Undertow signals oversized messages as errors rather than close frames.
                        isMessageTooLarge(error) -> connection.close(WsCloseReason.MessageTooBig)
                        // Broken pipe, connection reset, etc.
                        error is IOException -> connection.close(WsCloseReason.ClientDisconnected)
                        else -> {
                            connection.dispatchError(error)
                            connection.close(WsCloseReason.Error(error))
                        }
                    }
                }
            }

            override fun onFullPongMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                lastPong?.set(System.nanoTime())
                message.data.free()
            }
        })
    }

    /**
     * Registers two complementary close hooks:
     *
     * 1. **[WsConnection.onClose]** — application-level cleanup: cancels the ping
     *    task. (Removal from [activeWsConnections] is registered earlier, in
     *    [createConnection], so it also covers setup failures.)
     * 2. **[WebSocketChannel.addCloseTask]** — transport-level fallback: ensures
     *    the connection is closed if the underlying channel is torn down without a
     *    proper WebSocket close handshake (e.g. abrupt TCP disconnect).
     */
    private fun registerCloseHandlers(
        wsChannel: WebSocketChannel,
        connection: WsConnection,
        pingFuture: ScheduledFuture<*>?,
    ) {
        connection.onClose {
            pingFuture?.cancel(false)
        }
        wsChannel.addCloseTask {
            connection.close(WsCloseReason.ClientDisconnected)
        }
    }

    /**
     * Returns a [WebSocketProtocolHandshakeHandler] that rejects upgrade requests
     * with HTTP 503 when the connection limit is reached, avoiding the cost of
     * completing a handshake only to immediately send a WS close frame.
     *
     * [activeWsConnections].size is the authoritative connection count — no separate
     * atomic counter is maintained, eliminating the risk of the two diverging.
     *
     * Note: the pre-handshake size check is optimistic (no lock). In a burst, slightly
     * more connections than [WsConfig.maxConnections] may be admitted; the tradeoff
     * avoids contention on the hot upgrade path.
     */
    private fun createHandshakeHandler(callback: WebSocketConnectionCallback): WebSocketProtocolHandshakeHandler {
        return object : WebSocketProtocolHandshakeHandler(callback) {
            override fun handleRequest(exchange: HttpServerExchange) {
                if (wsConfig.maxConnections > 0 && activeWsConnections.size >= wsConfig.maxConnections) {
                    exchange.statusCode = 503
                    exchange.responseSender.send("Try Again Later")
                    return
                }
                super.handleRequest(exchange)
            }
        }
    }

    /**
     * Extracts binary data from a pooled buffer message into a byte array.
     *
     * Avoids the double-copy that arises from [WebSockets.mergeBuffers] followed by
     * a conditional array copy: instead, the total size is computed upfront and all
     * source buffers are drained directly into a single pre-allocated [ByteArray].
     */
    @Suppress("DEPRECATION")
    private fun extractBinaryData(message: BufferedBinaryMessage): ByteArray {
        val data = message.data
        return try {
            val buffers = data.resource
            val totalSize = buffers.sumOf { it.remaining() }
            val result = ByteArray(totalSize)
            var offset = 0
            for (buf in buffers) {
                val len = buf.remaining()
                buf.get(result, offset, len)
                offset += len
            }
            result
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
