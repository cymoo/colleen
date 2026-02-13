import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.SignedCookie
import io.github.cymoo.colleen.middleware.getSignedCookie
import io.github.cymoo.colleen.middleware.signedCookie
import io.github.cymoo.colleen.util.http.Cookie

/**
 * Signed Cookie Middleware Example
 *
 * Features demonstrated:
 * - How to enable signed cookies
 * - Setting, reading, and deleting signed cookies
 * - Security best practices
 * - Multiple signed cookies
 * - Key rotation for smooth secret migration
 */
fun main() {
    val app = Colleen()

    // Install SignedCookie middleware
    // ⚠ In production, secrets should always be loaded from environment variables or config files
    // ⚠ Secret must be at least 32 bytes (UTF-8 encoded) for security reasons
    app.use(SignedCookie(secret = "your-secret-key-must-be-at-least-32-characters-long"))

    app.get("/") { "hello" }

    // ------------------------------------------------------------------------
    // Example 1: Set a signed cookie
    // ------------------------------------------------------------------------
    app.get("/login") { ctx ->
        val username = ctx.query("username") ?: "guest"

        ctx.signedCookie(
            name = "session",
            value = username,
            maxAge = 3600,    // 1 hour
            httpOnly = true,  // Prevent XSS attacks
            secure = false,   // Should be true in production (HTTPS only)
            sameSite = Cookie.SameSite.LAX  // Prevent CSRF attacks
        )

        ctx.text("Logged in as: $username")
    }

    // ------------------------------------------------------------------------
    // Example 2: Read a signed cookie
    // ------------------------------------------------------------------------
    app.get("/profile") { ctx ->
        val username = ctx.getSignedCookie("session")

        if (username != null) {
            ctx.text("Welcome back, $username!")
        } else {
            // Possible reasons:
            // - Cookie does not exist
            // - Signature is invalid
            // - Cookie was tampered with
            ctx.status(401).text("Not authenticated or cookie tampered")
        }
    }

    // ------------------------------------------------------------------------
    // Example 3: Delete a signed cookie
    // ------------------------------------------------------------------------
    app.get("/logout") { ctx ->
        ctx.response.deleteCookie("session", path = "/")
        ctx.text("Logged out successfully")
    }

    // ------------------------------------------------------------------------
    // Example 4: Using multiple signed cookies
    // ------------------------------------------------------------------------
    app.get("/set-preferences") { ctx ->
        ctx.signedCookie("theme", "dark", maxAge = 86400 * 30) // 30 days
        ctx.signedCookie("language", "zh-CN", maxAge = 86400 * 30)
        ctx.text("Preferences saved")
    }

    app.get("/get-preferences") { ctx ->
        val theme = ctx.getSignedCookie("theme") ?: "light"
        val language = ctx.getSignedCookie("language") ?: "en-US"
        ctx.json(
            mapOf(
                "theme" to theme,
                "language" to language
            )
        )
    }

    app.listen(port = 8000)
    println("Server running on http://localhost:8000")
    println("Try the following endpoints:")
    println("  http://localhost:8000/login?username=alice")
    println("  http://localhost:8000/profile")
    println("  http://localhost:8000/logout")
}

// ============================================================================
// Key Rotation Example: Smooth Secret Migration
// ============================================================================
/**
 * Demonstrates how to rotate signing keys without breaking existing cookies.
 *
 * Key rotation strategy:
 * - The first key is used to sign new cookies
 * - All provided keys are tried when verifying existing cookies
 * - This allows seamless migration from an old secret to a new one
 */
fun keyRotationExample() {
    val app = Colleen()

    app.use(
        SignedCookie(
            secrets = listOf(
                // New key (used for signing new cookies)
                "new-secret-key-must-be-at-least-32-characters-long-v2",

                // Old key (kept only for verification of existing cookies)
                "old-secret-key-must-be-at-least-32-characters-long-v1"
            ),
            algorithm = "HmacSHA256"
        )
    )

    app.get("/rotate") { ctx ->
        // Try to read a cookie that might still be signed with the old key
        val oldValue = ctx.getSignedCookie("session")

        if (oldValue != null) {
            // Re-sign the cookie using the new key
            ctx.signedCookie("session", oldValue, maxAge = 3600)
            ctx.text("Cookie successfully re-signed with new key")
        } else {
            ctx.text("No valid cookie found")
        }
    }

    /*
     Recommended deployment process:

     1. Deploy a version with BOTH old and new keys (as shown above)
     2. Wait until most users have refreshed or re-signed cookies
     3. Remove the old key and keep only the new one
     */

    app.listen(port = 8001)
}