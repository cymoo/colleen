package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.TypeRef
import io.github.cymoo.colleen.util.http.UrlPath
import java.io.InputStream
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.reflect.KClass

/**
 * Request context that encapsulates HTTP request, response, and app state.
 *
 * The Context object is passed to all handlers and middleware, providing access to:
 * - HTTP request and response objects
 * - Path parameters and query parameters
 * - Request body parsing (JSON, form data, files)
 * - Response methods (JSON, HTML, redirects, SSE)
 * - Service injection
 * - State management across middleware chain
 *
 * Example usage:
 * ```kotlin
 * app.get("/users/{id}") { ctx ->
 *     val id = ctx.pathParam("id")
 *     ctx.json(mapOf("id" to id))
 * }
 * ```
 */
data class Context(
    /**
     * The HTTP request object containing method, path, headers, and body.
     */
    @JvmField
    val request: Request,

    /**
     * The HTTP response object for building the response.
     */
    @JvmField
    val response: Response = Response(),

    /**
     * The Colleen app instance handling this request.
     */
    @JvmField
    val app: Colleen,

    /**
     * Parent context when this request is handled by a mounted sub-app.
     *
     * `null` for root app contexts.
     */
    val parentContext: Context? = null,
) {
    val jsonMapper = app.config.jsonMapper

    /**
     * Exception thrown during request processing, if any.
     *
     * Colleen guarantees symmetric middleware execution (before/after),
     * even in the presence of exceptions.
     *
     * Exception handling model:
     * - All exceptions thrown during middleware and route handler execution
     *   are captured by Colleen.
     * - This ensures that if a middleware has executed its "before" logic,
     *   its corresponding "after" logic will always be executed as well.
     *   (The "before" and "after" phases are separated by a call to `next()`.)
     * - After the entire middleware chain and route handling have completed,
     *   the captured exception is rethrown in a unified manner.
     * - Setting `error.handled = true` indicates that the exception has been
     *   explicitly handled and should not be rethrown by Colleen.
     */
    var error: ErrorState? = null
        internal set

    // ========================================================================
    // State Management
    // ========================================================================

    /**
     * Mutable map for storing request-scoped state.
     *
     * A key may exist with a null value.
     */
    private val states: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Returns true if the state exists in this context or any parent context,
     * regardless of whether the value is null.
     */
    fun hasState(key: String): Boolean =
        states.containsKey(key) || parentContext?.hasState(key) == true

    /**
     * Returns the state value for the given key.
     *
     * It requires the state value to be non-null due to the type constraint.
     * Use [getStateOrNull] if you need to retrieve nullable state values.
     *
     * @param key the state key
     * @return the non-null state value
     * @throws NoSuchElementException if the state key does not exist
     * @throws NullPointerException if the value is null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getState(key: String): T {
        val value: Any? = when {
            states.containsKey(key) -> states[key]
            parentContext != null -> parentContext.getState(key)
            else -> throw NoSuchElementException("State '$key' not found")
        }

        return value as T
    }

    /**
     * Returns the state value for the given key, or null if the key does not exist.
     *
     * Note: This method returns null both when the key doesn't exist and when
     * the key exists with a null value. Use [hasState] to distinguish between
     * these cases.
     *
     * @param key the state key
     * @return the state value if the key exists (may be null), or null if the key doesn't exist
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getStateOrNull(key: String): T? {
        val value = when {
            states.containsKey(key) -> states[key]
            parentContext != null -> parentContext.getStateOrNull(key)
            else -> return null
        }
        return value as T?
    }

    /**
     * Sets a state value for the given key.
     *
     * The value may be null, which is treated as a valid state.
     * If the key already exists, its value will be replaced.
     *
     * @param key the state key
     * @param value the state value (may be null)
     */
    fun setState(key: String, value: Any?) {
        states[key] = value
    }

    /**
     * Iterates over state entries in this context.
     *
     * This context's states are visited first, followed by parent context states.
     *
     * @param includeParents whether to also iterate states from parent contexts
     *                       (default: true, child states are visited first)
     * @param consumer a function that accepts each key-value pair
     */
    fun forEachState(
        includeParents: Boolean = true,
        consumer: BiConsumer<String, Any?>
    ) {
        states.forEach { (k, v) -> consumer.accept(k, v) }
        if (includeParents) {
            parentContext?.forEachState(true, consumer)
        }
    }

    // ========================================================================
    // Path / Pattern
    // ========================================================================

    /**
     * The current request path.
     *
     * This path may have been rewritten when mounted as a sub-app.
     *
     * Examples:
     * - fullPath: "/admin/users/1"
     * - path: "/users/1"
     */
    val path: String get() = request.path

    /**
     * The original request path as received by the server.
     */
    val fullPath: String
        get() = parentContext?.fullPath ?: request.path

    /**
     * The route pattern matched at the current Context level.
     *
     * This value represents the route pattern defined in the
     * application that owns this Context.
     *
     * Example:
     * ```
     * app.mount("/api", subApp)
     * subApp.get("/users/{id}")
     *
     * Request: GET /api/users/1
     *
     * Context in app      -> pattern = "/api/users/{id}"
     * Context in subApp   -> pattern = "/users/{id}"
     * ```
     *
     * Notes:
     * - This value is set **only after route matching completes**.
     * - It is `null` before routing.
     * - In middleware, it MUST be accessed **after `next()` returns**.
     */
    var pattern: String? = null
        internal set

    /**
     * The fully qualified matched route pattern for this request.
     *
     * Notes:
     * - Returns `null` before route matching completes.
     * - In middleware, it MUST be accessed **after `next()` returns**.
     */
    val fullPattern: String?
        get() = run {
            val prefix = when {
                path.isEmpty() || path == "/" -> fullPath
                else -> fullPath.removeSuffix(path)
            }
            UrlPath.join(prefix, pattern ?: return null)
        }

    private val _pathParams = mutableMapOf<String, String>()

    /**
     * Read-only view of path parameter names to their values.
     *
     * For a route "/users/{id}" matching "/users/123", pathParams would contain {"id": "123"}.
     */
    val pathParams: Map<String, String> get() = _pathParams

    /**
     * Retrieves a path parameter value by key.
     *
     * @param key the parameter name
     * @return the parameter value, or `null` if not found
     */
    fun pathParam(key: String): String? = pathParams[key]

    // ========================================================================
    // Service Injection
    // ========================================================================

    /**
     * Retrieves a required service instance.
     *
     * Resolution order:
     * - Current app's service container.
     * - Parent contexts, walking up the chain until found.
     *
     * ```kotlin
     * // No qualifier — original API unchanged
     * val db = ctx.getService<Database>()
     *
     * // Object qualifier (recommended)
     * val primary = ctx.getService<DataSource>(Primary)
     *
     * // String qualifier
     * val primary = ctx.getService<DataSource>("primary")
     * ```
     *
     * @param qualifier optional qualifier to distinguish instances of the same type.
     * @throws IllegalStateException if the service is not registered.
     * @see getServiceOrNull
     */
    inline fun <reified T : Any> getService(qualifier: Any? = null): T =
        resolveService(T::class, qualifier)
            ?: error("Service ${T::class.simpleName}(qualifier=$qualifier) not registered")

    /**
     * Retrieves an optional service instance, or `null` if not registered.
     *
     * Resolution order:
     * - Current app's service container.
     * - Parent contexts, walking up the chain until found.
     *
     * Use this method when the service is optional or conditionally registered.
     *
     * ```kotlin
     * val db = ctx.getServiceOrNull<Database>()
     * val replica = ctx.getServiceOrNull<DataSource>(Replica)
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
     * Useful for plugin-style registrations where multiple implementations of
     * the same interface are registered under different qualifiers.
     *
     * ```kotlin
     * app.provide<EventHandler>(qualifier = "audit")   { AuditHandler() }
     * app.provide<EventHandler>(qualifier = "metrics") { MetricsHandler() }
     *
     * val handlers = ctx.getServices<EventHandler>()
     * handlers.forEach { it.handle(event) }
     * ```
     *
     * Note: only searches the current app's container; parent contexts are not
     * traversed to avoid unintended merging of registrations across app scopes.
     */
    inline fun <reified T : Any> getServices(): List<T> =
        app.serviceContainer.getAll(T::class)

    /**
     * Internal recursive resolver shared by all public retrieval methods.
     *
     * Walks up the context chain (current app → parent contexts) until the
     * service is found, or returns `null` if not registered anywhere.
     *
     * Must be non-inline to allow recursion via [parentContext].
     */
    @PublishedApi
    internal fun <T : Any> resolveService(kClass: KClass<T>, qualifier: Any? = null): T? =
        app.serviceContainer.getOrNull(kClass, qualifier)
            ?: parentContext?.resolveService(kClass, qualifier)

    @PublishedApi
    internal fun resolveQualifier(name: String): Any =
        app.serviceContainer.lookupQualifier(name)
            ?: parentContext?.resolveQualifier(name)
            ?: name

    // ========================================================================
    // Request Delegates
    // ========================================================================

    /**
     * The HTTP method of the request
     */
    val method: String get() = request.method

    /**
     * Checks whether the request accepts the given media type.
     *
     * The provided [type] may be either:
     * - A full MIME type (e.g. `"application/json"`, `"text/html"`), or
     * - A shorthand alias (e.g. `"json"`, `"html"`)
     *
     * If the `Accept` header is missing or empty, the type is treated as accepted.
     *
     * ### Example
     * ```kotlin
     * // Request header:
     * // Accept: application/json, text/html;q=0.8
     *
     * ctx.accepts("json")              // true
     * ctx.accepts("text/html")         // true
     * ctx.accepts("application/xml")  // false
     * ```
     *
     * @param type media type or shorthand alias
     * @return true if an acceptable match is found, false otherwise
     */
    fun accepts(type: String): Boolean = request.accepts(type)

    /**
     * Checks whether the request accepts the given language.
     *
     * Language negotiation is performed against the `Accept-Language` header
     * using quality factors (`q`) and language specificity.
     *
     * Matching rules:
     * - `"*"` matches any language
     * - `"zh"` matches `"zh"` and `"zh-CN"`
     * - `"zh-CN"` matches only `"zh-CN"`
     *
     * If the `Accept-Language` header is missing or empty, the language
     * is treated as accepted.
     *
     * ### Example
     * ```kotlin
     * // Request header:
     * // Accept-Language: zh-CN, en;q=0.8
     *
     * ctx.acceptsLang("zh")     // true
     * ctx.acceptsLang("zh-CN")  // true
     * ctx.acceptsLang("en")     // true
     * ctx.acceptsLang("ja")     // false
     * ```
     *
     * @param lang language tag (e.g. `"en"`, `"en-US"`, `"zh-CN"`)
     * @return true if an acceptable match is found, false otherwise
     */
    fun acceptsLang(lang: String) = request.acceptsLang(lang)

    /**
     * Retrieves a request header value by name.
     *
     * @param key the header name (case-insensitive)
     * @return the header value, or `null` if not present
     */
    fun header(key: String): String? = request.header(key)

    /**
     * Returns all query parameters as a map.
     */
    fun queries() = request.queries

    /**
     * Parses query parameters into an object of type T.
     *
     * @return the parsed object, or `null` if not present
     * @throws BadRequest if parsing fails
     */

    inline fun <reified T> queries(): T? = request.queries(jsonMapper)

    /**
     * Retrieves a query parameter value by name.
     *
     * @param key the query parameter name
     * @return the parameter value, or `null` if not present
     */
    fun query(key: String): String? = request.query(key)

    /**
     * Returns all form parameters as a map.
     */
    fun forms() = request.forms

    /**
     * Parses form parameters into an object of type T.
     *
     * @return the parsed object, or `null` if not present
     * @throws BadRequest if parsing fails
     */

    inline fun <reified T> forms(): T? = request.forms(jsonMapper)

    /**
     * Retrieves a form parameter value by name.
     *
     * @param key the form parameter name
     * @return the parameter value, or `null` if not present
     */
    fun form(key: String): String? = request.form(key)

    /**
     * Reads the request body as plain text.
     *
     * @return the request body text, or `null` if empty
     */
    fun text(): String? = request.text()

    /**
     * Parses the request body as JSON into an object of type T
     *
     * @return the parsed object, or `null` if not present
     * @throws BadRequest if parsing fails
     */
    inline fun <reified T> json(): T? = request.json<T>(jsonMapper)

    /**
     * Retrieves an uploaded file by form field name.
     *
     * @param name the form field name
     * @return the file item, or `null` if not present
     */
    fun file(name: String): FilePart? = request.file(name)

    // ========================================================================
    // Response Delegates
    // ========================================================================

    /**
     * Sets the HTTP response status code.
     *
     * @return this response for chaining
     */
    fun status(code: Int) = response.status(code)

    /**
     * Sets a response header.
     *
     * @return this response for chaining
     */
    fun header(key: String, value: String) = response.header(key, value)

    /**
     * Sends a plain text response.
     */
    fun text(content: String) = response.text(content)

    /**
     * Sends an HTML response.
     */
    fun html(content: String) = response.html(content)

    /**
     * Sends a JSON response.
     * By default, JSON is serialized eagerly into bytes.
     * Set [stream] to true to enable streaming serialization for large payloads.
     */
    @JvmOverloads
    fun json(data: Any?, stream: Boolean = false) = response.json(data, stream)

    /**
     * Sends a binary response.
     */
    @JvmOverloads
    fun bytes(data: ByteArray, contentType: String = "application/octet-stream") = response.bytes(data, contentType)

    /**
     * Establishes a Server-Sent Events (SSE) connection.
     */
    fun sse(handler: Consumer<SseConnection>) = response.sse(handler)

    /**
     * Streams response data from an input stream.
     */
    @JvmOverloads
    fun stream(stream: InputStream, contentType: String = "application/octet-stream") =
        response.stream(stream, contentType)

    /**
     * Sends an HTTP redirect response.
     */
    @JvmOverloads
    fun redirect(url: String, statusCode: Int = 302) = response.redirect(url, statusCode)

    // ========================================================================
    // Internal Methods
    // ========================================================================

    /**
     * Sets a path parameter. Used internally by the router.
     */
    internal fun setPathParam(key: String, value: String) {
        _pathParams[key] = value
    }

    /**
     * Creates a sub-context for a mounted app.
     *
     * Used internally when routing requests to mounted sub-app.
     *
     * @param path the rewritten path for the sub-app
     * @param app the mounted sub-app
     * @return a new context with the current context as parent
     */
    internal fun createSubContext(path: String, app: Colleen): Context {
        return Context(
            request = request.copy(path = path),
            response = response.copy(),
            app = app,
            parentContext = this,
        )
    }

    // ========================================================================
    // Java Compatibility
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
     * Retrieves an optional service instance (Java-compatible), or `null` if
     * not registered.
     *
     * @param clazz     the service class.
     * @param qualifier optional qualifier to distinguish instances of the same type.
     */
    @JvmOverloads
    fun <T : Any> getServiceOrNull(clazz: Class<T>, qualifier: Any? = null): T? =
        resolveService(clazz.kotlin, qualifier)

    /**
     * Retrieves all registered instances of [clazz] type (Java-compatible).
     *
     * @param clazz the service class.
     */
    fun <T : Any> getServices(clazz: Class<T>): List<T> =
        app.serviceContainer.getAll(clazz.kotlin)

    /**
     * Parses query parameters into an object of the specified class (Java-compatible).
     *
     * @param clazz the target class
     * @return the parsed object, or `null` if not present
     * @throws BadRequest if parsing fails
     */
    fun <T> queries(clazz: Class<T>): T? = request.queries(clazz, jsonMapper)

    /**
     * Parses form parameters into an object of the specified class (Java-compatible).
     *
     * @param clazz the target class
     * @return the parsed object, or `null` if not present
     * @throws BadRequest if parsing fails
     */
    fun <T> forms(clazz: Class<T>): T? = request.forms(clazz, jsonMapper)

    /**
     * Parses the request body as JSON using an explicit [TypeRef] (Java-compatible).
     *
     * This method is primarily intended for **Java users** or for cases where
     * full generic type information must be preserved at runtime.
     *
     * Example:
     * ```java
     * List<User> users = ctx.json(TypeRef.listOf(User.class));
     * ```
     *
     * @param typeRef a type reference describing the target type
     * @return the parsed object, or `null` if not present
     * @throws BadRequest if JSON parsing fails
     */
    fun <T> json(typeRef: TypeRef<T>): T? = request.json(jsonMapper, typeRef)

    /**
     * Parses the request body as JSON into an instance of the given Java [Class] (Java-compatible).
     *
     * This is a convenience overload for **non-generic** types and is especially
     * useful for **Java users**.
     *
     * Example:
     * ```java
     * User user = ctx.json(User.class);
     * ```
     *
     * @param clazz the target Java class
     * @return the parsed object, or `null` if not present
     * @throws BadRequest if JSON parsing fails
     */
    fun <T> json(clazz: Class<T>): T? = request.json(jsonMapper, clazz)
}