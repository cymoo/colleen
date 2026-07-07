package io.github.cymoo.colleen.util.http

/**
 * Utilities for path normalization and parsing.
 *
 * ## Decoding contract
 *
 * Functions in this object never percent-decode. Decoding is the
 * responsibility of the HTTP server layer (Undertow decodes the request
 * path exactly once before it reaches the framework), and route patterns
 * are written by developers in already-decoded form.
 *
 * Decoding here as well would decode twice: `%2541` would turn into `A`,
 * a literal `%` would crash the request, and `%2F` would be re-interpreted
 * as a path separator, changing the path structure (a security issue).
 */
object UrlPath {
    /**
     * Normalize and validate a URL path.
     *
     * Rules:
     * - Removes empty segments and redundant slashes
     * - Rejects "." and ".." segments
     * - Always returns an absolute, normalized path
     * - Does NOT percent-decode (see the decoding contract above)
     */
    fun normalize(rawPath: String): String {
        if (rawPath.isEmpty() || rawPath == "/") return "/"

        val segments = rawPath.split('/').filter { it.isNotEmpty() }

        require(segments.none { it == "." || it == ".." }) {
            "Invalid path segment in: $rawPath"
        }

        return "/" + segments.joinToString("/")
    }

    /**
     * Returns normalized path segments.
     *
     * This is the canonical representation and should be preferred
     * for routing, mounting and security checks.
     */
    fun split(rawPath: String): List<String> {
        val normalized = normalize(rawPath)
        return splitNormalized(normalized)
    }

    /**
     * Returns path segments for an already-normalized path.
     */
    fun splitNormalized(normalizedPath: String): List<String> {
        if (normalizedPath.isEmpty() || normalizedPath == "/") return emptyList()

        require(normalizedPath.startsWith("/")) {
            "Path must be absolute: $normalizedPath"
        }

        val segments = normalizedPath.drop(1).split('/').filter { it.isNotEmpty() }

        require(segments.none { it == "." || it == ".." }) {
            "Invalid path segment in: $normalizedPath"
        }

        return segments
    }

    /**
     * Safely joins a base path with one or more child path segments.
     *
     * Examples:
     *   join("/api", "users")        -> "/api/users"
     *   join("/api/", "/users/")     -> "/api/users"
     *   join("/", "health")          -> "/health"
     */
    fun join(base: String, vararg children: String): String {
        val baseSegments = split(base)

        val childSegments = children
            .flatMap { split(it) }

        return if (baseSegments.isEmpty() && childSegments.isEmpty()) {
            "/"
        } else {
            "/" + (baseSegments + childSegments).joinToString("/")
        }
    }

    /**
     * Percent-decodes a raw request path the way an HTTP server does.
     *
     * This mirrors Undertow's default behavior so that [io.github.cymoo.colleen.TestClient]
     * sees the same paths a real server would produce:
     * - `%XX` escapes are decoded as UTF-8
     * - `+` is NOT converted to a space (that rule only applies to query strings)
     * - `%2F` / `%5C` (encoded slash / backslash) are left encoded, so they can
     *   never change the path structure
     *
     * @throws IllegalArgumentException on malformed escape sequences
     *         (a real server would answer 400 in that case)
     */
    fun decodePath(rawPath: String): String {
        if ('%' !in rawPath) return rawPath

        val out = StringBuilder(rawPath.length)

        // Consecutive %XX escapes must be collected into a byte buffer and decoded
        // together, since one character may span multiple escapes (e.g. %E4%B8%AD).
        val pending = java.io.ByteArrayOutputStream(8)

        fun flushPending() {
            if (pending.size() > 0) {
                out.append(pending.toByteArray().toString(Charsets.UTF_8))
                pending.reset()
            }
        }

        var i = 0
        while (i < rawPath.length) {
            val c = rawPath[i]
            if (c != '%') {
                flushPending()
                out.append(c)
                i++
                continue
            }

            require(i + 2 < rawPath.length) { "Malformed escape sequence in path: $rawPath" }
            val hex = rawPath.substring(i + 1, i + 3)
            val value = hex.toIntOrNull(16)
                ?: throw IllegalArgumentException("Malformed escape sequence in path: $rawPath")

            if (value == '/'.code || value == '\\'.code) {
                // Keep encoded slashes opaque — decoding them would alter the path structure
                flushPending()
                out.append('%').append(hex)
            } else {
                pending.write(value)
            }
            i += 3
        }
        flushPending()

        return out.toString()
    }
}
