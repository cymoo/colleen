package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.*
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ── Internal model ────────────────────────────────────────────────────────────

/**
 * Minimal metadata for a resolved static resource.
 *
 * [length] is -1 when unknown (e.g. streamed classpath entry).
 * [lastModified] is [Instant.EPOCH] when unavailable (e.g. resources inside a JAR),
 * in which case conditional-request and Last-Modified header logic is skipped entirely.
 */
private data class ResolvedResource(
    val name: String,
    val length: Long,
    val lastModified: Instant,
    val open: () -> InputStream,
)

/**
 * A function that maps a sanitized relative path to a [ResolvedResource],
 * or returns null if the resource does not exist.
 *
 * Implementations are responsible for their own security boundary checks
 * (e.g. preventing path traversal outside their root).
 */
private typealias Resolver = (path: String) -> ResolvedResource?

// ── Resolver factories ────────────────────────────────────────────────────────

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
 * Path traversal is prevented by [normalizePath], which rejects any path whose
 * ".." segments would escape the logical root before it ever reaches the classloader.
 *
 * Note: [Instant.EPOCH] is used as the lastModified sentinel for JAR entries whose
 * timestamps are unreliable or absent, which suppresses Last-Modified / 304 handling.
 */
private fun classPathResolver(
    base: String,
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
): Resolver {
    val normalizedBase = base.trim('/')
    // Fail fast at construction time, consistent with fileSystemResolver.
    // A missing classpath root is always a configuration error, not a runtime 404.
    requireNotNull(classLoader.getResource(normalizedBase)) {
        "Classpath resource not found: $normalizedBase"
    }

    return { path ->
        // normalizePath is already called by the middleware, but repeated here defensively
        // so this resolver is safe to use standalone. The null-safe chain short-circuits
        // without needing a non-local return (which Kotlin prohibits in non-inline lambdas).
        normalizePath(path)?.let { safePath ->
            val resourcePath = if (normalizedBase.isEmpty()) safePath else "$normalizedBase/$safePath"
            classLoader.getResource(resourcePath)?.let { url ->
                val conn = url.openConnection()
                ResolvedResource(
                    name = safePath.substringAfterLast('/'),
                    length = conn.contentLengthLong,
                    lastModified = conn.lastModified
                        .takeIf { it > 0 }
                        ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
                    open = url::openStream,
                )
            }
        }
    }
}

/**
 * Normalizes a relative URL path by resolving "." and ".." segments.
 * Returns null if ".." segments would escape the logical root,
 * which indicates a path-traversal attempt that must be rejected.
 */
private fun normalizePath(path: String): String? {
    val stack = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (stack.isEmpty()) return null else stack.removeLast()
            else -> stack.addLast(segment)
        }
    }
    return stack.joinToString("/")
}

// ── Middleware ────────────────────────────────────────────────────────────────

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
 * - Dot-file access control ([DotFilesPolicy])
 * - Automatic directory index resolution
 * - Extension probing (e.g. serve `/about` as `about.html`)
 * - Cache-Control and Last-Modified headers
 * - Path-traversal protection for both filesystem and classpath roots
 *
 * Usage:
 * ```kotlin
 * // Filesystem root
 * ServeStatic("/var/www/html")
 *
 * // Classpath root — works inside a JAR (e.g. src/main/resources/static/)
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
            // Prefer thread-context classloader (works in most app servers),
            // fall back to the classloader that loaded this class.
            Thread.currentThread().contextClassLoader
                ?: ServeStatic::class.java.classLoader
        )

        else -> fileSystemResolver(root)
    }

    override fun invoke(ctx: Context, next: Next) {
        if ((ctx.method != "GET" && ctx.method != "HEAD") || !ctx.path.startsWith("$baseUrl/") && ctx.path != baseUrl) {
            next(); return
        }

        val rawPath = ctx.path.removePrefix(baseUrl).trimStart('/')

        // Reject null bytes and un-normalizable paths early to avoid ambiguous resolver behavior.
        if ('\u0000' in rawPath) return handleNotFound(next)
        val safePath = normalizePath(rawPath.ifEmpty { index }) ?: return handleNotFound(next)

        val resource = resolve(safePath) ?: return handleNotFound(next)

        when (resource.name.startsWith(".")) {
            true -> when (dotFiles) {
                DotFilesPolicy.DENY -> throw Forbidden("Access to dot files is denied")
                DotFilesPolicy.IGNORE -> return handleNotFound(next)
                DotFilesPolicy.ALLOW -> Unit
            }

            false -> Unit
        }

        if (checkNotModified(ctx, resource)) {
            ctx.status(304).empty()
            // ctx.status(304)
            return
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

    /**
     * Returns true if the resource has not been modified since the client's cached copy.
     * Skips the check entirely when [ResolvedResource.lastModified] is [Instant.EPOCH].
     */
    private fun checkNotModified(ctx: Context, resource: ResolvedResource): Boolean {
        if (resource.lastModified == Instant.EPOCH) return false
        val ifModifiedSince = ctx.header("if-modified-since") ?: return false
        return try {
            val since = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(ifModifiedSince))
            !resource.lastModified.truncatedTo(ChronoUnit.SECONDS).isAfter(since)
        } catch (_: Exception) {
            false
        }
    }

    /** Sets response headers and streams the resource body (omitted for HEAD requests). */
    private fun serveResource(ctx: Context, resource: ResolvedResource) {
        // URLConnection.guessContentTypeFromName is faster, OS-independent, and works correctly
        // for both filesystem and classpath resources (unlike Files.probeContentType).
        val contentType = (URLConnection.guessContentTypeFromName(resource.name)
            ?: "application/octet-stream")
            .let { mime ->
                val needsCharset = mime.startsWith("text/") ||
                        mime == "application/json" || mime == "application/javascript"
                if (needsCharset && "charset" !in mime) "$mime; charset=utf-8" else mime
            }

        ctx.response.apply {
            header("Content-Type", contentType)
            if (resource.length >= 0) header("Content-Length", resource.length.toString())
            header("X-Content-Type-Options", "nosniff")
            if (maxAge > 0) header("Cache-Control", "public, max-age=$maxAge")
            if (resource.lastModified != Instant.EPOCH) header(
                "Last-Modified",
                resource.lastModified.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME),
            )
        }

        // Avoid opening the stream entirely for HEAD requests — metadata is sufficient.
        if (ctx.method != "HEAD") ctx.stream(resource.open(), contentType)
    }

    private fun handleNotFound(next: Next) = if (fallthrough) next() else throw NotFound()
}