package io.github.cymoo.colleen.util.http

/**
 * Cookie utilities with a focus on simplicity and security.
 *
 * Design philosophy:
 * - Simple validation (reject obviously bad input)
 * - Automatic encoding (handle special characters transparently)
 * - Sensible defaults (secure by convention)
 */
object Cookie {

    /**
     * Parse Cookie header from HTTP request.
     *
     * Format: name1=value1; name2=value2
     *
     * @param cookieHeader Cookie header value
     * @return Map of cookie names to decoded values
     */
    fun parse(cookieHeader: String?): Map<String, String> {
        if (cookieHeader.isNullOrBlank()) return emptyMap()

        return cookieHeader.split(';')
            .mapNotNull { pair ->
                val trimmed = pair.trim()
                val index = trimmed.indexOf('=')
                if (index <= 0) return@mapNotNull null

                val name = trimmed.substring(0, index).trim()
                if (name.isEmpty()) return@mapNotNull null

                val rawValue = trimmed.substring(index + 1).trim()
                val value =
                    if (rawValue.length >= 2 &&
                        rawValue.startsWith('"') &&
                        rawValue.endsWith('"')
                    ) {
                        rawValue.substring(1, rawValue.length - 1)
                    } else {
                        rawValue
                    }

                name to value
            }
            .toMap()
    }


    /**
     * Build Set-Cookie header value.
     *
     * @param name Cookie name
     * @param value Cookie value (will be URL-encoded)
     * @param maxAge Lifetime in seconds (null=session, 0=delete, >0=persistent)
     * @param path Cookie path
     * @param domain Cookie domain
     * @param secure Require HTTPS
     * @param httpOnly Prevent JavaScript access
     * @param sameSite CSRF protection
     * @return Complete Set-Cookie header value
     */
    fun buildSetCookie(
        name: String,
        value: String,
        maxAge: Int?,
        path: String?,
        domain: String?,
        secure: Boolean,
        httpOnly: Boolean,
        sameSite: SameSite?
    ): String {
        // Validate inputs
        validateName(name)
        validateValue(value)
        path?.let { validatePath(it) }
        domain?.let { validateDomain(it) }
        validateMaxAge(maxAge)
        validateSameSiteSecure(sameSite, secure, maxAge)

        return buildString {
            append("$name=$value")

            maxAge?.let {
                append("; Max-Age=$it")
                if (it <= 0) {
                    append("; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
            }

            path?.let { append("; Path=$it") }
            domain?.let { append("; Domain=$it") }
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
            sameSite?.let { append("; SameSite=${it.value}") }
        }
    }

    /**
     * Validate cookie name.
     *
     * Rules:
     * - Not blank
     * - Alphanumeric, underscore, hyphen only (maximum compatibility)
     *
     * @throws IllegalArgumentException if invalid
     */
    fun validateName(name: String) {
        require(name.isNotBlank()) { "Cookie name cannot be blank" }
        require(name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            "Invalid cookie name: '$name' (use alphanumeric, _, - only)"
        }
    }

    /**
     * Validate cookie value.
     *
     * Rules (RFC 6265):
     * - Control characters are not allowed.
     * - The semicolon (';') is not allowed, as it is used to delimit cookie pairs.
     * - Empty values are allowed.
     *
     * Note:
     * This method validates a *raw* cookie value. No automatic escaping or
     * encoding is performed by this library. Callers must ensure the value
     * complies with RFC 6265.
     *
     * @throws IllegalArgumentException if the value is not a valid cookie value
     */
    fun validateValue(value: String) {
        require(!value.any { it.isISOControl() }) {
            "Cookie value must not contain control characters"
        }
        require(!value.contains(';')) {
            "Cookie value must not contain ';' (cookie-pair delimiter)"
        }
    }

    /**
     * Validate cookie path.
     *
     * Rules:
     * - Not blank
     * - Must start with '/'
     * - No '..' (path traversal)
     *
     * @throws IllegalArgumentException if invalid
     */
    fun validatePath(path: String) {
        require(path.isNotBlank()) { "Cookie path cannot be blank" }
        require(path.startsWith("/")) { "Cookie path must start with '/'" }
        require(!path.contains("..")) { "Cookie path cannot contain '..'" }
    }

    /**
     * Validate cookie domain.
     *
     * Simple rule: Must contain at least one dot (or be "localhost")
     * This prevents setting cookies on TLDs like ".com"
     *
     * Note: We intentionally keep this simple. Browser validation will
     * catch any domain issues, so we just prevent obvious mistakes.
     *
     * @throws IllegalArgumentException if invalid
     */
    fun validateDomain(domain: String) {
        require(domain.isNotBlank()) { "Cookie domain cannot be blank" }

        val normalized = domain.trimStart('.')
        require(normalized.isNotBlank()) { "Cookie domain cannot be only dots" }

        require(normalized.equals("localhost", ignoreCase = true) || normalized.contains(".")) {
            "Invalid cookie domain: '$domain' (must be localhost or contain a dot)"
        }
    }

    /**
     * Validate maxAge.
     *
     * Rule:
     * - null: not set
     * - >= 0: valid
     * - < 0: treated as expired cookie
     */
    fun validateMaxAge(maxAge: Int?) {
        // no-op: all Int values are allowed
    }

    /**
     * Validate SameSite=None requires Secure flag.
     *
     * @throws IllegalArgumentException if invalid
     */
    fun validateSameSiteSecure(sameSite: SameSite?, secure: Boolean, maxAge: Int?) {
        val isDeletion = maxAge == 0
        if (!isDeletion && sameSite == SameSite.NONE) {
            require(secure) { "SameSite=None requires Secure flag" }
        }
    }

    /**
     * SameSite attribute for CSRF protection.
     */
    enum class SameSite(val value: String) {
        /** Strictest: only same-site requests */
        STRICT("Strict"),

        /** Balanced: top-level navigation + same-site (recommended) */
        LAX("Lax"),

        /** Permissive: all contexts (requires Secure) */
        NONE("None")
    }
}