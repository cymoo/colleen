package io.github.cymoo.colleen

import io.github.cymoo.colleen.ws.Ws
import java.lang.reflect.Method
import kotlin.reflect.full.findAnnotation

/**
 * Data class representing controller metadata
 * @property basePath The base URL path for all routes in this controller
 * @property middlewares List of middleware methods to be applied
 * @property routes List of route definitions
 * @property wsRoutes List of WebSocket route definitions
 * @property obj The controller instance
 */
internal data class ControllerInfo(
    val basePath: String,
    val middlewares: List<Method>,
    val routes: List<RouteInfo>,
    val wsRoutes: List<WsRouteInfo>,
    val obj: Any
)

/**
 * Data class representing a single route definition
 * @property path The URL path pattern
 * @property method The HTTP method (GET, POST, etc.)
 * @property handler The method to handle this route
 */
internal data class RouteInfo(
    val path: String,
    val method: String,
    val handler: Method
)

/**
 * Data class representing a WebSocket route definition
 * @property path The URL path pattern
 * @property handler The method to handle this WebSocket endpoint
 */
internal data class WsRouteInfo(
    val path: String,
    val handler: Method
)

/**
 * Lightweight controller scanner that discovers routes and middlewares
 * via reflection on annotated methods
 */
internal object ControllerScanner {

    /**
     * Scans a controller instance for @Controller, @Use, and HTTP method annotations
     * @param obj The controller instance to scan
     * @return ControllerInfo containing all discovered metadata
     */
    fun scan(obj: Any): ControllerInfo {
        val klass = obj::class
        val javaClass = obj.javaClass

        // Extract base path from @Controller annotation, default to "/"
        val basePath = klass.findAnnotation<Controller>()?.value ?: "/"

        // Use Java reflection to get all methods (works for both Kotlin and Java classes)
        val allMethods = javaClass.declaredMethods

        // Scan for middleware methods annotated with @Use
        val middlewares = allMethods
            .filter { it.getAnnotation(Use::class.java) != null }
            .onEach {
                validateMiddleware(it)
                it.isAccessible = true
            }
            .toList()

        // Scan for route methods with HTTP annotations
        // Supports multiple annotations per method
        val routes = allMethods.flatMap { method ->
            extractRoutes(method).also {
                // Make method accessible only if it has routes
                if (it.isNotEmpty()) {
                    method.isAccessible = true
                }
            }
        }

        // Scan for WebSocket methods annotated with @Ws
        val wsRoutes = allMethods.flatMap { method ->
            extractWsRoutes(method).also {
                if (it.isNotEmpty()) {
                    method.isAccessible = true
                }
            }
        }

        return ControllerInfo(basePath, middlewares, routes, wsRoutes, obj)
    }

    /**
     * Validates that a middleware method has the correct signature:
     * - Exactly 2 parameters
     * - First parameter is Context
     * - Second parameter is Next
     * - Return type is void/Unit
     */
    private fun validateMiddleware(method: Method) {
        val params = method.parameterTypes

        require(params.size == 2) {
            "@Use method '${method.name}' must have exactly 2 parameters, but got ${params.size}"
        }

        require(isContextType(params[0])) {
            "@Use method '${method.name}': First parameter must be Context, but got ${params[0].name}"
        }

        require(isNextType(params[1])) {
            "@Use method '${method.name}': Second parameter must be Next, but got ${params[1].name}"
        }

        require(method.returnType == Void.TYPE || method.returnType.kotlin == Unit::class) {
            "@Use method '${method.name}': Return type must be void/Unit, but got ${method.returnType.name}"
        }
    }

    /**
     * Extracts all HTTP route annotations from a method.
     * Supports multiple annotations of the same or different types.
     *
     * Examples:
     * - @Get("/") @Get("/home") -> generates 2 GET routes
     * - @Get("/user") @Post("/user") -> generates GET and POST routes
     *
     * @param method The method to extract routes from
     * @return List of RouteInfo, empty if no HTTP annotations found
     */
    private fun extractRoutes(method: Method): List<RouteInfo> {
        val routes = mutableListOf<RouteInfo>()

        // Extract all @Get annotations (supports repeatable)
        method.getAnnotationsByType(Get::class.java).forEach { annotation ->
            routes.add(RouteInfo(annotation.value, "GET", method))
        }

        // Extract all @Post annotations
        method.getAnnotationsByType(Post::class.java).forEach { annotation ->
            routes.add(RouteInfo(annotation.value, "POST", method))
        }

        // Extract all @Put annotations
        method.getAnnotationsByType(Put::class.java).forEach { annotation ->
            routes.add(RouteInfo(annotation.value, "PUT", method))
        }

        // Extract all @Delete annotations
        method.getAnnotationsByType(Delete::class.java).forEach { annotation ->
            routes.add(RouteInfo(annotation.value, "DELETE", method))
        }

        // Extract all @Patch annotations
        method.getAnnotationsByType(Patch::class.java).forEach { annotation ->
            routes.add(RouteInfo(annotation.value, "PATCH", method))
        }

        // Validate that we don't have duplicate (path, method) combinations
        val duplicates = routes.groupBy { it.path to it.method }
            .filter { it.value.size > 1 }

        require(duplicates.isEmpty()) {
            "Method '${method.name}' has duplicate route definitions: ${duplicates.keys}"
        }

        return routes
    }

    /**
     * Extracts WebSocket route annotations from a method.
     *
     * The annotated method must accept exactly one parameter of type WsConnection.
     *
     * @param method The method to extract WS routes from
     * @return List of WsRouteInfo, empty if no @Ws annotation found
     */
    private fun extractWsRoutes(method: Method): List<WsRouteInfo> {
        val annotation = method.getAnnotation(Ws::class.java) ?: return emptyList()

        // Validate: must have exactly 1 parameter of type WsConnection
        val params = method.parameterTypes
        require(params.size == 1) {
            "@Ws method '${method.name}' must have exactly 1 parameter (WsConnection), but got ${params.size}"
        }
        require(params[0] == io.github.cymoo.colleen.ws.WsConnection::class.java) {
            "@Ws method '${method.name}': parameter must be WsConnection, but got ${params[0].name}"
        }
        require(method.returnType == Void.TYPE || method.returnType.kotlin == Unit::class) {
            "@Ws method '${method.name}': return type must be void/Unit, but got ${method.returnType.name}"
        }

        return listOf(WsRouteInfo(annotation.value, method))
    }

    /**
     * Checks if a class is Context
     */
    private fun isContextType(type: Class<*>): Boolean {
        return type == Context::class.java
    }

    /**
     * Checks if a class is or implements Next interface
     * Uses fully qualified name for more accurate matching
     */
    private fun isNextType(type: Class<*>): Boolean {
        // Check exact class name match
        if (type.simpleName == "Next") return true

        // Check if any implemented interface is named Next
        return type.interfaces.any { it.simpleName == "Next" }
    }
}