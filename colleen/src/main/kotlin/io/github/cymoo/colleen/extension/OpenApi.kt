package io.github.cymoo.colleen.extension

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.http.UrlPath
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure


// ============================================================================
// OpenAPI Metadata Annotations
// ============================================================================

/**
 * Short summary of a route handler (maps to OpenAPI `summary`).
 *
 * ```kotlin
 * @Summary("Get user by ID")
 * fun getUser(id: Path<Int>): User { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Summary(val value: String)

/**
 * Detailed description of a route handler (maps to OpenAPI `description`).
 *
 * ```kotlin
 * @Description("Returns a single user. Throws 404 if not found.")
 * fun getUser(id: Path<Int>): User { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Description(val value: String)

/**
 * Tags for grouping routes in OpenAPI UI.
 *
 * Can be applied to both a **class** (Controller) and a **method**.
 * When both are present, the tags are **merged**.
 *
 * ```kotlin
 * @Tags("users")
 * @Controller("/users")
 * class UserController {
 *
 *     @Tags("admin")
 *     @Get("/{id}")
 *     fun getUser(id: Path<Int>): User { ... }  // tags: ["users", "admin"]
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tags(vararg val value: String)

/**
 * Describes a single handler parameter in OpenAPI docs.
 *
 * The [name] must match the Kotlin parameter name (or the value set via `@Param`).
 *
 * ```kotlin
 * @ParamDesc(name = "id", description = "The unique user identifier")
 * @ParamDesc(name = "active", description = "Filter by active status")
 * fun listUsers(id: Path<Int>, active: Query<Boolean?>) { ... }
 * ```
 */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParamDesc(val name: String, val description: String)

/**
 * Describes an HTTP response for a route handler (maps to OpenAPI `responses`).
 *
 * Multiple `@ResponseDesc` annotations can be used to document different status codes.
 * A 200 response is always generated automatically; use this annotation to **augment**
 * or **override** its description, and to document additional status codes.
 *
 * ```kotlin
 * @ResponseDesc(200, "User found")
 * @ResponseDesc(404, "User not found")
 * fun getUser(id: Path<Int>): User { ... }
 * ```
 */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ResponseDesc(val status: Int, val description: String)

/**
 * Overrides the OpenAPI schema metadata for a class field.
 *
 * Used to provide human-readable documentation for DTO properties
 * when they are reflected into OpenAPI schemas.
 *
 * ```kotlin
 * data class CreateUserRequest(
 *     @Schema(description = "The user's login name", example = "john_doe")
 *     val username: String,
 *
 *     @Schema(description = "Age in years", example = "30")
 *     val age: Int,
 * )
 * ```
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Schema(val description: String = "", val example: String = "")

// ============================================================================
// Public Entry Point
// ============================================================================

/**
 * Enables OpenAPI 3.0.3 specification generation and Swagger UI serving.
 *
 * Automatically collects route metadata from the application and all mounted
 * sub-applications, then exposes:
 * - A JSON spec endpoint at [path]
 * - An optional Swagger UI page at [uiPath]
 *
 * ### Example
 * ```kotlin
 * val app = Colleen()
 * app.enableOpenApi(title = "My API", version = "1.0.0")
 *
 * @Summary("Get user by ID")
 * @ResponseDesc(404, "User not found")
 * fun getUser(id: Path<Int>): User { ... }
 *
 * app.get("/users/{id}", ::getUser)
 * app.listen(8000)
 * ```
 *
 * - Lambda handlers produce minimal metadata (path + method only).
 * - KFunction and Java Method handlers produce full parameter documentation.
 */
@JvmOverloads
fun Colleen.enableOpenApi(
    path: String = "/openapi.json",
    uiPath: String? = "/swagger-ui",
    title: String = "API",
    version: String = "1.0.0",
    description: String? = null,
) {
    val specPath = UrlPath.normalize(path)
    val swaggerPath = uiPath?.let { UrlPath.normalize(it) }
    val excludedPaths = setOfNotNull(specPath, swaggerPath)

    get(specPath) {
        val routes = collectRoutes(this@enableOpenApi, "", excludedPaths)
        buildSpec(routes, title, version, description)
    }

    if (swaggerPath != null) {
        get(swaggerPath) { ctx -> ctx.html(swaggerUiHtml(specPath)) }
    }
}

// ============================================================================
// Route Collection
// ============================================================================

private data class RouteEntry(val fullPath: String, val node: RouteNode)

/** Recursively collects routes from an app and all its mounted sub-apps. */
private fun collectRoutes(app: Colleen, prefix: String, excluded: Set<String>): List<RouteEntry> {
    val routes = app.router.routes
        .map { RouteEntry(UrlPath.join(prefix, it.path), it) }
        .filter { it.fullPath !in excluded }

    val mounted = app.router.mounts.flatMap { mount ->
        collectRoutes(mount.app, UrlPath.join(prefix, mount.prefix), excluded)
    }

    return routes + mounted
}

// ============================================================================
// Spec Building
// ============================================================================

private val HTTP_METHODS = listOf("get", "post", "put", "delete", "patch", "head", "options")

private fun buildSpec(
    routes: List<RouteEntry>,
    title: String,
    version: String,
    description: String?,
): Map<String, Any> {
    val paths = linkedMapOf<String, MutableMap<String, Any>>()

    for ((fullPath, node) in routes) {
        val pathItem = paths.getOrPut(fullPath) { linkedMapOf() }
        val methods = if (node.method == "*") HTTP_METHODS else listOf(node.method.lowercase())
        val operation = buildOperation(node)
        methods.forEach { method -> pathItem[method] = operation }
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

// ============================================================================
// Unified Parameter Descriptor
// ============================================================================

/**
 * Language-agnostic descriptor for a single handler parameter,
 * extracted from either a Kotlin [KParameter] or a Java [java.lang.reflect.Parameter].
 *
 * This intermediate representation allows [buildParamEntries] to be called
 * once and shared by both [buildOperationFromKFunction] and [buildOperationFromJavaMethod],
 * eliminating the near-identical `when` blocks that existed before.
 */
private data class ParamDescriptor(
    val name: String,
    val wrapperClass: Class<*>,
    val innerType: Type,       // the generic type argument inside the wrapper, e.g. Int in Path<Int>
    val isRequired: Boolean,
    val description: String?,
)

/**
 * Converts a list of [ParamDescriptor]s into OpenAPI parameter objects and
 * an optional request body. Returns them as a pair for use in operation building.
 *
 * This is the single place that maps wrapper types (Path, Query, Header, …)
 * to their OpenAPI representation.
 */
private fun buildParamEntries(
    descriptors: List<ParamDescriptor>,
): Pair<List<Map<String, Any>>, Map<String, Any>?> {
    val parameters = mutableListOf<Map<String, Any>>()
    var requestBody: Map<String, Any>? = null

    for (d in descriptors) {
        when {
            Path::class.java.isAssignableFrom(d.wrapperClass) ->
                parameters.add(buildParam(d.name, "path", typeToSchema(d.innerType), required = true, d.description))

            Query::class.java.isAssignableFrom(d.wrapperClass) ->
                parameters.add(buildParam(d.name, "query", typeToSchema(d.innerType), d.isRequired, d.description))

            Header::class.java.isAssignableFrom(d.wrapperClass) ->
                parameters.add(buildParam(d.name, "header", STRING_SCHEMA, d.isRequired, d.description))

            Cookie::class.java.isAssignableFrom(d.wrapperClass) ->
                parameters.add(buildParam(d.name, "cookie", STRING_SCHEMA, d.isRequired, d.description))

            Json::class.java.isAssignableFrom(d.wrapperClass) ->
                requestBody = buildRequestBody("application/json", typeToSchema(d.innerType), d.description)

            Form::class.java.isAssignableFrom(d.wrapperClass) ->
                requestBody = buildRequestBody("application/x-www-form-urlencoded", typeToSchema(d.innerType), d.description)

            Text::class.java.isAssignableFrom(d.wrapperClass) ->
                requestBody = buildRequestBody("text/plain", STRING_SCHEMA, d.description)

            Stream::class.java.isAssignableFrom(d.wrapperClass) ->
                requestBody = buildRequestBody("application/octet-stream", BINARY_SCHEMA, d.description)

            UploadedFile::class.java.isAssignableFrom(d.wrapperClass) ->
                requestBody = buildFileUploadBody(d.name.ifEmpty { "file" }, d.description)
        }
    }

    return parameters to requestBody
}

// ============================================================================
// Operation Building
// ============================================================================

/** Dispatches to the appropriate builder based on handler type. */
private fun buildOperation(node: RouteNode): Map<String, Any> = when (val h = node.handler) {
    is RouteHandler.KFunction -> buildOperationFromKFunction(h.fn)
    is RouteHandler.JavaMethod -> buildOperationFromJavaMethod(h.method)
    is RouteHandler.Lambda -> mapOf("responses" to defaultResponses())
}

// ---- Kotlin KFunction -------------------------------------------------------

private fun buildOperationFromKFunction(fn: kotlin.reflect.KFunction<*>): Map<String, Any> {
    val javaMethod = fn.javaMethod ?: return mapOf("responses" to defaultResponses())
    val declaringClass = javaMethod.declaringClass

    val valueParams = fn.parameters.filter { it.kind == KParameter.Kind.VALUE }
    val javaParams = javaMethod.parameters
    val paramDescs = javaMethod.paramDescMap()

    val descriptors = valueParams.mapIndexedNotNull { index, kParam ->
        if (index >= javaParams.size) return@mapIndexedNotNull null
        val jParam = javaParams[index]
        val wrapperClass = jParam.type
        if (Context::class.java.isAssignableFrom(wrapperClass)) return@mapIndexedNotNull null
        val name = kParam.getParamName()
        ParamDescriptor(
            name = name,
            wrapperClass = wrapperClass,
            // unwrapGeneric may throw for non-parameterized types (e.g. Context, plain services)
            innerType = runCatching { jParam.unwrapGeneric() }.getOrElse { jParam.type },
            isRequired = !kParam.isOptional && !kParam.isExtractorValueNullable(),
            description = paramDescs[name],
        )
    }

    val (parameters, requestBody) = buildParamEntries(descriptors)

    // Use Kotlin reflection to read Kotlin annotations on functions — Java getAnnotation()
    // may miss them when the annotation targets AnnotationTarget.FUNCTION only.
    val classTags = declaringClass.getAnnotation(Tags::class.java)?.value?.toList() ?: emptyList()
    val methodTags = fn.findAnnotation<Tags>()?.value?.toList() ?: emptyList()

    return buildOperationMap(
        operationId = operationId(declaringClass, javaMethod),
        summary = fn.findAnnotation<Summary>()?.value,
        description = fn.findAnnotation<Description>()?.value,
        tags = (classTags + methodTags).distinct(),
        parameters = parameters,
        requestBody = requestBody,
        responses = buildResponses(fn.returnType.jvmErasure.java, javaMethod.responsesMap()),
    )
}

// ---- Java Method ------------------------------------------------------------

private fun buildOperationFromJavaMethod(method: Method): Map<String, Any> {
    val declaringClass = method.declaringClass
    val paramDescs = method.paramDescMap()

    val descriptors = method.parameters.mapNotNull { jParam ->
        val wrapperClass = jParam.type
        if (Context::class.java.isAssignableFrom(wrapperClass)) return@mapNotNull null
        val name = jParam.getParamName()
        ParamDescriptor(
            name = name,
            wrapperClass = wrapperClass,
            innerType = runCatching { jParam.unwrapGeneric() }.getOrElse { jParam.type },
            isRequired = false, // Java has no default parameter concept
            description = paramDescs[name],
        )
    }

    val (parameters, requestBody) = buildParamEntries(descriptors)

    val classTags = declaringClass.getAnnotation(Tags::class.java)?.value?.toList() ?: emptyList()
    val methodTags = method.getAnnotation(Tags::class.java)?.value?.toList() ?: emptyList()

    return buildOperationMap(
        operationId = operationId(declaringClass, method),
        summary = method.getAnnotation(Summary::class.java)?.value,
        description = method.getAnnotation(Description::class.java)?.value,
        tags = (classTags + methodTags).distinct(),
        parameters = parameters,
        requestBody = requestBody,
        responses = buildResponses(method.returnType, method.responsesMap()),
    )
}

// ============================================================================
// OpenAPI Object Builders
// ============================================================================

private fun buildOperationMap(
    operationId: String,
    summary: String?,
    description: String?,
    tags: List<String>,
    parameters: List<Map<String, Any>>,
    requestBody: Map<String, Any>?,
    responses: Map<String, Any>,
): Map<String, Any> = buildMap {
    put("operationId", operationId)
    if (tags.isNotEmpty()) put("tags", tags)
    if (!summary.isNullOrBlank()) put("summary", summary)
    if (!description.isNullOrBlank()) put("description", description)
    if (parameters.isNotEmpty()) put("parameters", parameters)
    if (requestBody != null) put("requestBody", requestBody)
    put("responses", responses)
}

private fun buildParam(
    name: String,
    location: String,
    schema: Map<String, Any>,
    required: Boolean,
    description: String?,
): Map<String, Any> = buildMap {
    put("name", name)
    put("in", location)
    put("required", required)
    if (!description.isNullOrBlank()) put("description", description)
    put("schema", schema)
}

private fun buildRequestBody(
    contentType: String,
    schema: Map<String, Any>,
    description: String?,
): Map<String, Any> = buildMap {
    put("required", true)
    if (!description.isNullOrBlank()) put("description", description)
    put("content", mapOf(contentType to mapOf("schema" to schema)))
}

private fun buildFileUploadBody(fieldName: String, description: String?): Map<String, Any> =
    buildRequestBody(
        "multipart/form-data",
        mapOf(
            "type" to "object",
            "properties" to mapOf(fieldName to mapOf("type" to "string", "format" to "binary"))
        ),
        description,
    )

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun buildResponses(
    returnType: Class<*>,
    annotations: Map<Int, String>,
): Map<String, Any> {
    val isVoid = returnType == Unit::class.java
            || returnType == Void.TYPE
            || returnType == Void::class.java

    val successResponse: Map<String, Any> = buildMap {
        put("description", annotations[200] ?: "OK")
        if (!isVoid) {
            val content = when {
                returnType == String::class.java ->
                    mapOf("text/plain" to mapOf("schema" to STRING_SCHEMA))
                else ->
                    mapOf("application/json" to mapOf("schema" to typeToSchema(returnType)))
            }
            put("content", content)
        }
    }

    return buildMap {
        put("200", successResponse)
        for ((status, desc) in annotations) {
            if (status != 200) put(status.toString(), mapOf("description" to desc))
        }
    }
}

private fun defaultResponses(): Map<String, Any> =
    mapOf("200" to mapOf("description" to "OK"))

// ============================================================================
// Annotation Helpers
// ============================================================================

/** Builds a name → description map from all @ParamDesc on a method. */
private fun Method.paramDescMap(): Map<String, String> =
    getAnnotationsByType(ParamDesc::class.java).associate { it.name to it.description }

/** Builds a status → description map from all @ResponseDesc on a method. */
private fun Method.responsesMap(): Map<Int, String> =
    getAnnotationsByType(ResponseDesc::class.java).associate { it.status to it.description }

/**
 * Generates a unique operationId from the declaring class name and method name.
 *
 * When overloads exist (multiple methods share the same name), the parameter
 * type simple names are appended to disambiguate.
 *
 * Examples:
 * - `UserController.getUser` (no overloads)  → "UserController_getUser"
 * - `UserController.getUser(Path, Query)` (overloaded) → "UserController_getUser_Int_String"
 */
private fun operationId(declaringClass: Class<*>, method: Method): String {
    val base = "${declaringClass.simpleName}_${method.name}"
    val overloads = declaringClass.declaredMethods.count { it.name == method.name }
    return if (overloads <= 1) {
        base
    } else {
        val paramSuffix = method.parameterTypes.joinToString("_") { it.simpleName }
        "${base}_${paramSuffix}"
    }
}

// ============================================================================
// Schema Generation
// ============================================================================

private const val MAX_DEPTH = 5

private val STRING_SCHEMA = mapOf<String, Any>("type" to "string")
private val BINARY_SCHEMA = mapOf<String, Any>("type" to "string", "format" to "binary")

/**
 * Converts a Java [Type] to an OpenAPI schema map.
 *
 * Handles: primitives, String, enums, List/Set (array), Map (object with
 * additionalProperties), nullable types, and arbitrary DTOs.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun typeToSchema(type: Type, depth: Int = 0): Map<String, Any> {
    val rawClass: Class<*> = when (type) {
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

        rawClass.isEnum ->
            mapOf("type" to "string", "enum" to rawClass.enumConstants.map { it.toString() })

        List::class.java.isAssignableFrom(rawClass) || Set::class.java.isAssignableFrom(rawClass) -> {
            val elementType = (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
            buildMap {
                put("type", "array")
                if (elementType != null) put("items", typeToSchema(elementType, depth))
            }
        }

        Map::class.java.isAssignableFrom(rawClass) -> {
            // Emit additionalProperties for Map<String, V> so Swagger UI shows the value schema
            val valueType = (type as? ParameterizedType)?.actualTypeArguments?.getOrNull(1)
            buildMap {
                put("type", "object")
                if (valueType != null) put("additionalProperties", typeToSchema(valueType, depth))
            }
        }

        depth >= MAX_DEPTH ->
            mapOf("type" to "object")

        else -> buildObjectSchema(rawClass, depth)
    }
}

/**
 * Reflects over declared fields to produce an OpenAPI object schema.
 *
 * - Reads [@Schema][Schema] on each field to override `description` and `example`.
 * - Primitive fields are added to `required` (they cannot be null in Java/Kotlin).
 * - Non-primitive fields are marked `nullable: true`.
 */
private fun buildObjectSchema(clazz: Class<*>, depth: Int): Map<String, Any> {
    val properties = linkedMapOf<String, Any>()
    val requiredFields = mutableListOf<String>()

    for (field in clazz.declaredFields) {
        if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue

        val fieldSchema = typeToSchema(field.genericType, depth + 1).toMutableMap()
        val schemaAnnotation = field.getAnnotation(Schema::class.java)

        if (schemaAnnotation != null) {
            if (schemaAnnotation.description.isNotBlank()) fieldSchema["description"] = schemaAnnotation.description
            if (schemaAnnotation.example.isNotBlank()) fieldSchema["example"] = schemaAnnotation.example
        }

        if (field.type.isPrimitive) {
            requiredFields.add(field.name)
        } else {
            fieldSchema["nullable"] = true
        }

        properties[field.name] = fieldSchema
    }

    return buildMap {
        put("type", "object")
        if (properties.isNotEmpty()) put("properties", properties)
        if (requiredFields.isNotEmpty()) put("required", requiredFields)
    }
}

// ============================================================================
// Swagger UI HTML
// ============================================================================

private fun swaggerUiHtml(specPath: String): String = """
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
            SwaggerUIBundle({ url: '$specPath', dom_id: '#swagger-ui' })
        </script>
    </body>
    </html>
""".trimIndent()