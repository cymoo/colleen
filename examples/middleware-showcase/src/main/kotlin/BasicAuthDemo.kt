import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.BasicAuth
import io.github.cymoo.colleen.middleware.getBasicAuthUser

/**
 * BasicAuth Demo
 *
 * Demonstrates the BasicAuth middleware for HTTP Basic Authentication.
 * Supports two approaches:
 *
 * 1. Credentials map: simple username → password lookup
 * 2. Custom authenticate function: flexible validation (e.g. database lookup)
 *
 * The middleware is timing-attack resistant: it always performs a constant-time
 * comparison regardless of whether the user exists.
 *
 * After successful authentication, the username is stored in the request context
 * and can be retrieved with ctx.getBasicAuthUser().
 *
 * Try:
 *   curl -i http://localhost:8085/basic-auth/public
 *   curl -i http://localhost:8085/basic-auth/private              # → 401
 *   curl -i -u admin:secret http://localhost:8085/basic-auth/private
 *   curl -i -u superadmin:topsecret http://localhost:8085/basic-auth/admin
 */
fun basicAuthApp(): Colleen {
    val app = Colleen()

    // Public endpoint: no authentication required
    app.get("/public") {
        mapOf("message" to "This endpoint is public. No authentication required.")
    }

    // Credentials map: multiple users with different passwords.
    // The realm name is shown in browser login dialogs.
    app.get("/private")
        .use(BasicAuth("Restricted Area", mapOf("admin" to "secret", "user" to "pass")))
        .handle { ctx ->
            mapOf(
                "message" to "Authentication successful!",
                "authenticatedAs" to ctx.getBasicAuthUser(),
                "note" to "Try also: -u user:pass"
            )
        }

    // Custom authenticate function: useful for database lookups, LDAP, etc.
    // The function receives (username, password) and returns Boolean.
    app.get("/admin")
        .use(BasicAuth("Admin Panel") { username, password ->
            username == "superadmin" && password == "topsecret"
        })
        .handle { ctx ->
            mapOf(
                "message" to "Admin access granted.",
                "authenticatedAs" to ctx.getBasicAuthUser()
            )
        }

    return app
}
