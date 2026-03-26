import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.SignedCookie
import io.github.cymoo.colleen.middleware.getSignedCookie
import io.github.cymoo.colleen.middleware.signedCookie
import io.github.cymoo.colleen.util.http.Cookie

/**
 * SignedCookie Demo
 *
 * Demonstrates the SignedCookie middleware which adds HMAC-based cryptographic
 * signing to cookies. Signed cookies prevent tampering — if the cookie value is
 * modified by the client, verification will fail and getSignedCookie() returns null.
 *
 * Important:
 * - Signed cookies are NOT encrypted. The value is readable but tamper-evident.
 * - Secrets must be at least 32 bytes (UTF-8 encoded).
 * - For sensitive data use encryption in addition to signing.
 *
 * Key rotation: pass multiple secrets — the first is used to sign new cookies,
 * all are tried when verifying, enabling zero-downtime secret migration.
 *
 * Try (use -c/-b for cookie jar):
 *   # Set a signed cookie:
 *   curl -c cookies.txt http://localhost:8085/signed-cookie/login?username=alice
 *
 *   # Read it back:
 *   curl -b cookies.txt http://localhost:8085/signed-cookie/profile
 *
 *   # Tamper with the cookie value in cookies.txt, then:
 *   curl -b cookies.txt http://localhost:8085/signed-cookie/profile   # → 401
 *
 *   # Logout:
 *   curl -c cookies.txt -b cookies.txt http://localhost:8085/signed-cookie/logout
 */
fun signedCookieApp(): Colleen {
    val app = Colleen()

    // ⚠ In production, load secrets from environment variables or a secrets manager.
    // Secrets must be at least 32 bytes (UTF-8 encoded).
    app.use(
        SignedCookie(
            secrets = listOf(
                // First secret is used for signing new cookies
                "new-secret-key-must-be-at-least-32-characters-long-v2",
                // Additional secrets are tried during verification (key rotation)
                "old-secret-key-must-be-at-least-32-characters-long-v1"
            )
        )
    )

    app.get("/") {
        mapOf(
            "message" to "SignedCookie demo. See the endpoints below.",
            "endpoints" to mapOf(
                "/signed-cookie/login?username=<name>" to "Set a signed session cookie",
                "/signed-cookie/profile" to "Read and verify the signed cookie",
                "/signed-cookie/preferences" to "Set multiple signed cookies",
                "/signed-cookie/logout" to "Delete the session cookie"
            )
        )
    }

    // Set a signed cookie on successful login
    app.get("/login") { ctx ->
        val username = ctx.query("username") ?: "guest"

        ctx.signedCookie(
            name = "session",
            value = username,
            maxAge = 3600,
            httpOnly = true,   // Inaccessible to JavaScript — prevents XSS theft
            secure = false,    // Set to true in production (HTTPS only)
            sameSite = Cookie.SameSite.LAX  // Mitigates CSRF
        )

        mapOf("message" to "Logged in as: $username", "hint" to "Now call /signed-cookie/profile")
    }

    // Read and verify the signed cookie
    app.get("/profile") { ctx ->
        val username = ctx.getSignedCookie("session")

        if (username != null) {
            mapOf("message" to "Welcome back, $username!")
        } else {
            // Cookie absent, signature invalid, or value tampered
            ctx.status(401)
            mapOf("error" to "Not authenticated or cookie was tampered with.")
        }
    }

    // Delete the signed cookie (logout)
    app.get("/logout") { ctx ->
        ctx.response.deleteCookie("session", path = "/")
        mapOf("message" to "Logged out. Session cookie removed.")
    }

    // Multiple signed cookies (e.g. user preferences)
    app.get("/preferences") { ctx ->
        ctx.signedCookie("theme", "dark", maxAge = 86400 * 30)
        ctx.signedCookie("language", "zh-CN", maxAge = 86400 * 30)

        val theme = ctx.getSignedCookie("theme") ?: "light"
        val language = ctx.getSignedCookie("language") ?: "en-US"

        mapOf(
            "message" to "Preferences saved and read back within the same request.",
            "theme" to theme,
            "language" to language
        )
    }

    return app
}
