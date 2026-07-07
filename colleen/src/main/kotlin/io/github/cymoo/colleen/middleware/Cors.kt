package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next

/**
 * CORS (Cross-Origin Resource Sharing) middleware
 *
 * Handles CORS preflight requests and adds appropriate CORS headers to responses.
 *
 * @param allowOrigins Set of allowed origins. Use setOf("*") for all origins (not compatible with credentials)
 * @param allowMethods Comma-separated list of allowed HTTP methods
 * @param allowHeaders Comma-separated list of allowed request headers
 * @param exposeHeaders Comma-separated list of headers exposed to the client
 * @param allowCredentials Whether to allow credentials (cookies, authorization headers)
 * @param maxAge Cache duration for preflight results in seconds
 */
class Cors @JvmOverloads constructor(
    private val allowOrigins: Set<String> = setOf("*"),
    private val allowMethods: String = "GET,POST,PUT,DELETE,PATCH,OPTIONS",
    private val allowHeaders: String = "Content-Type,Authorization,X-Request-ID",
    private val exposeHeaders: String = "X-Request-ID",
    private val allowCredentials: Boolean = false,
    private val maxAge: Int = 86400 // 24 hours
) : Middleware {

    companion object {
        /**
         * Create CORS middleware with a single origin
         */
        @JvmStatic
        fun forOrigin(origin: String, allowCredentials: Boolean = false): Cors {
            return Cors(
                allowOrigins = setOf(origin),
                allowCredentials = allowCredentials
            )
        }

        /**
         * Create permissive CORS middleware (allows all origins, no credentials)
         */
        @JvmStatic
        fun permissive(): Cors {
            return Cors(allowOrigins = setOf("*"))
        }
    }

    private val isWildcard = allowOrigins.contains("*")

    init {
        require(allowOrigins.isNotEmpty()) {
            "allowOrigins cannot be empty"
        }
        require(!allowCredentials || !isWildcard) {
            "Cannot use wildcard origin (*) with credentials. Must specify explicit origins."
        }
    }

    override fun invoke(ctx: Context, next: Next) {
        val origin = ctx.header("origin")

        // For non-wildcard configs the response depends on the Origin header —
        // shared caches must be told regardless of whether THIS origin is
        // allowed, or a cached allowed-origin response could be served to a
        // disallowed origin (and vice versa).
        if (!isWildcard) {
            ctx.response.header("Vary", "Origin")
        }

        // Determine if the origin is allowed
        val isAllowed = origin != null && (isWildcard || allowOrigins.contains(origin))

        if (isAllowed) {
            // Set Access-Control-Allow-Origin
            val allowOriginValue = if (isWildcard) "*" else origin
            ctx.response.header("Access-Control-Allow-Origin", allowOriginValue)

            // Set credentials header if enabled
            if (allowCredentials) {
                ctx.response.header("Access-Control-Allow-Credentials", "true")
            }

            // Handle preflight OPTIONS request
            if (ctx.method == "OPTIONS") {
                // Allow-Methods / Allow-Headers / Max-Age are preflight-response
                // headers; browsers ignore them on actual responses.
                ctx.response.header("Access-Control-Allow-Methods", allowMethods)
                ctx.response.header("Access-Control-Allow-Headers", allowHeaders)
                ctx.response.header("Access-Control-Max-Age", maxAge.toString())
                ctx.status(204)
                // Don't call next() - preflight should not trigger business logic
                return
            }

            // Expose-Headers applies to actual (non-preflight) responses
            if (exposeHeaders.isNotEmpty()) {
                ctx.response.header("Access-Control-Expose-Headers", exposeHeaders)
            }
        }

        // For actual requests, continue to next middleware
        next()
    }
}