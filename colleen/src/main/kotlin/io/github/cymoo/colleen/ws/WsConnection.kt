package io.github.cymoo.colleen.ws

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.concurrent.withLock

// ============================================================================
// WsMessage
// ============================================================================

/**
 * Represents an incoming WebSocket message.
 */
sealed class WsMessage {
    /** Text message. */
    data class Text(val data: String) : WsMessage()

    /** Binary message. */
    data class Binary(val data: ByteArray) : WsMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }
}

// ============================================================================
// WsCloseReason
// ============================================================================

/**
 * Describes why a WebSocket connection was closed.
 */
sealed class WsCloseReason {
    /** Normal closure (code 1000). */
    object Normal : WsCloseReason() {
        override fun toString() = "Normal"
    }

    /** Client disconnected (IO error, broken pipe, etc.). */
    object ClientDisconnected : WsCloseReason() {
        override fun toString() = "ClientDisconnected"
    }

    /** Connection closed due to a server-side error. */
    data class Error(val cause: Throwable) : WsCloseReason()

    /** Connection closed with a specific WebSocket close code and reason. */
    data class Protocol(val code: Int, val reason: String) : WsCloseReason()
}

// ============================================================================
// WsChannel
// ============================================================================

/**
 * Low-level WebSocket channel abstraction.
 *
 * Implementations bridge between the server adapter (e.g. Undertow)
 * and the framework-level [WsConnection].
 *
 * Implementations are NOT required to be thread-safe.
 * All concurrency control is handled by [WsConnection].
 */
interface WsChannel : AutoCloseable {
    /**
     * Sends a text message.
     */
    @Throws(IOException::class)
    fun sendText(text: String)

    /**
     * Sends a binary message.
     */
    @Throws(IOException::class)
    fun sendBinary(data: ByteBuffer)

    /**
     * Closes the WebSocket connection with a close frame.
     *
     * @param code WebSocket close code (e.g. 1000 for normal closure)
     * @param reason human-readable close reason
     */
    fun close(code: Int, reason: String)
}

// ============================================================================
// WsConnection
// ============================================================================

/**
 * WebSocket connection.
 *
 * This is the primary API surface exposed to user handlers.
 *
 * ## Threading model
 * - [send] methods are serialized internally and may be called from multiple threads.
 * - [close] may be called at any time from any thread.
 * - Event callbacks ([onMessage], [onClose], [onError]) are invoked on the
 *   server adapter's IO thread and should not block.
 *
 * ## Lifecycle
 * 1. Connection is established by the framework after successful WebSocket handshake.
 * 2. User handler receives the connection and registers callbacks.
 * 3. Messages arrive via [onMessage] callbacks.
 * 4. Connection closes via [close] or when the remote peer disconnects.
 * 5. [onClose] callbacks are invoked exactly once.
 */
class WsConnection internal constructor(
    private val channel: WsChannel,
    /** Path parameters extracted from the WebSocket route pattern. */
    val pathParams: Map<String, String>,
    /** Query parameters from the WebSocket handshake request. */
    val queryParams: Map<String, List<String>> = emptyMap(),
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val closeReason = AtomicReference<WsCloseReason>(WsCloseReason.Normal)
    private val messageCallbacks = ArrayList<Consumer<WsMessage>>()
    private val closeCallbacks = ArrayList<Consumer<WsCloseReason>>()
    private val errorCallbacks = ArrayList<Consumer<Throwable>>()

    /** Whether the connection has been closed. */
    val isClosed: Boolean get() = closed.get()

    private val sendLock = ReentrantLock()

    // ========================================================================
    // Path Parameters
    // ========================================================================

    /**
     * Returns a path parameter value by name, or null if not found.
     */
    fun pathParam(key: String): String? = pathParams[key]

    // ========================================================================
    // Query Parameters
    // ========================================================================

    /**
     * Returns the first query parameter value by name, or null if not found.
     */
    fun query(key: String): String? = queryParams[key]?.firstOrNull()

    /**
     * Returns all query parameter values for the given name.
     */
    fun queryList(key: String): List<String> = queryParams[key] ?: emptyList()

    // ========================================================================
    // Send
    // ========================================================================

    /**
     * Sends a text message to the remote peer.
     *
     * This method is thread-safe and blocks until the message is sent.
     *
     * @throws IOException if the connection is closed or the write fails
     */
    @Throws(IOException::class)
    fun send(text: String) {
        sendLock.withLock {
            ensureOpen()
            try {
                channel.sendText(text)
            } catch (e: IOException) {
                close(WsCloseReason.ClientDisconnected)
                throw e
            }
        }
    }

    /**
     * Sends a binary message to the remote peer.
     *
     * This method is thread-safe and blocks until the message is sent.
     *
     * @throws IOException if the connection is closed or the write fails
     */
    @Throws(IOException::class)
    fun send(data: ByteArray) {
        sendLock.withLock {
            ensureOpen()
            try {
                channel.sendBinary(ByteBuffer.wrap(data))
            } catch (e: IOException) {
                close(WsCloseReason.ClientDisconnected)
                throw e
            }
        }
    }

    // ========================================================================
    // Callbacks
    // ========================================================================

    /**
     * Registers a callback for incoming messages.
     *
     * Multiple callbacks may be registered.
     * Callbacks are invoked in registration order.
     */
    fun onMessage(callback: Consumer<WsMessage>) {
        synchronized(messageCallbacks) {
            messageCallbacks.add(callback)
        }
    }

    /**
     * Registers a callback invoked when the connection is closed.
     *
     * If the connection is already closed, the callback is invoked immediately.
     * Multiple callbacks may be registered.
     */
    fun onClose(callback: Consumer<WsCloseReason>) {
        synchronized(closeCallbacks) {
            if (isClosed) {
                runCatching { callback.accept(closeReason.get()) }
            } else {
                closeCallbacks.add(callback)
            }
        }
    }

    /**
     * Registers a callback for errors.
     *
     * Errors from message processing or the transport layer
     * are delivered to registered error callbacks.
     */
    fun onError(callback: Consumer<Throwable>) {
        synchronized(errorCallbacks) {
            errorCallbacks.add(callback)
        }
    }

    // ========================================================================
    // Internal — called by the server adapter
    // ========================================================================

    /**
     * Dispatches an incoming message to registered callbacks.
     */
    internal fun dispatchMessage(message: WsMessage) {
        val callbacks: List<Consumer<WsMessage>>
        synchronized(messageCallbacks) {
            callbacks = ArrayList(messageCallbacks)
        }
        callbacks.forEach { cb ->
            runCatching { cb.accept(message) }.onFailure { dispatchError(it) }
        }
    }

    /**
     * Dispatches an error to registered callbacks.
     */
    internal fun dispatchError(error: Throwable) {
        val callbacks: List<Consumer<Throwable>>
        synchronized(errorCallbacks) {
            callbacks = ArrayList(errorCallbacks)
        }
        callbacks.forEach { cb ->
            runCatching { cb.accept(error) }
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun close() {
        close(WsCloseReason.Normal)
    }

    /**
     * Closes the connection with the given reason.
     *
     * This method is idempotent — only the first call takes effect.
     * Close callbacks are invoked exactly once.
     */
    fun close(reason: WsCloseReason) {
        if (!closed.compareAndSet(false, true)) return

        closeReason.compareAndSet(WsCloseReason.Normal, reason)

        val callbacks: List<Consumer<WsCloseReason>>
        synchronized(closeCallbacks) {
            try {
                val code = when (reason) {
                    is WsCloseReason.Normal -> 1000
                    is WsCloseReason.Protocol -> reason.code
                    else -> 1001
                }
                val msg = when (reason) {
                    is WsCloseReason.Normal -> ""
                    is WsCloseReason.Protocol -> reason.reason
                    is WsCloseReason.ClientDisconnected -> "Client disconnected"
                    is WsCloseReason.Error -> reason.cause.message ?: "Error"
                }
                runCatching { channel.close(code, msg) }
            } finally {
                callbacks = ArrayList(closeCallbacks)
                closeCallbacks.clear()
            }
        }
        synchronized(messageCallbacks) { messageCallbacks.clear() }
        synchronized(errorCallbacks) { errorCallbacks.clear() }

        val finalReason = closeReason.get()
        callbacks.forEach { cb ->
            runCatching { cb.accept(finalReason) }
        }
    }

    private fun ensureOpen() {
        if (isClosed) {
            throw IOException("WebSocket connection closed")
        }
    }
}
