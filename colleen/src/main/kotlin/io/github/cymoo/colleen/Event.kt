package io.github.cymoo.colleen

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlin.time.Duration

/**
 * Base class for all framework events.
 *
 * Events are:
 * - synchronous
 * - ordered by registration
 * - optionally bubble to parent applications
 */
sealed class Event(
    val bubbles: Boolean = true,
) {
    /**
     * The Colleen application instance that emitted this event.
     * This is set internally by EventBus.
     */
    lateinit var source: Colleen
        private set

    internal fun setSource(source: Colleen) {
        if (!::source.isInitialized) {
            this.source = source
        }
    }

    /**
     * Whether event propagation to parent EventBus is stopped.
     */
    @Volatile
    var isPropagationStopped: Boolean = false
        private set

    /**
     * Stops bubbling propagation to parent applications.
     */
    fun stopPropagation() {
        isPropagationStopped = true
    }

    // =========================================================================
    // Lifecycle Events
    // =========================================================================

    /** Emitted when the server is about to start. */
    class ServerStarting : Event(false)

    /** Emitted after the server has successfully started. */
    class ServerStarted : Event(false)

    /** Emitted when the server is about to stop. */
    class ServerStopping : Event(false)

    /** Emitted after the server has fully stopped. */
    class ServerStopped : Event(false)

    // =========================================================================
    // Request Handling Events
    // =========================================================================

    /**
     * Event emitted immediately after an HTTP request is received
     * and before any middleware or handler logic is executed.
     *
     * This event represents the *earliest interception point* in the
     * request lifecycle.
     *
     * Mutability:
     * - The [request] instance is **mutable**
     * - Listeners MAY modify the request (e.g. headers, path, metadata)
     *
     * Typical use cases:
     * - Method or path rewriting
     */
    class RequestReceived(var request: Request) : Event(false)

    /**
     * Event emitted after the application handler has produced a Response,
     * but before it is written to the underlying transport.
     *
     * At this point:
     * - Routing and business logic have completed
     * - The response has not yet been committed
     *
     * [duration] measures execution time rom request receipt to response produced.
     *
     * Typical use cases:
     * - Response inspection
     * - Attaching headers or cookies
     * - Observability hooks
     */
    class ResponseReady(val ctx: Context, val duration: Duration) : Event(false)

    /**
     * Event emitted after the HTTP response has been fully sent to the client.
     *
     * This marks the *end of the request lifecycle*.
     *
     * Timing semantics:
     * - [total]: total duration from request receipt to response completion
     * - [io]: time spent writing the response body to the client
     *
     * [bytesSent] reflects the actual number of bytes written to the transport
     * layer and may differ from logical payload size.
     *
     * Typical use cases:
     * - Access logging
     * - Metrics and latency tracking
     * - Distributed tracing completion
     */
    class ResponseSent(val ctx: Context, val total: Duration, val io: Duration, val bytesSent: Long) : Event(false)

    // =========================================================================
    // Middleware Events
    // =========================================================================

    /**
     * Event emitted before a middleware begins execution.
     *
     * Execution scope:
     * - Includes the middleware itself
     * - Includes ALL downstream middlewares and the final handler
     *
     * This event is emitted once per middleware in the chain.
     */
    class MiddlewareExecuting(val ctx: Context, val node: MiddlewareNode) : Event()

    /**
     * Event emitted after a middleware and all of its downstream
     * execution have completed.
     *
     * [duration] covers:
     * - The middleware's own logic
     * - All downstream middlewares
     * - The final handler execution
     */
    class MiddlewareExecuted(val ctx: Context, val node: MiddlewareNode, val duration: Duration) : Event()

    // =========================================================================
    // Handler Events
    // =========================================================================

    /**
     * Event emitted immediately before a route handler is invoked.
     *
     * This event represents the innermost execution unit in the
     * request handling pipeline.
     */
    class HandlerExecuting(val ctx: Context, val node: RouteNode) : Event()

    /**
     * Event emitted after the route handler has completed execution.
     *
     * [duration] measures handler execution time only.
     */
    class HandlerExecuted(val ctx: Context, val node: RouteNode, val duration: Duration) : Event()

    // =========================================================================
    // Sub Application Events
    // =========================================================================

    /**
     * Event emitted before a mounted sub-application begins execution.
     *
     * Execution scope:
     * - Includes the sub-application entry point
     * - Includes ALL middlewares and handlers within the sub-application
     */
    class SubAppExecuting(val ctx: Context, val node: MountNode) : Event()

    /**
     * Event emitted after a mounted sub-application and all of its internal
     * processing have completed.
     *
     * [duration] covers the entire sub-application execution scope.
     */
    class SubAppExecuted(val ctx: Context, val node: MountNode, val duration: Duration) : Event()

    // =========================================================================
    // Error Events
    // =========================================================================

    /**
     * Event emitted when an exception is caught during request processing.
     *
     * This event indicates that:
     * - Normal execution flow has been interrupted
     * - Error handling has not yet completed
     */
    class ExceptionCaught(val exception: Throwable, val ctx: Context) : Event()

    /**
     * Event emitted after an exception has been handled.
     *
     * At this point:
     * - A response has been generated or committed
     * - The exception will not propagate further
     */
    class ExceptionHandled(val exception: Throwable, val ctx: Context) : Event()
}

/**
 * Event listener wrapper.
 */
class EventListener<T : Event>(
    val eventType: Class<out Event>,
    val handler: Consumer<T>,
)

/**
 * Synchronous event bus with bubbling support.
 */
class EventBus(val owner: Colleen) {
    /**
     * Parent application's EventBus, if exists.
     */
    val parentBus: EventBus? get() = owner.parent?.eventBus

    @PublishedApi
    internal val listeners =
        ConcurrentHashMap<Class<out Event>, CopyOnWriteArrayList<EventListener<out Event>>>()

    /**
     * Subscribe to an event type.
     */
    inline fun <reified T : Event> on(noinline handler: (T) -> Unit): EventListener<T> {
        return on(T::class.java, Consumer(handler))
    }

    /**
     * Unsubscribe an event listener.
     */
    fun off(listener: EventListener<*>) {
        listeners[listener.eventType]?.remove(listener)
    }

    /**
     * Returns true if emitting an event of [eventType] could reach any listener
     * (exact type, catch-all `Event` listeners, or a parent bus via bubbling).
     *
     * This is a fast path allowing hot call sites to skip constructing event
     * objects (and timing measurements) when nobody is listening. It may
     * over-approximate for non-bubbling events, which only costs a no-op emit.
     */
    fun hasListeners(eventType: Class<out Event>): Boolean {
        if (listeners[eventType]?.isNotEmpty() == true) return true
        if (listeners[Event::class.java]?.isNotEmpty() == true) return true
        return parentBus?.hasListeners(eventType) ?: false
    }

    /**
     * Emits an event on the current EventBus and optionally bubbles it to parent buses.
     *
     * Dispatch order:
     * 1. Listeners registered for the exact event type
     * 2. Listeners registered for the base Event type (catch-all)
     *
     * Bubbling behavior:
     * - If [Event.bubbles] is true and propagation has not been stopped,
     *   the same event instance will be re-emitted on the parent EventBus.
     * - Bubbling continues recursively until there is no parent or propagation
     *   is stopped.
     *
     * Notes:
     * - Emission is synchronous and happens in listener registration order.
     * - Listener exceptions are caught and logged; they do NOT interrupt
     *   event propagation.
     * - The event source is set to the owner of this EventBus on first emission.
     *
     * Typical usage:
     * - Normal framework events (middleware, handlers)
     * - Events that should be observable by parent applications or global plugins
     */
    fun <T : Event> emit(event: T) {
        event.setSource(owner)

        dispatch(event, listeners[event::class.java])
        dispatch(event, listeners[Event::class.java])

        if (event.bubbles && !event.isPropagationStopped) {
            parentBus?.emit(event)
        }
    }

    private fun <T : Event> dispatch(
        event: T,
        list: CopyOnWriteArrayList<EventListener<out Event>>?
    ) {
        list?.forEach { listener ->
            try {
                @Suppress("UNCHECKED_CAST")
                (listener as EventListener<T>).handler.accept(event)
            } catch (e: Exception) {
                logger.error("Error in event listener for ${event::class.simpleName}", e)
            }
        }
    }

    // ========================================================================
    // Java Compatibility
    // ========================================================================

    /**
     * Subscribe to an event type (Java-compatible).
     */
    fun <T : Event> on(clazz: Class<T>, handler: Consumer<T>): EventListener<T> {
        val listener = EventListener(clazz, handler)

        listeners.computeIfAbsent(clazz) { CopyOnWriteArrayList() }.add(listener)

        return listener
    }
}