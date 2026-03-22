package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.UrlPath
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.math.BigDecimal
import java.math.BigInteger
import java.time.*
import java.util.*
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaMethod

// ============================================================================
// Tri-state Boolean for annotation overrides
// ============================================================================

/**
 * Tri-state boolean used in annotations to optionally override inferred behavior.
 *
 * - [UNSET] — use the value inferred from the type system (default).
 * - [TRUE]  — explicitly mark as required / non-nullable.
 * - [FALSE] — explicitly mark as optional / nullable.
 */
enum class OptionalBool { UNSET, TRUE, FALSE }

// ============================================================================
// Custom Extractor OpenAPI Spec Data Classes
// ============================================================================

/**
 * Describes how a custom extractor maps to OpenAPI spec elements.
 * Returned by [ExtractorFactory.describeOpenApi].
 *
 * Either [parameters] or [requestBody] (or both) may be provided.
 * Return `null` from `describeOpenApi` to skip the extractor entirely.
 */
data class OpenApiParamSpec(
    val parameters: List<OpenApiParameter> = emptyList(),
    val requestBody: OpenApiRequestBody? = null,
)

/**
 * Describes a single OpenAPI parameter (query, header, cookie, or path).
 *
 * @param name       Parameter name as it appears in the OpenAPI spec
 * @param location   One of "query", "header", "cookie", or "path"
 * @param schema     Static schema map (used when [schemaType] is null)
 * @param required   Explicit required flag; null means defer to the framework's inferred value
 * @param description Optional description for this parameter
 * @param schemaType If set, [typeToSchema] will be called on this [Type] to
 *                   produce the schema instead of using [schema]. Use this when the schema
 *                   depends on the Kotlin/Java generic type at the call site.
 */
data class OpenApiParameter(
    val name: String,
    val location: String,
    val schema: Map<String, Any> = mapOf("type" to "string"),
    val required: Boolean? = null,
    val description: String? = null,
    val schemaType: Type? = null,
)

/**
 * Describes a request body for an OpenAPI operation.
 *
 * @param contentType MIME type (e.g. "application/json", "multipart/form-data")
 * @param schema      Static schema map (used when [schemaType] is null)
 * @param description Optional description for the request body
 * @param schemaType  If set, [typeToSchema] will be called on this [Type]
 *                    to produce the schema instead of using [schema].
 */
data class OpenApiRequestBody(
    val contentType: String,
    val schema: Map<String, Any> = emptyMap(),
    val description: String? = null,
    val schemaType: Type? = null,
)

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
 *
 * The optional [required] field can override the inferred required/optional
 * semantics derived from the parameter type:
 *
 * ```kotlin
 * @ParamDesc(name = "q", description = "Search keyword", required = OptionalBool.TRUE)
 * fun search(q: Query<String?>) { ... }
 * ```
 */
@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParamDesc(val name: String, val description: String, val required: OptionalBool = OptionalBool.UNSET)

/**
 * Describes an HTTP response for a route handler (maps to OpenAPI `responses`).
 *
 * Multiple `@ResponseDesc` annotations can be used to document different status codes.
 * If no 2xx annotation is present, a default 200 response is generated automatically.
 * When at least one 2xx annotation is provided, only the annotated 2xx statuses appear
 * — the implicit 200 is **not** added.
 *
 * ```kotlin
 * @ResponseDesc(200, "User found")
 * @ResponseDesc(404, "User not found")
 * fun getUser(id: Path<Int>): User { ... }
 *
 * @ResponseDesc(201, "Resource created")
 * fun create(body: Json<Dto>): Dto { ... }   // only 201, no default 200
 *
 * @ResponseDesc(204, "Deleted successfully")
 * fun delete(id: Path<Int>) { ... }           // only 204 (no content body)
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
 * ### Basic usage
 * ```kotlin
 * data class CreateUserRequest(
 *     @Schema(description = "The user's login name", example = "john_doe")
 *     val username: String,
 *
 *     @Schema(description = "Age in years", example = "30")
 *     val age: Int,
 * )
 * ```
 *
 * ### Aliasing (overrides the field name in the schema)
 * ```kotlin
 * data class SearchRequest(
 *     @Schema(name = "q")
 *     val keyword: String,
 * )
 * ```
 *
 * ### Hiding a field from the schema
 * ```kotlin
 * data class InternalDto(
 *     val publicField: String,
 *     @Schema(hidden = true)
 *     val internalField: String,
 * )
 * ```
 *
 * ### Overriding the inferred type (e.g. for custom deserialization)
 * ```kotlin
 * data class EventDto(
 *     @Schema(type = "string", format = "date-time")
 *     val startTime: LocalDateTime,
 * )
 * ```
 *
 * ### Overriding required / nullable
 * By default, the OpenAPI extension infers `required` from Kotlin nullability.
 * Use [required] to override:
 *
 * ```kotlin
 * data class PatchRequest(
 *     @Schema(required = OptionalBool.FALSE)
 *     val name: String,        // normally required, but treated as optional here
 * )
 * ```
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Schema(
    val description: String = "",
    val example: String = "",
    val name: String = "",
    val hidden: Boolean = false,
    val type: String = "",
    val format: String = "",
    val required: OptionalBool = OptionalBool.UNSET,
)

/**
 * Marks a route handler or controller class as hidden from the OpenAPI specification.
 *
 * When applied to a **function**, only that operation is excluded.
 * When applied to a **class** (controller), all operations within the class are excluded.
 *
 * ```kotlin
 * @Hidden
 * fun internalHealthCheck(): String = "ok"
 *
 * @Hidden
 * @Controller("/internal")
 * class InternalController {
 *     @Get("/metrics")
 *     fun metrics(): Map<String, Any> = ...
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Hidden

// ============================================================================
// Route Collection
// ============================================================================

internal data class RouteEntry(val fullPath: String, val node: RouteNode)

/** Recursively collects routes from an app and all its mounted sub-apps. */
internal fun collectRoutes(app: Colleen, prefix: String, excluded: Set<String>): List<RouteEntry> {
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

internal fun buildSpec(
    routes: List<RouteEntry>,
    title: String,
    version: String,
    description: String?,
    filter: ((String, String) -> Boolean)?,
): Map<String, Any> {
    val paths = linkedMapOf<String, MutableMap<String, Any>>()
    val schemas = linkedMapOf<String, Map<String, Any>>()

    for ((fullPath, node) in routes) {
        val methods = if (node.method == "*") HTTP_METHODS else listOf(node.method.lowercase())
        val applicableMethods = if (filter != null) methods.filter { filter(fullPath, it) } else methods
        if (applicableMethods.isEmpty()) continue

        val operation = buildOperation(node, schemas) ?: continue
        val pathItem = paths.getOrPut(fullPath) { linkedMapOf() }
        for (method in applicableMethods) {
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
        if (schemas.isNotEmpty()) {
            put("components", mapOf("schemas" to schemas))
        }
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
    val requiredOverride: OptionalBool,
    val javaParam: Parameter,  // the Java reflection parameter, needed for describeOpenApi()
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
    schemas: MutableMap<String, Map<String, Any>>,
): Pair<List<Map<String, Any>>, Map<String, Any>?> {
    val parameters = mutableListOf<Map<String, Any>>()
    var requestBody: Map<String, Any>? = null

    for (d in descriptors) {
        val effectiveRequired = when (d.requiredOverride) {
            OptionalBool.TRUE -> true
            OptionalBool.FALSE -> false
            OptionalBool.UNSET -> d.isRequired
        }

        // Skip non-extractor parameters (e.g. injected services)
        if (!ParamExtractor::class.java.isAssignableFrom(d.wrapperClass)) continue

        val factory = runCatching { getExtractorFactory(d.wrapperClass) }.getOrNull() ?: continue
        val spec = factory.describeOpenApi(d.name, d.javaParam) ?: continue

        for (oaParam in spec.parameters) {
            val resolvedSchema = if (oaParam.schemaType != null) {
                typeToSchema(oaParam.schemaType, schemas = schemas)
            } else {
                oaParam.schema
            }
            // Path params are always required per OpenAPI spec.
            // Otherwise: use the extractor's explicit hint if set, else fall back to effectiveRequired.
            val paramRequired = when {
                oaParam.location == "path" -> true
                oaParam.required != null -> oaParam.required
                else -> effectiveRequired
            }
            parameters.add(buildParam(oaParam.name, oaParam.location, resolvedSchema, paramRequired, oaParam.description ?: d.description))
        }

        if (spec.requestBody != null) {
            val rb = spec.requestBody
            val resolvedSchema = if (rb.schemaType != null) {
                typeToSchema(rb.schemaType, schemas = schemas)
            } else {
                rb.schema
            }
            requestBody = buildRequestBody(rb.contentType, resolvedSchema, rb.description ?: d.description)
        }
    }

    return parameters to requestBody
}

// ============================================================================
// Operation Building
// ============================================================================

/** Dispatches to the appropriate builder based on handler type. Returns null if hidden. */
private fun buildOperation(node: RouteNode, schemas: MutableMap<String, Map<String, Any>>): Map<String, Any>? = when (val h = node.handler) {
    is RouteHandler.KFunction -> buildOperationFromKFunction(h.fn, schemas)
    is RouteHandler.JavaMethod -> buildOperationFromJavaMethod(h.method, schemas)
    is RouteHandler.Lambda -> mapOf("responses" to defaultResponses())
}

// ---- Kotlin KFunction -------------------------------------------------------

private fun buildOperationFromKFunction(fn: kotlin.reflect.KFunction<*>, schemas: MutableMap<String, Map<String, Any>>): Map<String, Any>? {
    val javaMethod = fn.javaMethod ?: return mapOf("responses" to defaultResponses())
    val declaringClass = javaMethod.declaringClass

    // Check @Hidden on function or declaring class
    if (fn.findAnnotation<Hidden>() != null || declaringClass.getAnnotation(Hidden::class.java) != null) {
        return null
    }

    val valueParams = fn.parameters.filter { it.kind == KParameter.Kind.VALUE }
    val javaParams = javaMethod.parameters
    val paramAnns = javaMethod.paramAnnotationMap()

    val descriptors = valueParams.mapIndexedNotNull { index, kParam ->
        if (index >= javaParams.size) return@mapIndexedNotNull null
        val jParam = javaParams[index]
        val wrapperClass = jParam.type
        if (Context::class.java.isAssignableFrom(wrapperClass)) return@mapIndexedNotNull null
        val name = kParam.getParamName()
        val ann = paramAnns[name]
        ParamDescriptor(
            name = name,
            wrapperClass = wrapperClass,
            // unwrapGeneric may throw for non-parameterized types (e.g. Context, plain services)
            innerType = runCatching { jParam.unwrapGeneric() }.getOrElse { jParam.type },
            isRequired = !kParam.isOptional && !kParam.isExtractorValueNullable(),
            description = ann?.description,
            requiredOverride = ann?.required ?: OptionalBool.UNSET,
            javaParam = jParam,
        )
    }

    val (parameters, requestBody) = buildParamEntries(descriptors, schemas)

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
        responses = buildResponses(javaMethod.genericReturnType, javaMethod.responsesMap(), schemas),
    )
}

// ---- Java Method ------------------------------------------------------------

private fun buildOperationFromJavaMethod(method: Method, schemas: MutableMap<String, Map<String, Any>>): Map<String, Any>? {
    val declaringClass = method.declaringClass

    // Check @Hidden on method or declaring class
    if (method.getAnnotation(Hidden::class.java) != null || declaringClass.getAnnotation(Hidden::class.java) != null) {
        return null
    }

    val paramAnns = method.paramAnnotationMap()

    val descriptors = method.parameters.mapNotNull { jParam ->
        val wrapperClass = jParam.type
        if (Context::class.java.isAssignableFrom(wrapperClass)) return@mapNotNull null
        val name = jParam.getParamName()
        val ann = paramAnns[name]
        ParamDescriptor(
            name = name,
            wrapperClass = wrapperClass,
            innerType = runCatching { jParam.unwrapGeneric() }.getOrElse { jParam.type },
            isRequired = false, // Java has no default parameter concept
            description = ann?.description,
            requiredOverride = ann?.required ?: OptionalBool.UNSET,
            javaParam = jParam,
        )
    }

    val (parameters, requestBody) = buildParamEntries(descriptors, schemas)

    val classTags = declaringClass.getAnnotation(Tags::class.java)?.value?.toList() ?: emptyList()
    val methodTags = method.getAnnotation(Tags::class.java)?.value?.toList() ?: emptyList()

    return buildOperationMap(
        operationId = operationId(declaringClass, method),
        summary = method.getAnnotation(Summary::class.java)?.value,
        description = method.getAnnotation(Description::class.java)?.value,
        tags = (classTags + methodTags).distinct(),
        parameters = parameters,
        requestBody = requestBody,
        responses = buildResponses(method.genericReturnType, method.responsesMap(), schemas),
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

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun buildResponses(
    returnType: Type,
    annotations: Map<Int, String>,
    schemas: MutableMap<String, Map<String, Any>>,
): Map<String, Any> {
    // Unwrap Result<T> → use the inner body type T as the effective response schema.
    val effectiveType: Type = run {
        val rawType = when (returnType) {
            is Class<*> -> returnType
            is ParameterizedType -> returnType.rawType as? Class<*>
            else -> null
        }
        if (rawType != null && Result::class.java.isAssignableFrom(rawType)) {
            (returnType as? ParameterizedType)?.actualTypeArguments?.firstOrNull() ?: Unit::class.java
        } else {
            returnType
        }
    }

    val rawClass: Class<*> = when (effectiveType) {
        is Class<*> -> effectiveType
        is ParameterizedType -> effectiveType.rawType as Class<*>
        else -> Any::class.java
    }

    val isVoid = rawClass == Unit::class.java
            || rawClass == Void.TYPE
            || rawClass == Void::class.java
            || Response::class.java.isAssignableFrom(rawClass)

    // Determine the primary success status code.
    // If annotations contain at least one 2xx status, use the smallest; otherwise default to 200.
    val successStatus = annotations.keys.filter { it in 200..299 }.minOrNull() ?: 200
    val successDescription = annotations[successStatus] ?: "OK"

    val successResponse: Map<String, Any> = buildMap {
        put("description", successDescription)
        if (!isVoid && successStatus != 204) {
            val content = when {
                rawClass == String::class.java ->
                    mapOf("text/plain" to mapOf("schema" to STRING_SCHEMA))
                else ->
                    mapOf("application/json" to mapOf("schema" to typeToSchema(effectiveType, schemas = schemas)))
            }
            put("content", content)
        }
    }

    return buildMap {
        put(successStatus.toString(), successResponse)
        for ((status, desc) in annotations) {
            if (status != successStatus) put(status.toString(), mapOf("description" to desc))
        }
    }
}

private fun defaultResponses(): Map<String, Any> =
    mapOf("200" to mapOf("description" to "OK"))

// ============================================================================
// Annotation Helpers
// ============================================================================

/** Builds a name → @ParamDesc map from all @ParamDesc on a method. */
private fun Method.paramAnnotationMap(): Map<String, ParamDesc> =
    getAnnotationsByType(ParamDesc::class.java).associateBy { it.name }

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

private val DATE_TIME_CLASSES: Set<Class<*>> = setOf(
    LocalDateTime::class.java,
    Instant::class.java,
    OffsetDateTime::class.java,
    ZonedDateTime::class.java,
    Date::class.java,
)

/**
 * Converts a Java [Type] to an OpenAPI schema map.
 *
 * Handles: primitives (Int, Long, Short, Byte, Float, Double, Boolean, Char),
 * String, enums, temporal types (LocalDate, LocalDateTime, Instant, OffsetDateTime,
 * ZonedDateTime, Date), UUID, BigDecimal, BigInteger, List/Set (array),
 * Map (object with additionalProperties), nullable types, and arbitrary DTOs.
 *
 * Custom DTO types are registered in [schemas] (if provided) and referenced
 * via `$ref: "#/components/schemas/ClassName"` to avoid duplication.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal fun typeToSchema(
    type: Type,
    depth: Int = 0,
    schemas: MutableMap<String, Map<String, Any>>? = null,
): Map<String, Any> {
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

        rawClass == Short::class.java || rawClass == java.lang.Short::class.java ->
            mapOf("type" to "integer", "format" to "int32")

        rawClass == Byte::class.java || rawClass == java.lang.Byte::class.java ->
            mapOf("type" to "integer", "format" to "int32")

        rawClass == Float::class.java || rawClass == java.lang.Float::class.java ->
            mapOf("type" to "number", "format" to "float")

        rawClass == Double::class.java || rawClass == java.lang.Double::class.java ->
            mapOf("type" to "number", "format" to "double")

        rawClass == Boolean::class.java || rawClass == java.lang.Boolean::class.java ->
            mapOf("type" to "boolean")

        rawClass == Char::class.java || rawClass == Character::class.java ->
            mapOf("type" to "string")

        rawClass == BigDecimal::class.java ->
            mapOf("type" to "number")

        rawClass == BigInteger::class.java ->
            mapOf("type" to "integer")

        rawClass == LocalDate::class.java ->
            mapOf("type" to "string", "format" to "date")

        rawClass in DATE_TIME_CLASSES ->
            mapOf("type" to "string", "format" to "date-time")

        rawClass == UUID::class.java ->
            mapOf("type" to "string", "format" to "uuid")

        rawClass.isEnum ->
            mapOf("type" to "string", "enum" to rawClass.enumConstants.map { it.toString() })

        List::class.java.isAssignableFrom(rawClass) || Set::class.java.isAssignableFrom(rawClass) -> {
            val elementType = (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
            buildMap {
                put("type", "array")
                if (elementType != null) put("items", typeToSchema(elementType, depth, schemas))
            }
        }

        Map::class.java.isAssignableFrom(rawClass) -> {
            // Emit additionalProperties for Map<String, V> so Swagger UI shows the value schema
            val valueType = (type as? ParameterizedType)?.actualTypeArguments?.getOrNull(1)
            buildMap {
                put("type", "object")
                if (valueType != null) put("additionalProperties", typeToSchema(valueType, depth, schemas))
            }
        }

        depth >= MAX_DEPTH ->
            mapOf("type" to "object")

        // Custom DTO — register in component schemas and return a $ref
        schemas != null -> {
            val schemaName = rawClass.simpleName
            if (schemaName !in schemas) {
                // Insert an empty placeholder first so that recursive calls for
                // self-referencing or circularly-dependent DTOs find the key
                // already present and return a $ref instead of recursing infinitely.
                schemas[schemaName] = emptyMap()
                schemas[schemaName] = buildObjectSchema(rawClass, depth, schemas)
            }
            mapOf($$"$ref" to "#/components/schemas/$schemaName")
        }

        else -> buildObjectSchema(rawClass, depth, schemas)
    }
}

/**
 * Reflects over declared fields to produce an OpenAPI object schema.
 *
 * - Reads [@Schema][Schema] on each field to override `description`, `example`,
 *   `name` (alias), `hidden`, `type`, `format`, and `required`.
 * - Fields marked `@Schema(hidden = true)` are excluded entirely.
 * - Fields with `@Schema(name = "alias")` use the alias as the property key.
 * - Fields with `@Schema(type = "...")` override the inferred type/format.
 * - Required / optional is determined by (highest priority first):
 *   1. `@Schema(required = TRUE / FALSE)` — explicit annotation override.
 *   2. Kotlin nullability — `val foo: String` → required; `val bar: String?` → optional.
 *   3. JVM primitive check — fallback for pure Java classes without Kotlin metadata.
 *
 * Required fields appear in the `required` array; optional fields are simply
 * omitted from it.  This avoids mixing `required` with `nullable` and keeps
 * the generated spec consistent regardless of whether a field uses `$ref`.
 */
private fun buildObjectSchema(
    clazz: Class<*>,
    depth: Int,
    schemas: MutableMap<String, Map<String, Any>>? = null,
): Map<String, Any> {
    val properties = linkedMapOf<String, Any>()
    val requiredFields = mutableListOf<String>()

    // Try to obtain Kotlin property metadata for accurate nullability information.
    // Falls back to null for pure Java classes or when Kotlin metadata is unavailable.
    val kotlinProperties: Map<String, KProperty1<*, *>>? = try {
        clazz.kotlin.memberProperties.associateBy { it.name }
    } catch (_: Exception) {
        null
    }

    for (field in clazz.declaredFields) {
        if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue

        val schemaAnnotation = field.getAnnotation(Schema::class.java)

        // Skip hidden fields
        if (schemaAnnotation != null && schemaAnnotation.hidden) continue

        // Use @Schema(type=...) override or infer from the field type
        val fieldSchema: MutableMap<String, Any> = if (schemaAnnotation != null && schemaAnnotation.type.isNotBlank()) {
            val m = linkedMapOf<String, Any>("type" to schemaAnnotation.type)
            if (schemaAnnotation.format.isNotBlank()) m["format"] = schemaAnnotation.format
            m
        } else {
            LinkedHashMap(typeToSchema(field.genericType, depth + 1, schemas))
        }

        if (schemaAnnotation != null) {
            if (schemaAnnotation.description.isNotBlank()) fieldSchema["description"] = schemaAnnotation.description
            if (schemaAnnotation.example.isNotBlank()) fieldSchema["example"] = schemaAnnotation.example
        }

        // Determine property name: @Schema(name=...) overrides the field name
        val propertyName = if (schemaAnnotation != null && schemaAnnotation.name.isNotBlank()) {
            schemaAnnotation.name
        } else {
            field.name
        }

        // Determine required / optional.
        // Priority: @Schema(required) > Kotlin nullability > JVM primitive check
        val isRequired = when {
            schemaAnnotation != null && schemaAnnotation.required == OptionalBool.TRUE -> true
            schemaAnnotation != null && schemaAnnotation.required == OptionalBool.FALSE -> false
            kotlinProperties != null -> {
                val kProp = kotlinProperties[field.name]
                kProp != null && !kProp.returnType.isMarkedNullable
            }
            else -> field.type.isPrimitive // Fallback for pure Java classes
        }

        if (isRequired) {
            requiredFields.add(propertyName)
        }

        properties[propertyName] = fieldSchema
    }

    return buildMap {
        put("type", "object")
        if (properties.isNotEmpty()) put("properties", properties)
        if (requiredFields.isNotEmpty()) put("required", requiredFields)
    }
}

// ============================================================================
// Documentation UI HTML Generators
// ============================================================================

/**
 * Generates a self-contained ReDoc HTML page that renders an OpenAPI spec.
 *
 * [specPath] is the URL path to the OpenAPI JSON endpoint (e.g. `/openapi.json`).
 * [jsUrl] may be overridden to point to a self-hosted or alternate CDN bundle.
 *
 * To use ReDoc instead of the default Swagger UI, pass this function to [Colleen.openApi]:
 * ```kotlin
 * app.openApi(uiHtml = ::redocHtml)
 * ```
 */
fun redocHtml(
    specPath: String,
    jsUrl: String = "https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js",
): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>API Documentation</title>
        <style>body { margin: 0; }</style>
    </head>
    <body>
        <redoc spec-url='$specPath'></redoc>
        <script src="$jsUrl"></script>
    </body>
    </html>
""".trimIndent()

/**
 * Generates a self-contained Swagger UI HTML page that renders an OpenAPI spec.
 *
 * [specPath] is the URL path to the OpenAPI JSON endpoint (e.g. `/openapi.json`).
 * [jsUrl] and [cssUrl] may be overridden to point to self-hosted or alternate CDN bundles.
 *
 * This is the **default** UI used by [Colleen.openApi].
 */
fun swaggerUiHtml(
    specPath: String,
    jsUrl: String = "https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js",
    cssUrl: String = "https://unpkg.com/swagger-ui-dist@5/swagger-ui.css",
): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Swagger UI</title>
        <link rel="stylesheet" href="$cssUrl">
        <style>body { margin: 0; }</style>
    </head>
    <body>
        <div id="swagger-ui"></div>
        <script src="$jsUrl"></script>
        <script>
            SwaggerUIBundle({ url: '$specPath', dom_id: '#swagger-ui' })
        </script>
    </body>
    </html>
""".trimIndent()