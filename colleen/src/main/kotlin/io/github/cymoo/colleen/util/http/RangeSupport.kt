package io.github.cymoo.colleen.util.http

import java.io.InputStream

/**
 * Result of evaluating a request's `Range` header against a resource.
 */
internal sealed class RangeOutcome {
    /** No usable Range — serve the full body with 200. */
    object Full : RangeOutcome()

    /** Serve bytes [start]..[end] (inclusive) with 206 Partial Content. */
    data class Partial(val start: Long, val end: Long, val total: Long) : RangeOutcome() {
        val length: Long get() = end - start + 1
        val contentRange: String get() = "bytes $start-$end/$total"
    }

    /** No satisfiable range — 416 with the unsatisfied-range form of Content-Range. */
    data class Unsatisfiable(val total: Long) : RangeOutcome() {
        val contentRange: String get() = "bytes */$total"
    }
}

/**
 * Single-range `Range` / `If-Range` evaluation (RFC 9110 §14).
 *
 * Deliberately conservative: anything unusual — non-`bytes` units, multiple
 * ranges, malformed or inverted specs — falls back to a full 200 response,
 * which §14.2 explicitly permits ("a server MAY ignore the Range header").
 * Multipart `multipart/byteranges` responses are intentionally not supported.
 */
internal object RangeSupport {

    /**
     * Evaluates the request headers against a resource of [totalLength] bytes.
     *
     * @param method             request method — Range is only defined for GET
     * @param rangeHeader        raw `Range` header value, or null
     * @param ifRangeHeader      raw `If-Range` header value, or null
     * @param lastModifiedHeader the `Last-Modified` value this server would send
     *                           for the resource (used as the If-Range validator),
     *                           or null when unavailable
     * @param totalLength        resource size in bytes; negative = unknown
     */
    fun evaluate(
        method: String,
        rangeHeader: String?,
        ifRangeHeader: String?,
        lastModifiedHeader: String?,
        totalLength: Long,
    ): RangeOutcome {
        if (method != "GET") return RangeOutcome.Full
        if (totalLength < 0) return RangeOutcome.Full
        if (rangeHeader == null) return RangeOutcome.Full

        // If-Range: "serve the range only if the representation is unchanged;
        // otherwise send me the whole thing". We compare against our own
        // Last-Modified value; an ETag-shaped validator never matches (we do
        // not emit ETags), which safely degrades to a full response.
        if (ifRangeHeader != null && ifRangeHeader != lastModifiedHeader) {
            return RangeOutcome.Full
        }

        val spec = rangeHeader.trim()
        if (!spec.startsWith("bytes=")) return RangeOutcome.Full

        val ranges = spec.removePrefix("bytes=").split(',')
        if (ranges.size != 1) return RangeOutcome.Full // multi-range: serve full

        val range = ranges[0].trim()
        val dash = range.indexOf('-')
        if (dash < 0) return RangeOutcome.Full

        val startPart = range.substring(0, dash).trim()
        val endPart = range.substring(dash + 1).trim()

        val start: Long
        val end: Long
        when {
            startPart.isEmpty() && endPart.isEmpty() -> return RangeOutcome.Full

            // Suffix form: bytes=-N (the last N bytes)
            startPart.isEmpty() -> {
                val suffix = endPart.toLongOrNull() ?: return RangeOutcome.Full
                // RFC 9110: a zero-length suffix range is unsatisfiable
                if (suffix <= 0) return RangeOutcome.Unsatisfiable(totalLength)
                start = maxOf(0, totalLength - suffix)
                end = totalLength - 1
            }

            else -> {
                start = startPart.toLongOrNull() ?: return RangeOutcome.Full
                end = if (endPart.isEmpty()) {
                    totalLength - 1
                } else {
                    val requestedEnd = endPart.toLongOrNull() ?: return RangeOutcome.Full
                    if (start > requestedEnd) return RangeOutcome.Full // inverted: ignore
                    minOf(requestedEnd, totalLength - 1)
                }
            }
        }

        if (start >= totalLength) return RangeOutcome.Unsatisfiable(totalLength)
        return RangeOutcome.Partial(start, end, totalLength)
    }

    /**
     * Opens [open]'s stream positioned at [start], bounded to [length] bytes.
     * The source stream is closed if positioning fails.
     */
    fun openSlice(open: () -> InputStream, start: Long, length: Long): InputStream {
        val input = open()
        try {
            input.skipNBytes(start)
        } catch (e: Exception) {
            runCatching { input.close() }
            throw e
        }
        return BoundedInputStream(input, length)
    }
}

/**
 * A view over [delegate] that ends after [remaining] bytes.
 * Closing it closes the underlying stream.
 */
internal class BoundedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val byte = delegate.read()
        if (byte >= 0) remaining--
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = delegate.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int =
        minOf(delegate.available().toLong(), remaining).toInt()

    override fun close() = delegate.close()
}
