package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.UrlPath
import io.github.cymoo.colleen.util.isKotlinInstance
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.kotlinFunction
import kotlin.time.measureTime

// ============================================================================
// Path Segments
// ============================================================================

/**
 * Represents a single segment in a path pattern.
 * A segment can now contain multiple parts (static text + parameters).
 */
sealed class PathSegment {
    /**
     * Pure static segment (e.g., "users")
     */
    data class Static(val value: String) : PathSegment()

    /**
     * Pure parameter segment (e.g., "{id}")
     */
    data class Param(val name: String) : PathSegment()

    /**
     * Wildcard segment that matches remaining path (e.g., "{path...}")
     */
    data class Wildcard(val name: String) : PathSegment()

    /**
     * Complex segment with mixed parts (e.g., "{name}-bar" or "prefix-{id}-suffix")
     *
     * Constraints:
     * - Must have at least one parameter and one static text
     * - Cannot have adjacent parameters (e.g., "{a}{b}" is invalid)
     */
    data class Complex(val parts: List<Part>) : PathSegment() {
        sealed class Part {
            data class Text(val value: String) : Part()
            data class Param(val name: String) : Part()
        }

        init {
            require(parts.size >= 2) {
                "Complex segment must have at least 2 parts"
            }
            require(parts.any { it is Part.Param }) {
                "Complex segment must contain at least one parameter"
            }
            // Prevent adjacent parameters: {a}{b} is ambiguous in matching
            val hasAdjacentParams = parts.zipWithNext().any { (first, second) ->
                first is Part.Param && second is Part.Param
            }
            require(!hasAdjacentParams) {
                "Adjacent parameters are not allowed in complex segment"
            }
        }
    }

    companion object {
        private val PARAM_NAME_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

        /**
         * Parses a complete path into segments.
         */
        fun parseAll(path: String): List<PathSegment> {
            if (path == "/" || path.isEmpty()) return emptyList()

            val segments = UrlPath.split(path).map { parse(it) }

            // Validate: no duplicate parameter names across all segments
            val paramNames = mutableSetOf<String>()
            for (segment in segments) {
                val names = extractParamNames(segment)
                for (name in names) {
                    require(name !in paramNames) {
                        "Duplicate parameter name '$name' in path: $path"
                    }
                    paramNames.add(name)
                }
            }

            // Validate: wildcard must be last segment
            val wildcardIndex = segments.indexOfFirst { it is Wildcard }
            if (wildcardIndex != -1 && wildcardIndex != segments.size - 1) {
                throw IllegalArgumentException(
                    "Wildcard parameter must be the last segment in path: $path"
                )
            }

            return segments
        }

        /**
         * Parses a single path segment into appropriate type.
         *
         * Examples:
         * - "users" -> Static("users")
         * - "{id}" -> Param("id")
         * - "{path...}" -> Wildcard("path")
         * - "{name}-bar" -> Complex([Param("name"), Text("-bar")])
         */
        fun parse(segment: String): PathSegment {
            tryParseColonParamSegment(segment)?.let { return it }

            // Scan for all parameter placeholders: {...}
            val params = findParameters(segment)

            return when {
                // No parameters: pure static segment
                params.isEmpty() -> Static(segment)

                // Single parameter that spans entire segment
                params.size == 1 && params[0].let { it.start == 0 && it.end == segment.length } -> {
                    val content = segment.substring(1, segment.length - 1).trim()
                    require(content.isNotBlank()) {
                        "Empty parameter name in segment: $segment"
                    }

                    if (content.endsWith("...")) {
                        // Wildcard: {path...}
                        val name = content.dropLast(3).trim()
                        require(name.isNotBlank()) {
                            "Empty wildcard name in segment: $segment"
                        }
                        require(isValidParamName(name)) {
                            "Invalid wildcard name '$name' in segment: $segment"
                        }
                        Wildcard(name)
                    } else {
                        // Regular parameter: {id}
                        require(isValidParamName(content)) {
                            "Invalid parameter name '$content' in segment: $segment"
                        }
                        Param(content)
                    }
                }

                // Multiple parameters or mixed with static text
                else -> {
                    val parts = buildComplexParts(segment, params)
                    Complex(parts)
                }
            }
        }

        /**
         * Parses colon-style parameter segments (e.g. ":id") as a shorthand for "{id}".
         *
         * This syntax is only allowed for pure parameter segments. Complex segments
         * and wildcard parameters must use the standard "{...}" form.
         */
        private fun tryParseColonParamSegment(segment: String): Param? {
            if (!segment.startsWith(":")) return null

            val name = segment.drop(1).trim()

            require(name.isNotBlank()) {
                "Empty parameter name in segment: $segment"
            }
            require(!name.endsWith("...")) {
                "Wildcard must use {...} syntax: $segment"
            }
            require(isValidParamName(name)) {
                "Invalid parameter name '$name' in segment: $segment"
            }

            return Param(name)
        }

        /**
         * Finds all parameter placeholders {...} in a segment.
         * Handles nested braces correctly via depth tracking.
         *
         * @return list of parameter ranges (start and end indices)
         */
        private fun findParameters(segment: String): List<ParamRange> {
            val params = mutableListOf<ParamRange>()
            var i = 0

            while (i < segment.length) {
                if (segment[i] == '{') {
                    val start = i
                    var depth = 1
                    i++

                    // Track brace depth to handle potential nesting
                    while (i < segment.length && depth > 0) {
                        when (segment[i]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        i++
                    }

                    require(depth == 0) {
                        "Unmatched '{' in segment: $segment"
                    }
                    params.add(ParamRange(start, i))
                } else {
                    i++
                }
            }

            return params
        }

        /**
         * Builds complex segment parts from segment string and parameter ranges.
         * Extracts alternating static text and parameters.
         */
        private fun buildComplexParts(segment: String, params: List<ParamRange>): List<Complex.Part> {
            val parts = mutableListOf<Complex.Part>()
            var lastEnd = 0

            for (param in params) {
                // Add static text before this parameter (if any)
                if (param.start > lastEnd) {
                    val text = segment.substring(lastEnd, param.start)
                    parts.add(Complex.Part.Text(text))
                }

                // Extract and validate parameter name
                val content = segment.substring(param.start + 1, param.end - 1).trim()
                require(content.isNotBlank()) {
                    "Empty parameter name in segment: $segment"
                }
                require(!content.endsWith("...")) {
                    "Wildcard not allowed in complex segment: $segment"
                }
                require(isValidParamName(content)) {
                    "Invalid parameter name '$content' in segment: $segment"
                }

                parts.add(Complex.Part.Param(content))
                lastEnd = param.end
            }

            // Add remaining static text after last parameter (if any)
            if (lastEnd < segment.length) {
                val text = segment.substring(lastEnd)
                parts.add(Complex.Part.Text(text))
            }

            return parts
        }

        /**
         * Extracts all parameter names from a segment.
         */
        private fun extractParamNames(segment: PathSegment): List<String> {
            return when (segment) {
                is Static -> emptyList()
                is Param -> listOf(segment.name)
                is Wildcard -> listOf(segment.name)
                is Complex -> segment.parts.filterIsInstance<Complex.Part.Param>().map { it.name }
            }
        }

        /**
         * Validates parameter name as a legal identifier.
         * Rules: starts with letter or underscore, followed by alphanumeric or underscore.
         *
         * Valid: id, userId, user_id, _internal
         * Invalid: 123, user-id, user@id
         */
        private fun isValidParamName(name: String): Boolean {
            return name.matches(PARAM_NAME_REGEX)
        }

        /**
         * Calculates priority for route matching.
         * Higher priority = more specific match.
         *
         * Priority order: Static > Complex > Param > Wildcard
         *
         * Examples:
         * - /users/admin -> higher priority
         * - /users/{id} -> lower priority
         * - /files/{name}.txt -> higher than /files/{name}
         */
        fun priority(segments: List<PathSegment>): Int {
            var priority = 0
            for (segment in segments) {
                priority = priority * 4 + when (segment) {
                    is Static -> 3
                    is Complex -> 2
                    is Param -> 1
                    is Wildcard -> 0
                }
            }
            return priority
        }

        private data class ParamRange(val start: Int, val end: Int)
    }
}

// ============================================================================
// Match Result
// ============================================================================

/**
 * Result of a path matching operation.
 *
 * @property matched whether the match succeeded
 * @property params extracted path parameters
 * @property consumedSegments number of segments consumed (for prefix matching)
 */
data class MatchResult(
    val matched: Boolean,
    val params: Map<String, String> = emptyMap(),
    val consumedSegments: Int = 0
) {
    companion object {
        val NO_MATCH = MatchResult(matched = false)

        fun success(params: Map<String, String> = emptyMap(), consumedSegments: Int = 0) =
            MatchResult(matched = true, params = params, consumedSegments = consumedSegments)
    }
}

// ============================================================================
// Path Matcher
// ============================================================================

/**
 * Matches request paths against path patterns.
 */
object PathMatcher {
    /**
     * Maximum allowed path length
     */
    private const val MAX_PATH_LENGTH = 4096

    /**
     * Maximum allowed path segments
     */
    private const val MAX_PATH_SEGMENTS = 100

    /**
     * Matches a request path against a pattern.
     *
     * @param requestPath the incoming request path
     * @param pathPattern the path pattern to match against
     * @param exactMatch if true, all request segments must be consumed; if false, allows prefix matching
     * @return the match result
     */
    fun match(
        requestPath: String,
        pathPattern: String,
        exactMatch: Boolean
    ): MatchResult {
        // Fast-fail protections
        if (requestPath.length > MAX_PATH_LENGTH) {
            return MatchResult.NO_MATCH
        }

        val requestSegments = UrlPath.split(requestPath)
        if (requestSegments.size > MAX_PATH_SEGMENTS) {
            return MatchResult.NO_MATCH
        }

        val patternSegments = PathSegment.parseAll(pathPattern)

        // Empty pattern handling
        if (patternSegments.isEmpty()) {
            return if (exactMatch) {
                // Exact match: only matches root path "/"
                if (requestSegments.isEmpty()) {
                    MatchResult.success(consumedSegments = 0)
                } else {
                    MatchResult.NO_MATCH
                }
            } else {
                // Prefix match: matches all paths (global middleware)
                MatchResult.success(consumedSegments = 0)
            }
        }

        // NOTE:
        // Path segments here are already URL-decoded by the underlying HTTP server
        // (e.g. Undertow / Netty / Servlet container).
        // Do NOT apply URL decoding again, otherwise it may cause double-decoding issues
        // (e.g. "%2F" -> "/" -> invalid path structure).
        val params = mutableMapOf<String, String>()
        var reqIdx = 0
        var patternIdx = 0

        while (patternIdx < patternSegments.size) {
            when (val pattern = patternSegments[patternIdx]) {
                is PathSegment.Static -> {
                    if (reqIdx >= requestSegments.size || requestSegments[reqIdx] != pattern.value) {
                        return MatchResult.NO_MATCH
                    }
                    reqIdx++
                    patternIdx++
                }

                is PathSegment.Param -> {
                    if (reqIdx >= requestSegments.size) {
                        return MatchResult.NO_MATCH
                    }
                    val value = requestSegments[reqIdx]
                    if (value.isEmpty()) {
                        return MatchResult.NO_MATCH
                    }
                    params[pattern.name] = value
                    reqIdx++
                    patternIdx++
                }

                is PathSegment.Wildcard -> {
                    val remaining = requestSegments.subList(reqIdx, requestSegments.size)
                    params[pattern.name] = remaining.joinToString("/")
                    reqIdx = requestSegments.size
                    patternIdx++
                }

                is PathSegment.Complex -> {
                    if (reqIdx >= requestSegments.size) {
                        return MatchResult.NO_MATCH
                    }
                    val extractedParams = matchComplexSegment(pattern, requestSegments[reqIdx])
                        ?: return MatchResult.NO_MATCH

                    params.putAll(extractedParams)
                    reqIdx++
                    patternIdx++
                }
            }
        }

        // Exact match: all request segments must be consumed
        if (exactMatch && reqIdx < requestSegments.size) {
            return MatchResult.NO_MATCH
        }

        return MatchResult.success(params = params, consumedSegments = reqIdx)
    }

    /**
     * Matches a complex segment against a value.
     * Uses greedy matching: each parameter captures until the next static delimiter.
     *
     * Example: pattern "{name}-{version}.txt" matching "foo-1.2.3.txt"
     * - name = "foo"
     * - version = "1.2.3"
     *
     * @param segment the complex path segment pattern
     * @param value the actual path segment value to match
     * @return map of extracted parameters if matched, null if not matched
     */
    private fun matchComplexSegment(
        segment: PathSegment.Complex,
        value: String
    ): Map<String, String>? {
        val params = mutableMapOf<String, String>()
        var pos = 0

        for ((index, part) in segment.parts.withIndex()) {
            when (part) {
                is PathSegment.Complex.Part.Text -> {
                    // Static text must match exactly at current position
                    if (pos + part.value.length > value.length) return null
                    if (!value.startsWith(part.value, pos)) return null
                    pos += part.value.length
                }

                is PathSegment.Complex.Part.Param -> {
                    if (pos >= value.length) return null

                    // Determine how much to capture for this parameter
                    val paramValue = when (val nextPart = segment.parts.getOrNull(index + 1)) {
                        is PathSegment.Complex.Part.Text -> {
                            // Find next static text as delimiter (greedy match)
                            val delimiterIndex = value.indexOf(nextPart.value, pos)
                            if (delimiterIndex == -1) return null
                            value.substring(pos, delimiterIndex)
                        }

                        is PathSegment.Complex.Part.Param -> {
                            // This should never happen due to init validation
                            error("Adjacent parameters should be prevented by Complex.init")
                        }

                        null -> {
                            // Last part: take everything remaining
                            value.substring(pos)
                        }
                    }

                    // Empty parameter values are not allowed
                    if (paramValue.isEmpty()) return null

                    params[part.name] = paramValue
                    pos += paramValue.length
                }
            }
        }

        // Must consume entire value exactly
        return if (pos == value.length) params else null
    }
}

/**
 * Represents an executable request handler bound to a route.
 *
 * A [RouteHandler] abstracts over different handler forms
 * (lambda, Kotlin function, or Java method) and exposes
 * a unified invocation model to the routing system.
 *
 * Implementations are responsible for adapting their underlying
 * callable into a common [Context] -> Any? contract.
 *
 * The returned value is passed to the response handling pipeline.
 */
sealed interface RouteHandler {
    /**
     * Invokes the handler with the given request [Context].
     *
     * Note: Although the signature allows null (`Any?`), returning null at runtime
     * will throw an error to enforce semantic clarity. Use `Unit` for empty responses
     * or `json(null)` for explicit JSON null values.
     */
    operator fun invoke(ctx: Context): Any?

    /**
     * A handler backed by a simple Kotlin or Java lambda.
     *
     * This is the most common handler form used by the routing DSL.
     */
    class Lambda(val lambda: Handler) : RouteHandler {
        override fun invoke(ctx: Context): Any? {
            return lambda.invoke(ctx)
        }
    }

    /**
     * A handler backed by a Kotlin reflective function.
     *
     * The function is adapted once at construction time into
     * an efficient invocation form to avoid repeated reflection
     * during request handling.
     */
    class KFunction(val fn: kotlin.reflect.KFunction<*>, val instance: Any?) : RouteHandler {
        private val handler = cx(fn, instance)
        override fun invoke(ctx: Context): Any? {
            return handler.invoke(ctx)
        }
    }

    /**
     * A handler backed by a Java reflective method.
     *
     * Similar to [KFunction], the reflective method is converted
     * into a cached invocation function during construction.
     */
    class JavaMethod(val method: Method, val instance: Any?) : RouteHandler {
        private val handler = cx(method, instance)
        override fun invoke(ctx: Context): Any? {
            return handler.invoke(ctx)
        }
    }
}

// ============================================================================
// Route Node
// ============================================================================

/**
 * Represents a registered route with handler.
 *
 * @property method HTTP method or "*" for all methods
 * @property path path pattern
 * @property handler the request handler
 */

class RouteNode private constructor(
    val method: String,
    val path: String,
    val handler: RouteHandler,
) {
    companion object {
        private val VALID_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        fun of(method: String, path: String, handler: RouteHandler): RouteNode {
            val normalizedMethod = normalizeMethod(method)
            val normalizedPath = UrlPath.normalize(path)

            return RouteNode(method = normalizedMethod, path = normalizedPath, handler = handler)
        }

        internal fun normalizeMethod(rawMethod: String): String {
            val method = rawMethod.uppercase()

            if (method == "*") return method

            require(method in VALID_HTTP_METHODS) {
                "Unsupported HTTP method '$rawMethod'"
            }

            return method
        }
    }

    // Routing priority (higher = more specific)
    val priority: Int = run {
        val segments = PathSegment.parseAll(path)
        PathSegment.priority(segments)
    }

    /**
     * Matches request path (exact match).
     */
    fun matchesPath(requestPath: String): MatchResult {
        return PathMatcher.match(requestPath, path, exactMatch = true)
    }

    /**
     * Matches both path and HTTP method.
     */
    fun matchesPathAndMethod(ctx: Context): MatchResult {
        if (method != "*" && method != ctx.method) {
            return MatchResult.NO_MATCH
        }

        val pathResult = matchesPath(ctx.path)
        if (!pathResult.matched) {
            return MatchResult.NO_MATCH
        }

        return pathResult
    }

    override fun toString() = "RouteNode(method=$method, path=$path)"
}

// ============================================================================
// Middleware Node
// ============================================================================

/**
 * Represents different types of middleware with their matching logic.
 */
sealed class MiddlewareNode {
    abstract val middleware: Middleware
    abstract fun match(ctx: Context): MatchResult

    /**
     * Global middleware that matches all requests.
     */
    class Global(
        override val middleware: Middleware,
    ) : MiddlewareNode() {
        override fun match(ctx: Context) = MatchResult.success()
    }

    /**
     * Prefix-based middleware that matches by prefix.
     */
    class Prefix private constructor(
        val prefix: String,
        override val middleware: Middleware,
    ) : MiddlewareNode() {
        companion object {
            fun of(prefix: String, middleware: Middleware): Prefix {
                return Prefix(UrlPath.normalize(prefix), middleware)
            }
        }

        override fun match(ctx: Context): MatchResult {
            return PathMatcher.match(ctx.path, prefix, exactMatch = false)
        }
    }

    /**
     * Per-route middleware that matches exact path + method.
     */
    class PerRoute private constructor(
        val method: String,
        val path: String,
        override val middleware: Middleware,
    ) : MiddlewareNode() {
        companion object {
            fun of(method: String, path: String, middleware: Middleware): PerRoute {
                val normalizedMethod = RouteNode.normalizeMethod(method)
                val normalizedPath = UrlPath.normalize(path)
                return PerRoute(normalizedMethod, normalizedPath, middleware)
            }
        }

        override fun match(ctx: Context): MatchResult {
            return if (method == ctx.method) {
                PathMatcher.match(ctx.path, path, exactMatch = true)
            } else {
                MatchResult.NO_MATCH
            }
        }
    }

    /**
     * Conditional middleware that matches based on predicate.
     */
    class Conditional(
        val predicate: (Context) -> Boolean,
        override val middleware: Middleware,
    ) : MiddlewareNode() {
        override fun match(ctx: Context): MatchResult {
            return if (predicate(ctx)) MatchResult.success() else MatchResult.NO_MATCH
        }
    }
}

// ============================================================================
// Mount Node
// ============================================================================

/**
 * Represents a mounted sub-application.
 *
 * @property prefix mount path
 * @property app the sub-application
 */
class MountNode private constructor(
    val prefix: String,
    val app: Colleen,
) {
    init {
        val segments = PathSegment.parseAll(prefix)
        require(segments.all { it is PathSegment.Static }) {
            "Mount path cannot contain parameters"
        }
    }

    companion object {
        fun of(prefix: String, app: Colleen): MountNode {
            return MountNode(UrlPath.normalize(prefix), app)
        }
    }

    /**
     * Matches request path by prefix.
     */
    fun match(ctx: Context): MatchResult {
        return PathMatcher.match(ctx.path, prefix, exactMatch = false)
    }
}

// ============================================================================
// Router
// ============================================================================

/**
 * Core router that handles request dispatching.
 */
internal class Router {
    internal val controllers = CopyOnWriteArrayList<Pair<String, Any>>()
    internal val middlewares = CopyOnWriteArrayList<MiddlewareNode>()
    internal val routes = CopyOnWriteArrayList<RouteNode>()
    internal val mounts = CopyOnWriteArrayList<MountNode>()

    // ========================================================================
    // Registration
    // ========================================================================

    fun addMiddleware(node: MiddlewareNode) = middlewares.add(node)

    /**
     * Registers a route handler.
     */
    fun addRoute(node: RouteNode) = routes.add(node)

    /**
     * Registers a mounted sub-application.
     */
    fun addMount(node: MountNode) = mounts.add(node)

    /**
     * Registers a controller object and add all of its routes and middlewares
     * under the given path prefix.
     *
     * This method performs the following steps:
     * 1. Scans the controller object for metadata (base path, routes, middlewares).
     * 2. Resolves the final base path by combining the provided prefix and the controller's base path.
     * 3. Registers all controller-level middlewares.
     * 4. Registers all route handlers, with Kotlin and Java reflection handled separately.
     *
     * Both Kotlin functions and Java methods are supported:
     * - Kotlin functions are wrapped as [RouteHandler.KFunction]
     * - Java methods are wrapped as [RouteHandler.JavaMethod]
     *
     * @param prefix URL prefix under which the controller is mounted
     * @param obj the controller instance to register
     */
    fun addController(prefix: String, obj: Any) {
        controllers.add(prefix to obj)

        val isKotlin = isKotlinInstance(obj)
        val controllerMeta = ControllerScanner.scan(obj)

        // Combine global prefix and controller-level base path
        val basePath = UrlPath.join(prefix, controllerMeta.basePath)
        val obj = controllerMeta.obj

        // Register controller-level middlewares
        controllerMeta.middlewares.forEach {
            addMiddleware(MiddlewareNode.Prefix.of(basePath) { ctx, next ->
                it.invoke(obj, ctx, next)
            })
        }

        // Register all routes defined in the controller
        controllerMeta.routes.forEach {
            val path = UrlPath.join(basePath, it.path)
            if (isKotlin) {
                addRoute(
                    RouteNode.of(
                        it.method,
                        path,
                        RouteHandler.KFunction(it.handler.kotlinFunction!!, obj)
                    )
                )
            } else {
                addRoute(
                    RouteNode.of(
                        it.method,
                        path,
                        RouteHandler.JavaMethod(it.handler, obj)
                    )
                )
            }
        }
    }

    // ========================================================================
    // Request Handling
    // ========================================================================

    /**
     * Handles an incoming request.
     *
     * Execution flow:
     * 1. Collect matching middlewares
     * 2. Try to match routes → execute chain and return if matched
     * 3. Try to match mounts → recursively call sub-apps
     * 4. Check for 405 (path matches but method doesn't)
     * 5. Throw 404
     *
     * @param ctx the request context
     * @throws NotFound if no route matches
     * @throws MethodNotAllowed if path matches but method doesn't
     */
    fun handleRequest(ctx: Context) {
        // 1. Collect matching middlewares
        val matchedMiddlewares = findMatchedMiddlewares(ctx)

        // 2. Try to match routes
        val matchedRoute = findBestMatchedRoute(ctx)

        if (matchedRoute != null) {
            val (route, matchResult) = matchedRoute
            ctx.pattern = route.path
            executeMiddlewareChain(ctx, matchedMiddlewares) {
                executeRoute(ctx, route, matchResult)
            }
            return
        }

        // 3. Try to match mounts
        val matchedMounts = findMatchedMounts(ctx)

        if (matchedMounts.isNotEmpty()) {
            executeMiddlewareChain(ctx, matchedMiddlewares) {
                var notFound = false
                var methodNotAllowed = false
                for ((mount, matchResult) in matchedMounts) {
                    try {
                        val subCtx = executeMount(ctx, mount, matchResult)
                        val subPattern = subCtx.pattern
                        ctx.response.merge(subCtx.response)
                        if (subPattern != null) {
                            ctx.pattern = UrlPath.join(mount.prefix, subPattern)
                        }
                        notFound = false
                        methodNotAllowed = false
                        // This sub-app handled successfully
                        break
                    } catch (_: NotFound) {
                        notFound = true
                        methodNotAllowed = false
                        // This sub-app returned 404, try next mount
                        continue
                    } catch (_: MethodNotAllowed) {
                        notFound = false
                        methodNotAllowed = true
                        // This sub-app returned 405, try next mount
                        continue
                    }
                }
                if (methodNotAllowed) {
                    throw RouteMethodNotAllowed(ctx.fullPath, ctx.method)
                }
                if (notFound) {
                    throw RouteNotFound(ctx.fullPath, ctx.method)
                }
            }
        } else {
            // Execute middleware chain only if no mounts were tried
            executeMiddlewareChain(ctx, matchedMiddlewares) {
                //  Check for 405: path matches but method doesn't
                val allowedMethods = findAllowedMethods(ctx.path)
                if (allowedMethods.isNotEmpty()) {
                    throw RouteMethodNotAllowed(ctx.fullPath, ctx.method, allowedMethods)
                } else {
                    throw RouteNotFound(ctx.fullPath, ctx.method)
                }
            }
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Collects all matching middlewares with their match results.
     */
    private fun findMatchedMiddlewares(ctx: Context): List<Pair<MiddlewareNode, MatchResult>> {
        return middlewares.mapNotNull { mw ->
            val result = mw.match(ctx)
            if (result.matched) mw to result else null
        }
    }

    /**
     * Finds the first and most specific matching route.
     */
    private fun findBestMatchedRoute(ctx: Context): Pair<RouteNode, MatchResult>? {
        val matches = routes.mapNotNull { route ->
            val matchResult = route.matchesPathAndMethod(ctx)
            if (matchResult.matched) route to matchResult
            else null
        }
        return matches.maxByOrNull { it.first.priority }
    }

    /**
     * Collects all matching mounts.
     */
    private fun findMatchedMounts(ctx: Context): List<Pair<MountNode, MatchResult>> {
        return mounts
            .mapNotNull { mount ->
                val result = mount.match(ctx)
                if (result.matched) mount to result else null
            }
    }

    /**
     * Finds allowed HTTP methods for a given path (for 405 handling).
     */
    private fun findAllowedMethods(path: String): Set<String> {
        return routes
            .filter { it.matchesPath(path).matched }
            .map { it.method }
            .filter { it != "*" }
            .toSet()
    }

    /**
     * Executes the middleware chain with onion model.
     *
     * @param ctx the request context
     * @param middlewares the matched middlewares
     * @param finalAction the action to execute at the end of the chain
     */
    private fun executeMiddlewareChain(
        ctx: Context,
        middlewares: List<Pair<MiddlewareNode, MatchResult>>,
        finalAction: () -> Unit
    ) {
        fun mergeErrors(downstream: Throwable?, current: Throwable?): Throwable? {
            if (downstream == null) return current
            if (current == null) return downstream

            if (current !== downstream) {
                downstream.addSuppressed(current)
            }
            return downstream
        }

        /**
         * dispatch returns the error (if any) that occurred in this layer or downstream.
         * It does NOT throw — caller (outermost) will throw the returned Throwable if non-null.
         */
        fun dispatch(index: Int): ErrorState? {
            // handler / finalAction
            if (index >= middlewares.size) {
                return try {
                    finalAction()
                    null
                } catch (e: Throwable) {
                    ErrorState(e)
                }
            }

            val (node, match) = middlewares[index]
            for ((k, v) in match.params) ctx.setPathParam(k, v)

            var nextCalled = false
            var downstreamErrorState: ErrorState? = null

            val next = {
                if (nextCalled) throw IllegalStateException("next() called multiple times at index $index")
                nextCalled = true
                // do not throw here — capture downstream error so after always runs
                downstreamErrorState = dispatch(index + 1)
                if (downstreamErrorState != null) {
                    ctx.error = downstreamErrorState
                }
            }

            return runCatching {
                ctx.app.eventBus.emit(Event.MiddlewareExecuting(ctx, node))
                val duration = measureTime { node.middleware(ctx, next) }
                ctx.app.eventBus.emit(Event.MiddlewareExecuted(ctx, node, duration))
            }.exceptionOrNull().let { currentError ->
                val downstream = downstreamErrorState

                when {
                    currentError == null -> downstream
                    downstream == null -> ErrorState(currentError)
                    else -> {
                        val merged = mergeErrors(downstream.cause, currentError)!!
                        downstream.copy(cause = merged)
                    }
                }.also { finalState ->
                    if (finalState != null) ctx.error = finalState
                }
            }
        }

        dispatch(0)?.let {
            ctx.error = null
            if (!it.handled) throw it.cause
        }
    }

    /**
     * Executes a route handler.
     */
    private fun executeRoute(ctx: Context, route: RouteNode, matchResult: MatchResult) {
        // Apply path parameters
        matchResult.params.forEach { (key, value) ->
            ctx.setPathParam(key, value)
        }

        // Execute handler
        ctx.app.eventBus.emit(Event.HandlerExecuting(ctx, route))
        try {
            val duration = measureTime {
                val result = route.handler(ctx)
                ctx.response.applyHandlerResult(result)
            }
            ctx.app.eventBus.emit(Event.HandlerExecuted(ctx, route, duration))
        } catch (err: ExtractionException) {
            throw BadRequest(message = err.message ?: "Bad Request", cause = err)
        }
    }

    /**
     * Executes a mounted sub-application.
     *
     * @throws NotFound if sub-app doesn't handle the request
     * @throws MethodNotAllowed if sub-app has path but not method
     */
    private fun executeMount(ctx: Context, mount: MountNode, matchResult: MatchResult): Context {
        // Calculate sub-path by removing mount prefix
        val pathSegments = UrlPath.split(ctx.path)
        val subSegments = pathSegments.drop(matchResult.consumedSegments)
        val subPath = if (subSegments.isEmpty()) "/" else "/${subSegments.joinToString("/")}"

        // Create sub-context
        val subCtx = ctx.createSubContext(path = subPath, app = mount.app)

        ctx.app.eventBus.emit(Event.SubAppExecuting(ctx, mount))
        // Recursively call sub-app
        val duration = measureTime { mount.app.handleRequest(subCtx) }
        ctx.app.eventBus.emit(Event.SubAppExecuted(ctx, mount, duration))

        return subCtx
    }
}

// ============================================================================
// Route Builder DSL
// ============================================================================

/**
 * DSL builder for defining routes with prefix.
 */
class RouteBuilder internal constructor(
    private val app: Colleen,
    private val prefix: String = ""
) {
    fun use(middleware: Middleware) = app.use(prefix, middleware)
    fun use(prefix: String, middleware: Middleware) =
        app.use(UrlPath.join(this@RouteBuilder.prefix, prefix), middleware)

    fun use(predicate: (Context) -> Boolean, middleware: Middleware) = app.use(predicate, middleware)

    fun addController(obj: Any) = app.addController(prefix, obj)
    fun addController(prefix: String, obj: Any) = app.addController(UrlPath.join(this@RouteBuilder.prefix, prefix), obj)

    fun get(path: String, handler: Handler) = app.get(UrlPath.join(prefix, path), handler)
    fun get(path: String, handler: KFunction<*>) = app.get(UrlPath.join(prefix, path), handler)
    fun post(path: String, handler: Handler) = app.post(UrlPath.join(prefix, path), handler)
    fun post(path: String, handler: KFunction<*>) = app.post(UrlPath.join(prefix, path), handler)
    fun put(path: String, handler: Handler) = app.put(UrlPath.join(prefix, path), handler)
    fun put(path: String, handler: KFunction<*>) = app.put(UrlPath.join(prefix, path), handler)
    fun delete(path: String, handler: Handler) = app.delete(UrlPath.join(prefix, path), handler)
    fun delete(path: String, handler: KFunction<*>) = app.delete(UrlPath.join(prefix, path), handler)
    fun patch(path: String, handler: Handler) = app.patch(UrlPath.join(prefix, path), handler)
    fun patch(path: String, handler: KFunction<*>) = app.patch(UrlPath.join(prefix, path), handler)
    fun head(path: String, handler: Handler) = app.head(UrlPath.join(prefix, path), handler)
    fun head(path: String, handler: KFunction<*>) = app.head(UrlPath.join(prefix, path), handler)
    fun options(path: String, handler: Handler) = app.options(UrlPath.join(prefix, path), handler)
    fun options(path: String, handler: KFunction<*>) = app.options(UrlPath.join(prefix, path), handler)
    fun all(path: String, handler: Handler) = app.all(UrlPath.join(prefix, path), handler)
    fun all(path: String, handler: KFunction<*>) = app.all(UrlPath.join(prefix, path), handler)

    /**
     * Route builder with per-route middleware support.
     */
    fun get(path: String) = RouteWithMiddlewares("GET", UrlPath.join(prefix, path))
    fun post(path: String) = RouteWithMiddlewares("POST", UrlPath.join(prefix, path))
    fun put(path: String) = RouteWithMiddlewares("PUT", UrlPath.join(prefix, path))
    fun delete(path: String) = RouteWithMiddlewares("DELETE", UrlPath.join(prefix, path))
    fun patch(path: String) = RouteWithMiddlewares("PATCH", UrlPath.join(prefix, path))
    fun head(path: String) = RouteWithMiddlewares("HEAD", UrlPath.join(prefix, path))
    fun options(path: String) = RouteWithMiddlewares("OPTIONS", UrlPath.join(prefix, path))
    fun all(path: String) = RouteWithMiddlewares("*", UrlPath.join(prefix, path))

    /**
     * Creates a nested route group.
     */
    fun group(prefix: String, block: RouteBuilder.() -> Unit) {
        val fullPath = UrlPath.join(this@RouteBuilder.prefix, prefix)
        RouteBuilder(app, fullPath).apply(block)
    }

    /**
     * Builder for routes with per-route middlewares.
     */
    inner class RouteWithMiddlewares(
        private val method: String,
        private val path: String,
    ) {
        /**
         * Adds a per-route middleware.
         */
        fun use(middleware: Middleware): RouteWithMiddlewares {
            app.use(method, path, middleware)
            return this
        }

        /**
         * Sets the final handler.
         */
        fun handle(handler: Handler) = app.addRoute(method, path, handler)

        /**
         * Sets the final handler.
         */
        fun handle(handler: KFunction<*>) = app.addRoute(method, path, handler)

        // Java Compatibility
        fun handle(handler: VoidHandler) = handle { ctx -> handler.invoke(ctx) }
    }

    // ========================================================================
    // Java Compatibility
    // ========================================================================

    fun get(path: String, handler: VoidHandler) = get(path) { ctx -> handler.invoke(ctx) }
    fun post(path: String, handler: VoidHandler) = post(path) { ctx -> handler.invoke(ctx) }
    fun put(path: String, handler: VoidHandler) = put(path) { ctx -> handler.invoke(ctx) }
    fun delete(path: String, handler: VoidHandler) = delete(path) { ctx -> handler.invoke(ctx) }
    fun patch(path: String, handler: VoidHandler) = patch(path) { ctx -> handler.invoke(ctx) }
    fun head(path: String, handler: VoidHandler) = head(path) { ctx -> handler.invoke(ctx) }
    fun options(path: String, handler: VoidHandler) = options(path) { ctx -> handler.invoke(ctx) }
    fun all(path: String, handler: VoidHandler) = all(path) { ctx -> handler.invoke(ctx) }
    fun group(prefix: String, block: GroupBlock) = group(prefix) { block.apply(this) }
}

@FunctionalInterface
fun interface GroupBlock {
    fun apply(builder: RouteBuilder)
}
