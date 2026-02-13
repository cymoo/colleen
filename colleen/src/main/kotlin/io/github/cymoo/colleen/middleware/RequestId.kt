package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import java.util.*

/**
 * Request ID Middleware
 *
 * Generates a unique ID for each request to facilitate tracing and debugging.
 * The request ID can be extracted from incoming request headers or generated automatically.
 *
 * Features:
 * - Extracts existing request ID from request headers
 * - Generates new UUID-based request ID if not present
 * - Optionally adds request ID to response headers
 * - Stores request ID in context state for downstream middleware access
 *
 * @param headerName The name of the header to read/write the request ID (default: "X-Request-ID")
 * @param generator Custom function to generate request IDs (default: UUID.randomUUID())
 * @param setResponseHeader Whether to include the request ID in response headers (default: true)
 */
class RequestId @JvmOverloads constructor(
    private val headerName: String = "X-Request-ID",
    private val generator: () -> String = { UUID.randomUUID().toString() },
    private val setResponseHeader: Boolean = true
) : Middleware {

    companion object {
        // Constant key to avoid conflicts with user-defined context attributes
        internal const val REQUEST_ID_KEY = "io.github.cymoo.colleen.middleware.RequestId.key"
    }

    init {
        // Validate that header name is not blank
        require(headerName.isNotBlank()) {
            "Header name cannot be blank"
        }
    }

    override fun invoke(ctx: Context, next: Next) {
        // Attempt to retrieve request ID from incoming request header
        // If not present, generate a new one using the provided generator
        val requestId = ctx.header(headerName)?.takeIf { it.isNotBlank() }
            ?: generator().also { generatedId ->
                // Validate generated request ID is not empty
                require(generatedId.isNotBlank()) {
                    "Generated request ID cannot be blank"
                }
            }

        // Store request ID in context state for access by downstream middleware
        // Using a consistent key name for retrieval
        ctx.setState(REQUEST_ID_KEY, requestId)

        // Set response header before calling next() to ensure it's included
        // even if an error occurs in downstream middleware
        if (setResponseHeader) {
            ctx.response.header(headerName, requestId)
        }

        // Proceed to next middleware in the chain
        next()
    }
}

fun Context.getRequestId(): String? {
    return this.getStateOrNull(RequestId.REQUEST_ID_KEY)
}