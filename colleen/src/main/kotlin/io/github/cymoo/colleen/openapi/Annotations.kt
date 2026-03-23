package io.github.cymoo.colleen.openapi

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
