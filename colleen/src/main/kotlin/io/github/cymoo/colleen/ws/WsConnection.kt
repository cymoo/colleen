package io.github.cymoo.colleen.ws

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.logger
import io.github.cymoo.colleen.util.http.Headers
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

// ============================================================================
// WsMessage
// ============================================================================

/**
 * Represents an incoming WebSocket message.
 */
sealed class WsMessage {
    data class Text(val data: String) : WsMessage()

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
    /** Normal closure (RFC 6455 code 1000). */
    object Normal : WsCloseReason() {
        override fun toString() = "Normal"
    }

    /** Endpoint going away, e.g. server shutdown or navigation (RFC 6455 code 1001). */
    object GoingAway : WsCloseReason() {
        override fun toString() = "GoingAway"
    }

    /** Message too big (RFC 6455 code 1009). */
    object MessageTooBig : WsCloseReason() {
        override fun toString() = "MessageTooBig"
    }

    /** Transport-level disconnection (IO error, no close frame received). */
    object ClientDisconnected : WsCloseReason() {
        override fun toString() = "ClientDisconnected"
    }

    /** An unexpected error occurred (RFC 6455 code 1011). */
    data class Error(val cause: Throwable) : WsCloseReason()

    /** Any other protocol-level close with explicit code and reason. */
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
 * Thread-safety contract: [WsConnection] serializes all `send*` calls behind
 * its send lock, but deliberately calls [close] OUTSIDE that lock (to avoid
 * holding it during blocking network I/O). Implementations must therefore
 * tolerate `close` racing with an in-flight `send*`; sends themselves are
 * never concurrent with each other. Undertow's blocking senders satisfy this.
 */
interface WsChannel : AutoCloseable {
    @Throws(IOException::class)
    fun sendText(text: String)

    @Throws(IOException::class)
    fun sendBinary(data: ByteBuffer)

    @Throws(IOException::class)
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
 * - Event callbacks ([onWsMessage], [onClose], [onError]) are dispatched to a worker
 *   thread by the server adapter to avoid blocking IO threads.
 *   Messages for the same connection are processed sequentially in order.
 *
 * ## Lifecycle
 * 1. Connection is established after a successful WebSocket handshake.
 * 2. User handler receives the connection and registers callbacks.
 * 3. Messages arrive via [onWsMessage] callbacks.
 * 4. Connection closes via [close] or when the remote peer disconnects.
 * 5. [onClose] callbacks are invoked exactly once.
 *
 * ## Virtual-thread compatibility (JDK 21+)
 * All internal locks use [ReentrantLock] instead of `synchronized` to prevent
 * carrier-thread pinning when running on virtual threads.
 */
class WsConnection internal constructor(
    private val channel: WsChannel,
    /** Path parameters extracted from the WebSocket route pattern. */
    val pathParams: Map<String, String>,
    /** Query parameters from the WebSocket handshake request. */
    val queryParams: Map<String, List<String>> = emptyMap(),
    /**
     * The Colleen application that owns this connection.
     * Used for service resolution; walks up the parent chain for mounted sub-apps.
     */
    private val app: Colleen? = null,
    /**
     * Request-scoped state carried over from the WS handshake phase.
     * State set by WS middleware during upgrade is captured here and remains
     * accessible for the lifetime of the connection.
     */
    private val states: MutableMap<String, Any?> = mutableMapOf(),
    /**
     * HTTP headers from the WebSocket upgrade (handshake) request.
     */
    private val requestHeaders: Headers = Headers(),
) : AutoCloseable {

    // ========================================================================
    // Internal state
    // ========================================================================

    private val closed = AtomicBoolean(false)

    /**
     * The reason this connection was closed.
     *
     * Written exactly once, inside [closeCallbacksLock], before [closeCallbacksDrained]
     * is set to true. @Volatile provides a visibility guarantee for any future reader
     * outside the lock — all current reads are inside the lock, but this is kept as
     * a defensive measure.
     */
    @Volatile
    private var closeReason: WsCloseReason = WsCloseReason.Normal

    /**
     * Set to true once [close] has drained and cleared [closeCallbacks].
     * Guarded by [closeCallbacksLock].
     *
     * Bridges the gap between [closed] (set atomically via CAS) and the critical
     * section where [closeReason] is recorded and callbacks are collected. Without
     * this flag, a concurrent [onClose] call could observe [isClosed] == true but
     * read [closeReason] before it has been assigned its final value.
     */
    @Volatile
    private var closeCallbacksDrained = false

    // ========================================================================
    // Locks
    // ========================================================================

    private val sendLock = ReentrantLock()
    private val statesLock = ReentrantLock()
    private val messageCallbacksLock = ReentrantLock()
    private val closeCallbacksLock = ReentrantLock()
    private val errorCallbacksLock = ReentrantLock()

    // ========================================================================
    // Callback lists (each guarded by its corresponding lock)
    // ========================================================================

    private val messageCallbacks = ArrayList<Consumer<WsMessage>>()
    private val closeCallbacks = ArrayList<Consumer<WsCloseReason>>()
    private val errorCallbacks = ArrayList<Consumer<Throwable>>()

    // ========================================================================
    // Public state
    // ========================================================================

    /** Whether the connection has been closed. */
    val isClosed: Boolean get() = closed.get()

    // ========================================================================
    // Path parameters
    // ========================================================================

    /** Returns a path parameter value by name, or null if not found. */
    fun pathParam(key: String): String? = pathParams[key]

    // ========================================================================
    // Query parameters
    // ========================================================================

    /** Returns the first query parameter value by name, or null if not found. */
    fun query(key: String): String? = queryParams[key]?.firstOrNull()

    /** Returns all query parameter values for the given name. */
    fun queryList(key: String): List<String> = queryParams[key] ?: emptyList()

    // ========================================================================
    // Request headers
    // ========================================================================

    /**
     * Returns the first value of the specified HTTP header from the upgrade request,
     * or null if absent. Header names are case-insensitive.
     */
    fun header(key: String): String? = requestHeaders[key]

    /**
     * Returns all values of the specified HTTP header from the upgrade request.
     * Returns an empty list if absent. Header names are case-insensitive.
     */
    fun headerValues(key: String): List<String> = requestHeaders.getAll(key)

    // ========================================================================
    // Connection-scoped state
    // ========================================================================

    /** Returns true if the state key exists, regardless of whether its value is null. */
    fun hasState(key: String): Boolean = statesLock.withLock { states.containsKey(key) }

    /**
     * Returns the state value for the given key.
     *
     * @throws NoSuchElementException if the key does not exist.
     * @throws NullPointerException if the value is null.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getState(key: String): T = statesLock.withLock {
        if (!states.containsKey(key)) throw NoSuchElementException("State '$key' not found")
        states[key] as T
    }

    /**
     * Returns the state value for the given key,
     * or null if the key does not exist or its value is null.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getStateOrNull(key: String): T? = statesLock.withLock {
        if (!states.containsKey(key)) return null
        states[key] as T?
    }

    /** Sets a state value. The value may be null. */
    fun setState(key: String, value: Any?) = statesLock.withLock {
        states[key] = value
    }

    // ========================================================================
    // Service injection (Kotlin)
    // ========================================================================

    /**
     * Retrieves a required service instance.
     * Resolution walks up the app parent chain for mounted sub-apps.
     *
     * @throws IllegalStateException if the service is not registered.
     */
    inline fun <reified T : Any> getService(qualifier: Any? = null): T =
        resolveService(T::class, qualifier)
            ?: error("Service ${T::class.simpleName}(qualifier=$qualifier) not registered")

    /** Retrieves an optional service instance, or null if not registered. */
    inline fun <reified T : Any> getServiceOrNull(qualifier: Any? = null): T? =
        resolveService(T::class, qualifier)

    /** Retrieves all registered instances of type [T], regardless of qualifier. */
    inline fun <reified T : Any> getServices(): List<T> =
        resolveAllServices(T::class)

    @PublishedApi
    internal fun <T : Any> resolveAllServices(kClass: KClass<T>): List<T> =
        app?.serviceContainer?.getAll(kClass) ?: emptyList()

    @PublishedApi
    internal fun <T : Any> resolveService(kClass: KClass<T>, qualifier: Any? = null): T? =
        resolveServiceFromApp(app, kClass, qualifier)

    private tailrec fun <T : Any> resolveServiceFromApp(
        current: Colleen?,
        kClass: KClass<T>,
        qualifier: Any?,
    ): T? {
        if (current == null) return null
        return current.serviceContainer.getOrNull(kClass, qualifier)
            ?: resolveServiceFromApp(current.parent, kClass, qualifier)
    }

    // ========================================================================
    // Service injection (Java-compatible)
    // ========================================================================

    /**
     * Retrieves a required service instance (Java-compatible).
     *
     * @throws IllegalStateException if the service is not registered.
     */
    @JvmOverloads
    fun <T : Any> getService(clazz: Class<T>, qualifier: Any? = null): T =
        resolveService(clazz.kotlin, qualifier)
            ?: error("Service ${clazz.simpleName}(qualifier=$qualifier) not registered")

    /** Retrieves an optional service instance (Java-compatible), or null if not registered. */
    @JvmOverloads
    fun <T : Any> getServiceOrNull(clazz: Class<T>, qualifier: Any? = null): T? =
        resolveService(clazz.kotlin, qualifier)

    // ========================================================================
    // Send
    // ========================================================================

    /**
     * Sends a text message to the remote peer.
     * Thread-safe; blocks until the message is written.
     *
     * @throws IOException if the connection is closed or the write fails.
     */
    @Throws(IOException::class)
    fun send(text: String): Unit = sendLock.withLock {
        ensureOpen()
        try {
            channel.sendText(text)
        } catch (e: IOException) {
            close(WsCloseReason.ClientDisconnected)
            throw e
        } catch (e: Exception) {
            close(WsCloseReason.Error(e))
            throw IOException("WebSocket send failed", e)
        }
    }

    /**
     * Sends a binary message to the remote peer.
     * Thread-safe; blocks until the message is written.
     *
     * @throws IOException if the connection is closed or the write fails.
     */
    @Throws(IOException::class)
    fun send(data: ByteArray): Unit = sendLock.withLock {
        ensureOpen()
        try {
            channel.sendBinary(ByteBuffer.wrap(data))
        } catch (e: IOException) {
            close(WsCloseReason.ClientDisconnected)
            throw e
        } catch (e: Exception) {
            close(WsCloseReason.Error(e))
            throw IOException("WebSocket send failed", e)
        }
    }

    // ========================================================================
    // Callback registration
    // ========================================================================

    /**
     * Registers a callback for incoming messages.
     * Multiple callbacks may be registered; they are invoked in registration order.
     */
    internal fun onWsMessage(callback: Consumer<WsMessage>) {
        messageCallbacksLock.withLock { messageCallbacks.add(callback) }
    }

    fun onMessage(callback: Consumer<String>) {
        onWsMessage { msg ->
            if (msg is WsMessage.Text) callback.accept(msg.data)
        }
    }

    fun onBinary(callback: Consumer<ByteArray>) {
        onWsMessage { msg ->
            if (msg is WsMessage.Binary) callback.accept(msg.data)
        }
    }

    /**
     * Registers a callback invoked when the connection closes.
     *
     * To avoid holding [closeCallbacksLock] while executing user code (which could
     * deadlock if the callback itself calls [onClose]), the implementation reads the
     * drained state and close reason inside the lock, then invokes the callback outside:
     *
     * - If [close] has already drained the callback list ([closeCallbacksDrained] == true),
     *   the callback is invoked immediately — but outside the lock — with the final
     *   [WsCloseReason]. Both [closeCallbacksDrained] and [closeReason] are written inside
     *   [closeCallbacksLock] before the lock is released, so reading them together inside
     *   the lock guarantees a consistent view.
     * - Otherwise, the callback is queued and invoked by [close].
     *
     * Either way, the callback is invoked exactly once.
     */
    fun onClose(callback: Consumer<WsCloseReason>) {
        val immediateReason: WsCloseReason? = closeCallbacksLock.withLock {
            if (closeCallbacksDrained) {
                closeReason  // captured inside lock; invoked below outside lock
            } else {
                closeCallbacks.add(callback)
                null
            }
        }
        // Invoke outside the lock to prevent deadlock when the callback itself
        // calls onClose (a legitimate use-case for chaining cleanup logic).
        if (immediateReason != null) {
            runCatching { callback.accept(immediateReason) }.onFailure { e ->
                logger.error("onClose callback threw an exception", e)
            }
        }
    }

    /** Registers a callback for transport or message-processing errors. */
    fun onError(callback: Consumer<Throwable>) {
        errorCallbacksLock.withLock { errorCallbacks.add(callback) }
    }

    // ========================================================================
    // Internal dispatch — called by the server adapter
    // ========================================================================

    internal fun dispatchMessage(message: WsMessage) {
        if (isClosed) return
        val snapshot = messageCallbacksLock.withLock { ArrayList(messageCallbacks) }
        snapshot.forEach { cb -> runCatching { cb.accept(message) }.onFailure { dispatchError(it) } }
    }

    internal fun dispatchError(error: Throwable) {
        // Short-circuit early: if already closed, callbacks have been cleared.
        // Log and return rather than acquiring the lock unnecessarily.
        if (isClosed) {
            logger.warn("WebSocket error after connection closed", error)
            return
        }
        val snapshot = errorCallbacksLock.withLock { ArrayList(errorCallbacks) }
        if (snapshot.isEmpty()) {
            logger.warn("Unhandled WebSocket error (no onError handler registered)", error)
            return
        }
        snapshot.forEach { cb ->
            runCatching { cb.accept(error) }.onFailure { callbackEx ->
                logger.error("onError callback threw an exception", callbackEx)
            }
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun close() = close(WsCloseReason.Normal)

    /**
     * Closes the connection with the given reason.
     *
     * Idempotent — only the first call takes effect.
     * Close callbacks are invoked exactly once, in registration order.
     */
    fun close(reason: WsCloseReason) {
        if (!closed.compareAndSet(false, true)) return

        // Send the close frame outside the lock to avoid holding it
        // during potentially blocking network I/O.
        try {
            channel.close(closeCode(reason), closeMessage(reason))
        } catch (_: IOException) {
            // Expected — remote peer may have already disconnected
        } catch (e: Exception) {
            logger.debug("Unexpected error sending WebSocket close frame", e)
        }

        // Inside the lock: record the reason, snapshot and drain the callback list,
        // then raise the drained flag. This ensures a concurrent onClose() call either:
        //   (a) sees closeCallbacksDrained == false → adds to the list → we invoke it, or
        //   (b) sees closeCallbacksDrained == true  → invokes immediately with correct reason.
        val callbacks = closeCallbacksLock.withLock {
            closeReason = reason
            val snapshot = ArrayList(closeCallbacks)
            closeCallbacks.clear()
            closeCallbacksDrained = true
            snapshot
        }

        callbacks.forEach { cb ->
            runCatching { cb.accept(reason) }.onFailure { e ->
                logger.error("onClose callback threw an exception", e)
            }
        }

        messageCallbacksLock.withLock { messageCallbacks.clear() }
        errorCallbacksLock.withLock { errorCallbacks.clear() }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun ensureOpen() {
        if (isClosed) throw IOException("WebSocket connection closed")
    }

    private fun closeCode(reason: WsCloseReason): Int = when (reason) {
        is WsCloseReason.Normal -> 1000
        is WsCloseReason.GoingAway -> 1001
        is WsCloseReason.MessageTooBig -> 1009
        is WsCloseReason.ClientDisconnected -> 1001
        is WsCloseReason.Error -> 1011
        is WsCloseReason.Protocol -> reason.code
    }

    private fun closeMessage(reason: WsCloseReason): String = when (reason) {
        is WsCloseReason.Normal -> ""
        is WsCloseReason.GoingAway -> "Going away"
        is WsCloseReason.MessageTooBig -> "Message too big"
        is WsCloseReason.ClientDisconnected -> "Client disconnected"
        is WsCloseReason.Error -> reason.cause.message ?: "Unexpected error"
        is WsCloseReason.Protocol -> reason.reason
    }
}