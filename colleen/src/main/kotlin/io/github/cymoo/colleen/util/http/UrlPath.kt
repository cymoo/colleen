package io.github.cymoo.colleen.util.http

import java.net.URLDecoder

/**
 * Utilities for path normalization and parsing.
 */
object UrlPath {
    /**
     * Decode, normalize and validate a URL path.
     *
     * Rules:
     * - Percent-decodes the path
     * - Removes empty segments and redundant slashes
     * - Rejects "." and ".." segments
     * - Always returns an absolute, normalized path
     */
    fun normalize(rawPath: String): String {
        if (rawPath.isEmpty() || rawPath == "/") return "/"

        val decoded = URLDecoder.decode(rawPath, Charsets.UTF_8)
        val segments = decoded.split('/').filter { it.isNotEmpty() }

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
     *
     * Unlike [split], this does not percent-decode again. Use this after
     * request ingress or route registration has already called [normalize].
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
}
