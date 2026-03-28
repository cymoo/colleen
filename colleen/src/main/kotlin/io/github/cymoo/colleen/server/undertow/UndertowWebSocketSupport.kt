package io.github.cymoo.colleen.server.undertow

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.ServerConfig
import io.github.cymoo.colleen.logger
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.ws.WsChannel
import io.github.cymoo.colleen.ws.WsCloseReason
import io.github.cymoo.colleen.ws.WsConfig
import io.github.cymoo.colleen.ws.WsConnection
import io.github.cymoo.colleen.ws.WsMessage
import io.undertow.server.HttpServerExchange
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.WebSocketProtocolHandshakeHandler
import io.undertow.websockets.core.AbstractReceiveListener
import io.undertow.websockets.core.BufferedBinaryMessage
import io.undertow.websockets.core.BufferedTextMessage
import io.undertow.websockets.core.CloseMessage
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.core.WebSockets
import io.undertow.websockets.spi.WebSocketHttpExchange
import org.xnio.ChannelListener
import org.xnio.Pooled
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

internal class UndertowWebSocketSupport(
    private val config: ServerConfig,
    private val wsConfig: WsConfig,
) {
    private val threadCounter = AtomicInteger(0)

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

    private val activeWsConnections: MutableSet<WsConnection> = ConcurrentHashMap.newKeySet()
    private val wsConnectionCount = AtomicInteger(0)

    private val lazyPingScheduler = lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable).apply {
                isDaemon = true
                name = "ws-ping-scheduler"
            }
        }
    }
    private val pingScheduler: ScheduledExecutorService by lazyPingScheduler

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
            // Re-check after handshake to avoid race between concurrent handshakes.
            val current = wsConnectionCount.incrementAndGet()
            if (wsConfig.maxConnections > 0 && current > wsConfig.maxConnections) {
                wsConnectionCount.decrementAndGet()
                runCatching { WebSockets.sendCloseBlocking(1013, "Try Again Later", wsChannel) }
                return@WebSocketConnectionCallback
            }

            // Apply WS configuration
            wsChannel.idleTimeout = wsConfig.idleTimeoutMs

            val channel = UndertowWsChannel(wsChannel)
            val connection = WsConnection(channel, pathParams, queryParams, app, states.toMutableMap(), headers)
            val orderedDispatcher = OrderedExecutor(wsExecutor)

            activeWsConnections.add(connection)

            var pingFuture: ScheduledFuture<*>? = null
            val cleanupOnce = AtomicBoolean(false)
            connection.onClose {
                if (cleanupOnce.compareAndSet(false, true)) {
                    pingFuture?.cancel(false)
                    activeWsConnections.remove(connection)
                    wsConnectionCount.decrementAndGet()
                }
            }

            wsChannel.addCloseTask(ChannelListener {
                connection.close(WsCloseReason.ClientDisconnected)
            })

            val lastPong = if (wsConfig.pingIntervalMs > 0) AtomicLong(System.currentTimeMillis()) else null
            if (lastPong != null) {
                pingFuture = pingScheduler.scheduleAtFixedRate({
                    try {
                        if (connection.isClosed) return@scheduleAtFixedRate
                        val sinceLastPong = System.currentTimeMillis() - lastPong.get()
                        if (sinceLastPong > wsConfig.pingIntervalMs + wsConfig.pingTimeoutMs) {
                            connection.close(WsCloseReason.Protocol(1001, "Ping timeout"))
                            return@scheduleAtFixedRate
                        }
                        WebSockets.sendPing(ByteBuffer.allocate(0), wsChannel, null)
                    } catch (_: Exception) {
                        // Connection may already be closed; cleanup runs via onClose.
                    }
                }, wsConfig.pingIntervalMs, wsConfig.pingIntervalMs, TimeUnit.MILLISECONDS)
            }

            wsChannel.receiveSetter.set(
                createReceiveListener(
                    connection = connection,
                    orderedDispatcher = orderedDispatcher,
                    lastPong = lastPong,
                ),
            )

            try {
                handler.accept(connection)
            } catch (e: Exception) {
                logger.error("WebSocket handler setup failed: ${e.message}", e)
                connection.close(WsCloseReason.Error(e))
                return@WebSocketConnectionCallback
            }

            wsChannel.resumeReceives()
        }

        val handshakeHandler = object : WebSocketProtocolHandshakeHandler(callback) {
            override fun handleRequest(exchange: HttpServerExchange) {
                if (wsConfig.maxConnections > 0 && wsConnectionCount.get() >= wsConfig.maxConnections) {
                    exchange.statusCode = 503
                    exchange.endExchange()
                    return
                }
                super.handleRequest(exchange)
            }
        }

        handshakeHandler.handleRequest(exchange)
    }

    fun shutdown(timeoutMs: Long) {
        val snapshot = ArrayList(activeWsConnections)
        snapshot.forEach { conn ->
            runCatching { conn.close(WsCloseReason.Normal) }
        }
        activeWsConnections.clear()

        if (lazyPingScheduler.isInitialized()) {
            pingScheduler.shutdown()
            if (!pingScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                pingScheduler.shutdownNow()
            }
        }

        if (lazyWsExecutor.isInitialized()) {
            wsExecutor.shutdown()
            if (!wsExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn("WebSocket executor did not terminate, forcing shutdown")
                wsExecutor.shutdownNow()
            }
        }
    }

    private fun createReceiveListener(
        connection: WsConnection,
        orderedDispatcher: OrderedExecutor,
        lastPong: AtomicLong?,
    ): AbstractReceiveListener {
        return object : AbstractReceiveListener() {
            override fun getMaxTextBufferSize(): Long = wsConfig.maxMessageSizeBytes
            override fun getMaxBinaryBufferSize(): Long = wsConfig.maxMessageSizeBytes

            override fun onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage) {
                orderedDispatcher.execute {
                    connection.dispatchMessage(WsMessage.Text(message.data))
                }
            }

            override fun onFullBinaryMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                val data = message.data
                try {
                    val bytes = extractBinaryData(data)
                    orderedDispatcher.execute {
                        connection.dispatchMessage(WsMessage.Binary(bytes))
                    }
                } finally {
                    data.free()
                }
            }

            override fun onFullPingMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                val data = message.data
                try {
                    val bytes = extractBinaryData(data)
                    WebSockets.sendPongBlocking(ByteBuffer.wrap(bytes), channel)
                } finally {
                    data.free()
                }
            }

            override fun onFullPongMessage(channel: WebSocketChannel, message: BufferedBinaryMessage) {
                lastPong?.set(System.currentTimeMillis())
                message.data.free()
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
                    connection.dispatchError(error)
                    runCatching { WebSockets.sendCloseBlocking(1009, "Message Too Big", channel) }
                    connection.close(WsCloseReason.Protocol(1009, "Message Too Big"))
                    return
                }

                if (error is IOException) {
                    connection.close(WsCloseReason.ClientDisconnected)
                    return
                }

                connection.dispatchError(error)
                connection.close(WsCloseReason.Error(error))
            }
        }
    }

    private fun extractBinaryData(data: Pooled<Array<ByteBuffer>>): ByteArray {
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

    private fun isMessageTooLarge(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            val msg = cause.message?.lowercase().orEmpty()
            msg.contains("message too big") ||
                msg.contains("message too large") ||
                msg.contains("maximum message size") ||
                msg.contains("max message size") ||
                msg.contains("ut002040") ||
                cause.javaClass.simpleName.contains("TooBig", ignoreCase = true) ||
                cause.javaClass.simpleName.contains("TooLarge", ignoreCase = true)
        }
    }

    private class OrderedExecutor(private val delegate: Executor) : Executor {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        private val running = AtomicBoolean(false)

        override fun execute(task: Runnable) {
            queue.add(task)
            tryScheduleDrain()
        }

        private fun tryScheduleDrain() {
            if (!running.compareAndSet(false, true)) {
                return
            }

            try {
                delegate.execute { drain() }
            } catch (_: RejectedExecutionException) {
                running.set(false)
            }
        }

        private fun drain() {
            try {
                while (true) {
                    val task = queue.poll() ?: break
                    try {
                        task.run()
                    } catch (_: Exception) {
                        // Task-level errors are handled by the task itself.
                    }
                }
            } finally {
                running.set(false)
                if (queue.isNotEmpty()) {
                    tryScheduleDrain()
                }
            }
        }
    }

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
}
