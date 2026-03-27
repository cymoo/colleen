package io.github.cymoo.colleen.ws

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.util.http.Headers
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    /**
     * The Colleen application that owns this connection.
     *
     * Used for service resolution. Walks up the parent chain when the
     * owning app is a mounted sub-application.
     */
    private val app: Colleen? = null,
    /**
     * Request-scoped state carried over from the WS handshake phase.
     *
     * State set by WS middleware during the upgrade handshake is captured here
     * and remains accessible for the lifetime of the connection.
     */
    private val states: MutableMap<String, Any?> = mutableMapOf(),
    /**
     * HTTP headers from the WebSocket upgrade (handshake) request.
     *
     * These are the headers sent by the client during the initial HTTP
     * request that triggers the WebSocket upgrade. Useful for authentication,
     * session tracking, or any other header-based logic.
     */
    private val requestHeaders: Headers = Headers(),
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val closeReason = AtomicReference<WsCloseReason>(WsCloseReason.Normal)
    private val messageCallbacks = ArrayList<Consumer<WsMessage>>()
    private val closeCallbacks = ArrayList<Consumer<WsCloseReason>>()
    private val errorCallbacks = ArrayList<Consumer<Throwable>>()

    /**
     * Set to true once close() has collected the close callbacks.
     * Guarded by [closeCallbacks] lock.
     *
     * This flag bridges the gap between [closed] (set via CAS) and the synchronized
     * block where [closeReason] is set and callbacks are drained. Without it, a
     * concurrent [onClose] call could observe [isClosed] == true but read [closeReason]
     * before it has been set to its final value.
     */
    private var closeCallbacksCollected = false

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
    // Headers
    // ========================================================================

    /**
     * Returns the first value of the specified HTTP header from the upgrade request,
     * or null if the header is not present.
     *
     * Header names are case-insensitive.
     *
     * ```kotlin
     * val auth = conn.header("Authorization")
     * val origin = conn.header("Origin")
     * ```
     */
    fun header(key: String): String? = requestHeaders[key]

    /**
     * Returns all values of the specified HTTP header from the upgrade request.
     *
     * Returns an empty list if the header is not present.
     * Header names are case-insensitive.
     *
     * ```kotlin
     * val cookies = conn.headerValues("Cookie")
     * ```
     */
    fun headerValues(key: String): List<String> = requestHeaders.getAll(key)

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Returns true if the state exists, regardless of whether the value is null.
     */
    fun hasState(key: String): Boolean = states.containsKey(key)

    /**
     * Returns the state value for the given key.
     *
     * @param key the state key
     * @return the non-null state value
     * @throws NoSuchElementException if the state key does not exist
     * @throws NullPointerException if the value is null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getState(key: String): T {
        if (!states.containsKey(key)) {
            throw NoSuchElementException("State '$key' not found")
        }
        return states[key] as T
    }

    /**
     * Returns the state value for the given key, or null if the key does not exist.
     *
     * @param key the state key
     * @return the state value if the key exists (may be null), or null if the key doesn't exist
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getStateOrNull(key: String): T? {
        if (!states.containsKey(key)) return null
        return states[key] as T?
    }

    /**
     * Sets a state value for the given key.
     *
     * @param key the state key
     * @param value the state value (may be null)
     */
    fun setState(key: String, value: Any?) {
        states[key] = value
    }

    // ========================================================================
    // Service Injection
    // ========================================================================

    /**
     * Retrieves a required service instance.
     *
     * Resolution walks up the app parent chain (for mounted sub-apps).
     *
     * ```kotlin
     * val db = conn.getService<Database>()
     * ```
     *
     * @param qualifier optional qualifier to distinguish instances of the same type.
     * @throws IllegalStateException if the service is not registered or app is not available.
     * @see getServiceOrNull
     */
    inline fun <reified T : Any> getService(qualifier: Any? = null): T =
        resolveService(T::class, qualifier)
            ?: error("Service ${T::class.simpleName}(qualifier=$qualifier) not registered")

    /**
     * Retrieves an optional service instance, or `null` if not registered.
     *
     * ```kotlin
     * val db = conn.getServiceOrNull<Database>()
     * ```
     *
     * @param qualifier optional qualifier to distinguish instances of the same type.
     * @see getService
     */
    inline fun <reified T : Any> getServiceOrNull(qualifier: Any? = null): T? =
        resolveService(T::class, qualifier)

    /**
     * Retrieves all registered instances of type [T] from the current app,
     * regardless of qualifier.
     *
     * ```kotlin
     * val handlers = conn.getServices<EventHandler>()
     * ```
     */
    inline fun <reified T : Any> getServices(): List<T> =
        resolveAllServices(T::class)

    /**
     * Internal resolver for getServices.
     */
    @PublishedApi
    internal fun <T : Any> resolveAllServices(kClass: KClass<T>): List<T> =
        app?.serviceContainer?.getAll(kClass) ?: emptyList()

    /**
     * Internal recursive resolver shared by all public retrieval methods.
     */
    @PublishedApi
    internal fun <T : Any> resolveService(kClass: KClass<T>, qualifier: Any? = null): T? =
        resolveServiceFromApp(app, kClass, qualifier)

    /**
     * Walks up the app parent chain to resolve a service.
     */
    private fun <T : Any> resolveServiceFromApp(current: Colleen?, kClass: KClass<T>, qualifier: Any?): T? {
        if (current == null) return null
        return current.serviceContainer.getOrNull(kClass, qualifier)
            ?: resolveServiceFromApp(current.parent, kClass, qualifier)
    }

    // ========================================================================
    // Java-compatible Service Injection
    // ========================================================================

    /**
     * Retrieves a required service instance (Java-compatible).
     *
     * @param clazz     the service class.
     * @param qualifier optional qualifier to distinguish instances of the same type.
     * @throws IllegalStateException if the service is not registered.
     */
    @JvmOverloads
    fun <T : Any> getService(clazz: Class<T>, qualifier: Any? = null): T =
        resolveService(clazz.kotlin, qualifier)
            ?: error("Service ${clazz.simpleName}(qualifier=$qualifier) not registered")

    /**
     * Retrieves an optional service instance (Java-compatible), or `null` if not registered.
     *
     * @param clazz     the service class.
     * @param qualifier optional qualifier to distinguish instances of the same type.
     */
    @JvmOverloads
    fun <T : Any> getServiceOrNull(clazz: Class<T>, qualifier: Any? = null): T? =
        resolveService(clazz.kotlin, qualifier)

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
     * If the connection is already closed AND the close procedure has finished
     * collecting callbacks, the callback is invoked immediately with the close reason.
     * Otherwise, the callback is added to the pending list for close() to invoke.
     *
     * Multiple callbacks may be registered.
     */
    fun onClose(callback: Consumer<WsCloseReason>) {
        synchronized(closeCallbacks) {
            if (closeCallbacksCollected) {
                // close() has already collected and cleared the list;
                // invoke immediately with the final reason.
                runCatching { callback.accept(closeReason.get()) }
            } else {
                // Either not yet closed, or close() is in progress but hasn't
                // collected the callbacks yet. Either way, add to the list.
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
        if (isClosed) return
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
        if (isClosed) return
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

        // Send close frame BEFORE acquiring the closeCallbacks lock
        // to avoid holding the lock during potentially blocking network I/O.
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

        // Set reason and collect callbacks atomically under the closeCallbacks lock.
        // This ensures that a concurrent onClose() call always sees the correct reason.
        val callbacks: List<Consumer<WsCloseReason>>
        synchronized(closeCallbacks) {
            closeReason.compareAndSet(WsCloseReason.Normal, reason)
            callbacks = ArrayList(closeCallbacks)
            closeCallbacks.clear()
            closeCallbacksCollected = true
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
