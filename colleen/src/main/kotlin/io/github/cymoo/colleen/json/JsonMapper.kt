package io.github.cymoo.colleen.json

import io.github.cymoo.colleen.BindingFailure
import io.github.cymoo.colleen.JsonConfig
import io.github.cymoo.colleen.util.TypeRef
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import java.util.stream.Stream


/**
 * Abstraction for JSON serialization and deserialization.
 *
 * Implementations may delegate to libraries such as Jackson, Moshi, Gson, etc.
 * Colleen interacts with JSON exclusively through this interface.
 */
interface JsonMapper {
    /**
     * Apply configuration to this mapper.
     * Each implementation decides how to interpret the configuration.
     */
    fun configure(config: JsonConfig) {
        // Default: no-op, implementations can override
    }

    /**
     * Serialize an object to a JSON string using its runtime type.
     *
     * This is the common-case API. Implementations typically infer the type
     * from the object itself. For cases where an explicit generic type is
     * required, use `toJsonString(Any, Type)` instead.
     */
    fun toJsonString(obj: Any): String =
        toJsonString(obj, obj::class.java)

    /**
     * Serialize an object to JSON and expose it as an InputStream using its runtime type.
     *
     * This is primarily intended for streaming responses. If the underlying
     * implementation requires an explicit generic type,
     * use `toJsonStream(Any, Type)` instead.
     */
    fun toJsonStream(obj: Any): InputStream =
        toJsonStream(obj, obj::class.java)

    /**
     * Serialize an object to a JSON string using an explicit target type.
     *
     * Not all implementations support this operation.
     */
    fun toJsonString(obj: Any, type: Type): String =
        throw UnsupportedOperationException(
            "This JsonMapper does not support explicit type-based serialization"
        )

    /**
     * Serialize an object to JSON and expose it as an InputStream.
     *
     * Not all implementations support this operation.
     */
    fun toJsonStream(obj: Any, type: Type): InputStream =
        throw UnsupportedOperationException(
            "This JsonMapper does not support explicit type-based serialization"
        )

    /**
     * Writes a stream of objects as a JSON array to the given OutputStream.
     *
     * Implementations are expected to:
     * - Consume the input stream element by element
     * - Serialize each element to JSON incrementally
     * - Write a valid JSON array to the output stream
     *
     * This method exists to minimize memory usage for large data streams.
     *
     * Not all implementations support streaming serialization.
     */
    fun writeToOutputStream(stream: Stream<*>, outputStream: OutputStream): Unit =
        throw UnsupportedOperationException(
            "This JsonMapper does not support streaming JSON array serialization"
        )

    /** Deserialize a JSON string into the given target type. */
    fun <T : Any> fromJsonString(json: String, targetType: Type): T =
        throw UnsupportedOperationException(
            "This JsonMapper does not support JSON string deserialization"
        )

    /**
     * Deserialize JSON from an InputStream into the given target type.
     *
     * This method is typically used for large payloads or streaming inputs.
     */
    fun <T : Any> fromJsonStream(json: InputStream, targetType: Type): T {
        throw UnsupportedOperationException(
            "This JsonMapper does not support InputStream deserialization"
        )
    }

    /**
     * Convert an object to the given target type.
     *
     * Common use cases include:
     * - Map -> data class
     * - List -> typed collection
     * - Loose JSON structures -> strongly typed models
     *
     * Not all implementations support arbitrary object-to-object conversion.
     */
    fun <T : Any> convertValue(obj: Any, targetType: Type): T =
        throw UnsupportedOperationException(
            "This JsonMapper does not support value conversion"
        )

    /**
     * Attempt to classify a deserialization failure into a generic binding failure.
     *
     * Implementations may return null if the error is not recognized.
     */
    fun classifyError(e: Exception): BindingFailure? = null
}

/** Deserialize a JSON string into type T. */
inline fun <reified T : Any> JsonMapper.fromJsonString(json: String): T =
    fromJsonString(json, object : TypeRef<T>() {}.type)
