package io.github.cymoo.colleen.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.github.cymoo.colleen.BindingFailure
import io.github.cymoo.colleen.Config
import io.github.cymoo.colleen.JsonConfig
import io.github.cymoo.colleen.util.InputStreamFactory
import io.github.cymoo.colleen.util.RingBufferInputStreamFactory
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.stream.Stream

class JacksonMapper(
    objectMapper: ObjectMapper? = null,
    private val useVirtualThreads: Boolean = false,
) : JsonMapper {

    private val inputStreamFactory: InputStreamFactory by lazy {
        RingBufferInputStreamFactory(useVirtualThreads)
    }

    /**
     * The underlying Jackson ObjectMapper.
     * Exposed for direct configuration by advanced users.
     */
    val mapper = objectMapper ?: defaultMapper()

    override fun configure(config: JsonConfig) {
        // Serialization features
        mapper.configure(SerializationFeature.INDENT_OUTPUT, config.pretty)
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, config.writeDatesAsTimestamps)
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, config.failOnEmptyBeans)
        mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, config.writeEnumsUsingToString)

        // Deserialization features
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, config.failOnUnknownProperties)
        mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, config.failOnNullForPrimitives)
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, config.acceptSingleValueAsArray)
        mapper.configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, config.readEnumsUsingToString)

        // Inclusion
        mapper.setSerializationInclusion(
            if (config.includeNulls) JsonInclude.Include.ALWAYS else JsonInclude.Include.NON_NULL
        )

        // Date format
        config.dateFormat?.let { mapper.dateFormat = SimpleDateFormat(it) }
    }

    override fun toJsonString(obj: Any): String = when (obj) {
        // the default mapper treats strings as if they are already JSON
        is String -> obj
        else -> mapper.writeValueAsString(obj)
    }

    override fun toJsonStream(obj: Any): InputStream = when (obj) {
        // the default mapper treats strings as if they are already JSON
        is String -> obj.byteInputStream()
        else -> inputStreamFactory.open { outputStream ->
            mapper.factory.createGenerator(outputStream).writeObject(obj)
        }
    }

    override fun writeToOutputStream(stream: Stream<*>, outputStream: OutputStream) {
        mapper.writer().writeValuesAsArray(outputStream).use { sequenceWriter ->
            stream.forEach { sequenceWriter.write(it) }
        }
    }

    override fun <T : Any> fromJsonString(json: String, targetType: Type): T =
        mapper.readValue(json, mapper.typeFactory.constructType(targetType))

    override fun <T : Any> fromJsonStream(json: InputStream, targetType: Type): T =
        mapper.readValue(json, mapper.typeFactory.constructType(targetType))

    override fun <T : Any> convertValue(obj: Any, targetType: Type): T {
        return mapper.convertValue(obj, mapper.typeFactory.constructType(targetType))
    }

    /**
     * Classifies low-level Jackson exceptions into semantic BindingFailure types.
     *
     * This layer hides Jackson-specific details from the rest of the framework
     * and provides stable, meaningful error semantics.
     */
    override fun classifyError(e: Exception): BindingFailure? =
        when (e) {
            is JsonParseException ->
                BindingFailure.Malformed(e)

            is UnrecognizedPropertyException ->
                BindingFailure.Unknown(buildFullPath(e) ?: e.propertyName, e)

            is InvalidFormatException ->
                classifyInvalidFormat(e)

            is ValueInstantiationException ->
                classifyInstantiationFailure(e)

            is MismatchedInputException ->
                classifyMismatchedInput(e)

            else -> null
        }

    /**
     * Handles value format conversion failures.
     */
    private fun classifyInvalidFormat(e: InvalidFormatException): BindingFailure {
        val fullPath = buildFullPath(e) ?: return BindingFailure.InvalidStructure(e)

        return findArrayElementError(e)?.let { (field, index) ->
            BindingFailure.InvalidElementType(
                field = field,
                index = index,
                expected = e.targetType?.simpleName,
                originalCause = e
            )
        } ?: BindingFailure.InvalidType(fullPath, e.targetType?.simpleName, e)
    }

    /**
     * Handles failures during object instantiation.
     */
    private fun classifyInstantiationFailure(e: ValueInstantiationException): BindingFailure {
        val field = buildFullPath(e) ?: return BindingFailure.InvalidStructure(e)

        return if (isLikelyMissing(e))
            BindingFailure.Missing(field, e)
        else
            BindingFailure.InvalidType(field, originalCause = e)
    }

    /**
     * Handles general input mismatches.
     */
    private fun classifyMismatchedInput(e: MismatchedInputException): BindingFailure {
        val fullPath = buildFullPath(e) ?: return BindingFailure.InvalidStructure(e)

        if (isLikelyMissing(e)) {
            return BindingFailure.Missing(fullPath, e)
        }

        return findArrayElementError(e)?.let { (field, index) ->
            BindingFailure.InvalidElementType(
                field = field,
                index = index,
                originalCause = e
            )
        } ?: BindingFailure.InvalidType(fullPath, originalCause = e)
    }

    /**
     * Heuristically determines whether an exception represents a missing required field.
     */
    private fun isLikelyMissing(e: Exception): Boolean {
        if (e !is MismatchedInputException && e !is ValueInstantiationException) {
            return false
        }

        val msg = e.message?.lowercase() ?: return false

        return msg.contains("missing required creator property") ||
                msg.contains("missing creator property") ||
                (msg.contains("missing") && msg.contains("parameter"))
    }

    /**
     * Builds the complete field path from the exception, including array indices.
     *
     * Examples:
     * - "user.address.city"
     * - "members[0].name"
     * - "data[2].items[1].value"
     */
    private fun buildFullPath(e: JsonMappingException): String? {
        if (e.path.isEmpty()) return null

        return e.path.joinToString(separator = "") { ref ->
            when {
                ref.fieldName != null -> {
                    if (ref == e.path.first()) ref.fieldName
                    else ".${ref.fieldName}"
                }

                ref.index >= 0 -> "[${ref.index}]"
                else -> ""
            }
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * Determines if the error is at the array element level (not a field within the element).
     *
     * Returns (fieldPath, index) if this is an element-level error, null otherwise.
     *
     * Element-level error: path ends with an index, like "members[1]"
     * Field-level error: path has fields after the index, like "members[1].age"
     */
    private fun findArrayElementError(e: JsonMappingException): Pair<String, Int>? {
        val path = e.path
        if (path.isEmpty()) return null

        // Check if the last element in the path is an array index
        val lastRef = path.last()
        if (lastRef.index < 0) return null

        // If last element is an index, it's an element-level error
        // Build the field path excluding the last index
        if (path.size == 1) return null // Just an index with no field name

        val fieldPath = path.dropLast(1).joinToString(separator = "") { ref ->
            when {
                ref.fieldName != null -> {
                    if (ref == path.first()) ref.fieldName
                    else ".${ref.fieldName}"
                }

                ref.index >= 0 -> "[${ref.index}]"
                else -> ""
            }
        }.takeIf { it.isNotEmpty() } ?: return null

        return fieldPath to lastRef.index
    }

    companion object {
        @JvmStatic
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(
                KotlinModule.Builder()
                    .enable(KotlinFeature.StrictNullChecks)
                    .build()
            )
            .registerModule(JavaTimeModule())
    }
}

/**
 * Configure the underlying Jackson ObjectMapper directly.
 *
 * This provides access to all Jackson features including:
 * - Registering modules (JavaTime, ParameterNames, etc.)
 * - Adding custom serializers/deserializers
 * - Setting visibility rules
 * - Configuring advanced features
 *
 * Note: This only works when using the default JacksonMapper.
 * If you've set a custom JsonMapper via [Config.jsonMapper], this will replace it
 * with a new JacksonMapper instance.
 *
 * Example:
 * ```kotlin
 * app.config {
 *     jackson {
 *         // Register modules
 *         registerModule(JavaTimeModule())
 *
 *         // Add custom serializers
 *         val module = SimpleModule()
 *         module.addSerializer(BigDecimal::class.java, ToStringSerializer())
 *         registerModule(module)
 *
 *         // Configure visibility
 *         setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
 *     }
 * }
 * ```
 *
 * @param block Configuration block with ObjectMapper as receiver
 */
fun Config.jackson(block: ObjectMapper.() -> Unit) {
    val jacksonMapper =
        (jsonMapperInstance as? JacksonMapper) ?: createDefaultJsonMapper().also { jsonMapperInstance = it }
    jacksonMapper.mapper.block()
}
