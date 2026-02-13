package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Forbidden
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.NotFound
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Static file serving middleware
 * Serves files from a specified directory with security checks, caching, and content negotiation
 *
 * @param root Root directory path for static files
 * @param baseUrl Base URL path, defaults to "/static"
 * @param index Default index file name, defaults to "index.html"
 * @param dotFiles Dot files (hidden files) handling policy, defaults to IGNORE
 * @param maxAge Cache max-age in seconds, defaults to 0 (no caching)
 * @param extensions List of file extensions to try automatically, defaults to empty list
 * @param fallthrough Whether to pass control to next middleware if file not found, defaults to true
 */
class ServeStatic @JvmOverloads constructor(
    private val root: String,
    private val baseUrl: String = "/static",
    private val index: String = "index.html",
    private val dotFiles: DotFilesPolicy = DotFilesPolicy.IGNORE,
    private val maxAge: Int = 0,
    private val extensions: List<String> = emptyList(),
    private val fallthrough: Boolean = true
) : Middleware {

    /**
     * Policy for handling dot files (hidden files)
     * - ALLOW Serve dot files
     * - DENY Return 403 for dot files
     * - IGNORE Treat as not found
     */
    enum class DotFilesPolicy { ALLOW, DENY, IGNORE }

    private val rootPath = File(root).canonicalFile
    private val rootPrefix = rootPath.path + File.separator

    init {
        require(rootPath.exists() && rootPath.isDirectory) {
            "Root path must be an existing directory: $root"
        }
    }

    override fun invoke(ctx: Context, next: Next) {
        // Only handle GET and HEAD requests that match baseUrl
        if (ctx.method !in listOf("GET", "HEAD") || !ctx.path.startsWith(baseUrl)) {
            next()
            return
        }

        // Build and validate file path
        val relativePath = ctx.path.removePrefix(baseUrl).trimStart('/').ifEmpty { index }
        if ('\u0000' in relativePath) {
            return handleNotFound(next)
        }

        var file = resolveFile(relativePath) ?: return handleNotFound(next)

        // Handle dot files
        if (file.name.startsWith(".")) {
            when (dotFiles) {
                DotFilesPolicy.DENY -> throw Forbidden("Access to dot files denied")
                DotFilesPolicy.IGNORE -> return handleNotFound(next)
                DotFilesPolicy.ALLOW -> {}
            }
        }

        // Handle directories
        if (file.isDirectory) {
            file = resolveFile("$relativePath/$index") ?: return handleNotFound(next)
        }

        // Try extensions if file doesn't exist
        if (!file.exists() && extensions.isNotEmpty()) {
            file = tryExtensions(file) ?: return handleNotFound(next)
        }

        // Final validation
        if (!file.exists() || !file.isFile) return handleNotFound(next)

        // Check conditional request
        if (checkNotModified(ctx, file)) {
            ctx.status(304)
            ctx.response.header("Content-Length", "0")
            return
        }

        // Serve file
        serveFile(ctx, file)
    }

    /**
     * Resolve file path with security checks
     */
    private fun resolveFile(relativePath: String): File? {
        val file = File(rootPath, relativePath).canonicalFile
        return if (file.path.startsWith(rootPrefix)) file else null
    }

    /**
     * Try appending extensions to find file
     */
    private fun tryExtensions(file: File): File? {
        return extensions.firstNotNullOfOrNull { ext ->
            File(file.path + ext).takeIf { it.exists() && it.isFile }
        }
    }

    /**
     * Check if file has been modified since last request
     */
    private fun checkNotModified(ctx: Context, file: File): Boolean {
        val ifModifiedSince = ctx.header("if-modified-since") ?: return false
        return try {
            val sinceTime = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(ifModifiedSince))
            val fileTime = Instant.ofEpochMilli(file.lastModified()).truncatedTo(ChronoUnit.SECONDS)
            !fileTime.isAfter(sinceTime)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Serve file with appropriate headers
     */
    private fun serveFile(ctx: Context, file: File) {
        // Determine content type
        var contentType = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
        if (contentType.startsWith("text/") ||
            contentType in listOf("application/json", "application/javascript")
        ) {
            if ("charset" !in contentType.lowercase()) {
                contentType += "; charset=utf-8"
            }
        }

        // Set headers
        ctx.response.apply {
            header("Content-Type", contentType)
            header("Content-Length", file.length().toString())
            header("X-Content-Type-Options", "nosniff")
            if (maxAge > 0) header("Cache-Control", "public, max-age=$maxAge")
            header(
                "Last-Modified",
                Instant.ofEpochMilli(file.lastModified())
                    .atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME)
            )
        }

        // Send file content (skip body for HEAD)
        if (ctx.method != "HEAD") {
            ctx.stream(file.inputStream(), contentType)
        }
    }

    /**
     * Handle file not found
     */
    private fun handleNotFound(next: Next) {
        if (fallthrough) next() else throw NotFound()
    }
}