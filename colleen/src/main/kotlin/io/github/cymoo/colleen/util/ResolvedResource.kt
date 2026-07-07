
package io.github.cymoo.colleen.util

import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Resolves a file as a [ResolvedResource] carrying both a stream factory and
 * metadata (size, last-modified) needed for Content-Length, Last-Modified, and 304 handling.
 *
 * Resolution order when [fromClasspath] is false:
 * 1. Filesystem — absolute or relative to the working directory.
 * 2. Classpath  — fallback for packaged resources.
 *
 * Filesystem resources carry accurate [ResolvedResource.length] and
 * [ResolvedResource.lastModified]. Classpath resources (JAR entries) use
 * [Instant.EPOCH] as the lastModified sentinel, suppressing conditional-request logic.
 *
 * @param path          Filesystem path or classpath resource path.
 * @param fromClasspath Skip filesystem lookup entirely.
 * @param baseDir       If provided, the resolved file must reside within this directory.
 *                      Prevents path traversal. Only applies to filesystem resolution.
 * @throws IllegalArgumentException if [baseDir] is provided and the path escapes it.
 * @throws IllegalStateException    if the resource cannot be found.
 */
internal fun resolveFileStream(
    path: String,
    fromClasspath: Boolean,
    baseDir: String?,
): ResolvedResource {
    if (!fromClasspath) {
        val file = File(path)
        if (file.exists() && file.isFile) {
            if (baseDir != null) {
                val target = file.canonicalFile
                val base = File(baseDir).canonicalFile
                require(target.startsWith(base)) {
                    "Path traversal detected: '$path' escapes base directory '$baseDir'"
                }
            }
            return ResolvedResource(
                name = file.name,
                length = file.length(),
                lastModified = Instant.ofEpochMilli(file.lastModified()),
                open = file::inputStream,
            )
        }
    }

    // contextClassLoader may legally be null (e.g. threads created by some
    // containers); fall back to this class's loader, then the system loader.
    val cl = Thread.currentThread().contextClassLoader
        ?: ResolvedResource::class.java.classLoader
        ?: ClassLoader.getSystemClassLoader()
    val url = cl.getResource(path)
        ?: if (fromClasspath) error("Classpath resource not found: $path")
        else error("File not found on filesystem or classpath: $path")

    // Open a connection only to read metadata (length, last-modified), then discard it.
    // The actual stream is opened lazily via url::openStream when the response is written.
    val (length, lastModified) = url.openConnection().let { conn ->
        conn.contentLengthLong to (conn.lastModified
            .takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH)
    }

    return ResolvedResource(
        name = path.substringAfterLast('/'),
        length = length,
        lastModified = lastModified,
        open = url::openStream,
    )
}

/**
 * Minimal metadata for a resolved static resource.
 *
 * [length] is -1 when unknown (e.g. streamed classpath entry).
 * [lastModified] is [Instant.EPOCH] when unavailable (e.g. resources inside a JAR),
 * in which case conditional-request and Last-Modified header logic is skipped entirely.
 */
internal data class ResolvedResource(
    val name: String,
    val length: Long,
    val lastModified: Instant,
    val open: () -> InputStream,
)

/**
 * Returns true if the resource has not been modified since the client's cached copy,
 * based on the `If-Modified-Since` request header value [ifModifiedSince].
 *
 * Always returns false when [lastModified] is [Instant.EPOCH] (sentinel for
 * unavailable timestamps, e.g. JAR entries), suppressing 304 handling entirely.
 */
internal fun ResolvedResource.notModifiedSince(ifModifiedSince: String?): Boolean {
    if (lastModified == Instant.EPOCH || ifModifiedSince == null) return false
    return try {
        val since = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(ifModifiedSince))
        !lastModified.truncatedTo(ChronoUnit.SECONDS).isAfter(since)
    } catch (_: Exception) {
        false
    }
}

/**
 * Formats [lastModified] as an RFC 1123 date-time string suitable for the
 * `Last-Modified` response header. Returns null when [lastModified] is [Instant.EPOCH].
 */
internal fun ResolvedResource.lastModifiedHeader(): String? {
    if (lastModified == Instant.EPOCH) return null
    return lastModified.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME)
}