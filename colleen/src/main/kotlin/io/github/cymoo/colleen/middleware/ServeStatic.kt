package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.ResolvedResource
import io.github.cymoo.colleen.util.http.FileResponse
import io.github.cymoo.colleen.util.http.RangeOutcome
import io.github.cymoo.colleen.util.http.RangeSupport
import io.github.cymoo.colleen.util.lastModifiedHeader
import io.github.cymoo.colleen.util.notModifiedSince
import java.io.File
import java.time.Instant

/**
 * Normalizes a relative URL path by resolving "." and ".." segments.
 * Returns null if ".." segments would escape the logical root,
 * indicating a path-traversal attempt that must be rejected with 404.
 *
 * Unlike [io.github.cymoo.colleen.util.http.UrlPath.normalize], this function accepts ".." segments rather
 * than throwing, because static file serving handles them silently as
 * not-found rather than propagating an error.
 */
private fun normalizePath(path: String): String? {
    val segments = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeLast()
            else -> segments.add(segment)
        }
    }
    return segments.joinToString("/")
}

private typealias Resolver = (path: String) -> ResolvedResource?

/**
 * Returns a [Resolver] that serves files from [root] on the real filesystem.
 *
 * Path traversal is prevented by comparing the canonical path of every resolved
 * file against the canonical root prefix; any attempt to escape returns null.
 */
private fun fileSystemResolver(root: String): Resolver {
    val rootDir = File(root).canonicalFile
    require(rootDir.exists() && rootDir.isDirectory) { "Root must be an existing directory: $root" }
    val rootPrefix = rootDir.path + File.separator

    return { path ->
        File(rootDir, path).canonicalFile
            .takeIf { it.path.startsWith(rootPrefix) && it.isFile }
            ?.let { file ->
                ResolvedResource(
                    name = file.name,
                    length = file.length(),
                    lastModified = Instant.ofEpochMilli(file.lastModified()),
                    open = file::inputStream,
                )
            }
    }
}

/**
 * Returns a [Resolver] that serves resources from the classpath under [base].
 *
 * Path traversal is not a concern here: [normalizePath] is called by the
 * middleware before this resolver is invoked, and classpath lookups are
 * inherently sandboxed by the classloader.
 *
 * Note: [Instant.EPOCH] is used as the lastModified sentinel for JAR entries
 * whose timestamps are unreliable or absent, which suppresses Last-Modified / 304 handling.
 */
private fun classPathResolver(base: String, classLoader: ClassLoader): Resolver {
    val normalizedBase = base.trim('/')
    requireNotNull(classLoader.getResource(normalizedBase)) {
        "Classpath resource not found: $normalizedBase"
    }

    return { path ->
        val resourcePath = if (normalizedBase.isEmpty()) path else "$normalizedBase/$path"
        classLoader.getResource(resourcePath)?.let { url ->
            // Open a connection only to read metadata (length, last-modified), then discard it.
            // The actual stream is opened lazily via url::openStream when the response is written.
            val (length, lastModified) = url.openConnection().let { conn ->
                conn.contentLengthLong to (conn.lastModified
                    .takeIf { it > 0 }
                    ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH)
            }
            ResolvedResource(
                name = path.substringAfterLast('/'),
                length = length,
                lastModified = lastModified,
                open = url::openStream,
            )
        }
    }
}

/**
 * Middleware that serves static files from either the filesystem or the classpath.
 *
 * The [root] parameter determines the source:
 * - A plain path (`"/var/www/html"`, `"./static"`) serves from the real filesystem.
 * - A `classpath:`-prefixed path (`"classpath:static"`) serves from the classpath,
 *   which works correctly both during development and when packaged as a JAR.
 *
 * Features:
 * - GET / HEAD support with correct 304 Not Modified handling
 * - Single-range requests (`Range: bytes=...`) with 206/416 and If-Range support
 * - Dot-file access control ([DotFilesPolicy])
 * - Automatic directory index resolution
 * - Extension probing (e.g. serve `/about` as `about.html`)
 * - Cache-Control and Last-Modified headers
 * - Path-traversal protection for both filesystem and classpath roots
 *
 * Usage:
 * ```kotlin
 * ServeStatic("/var/www/html")
 * ServeStatic("classpath:static")
 * ```
 *
 * @param root        Resource root. Prefix with `classpath:` to load from the classpath.
 * @param baseUrl     URL prefix this middleware responds to, e.g. `"/static"`.
 * @param index       Filename served when a directory path is requested.
 * @param dotFiles    How to handle files whose names begin with `"."`.
 * @param maxAge      Seconds for the `Cache-Control: max-age` directive; 0 disables caching.
 * @param extensions  Extensions appended during probing, e.g. `[".html"]` lets `/about` resolve to `about.html`.
 * @param fallthrough Pass control to the next middleware on 404; throw [NotFound] if false.
 */
class ServeStatic @JvmOverloads constructor(
    root: String,
    private val baseUrl: String = "/static",
    private val index: String = "index.html",
    private val dotFiles: DotFilesPolicy = DotFilesPolicy.IGNORE,
    private val maxAge: Int = 0,
    private val extensions: List<String> = emptyList(),
    private val fallthrough: Boolean = true,
) : Middleware {

    /**
     * Policy controlling how dot-files (names starting with ".") are handled.
     *
     * - [ALLOW]  Serve them like any other file.
     * - [DENY]   Respond with 403 Forbidden.
     * - [IGNORE] Treat as not found (pass through or 404 depending on [fallthrough]).
     */
    enum class DotFilesPolicy { ALLOW, DENY, IGNORE }

    private val resolver: Resolver = when {
        root.startsWith("classpath:") -> classPathResolver(
            root.removePrefix("classpath:"),
            Thread.currentThread().contextClassLoader
                ?: ServeStatic::class.java.classLoader,
        )
        else -> fileSystemResolver(root)
    }

    override fun invoke(ctx: Context, next: Next) {
        val normalizedBase = baseUrl.trimEnd('/')

        if (ctx.method != "GET" && ctx.method != "HEAD") {
            next(); return
        }
        if (ctx.path != normalizedBase && !ctx.path.startsWith("$normalizedBase/")) {
            next(); return
        }

        val rawPath = ctx.path.removePrefix(normalizedBase).trimStart('/')
        if ('\u0000' in rawPath) return handleNotFound(next)
        val safePath = normalizePath(rawPath.ifEmpty { index }) ?: return handleNotFound(next)

        val resource = resolve(safePath) ?: return handleNotFound(next)

        if (resource.name.startsWith(".")) {
            when (dotFiles) {
                DotFilesPolicy.DENY -> throw Forbidden("Access to dot files is denied")
                DotFilesPolicy.IGNORE -> return handleNotFound(next)
                else -> Unit
            }
        }

        if (resource.notModifiedSince(ctx.header("if-modified-since"))) {
            ctx.status(304).empty(); return
        }

        serveResource(ctx, resource)
    }

    /**
     * Resolves [path] by trying, in order:
     * 1. The path as-is (regular file).
     * 2. The path as a directory by appending the index filename.
     * 3. Each configured extension appended to the path.
     */
    private fun resolve(path: String): ResolvedResource? =
        resolver(path)
            ?: resolver("$path/$index")
            ?: extensions.firstNotNullOfOrNull { resolver("$path$it") }

    /** Sets response headers and streams the resource body (omitted for HEAD requests). */
    private fun serveResource(ctx: Context, resource: ResolvedResource) {
        val contentType = FileResponse.inferContentType(resource.name)
        val lastModified = resource.lastModifiedHeader()

        ctx.response.apply {
            header("Content-Type", contentType)
            header("X-Content-Type-Options", "nosniff")
            if (maxAge > 0) header("Cache-Control", "public, max-age=$maxAge")
            lastModified?.let { header("Last-Modified", it) }
            if (resource.length >= 0) header("Accept-Ranges", "bytes")
        }

        when (val outcome = RangeSupport.evaluate(
            method = ctx.method,
            rangeHeader = ctx.header("range"),
            ifRangeHeader = ctx.header("if-range"),
            lastModifiedHeader = lastModified,
            totalLength = resource.length,
        )) {
            is RangeOutcome.Partial -> {
                ctx.response.status = 206
                ctx.response.header("Content-Range", outcome.contentRange)
                ctx.response.header("Content-Length", outcome.length.toString())
                ctx.response.body = ResponseBody.Stream(
                    RangeSupport.openSlice(resource.open, outcome.start, outcome.length)
                )
            }

            is RangeOutcome.Unsatisfiable -> {
                ctx.response.status = 416
                ctx.response.header("Content-Range", outcome.contentRange)
                ctx.response.body = ResponseBody.Empty
            }

            is RangeOutcome.Full -> {
                ctx.response.apply {
                    if (resource.length >= 0) header("Content-Length", resource.length.toString())
                }
                if (ctx.method != "HEAD") ctx.stream(resource.open(), contentType)
            }
        }
    }

    private fun handleNotFound(next: Next) = if (fallthrough) next() else throw NotFound()
}