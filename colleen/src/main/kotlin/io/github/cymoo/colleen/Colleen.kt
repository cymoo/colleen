package io.github.cymoo.colleen

import io.github.cymoo.colleen.server.UndertowServer
import io.github.cymoo.colleen.server.WebServer
import io.github.cymoo.colleen.util.http.HtmlEscape
import io.github.cymoo.colleen.util.http.HttpStatus
import io.github.cymoo.colleen.util.http.UrlPath
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.time.measureTime

/**
 * Core application class for the Colleen Web Framework.
 *
 * Colleen is a lightweight web framework that focuses on:
 * - Explicit, code-first route registration
 * - A linear, composable middleware execution model
 * - Automatic extraction of request parameters by type
 * - Flexible handler return types
 * - Controller-style handlers
 * - Simple and explicit sub-application mounting
 * - Explicit service injection
 * - Lifecycle hooks for extending framework behavior via listeners
 * - Elegant Exception handling
 * - SSE (Server-Sent Events) support
 * - Validation utilities
 *
 * ### Example
 * ```kotlin
 * val app = Colleen()
 *
 * app.use(Cors())
 *
 * fun update(id: Path<Int>, req: Json<UpdateUserRequest>): User {
 *     // ...
 * }
 *
 * app.post("/users/{id}", ::update)
 *
 * app.listen(8000)
 * ```
 *
 * ### Minimal usage
 * ```kotlin
 * val app = Colleen()
 *
 * app.get("/") {
 *     "Hello, World!"
 * }
 *
 * app.listen(8000)
 * ```
 */

class Colleen {
    // ========================================================================
    // Fields
    // ========================================================================

    /**
     * Global application configuration.
     *
     * This object holds all configuration for the application, such as:
     * - JSON serialization / deserialization settings
     * - HTTP server options
     * - Framework-level behaviors
     */
    @JvmField
    val config = Config()

    /**
     * Parent application when this instance is mounted as a sub-application.
     *
     * - `null` indicates this is a root application.
     * - Non-null indicates this application is mounted under another `Colleen`
     */
    var parent: Colleen? = null
        internal set

    /**
     * Path prefix under which this application is mounted.
     *
     * For example, if mounted at `/api`, all routes of this application
     * will be resolved relative to that prefix.
     */
    var mountPath: String = ""
        internal set

    /**
     * Returns the full mount path of the application
     *
     * Example:
     * ```
     * app.mount("/api", subApp)
     * subApp.mount("/v1", subSubApp)
     * ```
     *
     * Result:
     *   `subSubApp.fullMountPath` -> /api/v1
     */
    val fullMountPath: String
        get() = run {
            val segments = generateSequence(this) { it.parent }
                .map { it.mountPath }
                .filter { it.isNotBlank() }
                .toList()
                .asReversed()

            return if (segments.isEmpty()) ""
            else UrlPath.join("/", *segments.toTypedArray())
        }

    /**
     * Indicates whether the application is currently running.
     */
    val running = AtomicBoolean(false)

    /**
     * Indicates whether the application is shutting down.
     */
    val shuttingDown = AtomicBoolean(false)

    /**
     * Internal event bus for framework-level lifecycle and runtime events.
     *
     * This event bus is responsible for emitting and dispatching:
     * - Request / response lifecycle events
     * - Middleware execution events
     * - Framework internal signals
     *
     * Marked as `@PublishedApi` to allow access from inline functions
     * while still remaining an internal implementation detail.
     */
    @PublishedApi
    internal val eventBus: EventBus = EventBus(this)

    /**
     * Router instance for handling route registration and request dispatching.
     */
    internal val router = Router()

    /**
     * Dependency injection container for managing service instances.
     */
    @PublishedApi
    internal val serviceContainer = ServiceContainer()

    /**
     * Map of exception handlers indexed by exception class.
     *
     * Handlers are looked up by walking up the exception class hierarchy.
     */
    @PublishedApi
    internal val errorHandlers = mutableMapOf<KClass<*>, ErrorHandler<*>>()

    /**
     * Underlying web server instance.
     */
    private var webServer: WebServer? = null

    /**
     * Applies configuration using a DSL-style block.
     *
     * Example:
     * ```kotlin
     * app.config {
     *     server {
     *         useVirtualThreads = false
     *     }
     *     json {
     *         prettyPrint = true
     *     }
     * }
     * ```
     */
    fun config(block: Config.() -> Unit) {
        config.apply(block)
    }

    // ========================================================================
    // Event System
    // ========================================================================

    /**
     * Registers an event listener for the specified event type.
     */
    inline fun <reified T : Event> on(noinline handler: (T) -> Unit): EventListener<T> {
        return eventBus.on<T>(handler)
    }

    // ========================================================================
    // Exception Handling
    // ========================================================================

    /**
     * Registers an error handler for a specific exception type.
     *
     * When an exception of type [T] (or its subtype) is thrown during request handling,
     * the registered handler will be invoked.
     *
     * Example:
     * ```kotlin
     * app.onError<IllegalArgumentException> { e, ctx ->
     *     ctx.status(400).json(mapOf("error" to e.message))
     * }
     * ```
     */
    inline fun <reified T : Throwable> onError(handler: ErrorHandler<T>) {
        errorHandlers[T::class] = handler as ErrorHandler<*>
    }

    // ========================================================================
    // Service API
    // ========================================================================

    /**
     * Registers a pre-built service instance.
     *
     * ```kotlin
     * app.provide(MyService())
     * app.provide(MyService(), qualifier = Primary)
     * ```
     *
     * @param instance  the service instance to register.
     * @param qualifier optional qualifier to distinguish instances of the same type.
     */
    inline fun <reified T : Any> provide(instance: T, qualifier: Any? = null) {
        serviceContainer.registerInstance(instance, qualifier)
    }

    /**
     * Registers a service using a factory function.
     *
     * ```kotlin
     * app.provide { Database.connect(config) }
     * app.provide(singleton = false) { HttpClient() }
     * app.provide(qualifier = Primary) { HikariDataSource(primaryConfig) }
     * ```
     *
     * @param qualifier optional qualifier to distinguish instances of the same type.
     * @param singleton if `true` (default), the factory is invoked once and the
     *                  result cached; if `false`, a new instance is created on
     *                  every resolution.
     * @param factory   the factory function used to create the service instance.
     */
    inline fun <reified T : Any> provide(
        qualifier: Any? = null,
        singleton: Boolean = true,
        noinline factory: () -> T,
    ) {
        if (singleton) {
            serviceContainer.registerSingleton(qualifier, factory)
        } else {
            serviceContainer.registerTransient(qualifier, factory)
        }
    }

    // ========================================================================
    // Middleware API
    // ========================================================================

    /**
     * Registers a global middleware that runs for all requests.
     */
    fun use(middleware: Middleware) {
        addMiddleware(MiddlewareNode.Global(middleware))
    }

    /**
     * Registers a middleware for the given path prefix.
     * The middleware applies to this prefix and all sub-paths.
     */
    fun use(prefix: String, middleware: Middleware) {
        addMiddleware(MiddlewareNode.Prefix.of(prefix, middleware))
    }

    /**
     * Registers a conditional middleware that runs when the predicate returns true.
     */
    fun use(predicate: (Context) -> Boolean, middleware: Middleware) {
        addMiddleware(MiddlewareNode.Conditional(predicate, middleware))
    }

    /**
     * Registers a per-route middleware (exact path + method matching).
     */
    fun use(method: String, path: String, middleware: Middleware) {
        addMiddleware(MiddlewareNode.PerRoute.of(method, path, middleware))
    }

    // ========================================================================
    // Route Registration - Simple Handlers
    // ========================================================================

    /**
     * Registers a route handler for the given HTTP method and path.
     *
     * This is a convenience overload for lambda-based handlers, which are
     * typically used for simple or inline request handling logic.
     *
     * ### Example
     * ```kotlin
     * app.addRoute("GET", "/") { ctx ->
     *     ctx.json(mapOf("msg" to "hello world"))
     * }
     * ```
     */
    fun addRoute(method: String, path: String, lambda: Handler) {
        addRoute(RouteNode.of(method, path, RouteHandler.Lambda(lambda)))
    }

    /**
     * Registers a route handler backed by a Kotlin function.
     *
     * This overload enables declarative and reflection-based routing,
     * allowing framework features such as:
     * - Automatic parameter binding
     * - Default argument handling
     * - Type-safe request extraction
     *
     * The function is analyzed at registration time and invoked
     * dynamically at request dispatch time.
     *
     * ### Example
     * ```kotlin
     * fun update(id: Path<Int>, req: Json<UpdateUserRequest>): User {
     *    ...
     * }
     *
     * app.addRoute("POST", "/users/{id}", ::update)
     * ```
     *
     * ### Note:
     * Parameter extractor types themselves must NOT be nullable.
     *
     * ❌ `id: Query<Int>?`
     *
     * ✅ `id: Query<Int?>`
     */
    fun addRoute(method: String, path: String, fn: KFunction<*>) {
        addRoute(RouteNode.of(method, path, RouteHandler.KFunction(fn, null)))
    }

    /**
     * Registers routes and middlewares from an annotated controller object.
     *
     * ### Example
     * ```kotlin
     * @Controller("/users")
     * class UserController {
     *
     *     @Post("/{id}")
     *     fun update(id: Path<Int>, req: Json<UpdateUserRequest>): User {
     *         ...
     *     }
     * }
     *
     * app.addController(UserController())
     * ```
     *
     * @param obj the controller object with annotated handler methods
     */

    fun addController(obj: Any) {
        addController("", obj)
    }

    /**
     * Registers routes and middlewares from an annotated controller object
     * with an additional path prefix.
     *
     * The prefix is applied before the controller's base path defined by
     * the `@Controller` annotation.
     *
     * @param prefix the path prefix to apply
     * @param obj the controller object with annotated handler methods
     */

    fun addController(prefix: String, obj: Any) {
        eventBus.emit(Event.ControllerRegistered(prefix, obj))
        router.addController(prefix, obj)
    }

    /**
     * Registers a GET route handler.
     */
    fun get(path: String, handler: Handler) = addRoute("GET", path, handler)
    fun get(path: String, handler: KFunction<*>) = addRoute("GET", path, handler)

    /**
     * Registers a POST route handler.
     */
    fun post(path: String, handler: Handler) = addRoute("POST", path, handler)
    fun post(path: String, handler: KFunction<*>) = addRoute("POST", path, handler)

    /**
     * Registers a PUT route handler.
     */
    fun put(path: String, handler: Handler) = addRoute("PUT", path, handler)
    fun put(path: String, handler: KFunction<*>) = addRoute("PUT", path, handler)

    /**
     * Registers a DELETE route handler.
     */
    fun delete(path: String, handler: Handler) = addRoute("DELETE", path, handler)
    fun delete(path: String, handler: KFunction<*>) = addRoute("DELETE", path, handler)

    /**
     * Registers a PATCH route handler.
     */
    fun patch(path: String, handler: Handler) = addRoute("PATCH", path, handler)
    fun patch(path: String, handler: KFunction<*>) = addRoute("PATCH", path, handler)

    /**
     * Registers a HEAD route handler.
     */
    fun head(path: String, handler: Handler) = addRoute("HEAD", path, handler)
    fun head(path: String, handler: KFunction<*>) = addRoute("HEAD", path, handler)

    /**
     * Registers an OPTIONS route handler.
     */
    fun options(path: String, handler: Handler) = addRoute("OPTIONS", path, handler)
    fun options(path: String, handler: KFunction<*>) = addRoute("OPTIONS", path, handler)

    /**
     * Registers a route handler that matches all HTTP methods.
     */
    fun all(path: String, handler: Handler) = addRoute("*", path, handler)
    fun all(path: String, handler: KFunction<*>) = addRoute("*", path, handler)

    // ========================================================================
    // Route Registration - Builder Pattern
    // ========================================================================

    /**
     * Creates a route builder for a GET route.
     *
     * The returned [RouteBuilder] allows further configuration of the route,
     * such as attaching route-level middleware and defining the final request handler.
     *
     * Example:
     * ```
     * app.get("/foo")
     *     .use(middleware1)
     *     .handle { ctx ->
     *         // handle request
     *     }
     * ```
     * @param path the route path
     * @return a [RouteBuilder] for configuring the GET route
     */
    fun get(path: String) = RouteBuilder(this).get(path)

    /**
     * Creates a route builder for a POST route.
     */
    fun post(path: String) = RouteBuilder(this).post(path)

    /**
     * Creates a route builder for a PUT route.
     */
    fun put(path: String) = RouteBuilder(this).put(path)

    /**
     * Creates a route builder for a DELETE route.
     */
    fun delete(path: String) = RouteBuilder(this).delete(path)

    /**
     * Creates a route builder for a PATCH route.
     */
    fun patch(path: String) = RouteBuilder(this).patch(path)

    /**
     * Creates a route builder for a HEAD route.
     */
    fun head(path: String) = RouteBuilder(this).head(path)

    /**
     * Creates a route builder for an OPTIONS route.
     */
    fun options(path: String) = RouteBuilder(this).options(path)

    /**
     * Creates a route builder that matches all HTTP methods.
     */
    fun all(path: String) = RouteBuilder(this).all(path)

    // ========================================================================
    // Route Grouping
    // ========================================================================

    /**
     * Groups routes under a common path prefix.
     *
     * Example:
     * ```kotlin
     * app.group("/api") {
     *     use {ctx, next -> ...}
     *     get("/users") { ... }  // Matches /api/users
     *     post("/users") { ... } // Matches /api/users
     * }
     * ```
     */
    fun group(prefix: String, block: RouteBuilder.() -> Unit) {
        val builder = RouteBuilder(this, prefix)
        builder.block()
    }

    // ========================================================================
    // Sub-Application Mounting
    // ========================================================================

    /**
     * Mounts a sub-application at the specified path.
     *
     * The mounted application operates as an isolated child application with its own
     * context, services, config, middlewares, and error handlers.
     *
     * Resolution rules:
     * - The child application first resolves values from its own context state and services.
     * - If a value is not found, the lookup falls back to the parent application.
     *
     * Error handling:
     * - If [Config.propagateExceptions] is set to `true`, uncaught exceptions from the
     *   child application are propagated to the parent application.
     * - Otherwise, exceptions are handled by the child application's error handlers.
     *
     * Path handling:
     * - The request context path is rewritten for the child application by removing
     *   the mount path prefix.
     *
     * @param prefix the mount path
     * @param app the sub-application to mount
     */
    fun mount(prefix: String, app: Colleen) {
        check(!app.running.get()) {
            "Cannot mount app at '$prefix': target app is already running"
        }

        check(app.parent == null) {
            "Cannot mount app at '$prefix': target app is already mounted at '${app.mountPath}'"
        }

        app.mountPath = prefix
        app.parent = this
        val node = MountNode.of(prefix, app)

        eventBus.emit(Event.SubAppMounted(node))
        router.addMount(node)

        with(app) {
            router.middlewares.forEach {
                eventBus.emitToParent(Event.MiddlewareRegistered(it))
            }

            router.routes.forEach {
                eventBus.emitToParent(Event.RouteRegistered(it))
            }

            router.mounts.forEach {
                eventBus.emit(Event.SubAppMounted(it))
            }

            router.controllers.forEach {
                eventBus.emit(Event.ControllerRegistered(it.first, it.second))
            }
        }
    }

    // ========================================================================
    // Server Lifecycle
    // ========================================================================

    /**
     * Starts the HTTP server.
     *
     * A shutdown hook is automatically registered to ensure proper cleanup.
     *
     * @param port the port to listen on (default: 8000)
     * @param host the host address to bind to (default: "127.0.0.1")
     */
    @JvmOverloads
    fun listen(
        port: Int = config.server.port,
        host: String = config.server.host,
    ) {
        check(parent == null) {
            "Cannot start server: this app is mounted at '$mountPath'"
        }

        check(running.compareAndSet(false, true)) {
            "Server is already running"
        }

        eventBus.emit(Event.ServerStarting())

        if (host != config.server.host) config.server.host = host
        if (port != config.server.port) config.server.port = port

        val server = UndertowServer(config.server)
        webServer = server

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(
            Thread({ shutdown() }, "shutdown")
        )

        try {
            server.start(createHttpHandler())
        } catch (e: Throwable) {
            logger.error("Failed to start server", e)
            shutdown()
            throw e
        }

        eventBus.emit(Event.ServerStarted())

        on<Event.ResponseReady> {
            runCatching { it.ctx.request.stream?.close() }
        }
    }

    /**
     * Stops the HTTP server gracefully.
     */
    fun stop() = shutdown()

    /**
     * Registers a shutdown hook to be executed when the server stops.
     */
    fun onShutdown(hook: Runnable) {
        eventBus.on<Event.ServerStopped> {
            hook.run()
        }
    }

    // ========================================================================
    // Internal Methods
    // ========================================================================

    /**
     * Handles an incoming HTTP request.
     *
     * Routes the request through the router and handles any exceptions.
     * If the request is from a mounted sub-application and [Config.propagateExceptions]
     * is true, exceptions are propagated to the parent application.
     *
     * This method materializes the response body before returning.
     */
    internal fun handleRequest(ctx: Context) {
        try {
            router.handleRequest(ctx)
            // Materialize the response body once routing and error handling are complete.
            // This binds the high-level ResponseBody to a concrete RawResponseBody
            ctx.response.materialize(ctx)
        } catch (e: Throwable) {
            when (e) {
                // Fatal JVM error: do not handle or log here, propagate immediately.
                is Error -> throw e
                is Exception -> {
                    // Propagate exceptions from mounted apps if configured
                    if (parent != null && config.propagateExceptions) {
                        throw e
                    } else {
                        handleError(e, ctx)
                        ctx.response.materialize(ctx)
                    }
                }
            }
        }
    }

    // ========================================================================
    // Private Methods
    // ========================================================================

    private fun addMiddleware(middleware: MiddlewareNode) {
        eventBus.emit(Event.MiddlewareRegistered(middleware))
        router.addMiddleware(middleware)
    }

    private fun addRoute(route: RouteNode) {
        eventBus.emit(Event.RouteRegistered(route))
        router.addRoute(route)
    }

    /**
     * Creates a request http handler function for the web server.
     *
     * This is the entry point called by the web server adapter.
     */
    private fun createHttpHandler(): (Request) -> Response {
        return { request ->
            val reqEvent = Event.RequestReceived(request)
            eventBus.emit(reqEvent)

            val ctx = Context(request = reqEvent.request, app = this)
            val duration = measureTime { handleRequest(ctx) }

            eventBus.emit(Event.ResponseReady(ctx, duration))

            ctx.response.apply {
                onResponseSent = { total, io, bytes ->
                    eventBus.emit(
                        Event.ResponseSent(ctx, total, io, bytes)
                    )
                }
            }
        }
    }

    /**
     * Handles an exception by finding and invoking the appropriate exception handler.
     *
     * If a handler is found, it is invoked. If the handler itself throws an exception,
     * the default exception handler is used. If no handler is found, the default
     * exception handler is used directly.
     */
    private fun handleError(e: Exception, ctx: Context) {
        eventBus.emit(Event.ExceptionCaught(e, ctx))
        val handler = findErrorHandler(e::class)
        if (handler != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                (handler as ErrorHandler<Exception>)(e, ctx)
            } catch (handlerError: Exception) {
                logger.error("Exception handler failed", handlerError)
                handleErrorByDefault(handlerError, ctx)
            }
        } else {
            handleErrorByDefault(e, ctx)
        }
        eventBus.emit(Event.ExceptionHandled(e, ctx))
    }

    /**
     * Default exception handler that converts uncaught errors into HTTP responses.
     *
     * - [HttpException] controls the response status and message.
     * - All other errors result in 500 Internal Server Error.
     * - Server errors are logged at ERROR level.
     */
    private fun handleErrorByDefault(err: Throwable, ctx: Context) {
        val httpException = err as? HttpException
        val status = httpException?.status ?: 500
        val statusText = HttpStatus.reasonPhrase(status)
        val message = httpException?.message

        if (status == 500) {
            logger.error("Server error", err)
        }

        if (ctx.accepts("html")) {
            ctx.status(status).html(
                renderErrorHtml(status, statusText, message)
            )
        } else {
            ctx.status(status).json(
                mapOf(
                    "status" to status,
                    "code" to (httpException?.code ?: "INTERNAL_SERVER_ERROR"),
                    "message" to (message ?: statusText),
                )
            )
        }
    }

    /**
     * Renders a minimal HTML error page for the given HTTP status.
     *
     * Displays the status code and reason phrase, and optionally a message
     * when it provides additional information.
     *
     * The message is HTML-escaped to prevent XSS vulnerabilities.
     */
    private fun renderErrorHtml(status: Int, statusText: String, message: String?): String {
        val detail =
            if (!message.isNullOrBlank() && message != statusText)
                "<p>${HtmlEscape.escape(message)}</p>"
            else
                ""

        return """
        <!DOCTYPE html>
        <html lang="en">
          <head>
            <meta charset="utf-8">
            <title>$status $statusText</title>
          </head>
          <body>
            <h1>$status $statusText</h1>
            $detail
          </body>
        </html>
    """.trimIndent()
    }

    /**
     * Finds an exception handler by walking up the exception class hierarchy.
     *
     * @param exceptionClass the exception class to find a handler for
     * @return the matching error handler, or null if none found
     */
    private fun findErrorHandler(exceptionClass: KClass<*>): ErrorHandler<*>? {
        var current: KClass<*>? = exceptionClass
        while (current != null && current != Throwable::class) {
            errorHandlers[current]?.let { return it }
            current = current.java.superclass?.kotlin
        }
        return null
    }

    /**
     * Shuts down the server and executes all registered shutdown hooks.
     *
     * This method ensures shutdown is executed only once using atomic flag.
     * Stops the web server first, then executes all shutdown hooks in order.
     */
    private fun shutdown() {
        // Ensure shutdown is only executed once
        if (!shuttingDown.compareAndSet(false, true)) {
            return
        }

        eventBus.emit(Event.ServerStopping())

        // 1. Stop accepting new HTTP requests
        try {
            webServer?.stop()
        } catch (e: Exception) {
            logger.warn("Error stopping server during shutdown", e)
        }

        // 2. Execute user shutdown hooks
        webServer = null
        eventBus.emit(Event.ServerStopped())
    }

    // ========================================================================
    // Java Compatibility
    // ========================================================================

    fun <T : Event> on(clazz: Class<T>, handler: Consumer<T>): EventListener<T> {
        return eventBus.on(clazz, handler)
    }

    /**
     * Registers an error handler for a specific exception type (Java-compatible).
     *
     * @param clazz the exception class
     * @param handler the error handler function
     */
    fun <T : Throwable> onError(clazz: Class<T>, handler: ErrorHandler<T>) {
        errorHandlers[clazz.kotlin] = handler as ErrorHandler<*>
    }

    /**
     * Registers a pre-built service instance (Java-compatible).
     *
     * @param clazz     the service class
     * @param instance  the service instance to register
     * @param qualifier optional qualifier to distinguish instances of the same type
     */
    @JvmOverloads
    fun <T : Any> provide(clazz: Class<T>, instance: T, qualifier: Any? = null) {
        serviceContainer.registerInstance(clazz, instance, qualifier)
    }

    /**
     * Registers a service using a factory function (Java-compatible).
     *
     * @param clazz     the service class
     * @param qualifier optional qualifier to distinguish instances of the same type
     * @param lifetime  defines the lifetime and caching behavior. Defaults to [Lifetime.Singleton]
     * @param factory   a function that creates instances of the service
     */
    @JvmOverloads
    fun <T : Any> provide(
        clazz: Class<T>,
        qualifier: Any? = null,
        lifetime: Lifetime = Lifetime.Singleton,
        factory: () -> T
    ) {
        when (lifetime) {
            Lifetime.Singleton -> serviceContainer.registerSingleton(clazz, qualifier, factory)
            Lifetime.Transient -> serviceContainer.registerTransient(clazz, qualifier, factory)
        }
    }

    /**
     * Registers a singleton-free service by lifetime without a qualifier (Java-compatible).
     */
    fun <T : Any> provide(clazz: Class<T>, lifetime: Lifetime, factory: () -> T) {
        provide(clazz, null, lifetime, factory)
    }

    fun get(path: String, handler: VoidHandler) = get(path) { ctx -> handler.invoke(ctx) }
    fun post(path: String, handler: VoidHandler) = post(path) { ctx -> handler.invoke(ctx) }
    fun put(path: String, handler: VoidHandler) = put(path) { ctx -> handler.invoke(ctx) }
    fun delete(path: String, handler: VoidHandler) = delete(path) { ctx -> handler.invoke(ctx) }
    fun patch(path: String, handler: VoidHandler) = patch(path) { ctx -> handler.invoke(ctx) }
    fun head(path: String, handler: VoidHandler) = head(path) { ctx -> handler.invoke(ctx) }
    fun options(path: String, handler: VoidHandler) = options(path) { ctx -> handler.invoke(ctx) }
    fun all(path: String, handler: VoidHandler) = all(path) { ctx -> handler.invoke(ctx) }

    /**
     * Groups routes under a common path prefix (Java-compatible).
     *
     * @param prefix the path prefix for all routes in this group
     * @param block the route configuration block
     */
    fun group(prefix: String, block: GroupBlock) = group(prefix) { block.apply(this) }
}