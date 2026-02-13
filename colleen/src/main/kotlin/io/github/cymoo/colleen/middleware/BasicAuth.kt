package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.logger
import java.security.MessageDigest
import java.util.*

/**
 * Basic Authentication middleware
 * Implements HTTP basic authentication with security best practices
 *
 * @param realm Authentication realm name displayed in browser prompt
 * @param credentials Optional map of username to password for simple auth
 * @param authenticate Optional custom authentication function
 */
class BasicAuth private constructor(
    private val realm: String,
    private val credentials: Map<String, String>?,
    private val authenticate: ((username: String, password: String) -> Boolean)?
) : Middleware {

    companion object {
        // Constant key to avoid conflicts with user-defined context attributes
        internal const val AUTHENTICATED_USER_KEY = "io.github.cymoo.colleen.middleware.BasicAuth"

        // Dummy password for constant-time comparison when user doesn't exist
        private const val DUMMY_PASSWORD = "dummy_password_to_prevent_timing_attack"
    }

    /**
     * Constructor with credentials map
     */
    @JvmOverloads
    constructor(
        realm: String = "Restricted",
        credentials: Map<String, String>
    ) : this(realm, credentials, null)

    /**
     * Constructor with custom authenticate function
     */
    @JvmOverloads
    constructor(
        realm: String = "Restricted",
        authenticate: (username: String, password: String) -> Boolean
    ) : this(realm, null, authenticate)

    override fun invoke(ctx: Context, next: Next) {
        val ip = ctx.request.ip.ifBlank { "-" }
        val authHeader = ctx.header("Authorization")

        // Check if Authorization header exists and starts with "Basic "
        if (authHeader == null || !authHeader.startsWith("Basic ", ignoreCase = true)) {
            logger.warn("Authentication failed from $ip")
            sendUnauthorized(ctx)
            return
        }

        val encoded = authHeader.substring(6).trim()

        // Validate base64 encoded string is not empty
        if (encoded.isEmpty()) {
            logger.warn("Authentication failed from $ip")
            sendUnauthorized(ctx)
            return
        }

        // Decode base64 credentials
        val decoded = try {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            logger.warn("Authentication failed from $ip")
            sendUnauthorized(ctx)
            return
        }

        // Parse username and password
        val colonIndex = decoded.indexOf(':')
        if (colonIndex == -1) {
            logger.warn("Authentication failed from $ip")
            sendUnauthorized(ctx)
            return
        }

        val username = decoded.substring(0, colonIndex)
        val password = decoded.substring(colonIndex + 1)

        // Authenticate user
        val isValid = authenticateUser(username, password)

        if (!isValid) {
            logger.warn("Authentication failed for $username from $ip")
            sendUnauthorized(ctx)
            return
        }

        // Store authenticated username in context for downstream middleware
        // Using a namespaced key to prevent conflicts
        ctx.setState(AUTHENTICATED_USER_KEY, username)

        // Continue to next middleware
        next()
    }

    /**
     * Authenticate user with constant-time comparison to prevent timing attacks
     * Always performs password comparison even if user doesn't exist
     */
    private fun authenticateUser(username: String, password: String): Boolean {
        return when {
            // Use custom authenticate function if provided
            authenticate != null -> {
                try {
                    authenticate.invoke(username, password)
                } catch (e: Exception) {
                    logger.error("Error invoking authenticate function", e)
                    false
                }
            }
            // Use credentials map
            credentials != null -> {
                // Always compare password even if user doesn't exist
                // This prevents username enumeration via timing attacks
                val expectedPassword = credentials[username] ?: DUMMY_PASSWORD
                val passwordMatches = constantTimeEquals(password, expectedPassword)

                // Only return true if user exists AND password matches
                credentials.containsKey(username) && passwordMatches
            }
            // No authentication method configured
            else -> false
        }
    }

    /**
     * Send 401 Unauthorized response with WWW-Authenticate header
     */
    private fun sendUnauthorized(ctx: Context) {
        ctx.response.header("WWW-Authenticate", """Basic realm="$realm"""")
        ctx.status(401).text("Unauthorized")
    }

    /**
     * Constant-time string comparison to prevent timing attacks
     * Uses MessageDigest.isEqual() which performs byte-by-byte comparison
     * without short-circuiting on first mismatch
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }
}

/**
 * Extension function to retrieve authenticated username from context
 * Usage: val username = ctx.getBasicAuthUser()
 */
fun Context.getBasicAuthUser(): String? {
    return this.getStateOrNull(BasicAuth.AUTHENTICATED_USER_KEY)
}