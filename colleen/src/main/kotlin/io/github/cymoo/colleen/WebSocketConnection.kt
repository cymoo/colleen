package io.github.cymoo.colleen

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.concurrent.withLock

/**
 * Represents a WebSocket message — either text or binary.
 */
sealed class WebSocketMessage {

    /** A UTF-8 text WebSocket frame. */
    class Text(private val data: String) : WebSocketMessage() {
        fun text(): String = data
        fun bytes(): ByteArray = data.toByteArray(Charsets.UTF_8)
        override fun toString() = "WebSocketMessage.Text(${data.take(64)})"
    }

    /** A binary WebSocket frame. */
    class Binary(private val data: ByteArray) : WebSocketMessage() {
        fun text(): String = String(data, Charsets.UTF_8)
        fun bytes(): ByteArray = data
        override fun toString() = "WebSocketMessage.Binary(${data.size} bytes)"
    }
}

/**
 * Reason a WebSocket connection was closed.
 *
 * @property code  WebSocket close status code (RFC 6455 §7.4)
 * @property reason Human-readable reason phrase (may be empty)
 */
data class WebSocketCloseReason(val code: Int, val reason: String) {
    companion object {
        /** Normal closure (1000): the purpose for which the connection was established has been fulfilled. */
        const val NORMAL = 1000

        /** Going away (1001): endpoint is going away (server shutdown, browser navigation, etc.). */
        const val GOING_AWAY = 1001

        /** Constructs a close reason indicating an internal error (1011). */
        @JvmStatic
        @JvmOverloads
        fun error(message: String = ""): WebSocketCloseReason = WebSocketCloseReason(1011, message)
    }
}

/**
 * Low-level WebSocket I/O adapter.
 *
 * Abstracts the underlying transport from [WebSocketConnection].
 * Implementations are NOT required to be thread-safe.
 * All concurrency control is handled by [WebSocketConnection].
 */
interface WebSocketChannelAdapter : AutoCloseable {
    /** Sends a UTF-8 text frame to the client. */
    fun sendText(text: String)

    /** Sends a binary frame to the client. */
    fun sendBinary(bytes: ByteArray)

    /** Initiates a WebSocket close handshake with the given code and reason. */
    fun close(code: Int, reason: String)

    /** Whether the underlying channel is already closed. */
    val isClosed: Boolean
}

/**
 * User-facing WebSocket connection handle.
 *
 * Passed to the WebSocket handler once the HTTP upgrade is complete.
 *
 * ### Threading model
 * - [send] methods are serialized internally and may be called from any thread.
 * - [close] may be called from any thread.
 * - Callback setters ([onOpen], [onMessage], [onError], [onClose]) should be
 *   called from the handler thread before the connection becomes active.
 *
 * ### Lifecycle
 * 1. Handler is invoked: register callbacks ([onMessage], [onClose], etc.).
 * 2. [dispatchOpen] is called by the adapter after the handler returns.
 * 3. Messages are dispatched via [dispatchMessage].
 * 4. On close, [dispatchClose] is called.
 *
 * Once closed, further [send] calls are silently ignored.
 */
class WebSocketConnection(
    private val channel: WebSocketChannelAdapter,
    /** Path parameters extracted from the route pattern (e.g. `{"room" to "lobby"}`). */
    val pathParams: Map<String, String>,
    /** Raw query string from the handshake request (e.g. `"token=abc&v=2"`). */
    val queryString: String,
    /** HTTP headers from the handshake request (lowercase names, first value only). */
    val headers: Map<String, String>,
    /** Context attributes set by middleware during the handshake phase. */
    val attributes: Map<String, Any?>,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val lock = ReentrantLock()

    /** Whether this connection has been closed. */
    val isClosed: Boolean get() = closed.get()

    // User-registered callbacks (single var — last registration wins for open/message/error)
    @Volatile private var openHandler: Runnable? = null
    @Volatile private var messageHandler: Consumer<WebSocketMessage>? = null
    @Volatile private var errorHandler: Consumer<Throwable>? = null

    // List-based close callbacks (multiple registrations allowed: framework + user)
    private val closeCallbacks = ArrayList<Consumer<WebSocketCloseReason>>()

    // ========================================================================
    // Send
    // ========================================================================

    /**
     * Sends a text message to the client.
     *
     * Thread-safe; silently ignored if the connection is already closed.
     */
    fun send(text: String) {
        lock.withLock {
            if (!closed.get()) {
                runCatching { channel.sendText(text) }
            }
        }
    }

    /**
     * Sends a binary message to the client.
     *
     * Thread-safe; silently ignored if the connection is already closed.
     */
    fun send(data: ByteArray) {
        lock.withLock {
            if (!closed.get()) {
                runCatching { channel.sendBinary(data) }
            }
        }
    }

    // ========================================================================
    // Callbacks
    // ========================================================================

    /**
     * Registers a callback invoked when the connection is fully opened.
     *
     * Replaces any previously registered open handler.
     */
    fun onOpen(handler: Runnable) {
        openHandler = handler
    }

    /**
     * Registers a callback invoked for each incoming message.
     *
     * Replaces any previously registered message handler.
     */
    fun onMessage(handler: Consumer<WebSocketMessage>) {
        messageHandler = handler
    }

    /**
     * Registers a callback invoked when an error occurs on the connection.
     *
     * Replaces any previously registered error handler.
     */
    fun onError(handler: Consumer<Throwable>) {
        errorHandler = handler
    }

    /**
     * Registers a callback invoked when the connection closes.
     *
     * Multiple callbacks may be registered; all are invoked on close.
     * This design allows both framework-level cleanup and user-defined teardown.
     */
    fun onClose(callback: Consumer<WebSocketCloseReason>) {
        lock.withLock {
            closeCallbacks.add(callback)
        }
    }

    // ========================================================================
    // Close
    // ========================================================================

    /** Closes with normal code (1000) and an empty reason. */
    override fun close() = close(WebSocketCloseReason.NORMAL, "")

    /**
     * Closes the connection with the given code and reason.
     *
     * Idempotent — subsequent calls are no-ops.
     */
    @JvmOverloads
    fun close(code: Int, reason: String = "") {
        if (!closed.compareAndSet(false, true)) return

        val callbacks: List<Consumer<WebSocketCloseReason>>
        lock.withLock {
            runCatching { channel.close(code, reason) }
            callbacks = ArrayList(closeCallbacks)
            closeCallbacks.clear()
        }

        val closeReason = WebSocketCloseReason(code, reason)
        callbacks.forEach { runCatching { it.accept(closeReason) } }
    }

    // ========================================================================
    // Request Data Access
    // ========================================================================

    /** Returns the path parameter value for the given name, or `null`. */
    fun pathParam(name: String): String? = pathParams[name]

    /** Returns the first query parameter value for the given name, or `null`. */
    fun query(name: String): String? {
        if (queryString.isEmpty()) return null
        return queryString.split("&").firstOrNull { param ->
            param.substringBefore('=') == name
        }?.let { param ->
            if ('=' in param) param.substringAfter('=') else ""
        }
    }

    /**
     * Returns the middleware-set attribute (context state) value for the given key.
     *
     * Example:
     * ```kotlin
     * // In middleware:
     * ctx.setState("userId", "alice")
     *
     * // In WS handler:
     * val userId = conn.attribute<String>("userId")
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> attribute(key: String): T? = attributes[key] as T?

    // ========================================================================
    // Internal Dispatch Methods (called by server adapter)
    // ========================================================================

    internal fun dispatchOpen() {
        runCatching { openHandler?.run() }
    }

    internal fun dispatchMessage(message: WebSocketMessage) {
        runCatching { messageHandler?.accept(message) }
    }

    internal fun dispatchError(error: Throwable) {
        runCatching { errorHandler?.accept(error) }
    }

    /**
     * Called by the adapter when the remote peer closes the connection.
     *
     * Distinct from [close] in that the channel is already closed — we only
     * need to run callbacks and flip the flag.
     */
    internal fun dispatchClose(code: Int, reason: String) {
        if (!closed.compareAndSet(false, true)) return

        val callbacks: List<Consumer<WebSocketCloseReason>>
        lock.withLock {
            callbacks = ArrayList(closeCallbacks)
            closeCallbacks.clear()
        }

        val closeReason = WebSocketCloseReason(code, reason)
        callbacks.forEach { runCatching { it.accept(closeReason) } }
    }
}
