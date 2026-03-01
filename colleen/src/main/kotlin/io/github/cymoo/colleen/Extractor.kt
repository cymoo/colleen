package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.TypeRef
import java.io.InputStream
import java.lang.reflect.*
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

// ========================================================================
// Exception Definitions
// ========================================================================

open class ExtractionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TypeConversionFailed(value: String, targetType: String, cause: Throwable? = null) :
    ExtractionException("Cannot convert '$value' to $targetType", cause)

// ========================================================================
// Core Handler Functions
// ========================================================================

/**
 * Creates a Handler from a Kotlin function by automatically extracting and converting
 * HTTP request parameters.
 *
 * This function has two distinct phases:
 *
 * 1. Build phase:
 *    - Analyze function signature
 *    - Build extraction lambdas
 *    - Build resolution lambdas
 *
 * 2. Execution phase:
 *    - Extract values from Context
 *    - Apply resolution rules (default / nullable / required)
 *    - Invoke the function via callBy
 *
 * Reflection cost is paid only once during the build phase.
 *
 * Example:
 * ```kotlin
 * fun getUser(id: Path<Int>): User {
 *     return userService.findById(id.value)
 * }
 *
 * app.get("/users/{id}", ::getUser)
 * ```
 *
 * @param fn The Kotlin function to wrap
 * @param instance Instance object for member functions (null for top-level functions)
 * @return Handler that extracts parameters and invokes the function
 */
internal fun cx(fn: KFunction<*>, instance: Any? = null): Handler {
    // === BUILD PHASE: preprocess all reflection operations ===

    val instanceParam = fn.parameters.firstOrNull { it.kind == KParameter.Kind.INSTANCE }
    val valueParams = fn.parameters.filter { it.kind == KParameter.Kind.VALUE }
    val javaParams = fn.javaMethod!!.parameters

    val paramMetas = valueParams.mapIndexed { index, kParam ->
        val javaParam = javaParams[index]
        val paramName = kParam.getParamName()

        val extractor = buildExtractor(paramName, javaParam)
        val resolver = buildResolver(kParam, javaParam, paramName)

        ParamMeta(kParam, extractor, resolver)
    }

    // === EXECUTION PHASE: runtime execution ===

    return Handler { ctx ->
        val args = buildMap(paramMetas.size + 1) {
            if (instanceParam != null && instance != null) {
                put(instanceParam, instance)
            }

            for (meta in paramMetas) {
                val extracted = meta.extract(ctx)
                val resolved = meta.resolve(extracted)

                // Unit means use the default value
                if (resolved != Unit) {
                    put(meta.kParam, resolved)
                }
            }
        }

        try {
            fn.callBy(args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}

/**
 * Creates a Handler from a Java method by automatically extracting and converting
 * HTTP request parameters.
 *
 * Differences from Kotlin version:
 * - No default parameters
 * - All parameters are considered nullable
 *
 * Extraction logic is otherwise identical.
 *
 * Example:
 * ```java
 * import static io.github.cymoo.colleen.lambda.ch;
 *
 * public class UserController {
 *     public User getUser(@Param("id") Path<Integer> id) {
 *         return userService.findById(id.value);
 *     }
 * }
 *
 * UserController controller = new UserController();
 * app.get("/users/{id}", ch(controller::getUser))
 * ```
 *
 * @param method The Java method to wrap
 * @param instance Instance object for instance methods (null for static methods)
 * @return Handler that extracts parameters and invokes the method
 */
internal fun cx(method: Method, instance: Any? = null): Handler {
    val returnType = method.returnType
    val parameters = method.parameters

    // Build phase: pre-generate all extraction functions
    val extractors = parameters.map { param ->
        buildExtractor(param.getParamName(), param)
    }

    return Handler { ctx ->
        val args = parameters.indices.map { index ->
            extractors[index](ctx)
        }.toTypedArray()

        try {
            // If the underlying method return type is void, the invocation returns null.
            val rv = method.invoke(instance, *args)
            if (returnType != Void.TYPE) {
                return@Handler rv
            }
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}

// ========================================================================
// Annotations
// ========================================================================

/**
 * Specifies the name of the parameter to extract from the request.
 *
 * ### Kotlin note
 * In Kotlin, parameter names are preserved by default and usually **do not
 * require** this annotation.
 *
 * Use [Param] only when you want to **override the parameter name**, or when
 * working from Java code.
 *
 * Example:
 * ```kotlin
 * fun handler(@Param("user_id") id: Path<Int>)
 * ```
 *
 * ```java
 * public User handler(@Param("user_id") Path<Integer> id)
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(val value: String = "")

/**
 * Declares a qualifier for a handler function parameter, enabling the
 * framework to resolve a specific service registration when multiple
 * instances of the same type are registered under different qualifiers.
 *
 * The [name] is matched against registered qualifier objects using
 * [ServiceContainer.lookupQualifier]:
 * - If a non-String qualifier (e.g. an `object`) with a matching simple
 *   class name was registered, that object is used as the lookup key.
 * - Otherwise [name] itself is used as a plain String qualifier.
 *
 * Both exact case and lowercase are tried, so `@Qualifier("Primary")`
 * and `@Qualifier("primary")` are equivalent.
 *
 * ### Example
 * ```kotlin
 * object Primary
 * object Replica
 *
 * app.provide<DataSource>(Primary) { HikariDataSource(primaryConfig) }
 * app.provide<DataSource>(Replica) { HikariDataSource(replicaConfig) }
 *
 * // Resolved automatically — no extra registration step required
 * fun handler(
 *     @Qualifier("Primary") primary: DataSource,
 *     @Qualifier("Replica") replica: DataSource,
 * ): String = primary.query("SELECT 1")
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Qualifier(val name: String)

// ========================================================================
// Base class and interface
// ========================================================================

/**
 * Base class for parameter extractors.
 *
 * Extractors wrap values extracted from HTTP requests.
 * The wrapped value may be null, in which case the framework will handle it
 * based on the parameter's type (optional, nullable, or required).
 */
abstract class ParamExtractor<T>(@JvmField val value: T)

/**
 * Factory interface for creating parameter extractors.
 *
 * Implementations build extraction functions that retrieve values from the HTTP request.
 * The framework handles parameter semantics (optional/nullable/required) separately.
 */
interface ExtractorFactory<T : ParamExtractor<*>> {
    /**
     * Builds an extractor for a handler parameter.
     *
     * The returned lambda should:
     * - Extract the value from the Context
     * - Return an extractor instance (value may be null)
     * - Throw exceptions for critical failures (e.g., validation errors)
     *
     * The framework will automatically handle:
     * - Optional parameters (use function's default value)
     * - Nullable parameters (allow null values)
     * - Required parameters (throw BadRequest if null)
     *
     * @param paramName Parameter name used for extraction
     * @param param Handler method parameter metadata
     * @return Function that extracts the value from a [Context]
     */
    fun build(paramName: String, param: Parameter): (Context) -> T

    /**
     * Returns the error message used when a required parameter is missing.
     *
     * This method is called by the framework *only* when:
     * - The extractor successfully ran
     * - The extracted value is `null`
     * - The handler parameter is neither optional nor nullable
     *
     * Notes:
     * - This method should NOT perform any extraction or validation logic.
     * - The missing semantics are type-level and should be independent of runtime state.
     * - Implementations may customize the message based on the parameter metadata
     *   (e.g. target type, source location such as query/form/json).
     *
     * This method is executed on the error path only and does not affect
     * the normal request execution performance.
     *
     * @param paramName Logical name of the handler parameter
     * @param param Java reflection metadata of the handler parameter
     * @return Human-readable error message describing the missing parameter
     */
    fun missingMessage(paramName: String, param: Parameter): String {
        return "Missing required parameter: $paramName"
    }
}

// ========================================================================
// Parameter Wrapper Types
// ========================================================================

/**
 * Extracts a value from the URL path parameters.
 *
 * Example:
 * ```kotlin
 * fun handler(id: Path<Int>) {
 *     // id.value is the extracted integer
 * }
 *
 * app.get("/users/{id}", ::handler)
 * ```
 *
 * Rules:
 * - The parameter name must be resolvable
 *  (from the Kotlin parameter name, or explicitly via [Param] in Java).
 * - The path parameter MUST exist in the route pattern.
 * - The value is automatically converted to the target type.
 *
 * Errors:
 * - Throws if the path parameter is missing.
 * - Throws if type conversion fails.
 */
class Path<T : Any>(value: T) : ParamExtractor<T>(value) {
    companion object : ExtractorFactory<Path<*>> {
        override fun build(paramName: String, param: Parameter): (Context) -> Path<*> {
            val innerType = param.unwrapGeneric()
            val targetClass = innerType.getRawClass()

            requireParamName("Path", paramName)

            return { ctx ->
                val value = ctx.pathParam(paramName)
                require(value != null) {
                    "Path param '$paramName' not found in path pattern: ${ctx.fullPattern}"
                }
                Path(value.convertTo(targetClass)!!)
            }
        }
    }
}

/**
 * Extracts a single HTTP request header.
 *
 * Example:
 * ```kotlin
 * fun handler(token: Header) {
 *     val value = token.value  // may be null
 * }
 * ```
 *
 * Semantics:
 * - Header values are always treated as nullable.
 * - If the header is missing, value will be null.
 *
 * This mirrors real HTTP semantics where headers are optional by default.
 */
class Header(value: String?) : ParamExtractor<String?>(value) {
    companion object : ExtractorFactory<Header> {
        override fun build(paramName: String, param: Parameter): (Context) -> Header {
            requireParamName("Header", paramName)
            return { ctx -> Header(ctx.header(paramName)) }
        }
    }
}

/**
 * Extracts a cookie value from the request.
 *
 * Example:
 * ```kotlin
 * fun handler(session: Cookie) {
 *     val value = session.value  // may be null
 * }
 * ```
 *
 * Semantics:
 * - If the cookie is missing, value will be null.
 * - No automatic decoding is performed.
 */
class Cookie(value: String?) : ParamExtractor<String?>(value) {
    companion object : ExtractorFactory<Cookie> {
        override fun build(paramName: String, param: Parameter): (Context) -> Cookie {
            requireParamName("Cookie", paramName)
            return { ctx -> Cookie(ctx.request.cookie(paramName)) }
        }
    }
}

/**
 * Extracts the raw request body as a String.
 *
 * Example:
 * ```kotlin
 * fun handler(body: Text) {
 *     val content = body.value  // may be null if body is empty
 * }
 * ```
 *
 * Notes:
 * - Intended for text-based payloads (plain text, JSON, etc.).
 * - Returns null if the request body is empty.
 */
class Text(value: String?) : ParamExtractor<String?>(value) {
    companion object : ExtractorFactory<Text> {
        override fun build(paramName: String, param: Parameter): (Context) -> Text {
            return { ctx -> Text(ctx.text()) }
        }
    }
}

/**
 * Extracts the raw request body as an InputStream.
 *
 * Example:
 * ```kotlin
 * fun handler(stream: Stream) {
 *     stream.value?.use { inputStream ->
 *         // process stream
 *     }
 * }
 * ```
 *
 * Use cases:
 * - Large file uploads
 * - Streaming data processing
 *
 * Notes:
 * - The stream may only be consumed once.
 * - Returns null if the request body is absent.
 */
class Stream(value: InputStream?) : ParamExtractor<InputStream?>(value) {
    companion object : ExtractorFactory<Stream> {
        override fun build(paramName: String, param: Parameter): (Context) -> Stream {
            return { ctx -> Stream(ctx.request.stream) }
        }
    }
}

/**
 * Extracts an uploaded file from a multipart/form-data request.
 *
 * Example:
 * ```kotlin
 * fun upload(file: UploadedFile) {
 *     file.value?.let { fileItem ->
 *         // process uploaded file
 *     }
 * }
 * ```
 *
 * Semantics:
 * - Defaults to parameter name "file" if not specified.
 * - Returns null if no file was uploaded.
 *
 * This wrapper is only meaningful for multipart requests.
 */
class UploadedFile(value: FileItem?) : ParamExtractor<FileItem?>(value) {
    companion object : ExtractorFactory<UploadedFile> {
        private const val DEFAULT_FILE_PARAM = "file"

        override fun build(paramName: String, param: Parameter): (Context) -> UploadedFile {
            val name = paramName.ifEmpty { DEFAULT_FILE_PARAM }

            return { ctx -> UploadedFile(ctx.file(name)) }
        }
    }
}

/**
 * Deserializes the request body as JSON into the specified type.
 *
 * Example:
 * ```kotlin
 * fun createUser(user: Json<User>) {
 *     val userData = user.value
 *     // userData is guaranteed non-null if parameter is required
 * }
 * ```
 *
 * Semantics:
 * - The request body is parsed exactly once.
 * - Deserialization is delegated to the configured JsonMapper.
 */
class Json<T>(value: T) : ParamExtractor<T>(value) {
    companion object : ExtractorFactory<Json<*>> {
        override fun build(paramName: String, param: Parameter): (Context) -> Json<*> {
            val innerType = param.unwrapGeneric()

            return { ctx ->
                Json(ctx.request.json<Any?>(ctx.jsonMapper, TypeRef.of(innerType)))
            }
        }

        override fun missingMessage(paramName: String, param: Parameter) =
            "Missing required JSON body"
    }
}

/**
 * Extracts query parameters from the request URL.
 *
 * Supported shapes:
 * - `Query<String>`
 * - `Query<List<String>>`
 * - `Query<Map<String, String>>`
 * - `Query<Map<String, List<String>>>`
 * - `Query<CustomDTO>`
 *
 * Example:
 * ```kotlin
 * fun handler(
 *     q: Query<String>,
 *     tags: Query<List<String>>,
 *     filters: Query<Map<String, String>>
 * ) { ... }
 * ```
 *
 * Semantics:
 * - Missing parameters respect the Kotlin parameter declaration:
 *   - Nullable parameters receive `null`
 *   - Parameters with default values use the default
 *   - Non-null parameters without defaults cause an error
 * - Automatic type conversion is applied where possible.
 *
 * Example:
 * ```kotlin
 * fun handler(
 *     q: Query<String?>,                  // missing -> null
 *     tag: Query<String>,                 // missing -> error
 *     page: Query<Int> = Query(1),        // missing -> default (1)
 * )
 * ```
 */
class Query<T>(value: T) : ParamExtractor<T>(value) {
    companion object : ExtractorFactory<Query<*>> {
        override fun build(paramName: String, param: Parameter): (Context) -> Query<*> {
            return buildCollectionExtractor(
                paramName = paramName,
                innerType = param.unwrapGeneric(),
                getSingleValue = { ctx, name -> ctx.query(name) },
                getAllValues = { ctx, name -> ctx.request.queryList(name) },
                getAllMap = { ctx -> ctx.queries() },
                getAsObject = { ctx, clazz -> ctx.queries(clazz) },
                wrapper = { Query(it) }
            )
        }

        override fun missingMessage(paramName: String, param: Parameter) =
            missingMessageByTargetType("query", paramName, param.unwrapGeneric().getRawClass())
    }
}

/**
 * Extracts form parameters from application/x-www-form-urlencoded
 * or multipart/form-data requests.
 *
 * Behavior mirrors `Query<T>`, but operates on form data.
 *
 * Example:
 * ```kotlin
 * fun handler(form: Form<UserForm>) { ... }
 * ```
 */
class Form<T>(value: T) : ParamExtractor<T>(value) {
    companion object : ExtractorFactory<Form<*>> {
        override fun build(paramName: String, param: Parameter): (Context) -> Form<*> {
            return buildCollectionExtractor(
                paramName = paramName,
                innerType = param.unwrapGeneric(),
                getSingleValue = { ctx, name -> ctx.form(name) },
                getAllValues = { ctx, name -> ctx.request.formList(name) },
                getAllMap = { ctx -> ctx.forms() },
                getAsObject = { ctx, clazz -> ctx.forms(clazz) },
                wrapper = { Form(it) }
            )
        }

        override fun missingMessage(paramName: String, param: Parameter) =
            missingMessageByTargetType("form", paramName, param.unwrapGeneric().getRawClass())
    }
}

// ========================================================================
// Core Extractor Builder
// ========================================================================

/**
 * Builds an extraction function for a single handler parameter.
 *
 * Resolution order:
 * 1. If the parameter type is Context (or a subclass), pass the Context through directly.
 * 2. If the parameter type is a ParamExtractor, delegate to its ExtractorFactory.
 * 3. Otherwise, treat the parameter as a service dependency and resolve it from Context.
 *
 * This function is executed during the "build phase" so that all reflection
 * cost is paid only once.
 */
private fun buildExtractor(paramName: String, param: Parameter): (Context) -> Any? {
    val wrapperClass = param.type.getRawClass()

    // 1. Direct Context parameter
    if (wrapperClass == Context::class.java) {
        return { it }
    }

    // 2. ParamExtractor
    if (ParamExtractor::class.java.isAssignableFrom(wrapperClass)) {
        val factory = getExtractorFactory(wrapperClass)
        return factory.build(paramName, param)
    }

    // 3. Service injection
    val qualifierName = param.getAnnotation(Qualifier::class.java)?.name
    val kClass = wrapperClass.kotlin

    return if (qualifierName == null) {
        { ctx -> ctx.resolveService(kClass, null) }
    } else {
        { ctx ->
            val qualifier = ctx.resolveQualifier(qualifierName)
            ctx.resolveService(kClass, qualifier)
        }
    }
}

/**
 * Builds a resolution function for a single handler parameter.
 *
 * The resolver decides what value to pass to the function based on:
 * - Whether the extracted value is null
 * - Whether the parameter has a default value (Kotlin only)
 * - Whether the parameter type is nullable
 *
 * This function is executed during the "build phase" so that all decisions
 * are made once, not on every request.
 */
private fun buildResolver(kParam: KParameter, jParam: Parameter, paramName: String): (Any?) -> Any? {
    // Context parameters: pass through directly
    if (kParam.isContextType()) {
        return { it }
    }

    // ParamExtractor parameters: apply extraction resolution logic
    if (kParam.isParamExtractorType()) {
        require(!kParam.type.isMarkedNullable) {
            val name = kParam.name ?: "<anonymous>"
            val type = kParam.type.toString()

            """
            Invalid parameter: `$name: $type`
            Parameter extractor types must NOT be nullable.
            For example, use `Query<Int?>` instead of `Query<Int>?`.
            """.trimIndent()
        }

        val isOptional = kParam.isOptional
        val isNullable = kParam.isExtractorValueNullable()

        return { extracted ->
            val extractor = extracted as ParamExtractor<*>
            when {
                extractor.value != null -> extractor
                isOptional -> Unit  // use function's default parameter value
                isNullable -> extractor  // pass extractor(value=null)
                else -> {
                    val factory = getExtractorFactory(extractor.javaClass)
                    throw BadRequest(factory.missingMessage(paramName, jParam))
                }
            }
        }
    }

    // Regular service injection parameters
    val paramType = kParam.type.toString()
    val isOptional = kParam.isOptional
    val isNullable = kParam.type.isMarkedNullable

    return { extracted ->
        when {
            extracted != null -> extracted
            isOptional -> Unit  // use function's default parameter value
            isNullable -> null
            else -> throw IllegalArgumentException("Missing required service: $paramType")
        }
    }
}

/**
 * Resolves the ExtractorFactory for a ParamExtractor type.
 *
 * Supported strategies:
 * 1. Kotlin: companion object implementing ExtractorFactory
 *    - resolved via Java reflection on `Companion` field
 * 2. Java: public static FACTORY field implementing ExtractorFactory
 */
private fun getExtractorFactory(wrapperClass: Class<*>): ExtractorFactory<*> {
    // 1. Kotlin: companion object
    runCatching {
        // After compilation, kotlin companion object generates a public static field: Companion.
        val companionField = wrapperClass.getDeclaredField("Companion")
        val companionInstance = companionField.get(null)

        if (companionInstance is ExtractorFactory<*>) {
            return companionInstance
        }
    }

    // 2. Java: public static FACTORY field
    runCatching {
        val factoryField = wrapperClass.getField("FACTORY")
        val factoryInstance = factoryField.get(null)

        if (factoryInstance is ExtractorFactory<*>) {
            return factoryInstance
        }
    }

    throw IllegalArgumentException(
        "${wrapperClass.simpleName} must have a companion object (Kotlin) or " +
                "FACTORY field (Java) implementing ExtractorFactory"
    )
}

// ========================================================================
// Helper Classes
// ========================================================================

/**
 * Internal metadata for a single handler parameter.
 *
 * This class encapsulates:
 * - The Kotlin parameter reference (for callBy)
 * - The extraction function (gets raw value from Context)
 * - The resolution function (decides what to pass to the function)
 *
 * Both extract and resolve are built during the "build phase" and are
 * simple lambdas with zero reflection cost during execution.
 */
private data class ParamMeta(
    val kParam: KParameter,
    val extract: (Context) -> Any?,
    val resolve: (Any?) -> Any?
)

// ========================================================================
// Helper Functions
// ========================================================================

/**
 * Builds a default "missing parameter" message based on the extractor target type.
 *
 * Rules:
 * - Scalar or list types are treated as a single parameter (include name).
 * - Map and object types represent multiple parameters (omit name).
 *
 * Field-level semantics of custom DTOs are intentionally NOT inferred.
 * Detailed validation should be handled separately.
 *
 * @param source Parameter source (e.g. "query", "path", "form")
 * @param paramName Handler parameter name
 * @param targetClass Target Java class of the extractor value
 */
internal fun missingMessageByTargetType(source: String, paramName: String, targetClass: Class<*>): String =
    when {
        targetClass.isScalar() || targetClass.isList() ->
            "Missing required $source parameter: $paramName"

        targetClass.isMap() ->
            "Missing required $source parameters"

        else ->
            // Custom DTO or object: do not guess field-level semantics
            "Missing required $source parameters"
    }

/**
 * Builds an extractor for collection-like parameters such as `Query<T>` and `Form<T>`.
 *
 * Supported shapes:
 * - `Map<String, String>`
 * - `Map<String, List<String>>`
 * - `List<T>`
 * - Simple types (String, Int, etc.)
 * - Custom DTOs
 *
 * This function centralizes all collection-related extraction logic
 * to avoid duplication between Query and Form.
 */
private fun <T : ParamExtractor<*>> buildCollectionExtractor(
    paramName: String,
    innerType: Type,
    getAllValues: (Context, String) -> List<String>,
    getAllMap: (Context) -> Map<String, List<String>>,
    getSingleValue: (Context, String) -> String?,
    getAsObject: (Context, Class<*>) -> Any?,
    wrapper: (Any?) -> T
): (Context) -> T {
    val targetClass = innerType.getRawClass()

    // Handle Map<String, ...>
    if (targetClass.isMap()) {
        val valueTypeInfo = (innerType as ParameterizedType).secondTypeArgument()
        require(valueTypeInfo != null) {
            "Type '$innerType' is not supported: Map missing value type"
        }
        val valueTypeClass = valueTypeInfo.getRawClass()

        return when {
            // Map<String, List<String>>
            valueTypeClass.isList() -> { ctx -> wrapper(getAllMap(ctx)) }
            // Map<String, String>
            valueTypeClass == String::class.java -> { ctx ->
                wrapper(getAllMap(ctx).mapValues { it.value.firstOrNull() ?: "" })
            }

            else -> throw IllegalArgumentException("Type '$innerType' is not supported: Map value type must be String or List<String>")
        }
    }

    // Handle List<T>
    if (targetClass.isList()) {
        val elementTypeInfo = (innerType as ParameterizedType).firstTypeArgument()
        require(elementTypeInfo != null) {
            "Type '$innerType' is not supported: List missing element type"
        }
        val elementClass = elementTypeInfo.getRawClass()

        return { ctx ->
            val values = getAllValues(ctx, paramName)
            wrapper(values.map { it.convertTo(elementClass) })
        }
    }

    // Handle simple types (Int, String, etc.)
    if (targetClass.isScalar()) {
        return { ctx ->
            val value = getSingleValue(ctx, paramName)
            when {
                value != null -> wrapper(value.convertTo(targetClass))
                else -> wrapper(null)
            }
        }
    }

    // Handle custom DTOs
    return { ctx ->
        wrapper(getAsObject(ctx, targetClass))
    }
}

private fun requireParamName(type: String, name: String) {
    require(name.isNotEmpty()) {
        "$type parameter name not available; either use @Param annotation or compile with -parameters flag"
    }
}

// ========================================================================
// Extension Functions
// ========================================================================

/**
 * Resolves the raw Java class from a [Type].
 *
 * Supported cases:
 * - [Class]            -> returned directly
 * - [ParameterizedType] -> its raw type (e.g. `List<String>` -> `List::class.java`)
 *
 * Unsupported or exotic Type implementations (e.g. WildcardType, TypeVariable)
 * are intentionally rejected, as Colleen requires a concrete runtime class
 * for parameter extraction and conversion.
 */
internal fun Type.getRawClass(): Class<*> {
    return when (this) {
        is Class<*> -> this
        is ParameterizedType -> this.rawType as Class<*>
        else -> throw IllegalArgumentException("Type '$this' is not supported: cannot resolve class")
    }
}

/**
 * Returns the first generic type argument of a [ParameterizedType], if present.
 *
 * Example:
 * - `List<String>` -> `String`
 * - `Map<String, Int>` -> `String`
 */
internal fun ParameterizedType.firstTypeArgument(): Type? {
    return this.actualTypeArguments.firstOrNull()
}

/**
 * Returns the second generic type argument of a [ParameterizedType], if present.
 *
 * Example:
 * - `Map<String, Int>` -> `Int`
 *
 * Mainly used when handling Map-like parameter types, such as:
 * `Map<String, String>` or `Map<String, List<String>>`.
 */
internal fun ParameterizedType.secondTypeArgument(): Type? {
    return this.actualTypeArguments.getOrNull(1)
}

/**
 * Resolves the logical parameter name for a Java method parameter.
 *
 * Resolution order:
 * 1. @Param annotation value (if present and non-empty)
 * 2. Java reflection parameter name (requires `-parameters` compiler flag)
 * 3. Empty string if name information is unavailable
 *
 * An empty name usually indicates that the parameter cannot be reliably
 * extracted and will later result in a validation error.
 */
internal fun Parameter.getParamName(): String {
    val paramName = this.getAnnotation(Param::class.java)?.value?.takeIf { it.isNotEmpty() }
        ?: if (this.isNamePresent) this.name else ""
    return paramName
}

/**
 * Resolves the logical parameter name for a Kotlin function parameter.
 *
 * Resolution order:
 * 1. @Param annotation value (if present and non-empty)
 * 2. Kotlin parameter name (available by default)
 *
 * Unlike Java, Kotlin reliably preserves parameter names, making this
 * path more robust without additional compiler flags.
 */
internal fun KParameter.getParamName(): String {
    val paramName = this.findAnnotation<Param>()?.value?.takeIf { it.isNotEmpty() }
        ?: this.name.orEmpty()
    return paramName
}

/**
 * Checks whether this parameter represents a [Context] injection point.
 */
internal fun KParameter.isContextType(): Boolean {
    val paramType = this.type.jvmErasure
    return paramType == Context::class || paramType.isSubclassOf(Context::class)
}

/**
 * Checks whether this parameter is a [ParamExtractor] type.
 */
internal fun KParameter.isParamExtractorType(): Boolean {
    return this.type.jvmErasure.isSubclassOf(ParamExtractor::class)
}

/**
 * Determines whether the *value wrapped by a ParamExtractor* is nullable.
 *
 * This is distinct from checking whether the parameter itself is nullable.
 *
 * Two cases are supported:
 * 1. Extractors without type arguments (e.g. Header, Cookie)
 *    -> inferred from the `ParamExtractor<T>` supertype
 * 2. Generic extractors (e.g. `Query<T>`, `Json<T>`)
 *    -> inferred from the generic type argument
 *
 * This information is used to decide whether a missing parameter should:
 * - throw an error
 * - propagate null
 * - or fall back to a default value
 */
internal fun KParameter.isExtractorValueNullable(): Boolean {
    val paramType = this.type

    if (!paramType.isSubtypeOf(ParamExtractor::class.starProjectedType)) {
        return false
    }

    val typeArguments = paramType.arguments

    return if (typeArguments.isEmpty()) {
        // Case 1: No type parameters (e.g., Header, Cookie)
        // Obtain from the type parameter of the parent class ParamExtractor<T>
        val superType = paramType.jvmErasure.supertypes.find {
            it.jvmErasure == ParamExtractor::class
        }
        superType?.arguments?.firstOrNull()?.type?.isMarkedNullable ?: false
    } else {
        // Case 2: Has type parameters (e.g., Query<T>, Json<T>)
        typeArguments.firstOrNull()?.type?.isMarkedNullable ?: false
    }
}

/**
 * Unwraps the generic type argument from a Java method parameter.
 *
 * Example:
 * - `Path<Int> -> Int`
 * - `Query<List<String>> -> List<String>`
 *
 * This function assumes that the parameter is parameterized.
 * Missing or raw generic types are rejected early to avoid
 * ambiguous runtime behavior.
 */
internal fun Parameter.unwrapGeneric(): Type {
    val parameterizedType = this.parameterizedType
    if (parameterizedType is ParameterizedType) {
        val typeArgs = parameterizedType.actualTypeArguments
        if (typeArgs.isNotEmpty()) {
            return typeArgs[0]
        }
    }
    throw IllegalArgumentException("Type '$type' is not supported: missing generic type parameter")
}

/**
 * Determines nullability for Java parameters based on common annotations.
 *
 * Heuristics:
 * - @NotNull / @NonNull / @Required -> non-null
 * - @Nullable -> nullable
 * - No annotation -> nullable by default
 *
 * This mirrors typical Java ecosystem conventions while remaining conservative.
 */
@Suppress("UNUSED_PARAMETER")
internal fun Parameter.isNullable(): Boolean {
    return when {
        hasAnnotation("NotNull", "NonNull", "Required") -> false
        hasAnnotation("Nullable") -> true
        else -> true  // default nullable
    }
}

/**
 * Checks whether this parameter has any annotation whose simple name matches
 * one of the provided values.
 *
 * Used to support nullability and validation annotations without
 * hard dependencies on specific annotation libraries.
 */
internal fun Parameter.hasAnnotation(vararg simpleNames: String): Boolean {
    return annotations.any { ann ->
        simpleNames.any { ann.annotationClass.java.simpleName == it }
    }
}

/**
 * Converts a string value to a supported primitive or wrapper type.
 *
 * Supported target types:
 * - String
 * - Int / Long / Double / Float
 * - Boolean (lenient parsing)
 *
 * Used by Path, Query, and Form extractors.
 *
 * Conversion rules:
 * - null is allowed for non-primitive targets
 * - null for primitive targets results in an error
 * - invalid formats result in [TypeConversionFailed]
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal fun String?.convertTo(targetClass: Class<*>): Any? {
    if (this == null) {
        if (targetClass.isPrimitive) {
            throw TypeConversionFailed(
                "null",
                targetClass.simpleName,
                IllegalArgumentException("Primitive type cannot be null")
            )
        }
        return null
    }

    return try {
        when (targetClass) {
            String::class.java -> this
            Int::class.java, Integer::class.java ->
                toIntOrNull() ?: throw TypeConversionFailed(this, "Int")

            Long::class.java, java.lang.Long::class.java ->
                toLongOrNull() ?: throw TypeConversionFailed(this, "Long")

            Double::class.java, java.lang.Double::class.java ->
                toDoubleOrNull() ?: throw TypeConversionFailed(this, "Double")

            Float::class.java, java.lang.Float::class.java ->
                toFloatOrNull() ?: throw TypeConversionFailed(this, "Float")

            Boolean::class.java, java.lang.Boolean::class.java ->
                toLenientBoolean()

            else ->
                throw IllegalArgumentException("Type '${targetClass.simpleName}' is not supported: Type conversion cannot be done")
        }
    } catch (e: ExtractionException) {
        throw e
    } catch (e: Exception) {
        throw TypeConversionFailed(this, targetClass.simpleName, e)
    }
}

/**
 * Converts a string to a boolean using lenient rules.
 *
 * Accepted true values:
 * - "true", "1", "yes", "y" (case-insensitive)
 *
 * Any other value is interpreted as false.
 */
internal fun String.toLenientBoolean(): Boolean {
    return lowercase() in setOf("true", "1", "yes", "y")
}

/**
 * Checks whether this class represents a simple scalar type
 * supported by Colleen's built-in converters.
 */
internal fun Class<*>.isScalar() = this in SIMPLE_TYPES

/**
 * Checks whether this class represents a Map type.
 */
internal fun Class<*>.isMap() = Map::class.java.isAssignableFrom(this)

/**
 * Checks whether this class represents a List type.
 */
internal fun Class<*>.isList() = List::class.java.isAssignableFrom(this)

/**
 * Set of scalar types supported by Colleen's default string conversion logic.
 *
 * These types are safe to construct directly from textual HTTP data.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal val SIMPLE_TYPES = setOf(
    String::class.java,
    Int::class.java, Integer::class.java,
    Long::class.java, java.lang.Long::class.java,
    Double::class.java, java.lang.Double::class.java,
    Float::class.java, java.lang.Float::class.java,
    Boolean::class.java, java.lang.Boolean::class.java
)