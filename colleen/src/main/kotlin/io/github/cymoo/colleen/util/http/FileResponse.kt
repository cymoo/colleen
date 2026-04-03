package io.github.cymoo.colleen.util.http

import java.net.URLConnection
import java.net.URLEncoder

object FileResponse {
    /**
     * Infers a MIME type with charset from a filename or path.
     *
     * Uses [URLConnection.guessContentTypeFromName] for OS-independent, consistent
     * behavior across both filesystem and classpath resources. Falls back to
     * `application/octet-stream` for unrecognized extensions.
     *
     * UTF-8 charset is appended automatically for text-based MIME types.
     */
    fun inferContentType(path: String): String =
        (URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream")
            .withCharset()

    /**
     * Builds a `Content-Disposition` header value.
     *
     * For [attachment] = true, the filename is dual-encoded per RFC 5987:
     * - `filename=` provides an ASCII fallback for legacy clients (non-ASCII replaced with `_`)
     * - `filename*=` provides the RFC 5987 encoded value for modern clients
     *
     * Example: `attachment; filename="report___.pdf"; filename*=UTF-8''report%E4%B8%AD%E6%96%87.pdf`
     */
    fun buildContentDisposition(filename: String, attachment: Boolean = false): String {
        if (!attachment) return "inline"
        val safeAscii = filename
            .replace(Regex("[\\r\\n\"]"), "")
            .replace(NON_ASCII, "_")
        val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return """attachment; filename="$safeAscii"; filename*=UTF-8''$encoded"""
    }

    /**
     * Appends `; charset=utf-8` to text-based MIME types that lack an explicit charset.
     */
    private fun String.withCharset(): String {
        val needsCharset = startsWith("text/") ||
                this == "application/json" ||
                this == "application/javascript"
        return if (needsCharset && "charset" !in this) "$this; charset=utf-8" else this
    }

    // Matches any character outside the printable ASCII range (0x20–0x7E).
    private val NON_ASCII = Regex("[^\\x20-\\x7E]")
}

