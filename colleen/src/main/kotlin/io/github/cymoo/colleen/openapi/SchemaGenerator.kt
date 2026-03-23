package io.github.cymoo.colleen.openapi

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Date
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

// ============================================================================
// Schema Generation
// ============================================================================

private const val MAX_DEPTH = 5

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
