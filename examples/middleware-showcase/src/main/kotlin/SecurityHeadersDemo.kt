import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.SecurityHeaders

/**
 * SecurityHeaders Demo
 *
 * Demonstrates the SecurityHeaders middleware which protects against common web
 * vulnerabilities by adding security-related HTTP response headers:
 *
 * - X-Frame-Options: Prevents clickjacking by controlling frame embedding
 * - X-Content-Type-Options: Prevents MIME type sniffing attacks
 * - Referrer-Policy: Controls how much referrer info is included in requests
 * - Content-Security-Policy: Restricts which resources the browser can load
 * - Strict-Transport-Security: Enforces HTTPS (only on HTTPS connections)
 * - Permissions-Policy: Controls access to browser features (camera, mic, etc.)
 *
 * Try:
 *   curl -i http://localhost:8085/security-headers/
 *   curl -i http://localhost:8085/security-headers/strict
 */
fun securityHeadersApp(): Colleen {
    val app = Colleen()

    // Default security headers: sensible defaults for most applications
    app.use(
        SecurityHeaders(
            contentSecurityPolicy = "default-src 'self'",
            permissionsPolicy = "microphone=(), camera=(), geolocation=()"
        )
    )

    app.get("/") {
        mapOf(
            "message" to "Security headers have been added to this response.",
            "headers_added" to listOf(
                "X-Frame-Options: DENY",
                "X-Content-Type-Options: nosniff",
                "Referrer-Policy: no-referrer",
                "Content-Security-Policy: default-src 'self'",
                "Permissions-Policy: microphone=(), camera=(), geolocation=()",
                "Strict-Transport-Security: max-age=31536000 (HTTPS only)"
            ),
            "hint" to "Use 'curl -i' to see the headers in the response."
        )
    }

    // Custom configuration: stricter settings for a high-security API
    app.get("/strict") {
        mapOf(
            "message" to "Same security headers apply here (middleware is app-level).",
            "note" to "To apply different SecurityHeaders per route, use separate sub-apps or route-level middleware."
        )
    }

    return app
}
