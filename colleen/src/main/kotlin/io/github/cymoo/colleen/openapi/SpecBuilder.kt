package io.github.cymoo.colleen.openapi

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.ParamExtractor
import io.github.cymoo.colleen.Response
import io.github.cymoo.colleen.Result
import io.github.cymoo.colleen.RouteHandler
import io.github.cymoo.colleen.RouteNode
import io.github.cymoo.colleen.getExtractorFactory
import io.github.cymoo.colleen.getParamName
import io.github.cymoo.colleen.isExtractorValueNullable
import io.github.cymoo.colleen.unwrapGeneric
import io.github.cymoo.colleen.util.http.UrlPath
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod

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

private val STRING_SCHEMA = mapOf<String, Any>("type" to "string")

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
