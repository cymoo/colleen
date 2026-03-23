package io.github.cymoo.colleen.openapi

import java.lang.reflect.Type

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
 * Returned by [io.github.cymoo.colleen.ExtractorFactory.describeOpenApi].
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
