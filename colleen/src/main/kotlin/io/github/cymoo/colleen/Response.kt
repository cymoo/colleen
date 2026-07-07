package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.Cookie
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.ws.WsConnection
import java.io.InputStream
import java.util.function.Consumer
import kotlin.time.Duration

/**
 * Represents an outgoing HTTP response.
 *
 * A Response models the *intent* of an HTTP reply, including status,
 * headers, and a high-level response body.
 *
 * It is usually accessed and modified via [Context], while the actual
 * network write is performed by the server adapter.
 *
 * ## Body & Materialization
 *
 * - [body] describes the response payload at a high level.
 * - It is lazily converted into a [RawResponseBody] just before sending.
 * - Materialization is idempotent and happens at most once.
 *
 * ## Handler Return Semantics
 *
 * Handlers may return various types (e.g. status codes, strings, JSON objects,
 * or [Result]). These values are internally mapped onto this [Response].
 *
 * ## Streaming Responses
 *
 * - Streaming bodies and SSE are one-time consumable.
 * - Long-lived responses (e.g. SSE) keep the connection open and are
 *   fully controlled by the handler.
 *
 * This type is mutable and not thread-safe.
 *
 * Deliberately a plain class (not a data class): a response has identity and
 * mutable state, so structural `equals`/`copy` semantics would be misleading —
 * the only sanctioned copy is the internal [copyForSubApp].
 */
class Response @JvmOverloads constructor(
    var status: Int = 200,
    val headers: Headers = Headers(),
    var body: ResponseBody = ResponseBody.Unset,
) {
    /** Whether the response body has been explicitly set */
    val isBodySet get() = body !is ResponseBody.Unset

    /**
     * Copy handed to a mounted sub-app.
     *
     * This is THE single place defining what is shared vs. copied:
     * - [status] is copied by value; the [body] REFERENCE is carried over
     *   as-is (the sub-app starts from the parent's view and typically
     *   replaces the body wholesale rather than mutating its contents).
     * - Deep-copied: [headers] — a sub-app that fails with 404 (mount
     *   fallthrough) must not leak headers into the parent response; on
     *   success the sub-response is merged back explicitly via [merge].
     * - NOT carried over: [materializedBody] and [onResponseSent] — those
     *   belong to the write phase of the parent exchange.
     */
    internal fun copyForSubApp(): Response = Response(
        status = status,
        headers = headers.copy(),
        body = body,
    )

    override fun toString() = "Response(status=$status, body=${body::class.simpleName})"

    /** Materialized response body, ready to be written by the server adapter. */
    var materializedBody: RawResponseBody? = null
        internal set

    /**
     * Callback invoked after the HTTP response has been fully written and flushed to the client.
     *
     * The callback receives:
     * - `total`: total time elapsed from request receipt to response completion.
     * - `io`: time spent writing and flushing the response to the client.
     * - `bytesSent`: total number of bytes written to the response body.
     */
    internal var onResponseSent: ((total: Duration, io: Duration, bytesSent: Long) -> Unit)? = null

    /**
     * Materializes the response body.
     *
     * This method is idempotent - calling it multiple times has no effect
     * after the first materialization.
     */
    internal fun materialize(ctx: Context) {
        if (materializedBody == null) {
            materializedBody = body.materialize(ctx)
        }
    }

    /** Sets HTTP status code */
    fun status(code: Int): Response {
        status = code
        return this
    }

    /** Get the first value of a response header */
    fun header(key: String): String? = headers[key]

    /** Get all values of a response header */
    fun headers(key: String): List<String> = headers.getAll(key)

    /** Set (replace) a response header */
    fun header(key: String, value: String): Response {
        headers[key] = value
        return this
    }

    /** Append a response header value without replacing existing ones */
    fun addHeader(key: String, value: String): Response {
        headers.add(key, value)
        return this
    }

    /** Explicitly set an empty response body */
    fun empty() {
        if (status == 200) status = 204
        body = ResponseBody.Empty
    }

    /** Set plain text response */
    @JvmOverloads
    fun text(content: String, contentType: String = "text/plain; charset=utf-8") {
        headers["Content-Type"] = contentType
        body = ResponseBody.Text(content)
    }

    /** Set HTML response */
    fun html(content: String) {
        text(content, "text/html; charset=utf-8")
    }

    /**
     * Set JSON response.
     *
     * By default, JSON is serialized eagerly into bytes.
     * Set [stream] to true to enable streaming serialization for large payloads.
     */
    @JvmOverloads
    fun json(data: Any?, stream: Boolean = false) {
        headers["Content-Type"] = "application/json; charset=utf-8"
        body = ResponseBody.Json(data, stream)
    }

    /** Set binary response */
    @JvmOverloads
    fun bytes(data: ByteArray, contentType: String = "application/octet-stream") {
        headers["Content-Type"] = contentType
        body = ResponseBody.Bytes(data)
    }

    /**
     * Set streaming response.
     *
     * **Ownership transfer**: The provided [stream] is consumed and closed
     * by the framework after the response is fully written. Callers must
     * NOT close or reuse it after calling this method.
     */
    @JvmOverloads
    fun stream(stream: InputStream, contentType: String = "application/octet-stream") {
        headers["Content-Type"] = contentType
        body = ResponseBody.Stream(stream)
    }

    /**
     * Enable Server-Sent Events (SSE).
     *
     * The connection remains open and is managed by the handler.
     */
    fun sse(handler: Consumer<SseConnection>) {
        headers["Content-Type"] = "text/event-stream; charset=utf-8"
        headers["Cache-Control"] = "no-cache"
        headers["Connection"] = "keep-alive"
        headers["X-Accel-Buffering"] = "no" // disable nginx buffering
        body = ResponseBody.Sse(handler)
    }

    /** Redirect to another URL */
    @JvmOverloads
    fun redirect(url: String, statusCode: Int = 302) {
        status = statusCode
        headers["Location"] = url
        body = ResponseBody.Empty
    }

    /**
     * Set a cookie.
     *
     * @param name Cookie name (alphanumeric, _, - only)
     * @param value Cookie value (auto-encoded)
     * @param maxAge Lifetime in seconds (null=session, 0=delete, >0=persistent)
     * @param path Cookie path (default: "/")
     * @param domain Cookie domain (default: current host only)
     * @param secure Require HTTPS (default: false, set true in production)
     * @param httpOnly Prevent JS access (default: false, recommended: true)
     * @param sameSite CSRF protection (default: LAX)
     */
    @JvmOverloads
    fun cookie(
        name: String,
        value: String,
        maxAge: Int? = null,
        path: String? = "/",
        domain: String? = null,
        secure: Boolean = false,
        httpOnly: Boolean = false,
        sameSite: Cookie.SameSite? = Cookie.SameSite.LAX
    ): Response {
        // Build and add header
        val cookieValue = Cookie.buildSetCookie(
            name, value, maxAge, path, domain, secure, httpOnly, sameSite
        )
        headers.add("Set-Cookie", cookieValue)

        return this
    }

    /**
     * Delete a cookie.
     *
     * IMPORTANT: path and domain must match the original cookie.
     *
     * @param name Cookie name
     * @param path Cookie path (must match original, default: "/")
     * @param domain Cookie domain (must match original)
     */
    @JvmOverloads
    fun deleteCookie(
        name: String,
        path: String = "/",
        domain: String? = null
    ): Response =
        cookie(name, "", maxAge = 0, path = path, domain = domain, sameSite = Cookie.SameSite.LAX)

    /**
     * Merge another response into this one.
     *
     * Used internally for sub-app mounting and result propagation.
     */
    internal fun merge(subResponse: Response): Response {
        this.status = subResponse.status
        this.body = subResponse.body
        subResponse.headers.forEach { key, values ->
            this.headers[key] = values
        }
        return this
    }

    /**
     * Apply a handler's return value to this response.
     *
     * This is the core return-value-to-response mapping logic.
     */
    internal fun applyHandlerResult(result: Any?) {
        if (result is Response) {
            // Returning ctx.response (fluent style) needs no processing, but a
            // *different* Response instance carries intent — apply it instead of
            // silently dropping it.
            if (result !== this) {
                status = result.status
                result.headers.forEach { key, values -> headers[key] = values }
                if (result.isBodySet) body = result.body
            }
            return
        }

        if (result != null) forbidInternalTypes(result)

        // Response has been manually set
        if (isBodySet) {
            if (result != Unit) {
                logger.warn("Response already set, ignoring return value: {}", result)
            }
            return
        }

        when (result) {
            // Null is ambiguous and therefore forbidden: should it be an empty response or JSON null?
            null -> error(
                "Handler returned null. " +
                        "Null is not a valid handler result. " +
                        "Use Unit/void for an empty response, or call json(null) to return a JSON null."
            )

            Unit -> {
                if (status == 200) status = 204
                body = ResponseBody.Empty
            }

            // Numeric return value = HTTP status
            is Int, is Long -> {
                // Validate RFC 7231 compliant status code range.
                // Compare as Long so a Long return value never hits a ClassCastException.
                val code = (result as Number).toLong()
                require(code in 100..599) { "Invalid HTTP status code: $result" }
                status(code.toInt())
                if (!isBodySet) body = ResponseBody.Empty
            }

            is Status -> {
                // Validate RFC 7231 compliant status code range
                require(result.code in 100..599)
                status(result.code)
                body = ResponseBody.Empty
            }

            is String -> {
                headers.putIfAbsent("Content-Type", "text/plain; charset=utf-8")
                body = ResponseBody.Text(result)
            }

            is ByteArray -> {
                headers.putIfAbsent("Content-Type", "application/octet-stream")
                body = ResponseBody.Bytes(result)
            }

            is InputStream -> {
                headers.putIfAbsent("Content-Type", "application/octet-stream")
                body = ResponseBody.Stream(result)
            }

            // Common JSON shapes
            is Map<*, *>, is List<*> -> {
                headers.putIfAbsent("Content-Type", "application/json; charset=utf-8")
                body = ResponseBody.Json(result, false)
            }

            // Structured Result<T>
            is Result<*> -> {
                status = result.status
                result.headers.forEach { key, values ->
                    values.forEach { headers.add(key, it) }
                }
                when (val resultBody = result.body) {
                    // Result carries an explicit status, so a numeric body is payload —
                    // it must NOT be re-interpreted as a status code
                    // (e.g. Result.ok(42) means "200 with JSON body 42").
                    is Int, is Long -> {
                        headers.putIfAbsent("Content-Type", "application/json; charset=utf-8")
                        body = ResponseBody.Json(resultBody, false)
                    }

                    else -> applyHandlerResult(resultBody)
                }
            }

            is ResponseBody -> body = result

            // Fallback: treat as JSON
            else -> {
                headers.putIfAbsent("Content-Type", "application/json; charset=utf-8")
                body = ResponseBody.Json(result, false)
            }
        }
    }
}

/**
 * High-level response body representation.
 *
 * It is converted to RawResponseBody only at the server adapter layer.
 */
sealed class ResponseBody {
    /** Convert this body into a server-ready form */
    internal abstract fun materialize(ctx: Context): RawResponseBody

    /** Body not explicitly set */
    object Unset : ResponseBody() {
        override fun materialize(ctx: Context) = RawResponseBody.Empty
    }

    /** Explicit empty body */
    object Empty : ResponseBody() {
        override fun materialize(ctx: Context) = RawResponseBody.Empty
    }

    /** UTF-8 text body */
    class Text(val value: String) : ResponseBody() {
        override fun materialize(ctx: Context): RawResponseBody {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return RawResponseBody.Bytes(bytes)
        }
    }

    /** Binary body */
    class Bytes(val value: ByteArray) : ResponseBody() {
        override fun materialize(ctx: Context) = RawResponseBody.Bytes(value)
    }

    /**
     * JSON body.
     *
     * Serialization is deferred and uses the Context's JsonMapper.
     *
     * - `null` becomes the JSON literal `null`
     * - [io.github.cymoo.colleen.json.RawJson] passes through verbatim
     * - everything else (including plain Strings, which are QUOTED) goes
     *   through the JsonMapper
     */
    class Json(val value: Any?, val stream: Boolean) : ResponseBody() {
        override fun materialize(ctx: Context): RawResponseBody {
            val mapper = ctx.jsonMapper

            return if (stream) {
                RawResponseBody.Stream(
                    when (value) {
                        null -> "null".byteInputStream()
                        is io.github.cymoo.colleen.json.RawJson -> value.json.byteInputStream()
                        else -> mapper.toJsonStream(value)
                    }
                )
            } else {
                val json = when (value) {
                    null -> "null"
                    is io.github.cymoo.colleen.json.RawJson -> value.json
                    else -> mapper.toJsonString(value)
                }
                RawResponseBody.Bytes(json.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** Streaming body */
    class Stream(val input: InputStream) : ResponseBody() {
        override fun materialize(ctx: Context) = RawResponseBody.Stream(input)
    }

    /** Server-Sent Events body */
    class Sse(val handler: Consumer<SseConnection>) : ResponseBody() {
        override fun materialize(ctx: Context) = RawResponseBody.Sse(handler)
    }

    /** WebSocket upgrade body */
    class WebSocket(
        val handler: Consumer<WsConnection>,
        val pathParams: Map<String, String>,
        val queryParams: Map<String, List<String>>,
        val app: Colleen? = null,
        val states: Map<String, Any?> = emptyMap(),
        val headers: Headers = Headers(),
    ) : ResponseBody() {
        override fun materialize(ctx: Context) =
            RawResponseBody.WebSocket(handler, pathParams, queryParams, app, states, headers)
    }
}

@JvmInline
value class Status(val code: Int) {
    companion object {
        @JvmStatic
        fun of(code: Int): Status = Status(code)
    }
}

/**
 * Low-level response body understood by server adapters.
 */
sealed class RawResponseBody {
    object Empty : RawResponseBody()

    class Bytes(val bytes: ByteArray) : RawResponseBody()

    class Stream(val input: InputStream) : RawResponseBody()

    class Sse(val handler: Consumer<SseConnection>) : RawResponseBody()

    class WebSocket(
        val handler: Consumer<WsConnection>,
        val pathParams: Map<String, String>,
        val queryParams: Map<String, List<String>>,
        val app: Colleen? = null,
        val states: Map<String, Any?> = emptyMap(),
        val headers: Headers = Headers(),
    ) : RawResponseBody()
}

/**
 * Immutable, declarative return type for handlers.
 *
 * Represents the intent to return an HTTP response.
 */
data class Result<out T>(
    val status: Int,
    val body: T,
    val headers: Headers = Headers(),
) {

    /**
     * Add a header value.
     *
     * Multiple calls with the same name will produce multiple values.
     */
    fun header(name: String, value: String): Result<T> =
        copy(headers = headers.copy().apply {
            add(name, value)
        })

    /**
     * Change the HTTP status code.
     */
    fun status(code: Int): Result<T> =
        copy(status = code)

    /**
     * Transform the response body.
     */
    fun <R> map(transform: (T) -> R): Result<R> =
        Result(status, transform(body), headers)

    companion object {

        // === 2xx ===

        @JvmStatic
        fun <T> ok(body: T) = Result(200, body)

        @JvmStatic
        fun <T> created(body: T) = Result(201, body)

        @JvmStatic
        fun noContent() = Result(204, Unit)

        // === 3xx ===

        @JvmStatic
        fun redirect(location: String, permanent: Boolean = false) =
            Result(
                status = if (permanent) 301 else 302,
                body = Unit,
                headers = Headers.of("Location", location)
            )

        // === 4xx ===

        @JvmStatic
        fun <T> badRequest(body: T) = Result(400, body)

        @JvmStatic
        fun <T> unauthorized(body: T) = Result(401, body)

        @JvmStatic
        fun <T> forbidden(body: T) = Result(403, body)

        @JvmStatic
        fun <T> notFound(body: T) = Result(404, body)

        // === Custom ===

        @JvmStatic
        fun <T> of(status: Int, body: T) = Result(status, body)

        @JvmStatic
        fun of(status: Int) = Result(status, Unit)
    }
}

private fun forbidInternalTypes(value: Any) {
    require(
        // @formatter:off
        value !is Request &&
        value !is Context &&
        value !is RawResponseBody &&
        value !is Colleen
    ) {
        "Handler must not return colleen internal type: ${value::class.qualifiedName}"
    }
}