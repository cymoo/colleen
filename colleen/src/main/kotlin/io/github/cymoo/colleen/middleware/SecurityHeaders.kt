package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next

/**
 * Security headers middleware
 * Adds common security-related HTTP headers
 *
 * @param xFrameOptions Controls frame embedding (default: DENY)
 * @param xContentTypeOptions Prevents MIME type sniffing (default: nosniff)
 * @param referrerPolicy Controls referrer information (default: no-referrer)
 * @param contentSecurityPolicy CSP directive, null to skip
 * @param strictTransportSecurity HSTS directive for HTTPS (default: max-age=31536000)
 * @param permissionsPolicy Permissions-Policy directive, null to skip
 */
class SecurityHeaders @JvmOverloads constructor(
    private val xFrameOptions: String = "DENY",
    private val xContentTypeOptions: String = "nosniff",
    private val referrerPolicy: String = "no-referrer",
    private val contentSecurityPolicy: String? = null,
    private val strictTransportSecurity: String = "max-age=31536000",
    private val permissionsPolicy: String? = null
) : Middleware {

    override fun invoke(ctx: Context, next: Next) {
        next()

        // Basic security headers
        ctx.response.header("X-Frame-Options", xFrameOptions)
        ctx.response.header("X-Content-Type-Options", xContentTypeOptions)
        ctx.response.header("Referrer-Policy", referrerPolicy)

        // Optional headers
        contentSecurityPolicy?.let {
            ctx.response.header("Content-Security-Policy", it)
        }

        permissionsPolicy?.let {
            ctx.response.header("Permissions-Policy", it)
        }

        // HSTS only on HTTPS
        if (ctx.request.serverInfo.isSecure) {
            ctx.response.header("Strict-Transport-Security", strictTransportSecurity)
        }
    }
}