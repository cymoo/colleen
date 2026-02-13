package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.Response
import io.github.cymoo.colleen.util.http.Cookie
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed Cookie middleware.
 *
 * Provides cryptographic signing of cookie values to prevent tampering.
 * Supports multiple secrets for key rotation.
 *
 * IMPORTANT: Signed cookies prevent tampering but do NOT encrypt values.
 * Anyone can read the cookie content. For sensitive data, consider encryption.
 *
 * Usage:
 * ```
 * app.use(SignedCookie(secret = "your-secret-key"))
 *
 * app.get("/set") { ctx ->
 *     ctx.signedCookie("user", "alice", maxAge = 3600)
 *     ctx.text("Cookie set")
 * }
 *
 * app.get("/get") { ctx ->
 *     val user = ctx.getSignedCookie("user")
 *     ctx.text("User: $user")
 * }
 * ```
 *
 * @param secrets List of secrets for signing. First secret is used for signing,
 *                all secrets are tried when verifying (enables key rotation)
 * @param algorithm HMAC algorithm to use (default: HmacSHA256)
 */
class SignedCookie @JvmOverloads constructor(
    private val secrets: List<String>,
    private val algorithm: String = "HmacSHA256"
) : Middleware {

    /**
     * Convenience constructor for single secret.
     */
    @JvmOverloads
    constructor(secret: String, algorithm: String = "HmacSHA256") : this(listOf(secret), algorithm)

    companion object {
        internal const val SIGNED_COOKIE_KEY = "io.github.cymoo.colleen.middleware.SignedCookie"
        internal const val SEPARATOR = "."
        internal const val MIN_SECRET_BYTES = 32
    }

    init {
        require(secrets.isNotEmpty()) { "At least one secret is required" }
        secrets.forEach { secret ->
            require(secret.isNotBlank()) { "Secret cannot be blank" }
            val secretBytes = secret.toByteArray(Charsets.UTF_8).size
            require(secretBytes >= MIN_SECRET_BYTES) {
                "Secret must be at least $MIN_SECRET_BYTES bytes (UTF-8 encoded). Got $secretBytes bytes."
            }
        }

        // Validate algorithm
        try {
            Mac.getInstance(algorithm)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unsupported algorithm: $algorithm", e)
        }
    }

    override fun invoke(ctx: Context, next: Next) {
        // Store cookie signer in context for use by extension functions
        ctx.setState(SIGNED_COOKIE_KEY, CookieSigner(secrets, algorithm))
        next()
    }
}

/**
 * Internal class for signing and verifying cookie values.
 *
 * Security improvements:
 * - Signs both cookie name and value to prevent cross-cookie attacks
 * - Signs the base64-encoded value (what's actually transmitted)
 * - Uses constant-time comparison to prevent timing attacks
 */
internal class CookieSigner(
    private val secrets: List<String>,
    private val algorithm: String
) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /**
     * Sign a cookie name-value pair using the first (current) secret.
     *
     * Format: base64(value).base64(hmac(name:base64(value)))
     *
     * The signature includes the cookie name to prevent attackers from
     * copying a valid signed value from one cookie to another.
     */
    fun sign(name: String, value: String): String {
        require(name.isNotBlank()) { "Cookie name cannot be blank" }

        // Encode the value first
        val encodedValue = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

        // Sign "name:encodedValue" to bind the signature to this specific cookie
        val dataToSign = "$name:$encodedValue"
        val signature = generateSignature(dataToSign, secrets.first())
        val encodedSignature = encoder.encodeToString(signature)

        return "$encodedValue${SignedCookie.SEPARATOR}$encodedSignature"
    }

    /**
     * Verify and extract the original value from a signed cookie.
     *
     * Tries all secrets in order (allows for key rotation).
     * Returns null if signature is invalid.
     */
    fun unsign(name: String, signedValue: String): String? {
        if (signedValue.isBlank()) return null

        // Use lastIndexOf for more robust parsing
        val separatorIndex = signedValue.lastIndexOf(SignedCookie.SEPARATOR)
        // separatorIndex < 0: no separator found
        // separatorIndex >= length - 1: separator is the last character (no signature)
        if (separatorIndex < 0 || separatorIndex >= signedValue.length - 1) {
            return null
        }

        val encodedValue = signedValue.substring(0, separatorIndex)
        val encodedSignature = signedValue.substring(separatorIndex + 1)

        return try {
            val providedSignature = decoder.decode(encodedSignature)

            // Reconstruct what was signed
            val dataToVerify = "$name:$encodedValue"

            // Try each secret until one verifies
            for (secret in secrets) {
                val expectedSignature = generateSignature(dataToVerify, secret)
                if (MessageDigest.isEqual(expectedSignature, providedSignature)) {
                    // Signature valid, decode and return the original value
                    return String(decoder.decode(encodedValue), Charsets.UTF_8)
                }
            }

            // No secret verified the signature
            null
        } catch (_: Exception) {
            // Invalid base64 or other parsing error
            null
        }
    }

    /**
     * Generate HMAC signature for a value using the given secret.
     */
    private fun generateSignature(data: String, secret: String): ByteArray {
        val mac = Mac.getInstance(algorithm)
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm)
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Set a signed cookie.
 *
 * The cookie value is cryptographically signed to prevent tampering.
 * The signature includes both the cookie name and value, preventing
 * attackers from copying signed values between different cookies.
 *
 * IMPORTANT: Signed cookies are NOT encrypted. The value is visible
 * to anyone who can read the cookie. Use encryption for sensitive data.
 *
 * Requires SignedCookie middleware to be installed.
 *
 * @param name Cookie name
 * @param value Cookie value (will be signed)
 * @param maxAge Maximum age in seconds (null = session cookie)
 * @param path Cookie path
 * @param domain Cookie domain
 * @param secure Secure flag (HTTPS only)
 * @param httpOnly HttpOnly flag (not accessible via JavaScript)
 * @param sameSite SameSite policy
 * @throws IllegalStateException if SignedCookie middleware is not installed
 */
@JvmOverloads
fun Context.signedCookie(
    name: String,
    value: String,
    maxAge: Int? = null,
    path: String? = "/",
    domain: String? = null,
    secure: Boolean = false,
    httpOnly: Boolean = true,
    sameSite: Cookie.SameSite? = Cookie.SameSite.LAX
): Response {
    if (!this.hasState(SignedCookie.SIGNED_COOKIE_KEY)) {
        throw IllegalStateException(
            "SignedCookie middleware not installed. " +
                    "Add 'app.use(SignedCookie(secret))' before using signed cookies."
        )
    }
    val signer = this.getState<CookieSigner>(SignedCookie.SIGNED_COOKIE_KEY)

    val signedValue = signer.sign(name, value)

    return this.response.cookie(
        name = name,
        value = signedValue,
        maxAge = maxAge,
        path = path,
        domain = domain,
        secure = secure,
        httpOnly = httpOnly,
        sameSite = sameSite
    )
}


fun Context.getSignedCookie(name: String): String? {
    if (!this.hasState(SignedCookie.SIGNED_COOKIE_KEY)) {
        throw IllegalStateException(
            "SignedCookie middleware not installed. " +
                    "Add 'app.use(SignedCookie(secret))' before using signed cookies."
        )
    }
    val signer = this.getState<CookieSigner>(SignedCookie.SIGNED_COOKIE_KEY)

    val signedValue = this.request.cookie(name) ?: return null
    return signer.unsign(name, signedValue)
}