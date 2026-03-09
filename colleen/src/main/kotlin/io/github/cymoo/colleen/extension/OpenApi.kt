package io.github.cymoo.colleen.extension

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Cookie
import io.github.cymoo.colleen.Event
import io.github.cymoo.colleen.Form
import io.github.cymoo.colleen.Header
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.MountNode
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.Query
import io.github.cymoo.colleen.RouteHandler
import io.github.cymoo.colleen.RouteNode
import io.github.cymoo.colleen.Stream
import io.github.cymoo.colleen.Text
import io.github.cymoo.colleen.UploadedFile
import io.github.cymoo.colleen.getParamName
import io.github.cymoo.colleen.getRawClass
import io.github.cymoo.colleen.isExtractorValueNullable
import io.github.cymoo.colleen.unwrapGeneric
import io.github.cymoo.colleen.util.http.UrlPath
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

/**
 * Enables OpenAPI 3.0.3 specification generation and serving.
 *
 * This extension automatically:
 * - Collects route metadata from the application and its mounted sub-applications
 * - Extracts parameter information from handler function signatures
 * - Generates an OpenAPI 3.0.3 specification
 * - Serves the spec as JSON at the specified path
 * - Optionally serves a Swagger UI page
 *
 * ### Example
 * ```kotlin
 * val app = Colleen()
 * app.enableOpenApi(title = "My API", version = "1.0.0")
 *
 * fun getUser(id: Path<Int>, active: Query<Boolean?>): User {
 *     ...
 * }
 *
 * app.get("/users/{id}", ::getUser)
 * app.listen(8000)
 *
 * // GET /openapi.json  → OpenAPI 3.0.3 JSON spec
 * // GET /swagger-ui    → Interactive API documentation
 * ```
 *
 * ### Notes
 * - Lambda handlers provide minimal metadata (path and method only).
 * - KFunction and Java Method handlers provide full parameter information.
 *
 * @param path URL path for the OpenAPI JSON spec endpoint
 * @param uiPath URL path for the Swagger UI page (null to disable)
 * @param title API title in the info section
 * @param version API version in the info section
 * @param description Optional API description in the info section
 */
@JvmOverloads
fun Colleen.enableOpenApi(
    path: String = "/openapi.json",
    uiPath: String? = "/swagger-ui",
    title: String = "API",
    version: String = "1.0.0",
    description: String? = null,
) {
    val normalizedPath = UrlPath.normalize(path)
    val normalizedUiPath = uiPath?.let { UrlPath.normalize(it) }
    val excludedPaths = setOfNotNull(normalizedPath, normalizedUiPath)

    // Serve the OpenAPI JSON spec
    get(normalizedPath) {
        val routes = collectAllRoutes(this@enableOpenApi, "", excludedPaths)
        buildOpenApiSpec(routes, title, version, description)
    }

    // Optionally serve Swagger UI
    if (normalizedUiPath != null) {
        get(normalizedUiPath) { ctx ->
            ctx.html(renderSwaggerUiHtml(normalizedPath))
        }
    }
}

// ========================================================================
// Internal Data Types
// ========================================================================

/**
 * Associates a route with its full path (including mount prefix).
 */
private data class OpenApiRoute(val fullPath: String, val node: RouteNode)

// ========================================================================
// Route Collection
// ========================================================================

/**
 * Recursively collects all routes from an app and its mounted sub-apps.
 */
private fun collectAllRoutes(
    app: Colleen,
    prefix: String,
    excludedPaths: Set<String>,
): List<OpenApiRoute> {
    val result = mutableListOf<OpenApiRoute>()

    for (route in app.router.routes) {
        val fullPath = if (prefix.isEmpty()) route.path
        else UrlPath.join(prefix, route.path)
        if (fullPath !in excludedPaths) {
            result.add(OpenApiRoute(fullPath, route))
        }
    }

    for (mount in app.router.mounts) {
        val mountPrefix = if (prefix.isEmpty()) mount.prefix
        else UrlPath.join(prefix, mount.prefix)
        result.addAll(collectAllRoutes(mount.app, mountPrefix, excludedPaths))
    }

    return result
}

// ========================================================================
// Spec Building
// ========================================================================

/**
 * Maximum depth for recursive schema introspection.
 */
private const val MAX_SCHEMA_DEPTH = 3

/**
 * Builds an OpenAPI 3.0.3 specification from collected routes.
 */
private fun buildOpenApiSpec(
    routes: List<OpenApiRoute>,
    title: String,
    version: String,
    description: String?,
): Map<String, Any> {
    val paths = linkedMapOf<String, MutableMap<String, Any>>()

    for ((fullPath, node) in routes) {
        val methods = if (node.method == "*") {
            listOf("get", "post", "put", "delete", "patch", "head", "options")
        } else {
            listOf(node.method.lowercase())
        }

        val operation = buildOperation(node)
        val pathItem = paths.getOrPut(fullPath) { linkedMapOf() }

        for (method in methods) {
            pathItem[method] = operation
        }
    }

    return buildMap {
        put("openapi", "3.0.3")
        put("info", buildMap {
            put("title", title)
            put("version", version)
            if (description != null) put("description", description)
        })
        put("paths", paths)
    }
}

// ========================================================================
// Operation Building
// ========================================================================

private val DEFAULT_RESPONSES: Map<String, Any> =
    mapOf("200" to mapOf("description" to "OK"))

/**
 * Builds an OpenAPI operation object from a route node.
 *
 * For KFunction and JavaMethod handlers, extracts parameter metadata
 * from the handler signature. For Lambda handlers, only the path
 * and method are available.
 */
private fun buildOperation(node: RouteNode): Map<String, Any> {
    return when (val handler = node.handler) {
        is RouteHandler.KFunction -> buildKFunctionOperation(handler.fn)
        is RouteHandler.JavaMethod -> buildJavaMethodOperation(handler.method)
        is RouteHandler.Lambda -> mapOf("responses" to DEFAULT_RESPONSES)
    }
}

/**
 * Builds an operation from a Kotlin function handler.
 */
private fun buildKFunctionOperation(fn: KFunction<*>): Map<String, Any> {
    val operation = linkedMapOf<String, Any>()
    operation["operationId"] = fn.name

    val javaMethod = fn.javaMethod
        ?: return operation.apply { put("responses", DEFAULT_RESPONSES) }

    val valueParams = fn.parameters.filter { it.kind == KParameter.Kind.VALUE }
    val javaParams = javaMethod.parameters

    val parameters = mutableListOf<Map<String, Any>>()
    var requestBody: Map<String, Any>? = null

    for ((index, kParam) in valueParams.withIndex()) {
        if (index >= javaParams.size) break
        val javaParam = javaParams[index]
        val paramName = kParam.getParamName()
        val wrapperClass = javaParam.type

        when {
            Context::class.java.isAssignableFrom(wrapperClass) -> continue

            Path::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                parameters.add(
                    buildParameter(paramName, "path", typeToSchema(innerType), required = true)
                )
            }

            Query::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                val required = !kParam.isOptional && !kParam.isExtractorValueNullable()
                parameters.add(
                    buildParameter(paramName, "query", typeToSchema(innerType), required = required)
                )
            }

            Header::class.java.isAssignableFrom(wrapperClass) -> {
                val required = !kParam.isOptional && !kParam.isExtractorValueNullable()
                parameters.add(
                    buildParameter(paramName, "header", STRING_SCHEMA, required = required)
                )
            }

            Cookie::class.java.isAssignableFrom(wrapperClass) -> {
                val required = !kParam.isOptional && !kParam.isExtractorValueNullable()
                parameters.add(
                    buildParameter(paramName, "cookie", STRING_SCHEMA, required = required)
                )
            }

            Json::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                requestBody = buildRequestBody("application/json", typeToSchema(innerType))
            }

            Form::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                requestBody =
                    buildRequestBody("application/x-www-form-urlencoded", typeToSchema(innerType))
            }

            Text::class.java.isAssignableFrom(wrapperClass) -> {
                requestBody = buildRequestBody("text/plain", STRING_SCHEMA)
            }

            Stream::class.java.isAssignableFrom(wrapperClass) -> {
                requestBody = buildRequestBody(
                    "application/octet-stream",
                    mapOf("type" to "string", "format" to "binary")
                )
            }

            UploadedFile::class.java.isAssignableFrom(wrapperClass) -> {
                requestBody = buildRequestBody(
                    "multipart/form-data",
                    mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            paramName.ifEmpty { "file" } to mapOf(
                                "type" to "string",
                                "format" to "binary"
                            )
                        )
                    )
                )
            }
        }
    }

    if (parameters.isNotEmpty()) operation["parameters"] = parameters
    if (requestBody != null) operation["requestBody"] = requestBody

    operation["responses"] = buildResponses(fn.returnType.jvmErasure.java)

    return operation
}

/**
 * Builds an operation from a Java method handler.
 */
private fun buildJavaMethodOperation(method: Method): Map<String, Any> {
    val operation = linkedMapOf<String, Any>()
    operation["operationId"] = method.name

    val parameters = mutableListOf<Map<String, Any>>()
    var requestBody: Map<String, Any>? = null

    for (javaParam in method.parameters) {
        val paramName = javaParam.getParamName()
        val wrapperClass = javaParam.type

        when {
            Context::class.java.isAssignableFrom(wrapperClass) -> continue

            Path::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                parameters.add(
                    buildParameter(paramName, "path", typeToSchema(innerType), required = true)
                )
            }

            Query::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                parameters.add(
                    buildParameter(paramName, "query", typeToSchema(innerType), required = false)
                )
            }

            Header::class.java.isAssignableFrom(wrapperClass) -> {
                parameters.add(
                    buildParameter(paramName, "header", STRING_SCHEMA, required = false)
                )
            }

            Cookie::class.java.isAssignableFrom(wrapperClass) -> {
                parameters.add(
                    buildParameter(paramName, "cookie", STRING_SCHEMA, required = false)
                )
            }

            Json::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                requestBody = buildRequestBody("application/json", typeToSchema(innerType))
            }

            Form::class.java.isAssignableFrom(wrapperClass) -> {
                val innerType = javaParam.unwrapGeneric()
                requestBody =
                    buildRequestBody("application/x-www-form-urlencoded", typeToSchema(innerType))
            }

            Text::class.java.isAssignableFrom(wrapperClass) -> {
                requestBody = buildRequestBody("text/plain", STRING_SCHEMA)
            }

            Stream::class.java.isAssignableFrom(wrapperClass) -> {
                requestBody = buildRequestBody(
                    "application/octet-stream",
                    mapOf("type" to "string", "format" to "binary")
                )
            }

            UploadedFile::class.java.isAssignableFrom(wrapperClass) -> {
                requestBody = buildRequestBody(
                    "multipart/form-data",
                    mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            paramName.ifEmpty { "file" } to mapOf(
                                "type" to "string",
                                "format" to "binary"
                            )
                        )
                    )
                )
            }
        }
    }

    if (parameters.isNotEmpty()) operation["parameters"] = parameters
    if (requestBody != null) operation["requestBody"] = requestBody

    operation["responses"] = buildResponses(method.returnType)

    return operation
}

// ========================================================================
// OpenAPI Object Builders
// ========================================================================

private fun buildParameter(
    name: String,
    location: String,
    schema: Map<String, Any>,
    required: Boolean,
): Map<String, Any> = buildMap {
    put("name", name)
    put("in", location)
    put("required", required)
    put("schema", schema)
}

private fun buildRequestBody(
    contentType: String,
    schema: Map<String, Any>,
): Map<String, Any> = mapOf(
    "required" to true,
    "content" to mapOf(contentType to mapOf("schema" to schema))
)

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun buildResponses(returnType: Class<*>): Map<String, Any> {
    if (returnType == Unit::class.java
        || returnType == Void.TYPE
        || returnType == java.lang.Void::class.java
    ) {
        return DEFAULT_RESPONSES
    }

    val content = when {
        returnType == String::class.java ->
            mapOf("text/plain" to mapOf("schema" to STRING_SCHEMA))

        else ->
            mapOf("application/json" to mapOf("schema" to typeToSchema(returnType)))
    }

    return mapOf(
        "200" to mapOf(
            "description" to "OK",
            "content" to content
        )
    )
}

// ========================================================================
// Schema Generation
// ========================================================================

private val STRING_SCHEMA = mapOf<String, Any>("type" to "string")

/**
 * Maps a Java type to an OpenAPI schema object.
 *
 * Handles:
 * - Scalar types (String, Int, Long, Float, Double, Boolean)
 * - Collection types (List, Set → array; Map → object)
 * - Data classes / POJOs (introspects declared fields)
 * - Recursive types (limited by [MAX_SCHEMA_DEPTH])
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun typeToSchema(type: Type, depth: Int = 0): Map<String, Any> {
    val rawClass = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as Class<*>
        else -> return mapOf("type" to "object")
    }

    return when {
        rawClass == String::class.java ->
            mapOf("type" to "string")

        rawClass == Int::class.java || rawClass == Integer::class.java ->
            mapOf("type" to "integer", "format" to "int32")

        rawClass == Long::class.java || rawClass == java.lang.Long::class.java ->
            mapOf("type" to "integer", "format" to "int64")

        rawClass == Float::class.java || rawClass == java.lang.Float::class.java ->
            mapOf("type" to "number", "format" to "float")

        rawClass == Double::class.java || rawClass == java.lang.Double::class.java ->
            mapOf("type" to "number", "format" to "double")

        rawClass == Boolean::class.java || rawClass == java.lang.Boolean::class.java ->
            mapOf("type" to "boolean")

        List::class.java.isAssignableFrom(rawClass)
                || Set::class.java.isAssignableFrom(rawClass) -> {
            val elementType =
                (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
            buildMap {
                put("type", "array")
                if (elementType != null) put("items", typeToSchema(elementType, depth))
            }
        }

        Map::class.java.isAssignableFrom(rawClass) ->
            mapOf("type" to "object")

        depth >= MAX_SCHEMA_DEPTH ->
            mapOf("type" to "object")

        else -> buildObjectSchema(rawClass, depth)
    }
}

/**
 * Builds an OpenAPI object schema by introspecting declared fields.
 */
private fun buildObjectSchema(clazz: Class<*>, depth: Int): Map<String, Any> {
    val properties = linkedMapOf<String, Any>()

    for (field in clazz.declaredFields) {
        if (Modifier.isStatic(field.modifiers)) continue
        if (field.isSynthetic) continue
        properties[field.name] = typeToSchema(field.genericType, depth + 1)
    }

    return buildMap {
        put("type", "object")
        if (properties.isNotEmpty()) {
            put("properties", properties)
        }
    }
}

// ========================================================================
// Swagger UI
// ========================================================================

/**
 * Renders a minimal Swagger UI HTML page.
 *
 * Uses the Swagger UI distribution from unpkg CDN.
 */
private fun renderSwaggerUiHtml(specPath: String): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Swagger UI</title>
        <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
        <style>body { margin: 0; }</style>
    </head>
    <body>
        <div id="swagger-ui"></div>
        <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
        <script>
            SwaggerUIBundle({
                url: '$specPath',
                dom_id: '#swagger-ui',
            })
        </script>
    </body>
    </html>
""".trimIndent()
