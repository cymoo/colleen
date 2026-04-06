@file:Suppress("unused")

package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.HttpStatus

/**
 * Represents the error state within a request context during middleware chain execution.
 *
 * This class wraps exceptions that occur during request processing and tracks whether
 * they have been handled by middleware. It enables middleware to:
 * - Inspect errors from downstream handlers/middleware
 * - Mark errors as handled to prevent them from being rethrown
 * - Implement custom error recovery or transformation logic
 *
 * ## Lifecycle
 *
 * 1. When an exception occurs in a handler or middleware, it's wrapped in an [ErrorState]
 * 2. The [ErrorState] is stored in [Context.error] and propagates up the middleware chain
 * 3. Middleware can inspect [cause] and optionally set [handled] = true
 * 4. After the middleware chain completes, unhandled errors ([handled] = false) are rethrown
 *
 * ## Error Merging
 *
 * If exceptions occur in both downstream and current middleware layers, they are merged:
 * - The downstream exception remains the primary [cause]
 * - The current exception is added as a suppressed exception via [Throwable.addSuppressed]
 * - The [handled] flag from the downstream [ErrorState] is preserved
 *
 * @property cause The underlying exception that occurred during request processing
 * @property handled Whether this error has been handled by middleware. Defaults to `false`.
 *                   When `true`, the error will not be rethrown at the end of the middleware chain.
 *
 * @see Context.error
 */
data class ErrorState(
    @JvmField
    val cause: Throwable,
    @JvmField
    var handled: Boolean = false
)

/**
 * Base HTTP exception used to short-circuit request handling and produce
 * an HTTP error response.
 *
 * An [HttpException] carries both an HTTP [status] code and a machine-readable
 * error [code]. The [code] is automatically derived from the status
 * (e.g. `404` -> `NOT_FOUND`), but can be overridden if needed.
 *
 * This exception is intended to be thrown from handlers or middleware and
 * caught by the framework's global error handling mechanism.
 *
 * @param status HTTP status code to be returned to the client
 * @param message Human-readable error message
 * @param cause Optional underlying cause
 * @param code Stable error identifier, defaults to a value derived from [status]
 */
open class HttpException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
    val code: String = HttpStatus.statusCodeName(status),
) : RuntimeException(message, cause)

// ========================================================================
// 4xx Client Error
// ========================================================================

open class BadRequest @JvmOverloads constructor(
    message: String = "Bad Request",
    cause: Throwable? = null
) : HttpException(400, message, cause)

class Unauthorized @JvmOverloads constructor(
    message: String = "Unauthorized",
    cause: Throwable? = null
) : HttpException(401, message, cause)

class Forbidden @JvmOverloads constructor(
    message: String = "Forbidden",
    cause: Throwable? = null
) : HttpException(403, message, cause)

open class NotFound @JvmOverloads constructor(
    message: String = "Not Found",
    cause: Throwable? = null
) : HttpException(404, message, cause)

open class MethodNotAllowed(
    message: String = "Method Not Allowed",
    val allowedMethods: Set<String> = emptySet(),
    cause: Throwable? = null
) : HttpException(405, message, cause)

class NotAcceptable(
    message: String = "Not Acceptable",
    cause: Throwable? = null
) : HttpException(406, message, cause)

class Conflict(
    message: String = "Conflict",
    cause: Throwable? = null
) : HttpException(409, message, cause)

class Gone(
    message: String = "Gone",
    cause: Throwable? = null
) : HttpException(410, message, cause)

class PayloadTooLarge(
    message: String = "Payload Too Large",
    cause: Throwable? = null
) : HttpException(413, message, cause)

class UnsupportedMediaType(
    message: String = "Unsupported Media Type",
    cause: Throwable? = null
) : HttpException(415, message, cause)

class UnprocessableEntity(
    message: String = "Unprocessable Entity",
    cause: Throwable? = null
) : HttpException(422, message, cause)

class TooManyRequests @JvmOverloads constructor(
    message: String = "Too Many Requests",
    val retryAfter: Int? = null,
    cause: Throwable? = null
) : HttpException(429, message, cause)

/**
 * HTTP exception representing request validation failures.
 *
 * This exception is typically thrown when user input does not satisfy
 * validation rules (e.g. missing fields, invalid formats).
 *
 * The HTTP status code is fixed to 422 (Unprocessable Entity) and a stable
 * error code `VALIDATION_FAILED` is used for client-side error handling.
 *
 * The error [message] defaults to a compact, single-line representation
 * of [errors], making it suitable for HTTP responses and logs. A custom
 * message may be provided to override the default formatting.
 *
 * The structured [errors] map preserves field-level validation details
 * and can be used by error handlers to produce richer responses if needed.
 *
 * @param errors Field-level validation errors, grouped by field name
 * @param message Optional human-readable summary message
 */
class ValidationException(
    val errors: Map<String, List<String>>,
    message: String? = null
) : HttpException(
    status = 422,
    message = message ?: formatAsSingleLine(errors),
    code = "VALIDATION_FAILED"
) {
    companion object {
        /**
         * Formats validation errors into a single-line, human-readable string.
         *
         * Example output:
         * name: must not be blank; age: must be greater than 0
         *
         * Suitable for compact error messages (e.g. HTTP responses, logs).
         */
        fun formatAsSingleLine(errors: Map<String, List<String>>): String {
            return errors.entries.joinToString("; ") { (field, messages) ->
                "$field: ${messages.joinToString(", ")}"
            }
        }

        /**
         * Formats validation errors into a multi-line, human-readable string.
         *
         * Example output:
         * Validation failed:
         *   ✗ name: must not be blank
         *   ✗ age: must be greater than 0
         *
         * Suitable for detailed error reports or developer-facing output.
         */
        fun formatAsMultiLine(errors: Map<String, List<String>>): String = buildString {
            appendLine("Validation failed:")
            errors.forEach { (field, messages) ->
                messages.forEach { msg ->
                    appendLine("  ✗ $field: $msg")
                }
            }
        }
    }
}

// ========================================================================
// 5xx Server Error
// ========================================================================

class ServerError @JvmOverloads constructor(
    message: String = "Internal Server Error",
    cause: Throwable? = null
) : HttpException(500, message, cause)

class NotImplemented(
    message: String = "Not Implemented",
    cause: Throwable? = null
) : HttpException(501, message, cause)

class BadGateway(
    message: String = "Bad Gateway",
    cause: Throwable? = null
) : HttpException(502, message, cause)

class ServiceUnavailable(
    message: String = "Service Unavailable",
    val retryAfter: Int? = null,
    cause: Throwable? = null
) : HttpException(503, message, cause)

class GatewayTimeout(
    message: String = "Gateway Timeout",
    cause: Throwable? = null
) : HttpException(504, message, cause)

// ========================================================================
// Route Not Found
// ========================================================================

internal class RouteNotFound(
    val path: String,
    val method: String
) : NotFound("No route found for $method $path")

internal class RouteMethodNotAllowed(
    val path: String,
    val method: String,
    allowedMethods: Set<String> = emptySet()
) : MethodNotAllowed(
    message = "Method $method not allowed for $path",
    allowedMethods = allowedMethods
)

// ========================================================================
// Data Binding Failure
// ========================================================================

/**
 * Represents a semantic failure during request data binding.
 *
 * BindingFailure sits between low-level deserialization errors (e.g. Jackson exceptions)
 * and protocol-level errors (e.g. HTTP 400).
 *
 * It describes *what went wrong* in terms of request semantics, not *how* it failed internally.
 */
sealed class BindingFailure(
    /** Field name associated with the failure, if applicable */
    open val field: String? = null,
    cause: Throwable? = null
) : Exception(cause) {

    /**
     * A required (non-null / non-optional) field is missing from the input.
     *
     * Example:
     *   data class User(val age: Int)
     *   JSON: {}
     */
    data class Missing(
        override val field: String,
        val originalCause: Throwable? = null
    ) : BindingFailure(field, originalCause)

    /**
     * Field value exists but cannot be converted to the expected type.
     *
     * Example:
     *   age = "abc" when Int is expected
     */
    data class InvalidType(
        override val field: String,
        val expected: String? = null,
        val originalCause: Throwable? = null
    ) : BindingFailure(field, originalCause)

    /**
     * An unknown or extra field that is not defined in the target model.
     *
     * Typically, produced when FAIL_ON_UNKNOWN_PROPERTIES is enabled.
     */
    data class Unknown(
        override val field: String,
        val originalCause: Throwable? = null
    ) : BindingFailure(field, originalCause)

    /**
     * Input is not valid JSON (syntax error).
     *
     * Example:
     *   "{ name: }"
     */
    class Malformed(
        val originalCause: Throwable? = null
    ) : BindingFailure(null, originalCause)

    /**
     * Input is syntactically valid but does not match the expected object structure.
     *
     * Example:
     *   expecting an object but receiving an array
     */
    class InvalidStructure(
        val originalCause: Throwable? = null
    ) : BindingFailure(null, originalCause)

    /**
     * A collection element cannot be converted to the expected type.
     *
     * Example:
     *   ids = [1, "abc", 3]
     *   -> element at index 1 is invalid
     */
    data class InvalidElementType(
        override val field: String,
        val index: Int,
        val expected: String? = null,
        val originalCause: Throwable? = null
    ) : BindingFailure(field, originalCause)
}

/**
 * Converts a BindingFailure into a protocol-level HTTP exception.
 *
 * All binding failures currently map to HTTP 400 (Bad Request),
 * while preserving semantic error messages for clients.
 */
fun BindingFailure.toHttpException(): BadRequest =
    when (this) {
        is BindingFailure.Missing ->
            BadRequest("Missing required field: $field", this)

        is BindingFailure.InvalidType ->
            BadRequest(
                expected?.let {
                    "Invalid type for field '$field': expected $it"
                } ?: "Invalid type for field '$field'",
                this
            )

        is BindingFailure.InvalidElementType ->
            BadRequest(
                expected?.let {
                    "Invalid type for element $index of field '$field': expected $it"
                } ?: "Invalid type for element $index of field '$field'",
                this
            )

        is BindingFailure.Unknown ->
            BadRequest("Unknown field: $field", this)

        is BindingFailure.Malformed ->
            BadRequest("Malformed request body", this)

        is BindingFailure.InvalidStructure ->
            BadRequest("Request body does not match expected structure", this)
    }
