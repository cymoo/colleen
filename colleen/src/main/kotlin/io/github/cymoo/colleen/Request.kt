package io.github.cymoo.colleen

import io.github.cymoo.colleen.json.JsonMapper
import io.github.cymoo.colleen.util.OnceCell
import io.github.cymoo.colleen.util.TypeRef
import io.github.cymoo.colleen.util.http.*
import io.github.cymoo.colleen.util.http.Cookie
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Paths


/**
 * Represents an incoming HTTP request.
 *
 * This type provides low-level request access and is primarily exposed
 * through [Context], which delegates most of its request-related APIs here.
 *
 * ## Request Body & Stream Semantics
 *
 * The underlying [stream] represents a **one-time readable input stream**.
 * It is owned by this [Request] instance and must not be consumed elsewhere.
 *
 * - The request body can only be read once.
 * - Accessing [body], [text], [json], or [forms] will consume the stream.
 * - For `multipart/form-data` requests, the stream is consumed by the
 *   multipart parser provided via [multipartSupplier].
 * - Accessing [body] for multipart requests is prohibited and will throw.
 *
 * Framework integrators must ensure that:
 * - Either the request is treated as a regular body (JSON / form),
 * - Or it is parsed as multipart, but never both.
 */
data class Request @JvmOverloads constructor(
    val method: String,
    val path: String,
    val queryString: String = "",
    val headers: Headers = Headers(),
    val stream: InputStream? = null,
    val serverInfo: ServerInfo = ServerInfo(),
    private val multipartSupplier: () -> List<Part> = { emptyList() },
    private val bodyCache: BodyCache = BodyCache(),
) {
    /**
     * Caches for everything derived from the one-shot request [stream].
     *
     * This is a constructor parameter (not a class-body property) on purpose:
     * `copy()` then shares the SAME cache between the original and the copy.
     * Mounted sub-apps rewrite the path via `request.copy(path=...)`, and since
     * the underlying stream can only be consumed once, parent and child must
     * see one shared body — otherwise whichever side reads second would get an
     * already-closed, empty stream.
     *
     * [OnceCell] is also thread-safe, so a request captured by an asynchronous
     * consumer (SSE/WS handler) cannot double-initialize these caches.
     *
     * The class itself is public only because the (public) data-class
     * constructor must reference it; the internal constructor keeps it opaque.
     */
    class BodyCache internal constructor() {
        internal val body = OnceCell<ByteArray?>()
        internal val forms = OnceCell<Map<String, List<String>>>()
        internal val parts = OnceCell<List<Part>>()
    }

    /** Full request URI (path + query string). */
    val uri: String by lazy { if (queryString.isEmpty()) path else "$path?$queryString" }

    /** Whether the request uses multipart/form-data. */
    val isMultipart = contentType?.lowercase()?.startsWith("multipart/form-data") == true

    /**
     * Request body as raw bytes.
     *
     * This property lazily consumes the underlying request [stream] and
     * caches the result. The stream is closed after reading.
     * The cache is shared with copies of this request (see [BodyCache]).
     *
     * Not available for `multipart/form-data` requests.
     *
     * @throws IllegalStateException if accessed for multipart requests.
     */
    val body: ByteArray?
        get() = bodyCache.body.getOrCompute {
            if (isMultipart) {
                throw IllegalStateException(
                    "Cannot access body for multipart/form-data requests. " +
                            "Use parts(), file(), or files() instead."
                )
            }
            stream?.use { it.readBytes() }
        }

    /** Request body as text using the given charset. */
    fun text(charset: Charset = UTF_8) = body?.toString(charset)

    // ========================================================================
    // Content Negotiation
    // ========================================================================

    /**
     * Checks whether the request accepts the given media type.
     *
     * The provided [type] may be either:
     * - A full MIME type (e.g. `"application/json"`, `"text/html"`), or
     * - A shorthand alias (e.g. `"json"`, `"html"`)
     *
     * If the `Accept` header is missing or empty, the type is treated as accepted.
     *
     * ### Example
     * ```kotlin
     * // Request header:
     * // Accept: application/json, text/html;q=0.8
     *
     * req.accepts("json")              // true
     * req.accepts("text/html")         // true
     * req.accepts("application/xml")  // false
     * ```
     *
     * @param type media type or shorthand alias
     * @return true if an acceptable match is found, false otherwise
     */
    fun accepts(type: String): Boolean = accepts(listOf(type)) != null

    /**
     * Returns the best matching acceptable media type from the given list.
     *
     * The negotiation is performed against the `Accept` header using
     * quality factors (`q`) and media type specificity.
     *
     * The returned value is the **normalized MIME type** (e.g. `"application/json"`),
     * not the original shorthand.
     *
     * If the `Accept` header is missing or empty, the first provided type
     * is treated as acceptable and returned.
     *
     * ### Example
     * ```kotlin
     * // Request header:
     * // Accept: text/html;q=0.9, application/json;q=1.0
     *
     * req.accepts(listOf("html", "json"))
     * // -> "application/json"
     *
     * req.accepts(listOf("xml", "html"))
     * // -> "text/html"
     *
     * req.accepts(listOf("xml"))
     * // -> null
     * ```
     *
     * @param types list of media types or shorthand aliases
     * @return the matched normalized media type, or null if none match
     */
    fun accepts(types: List<String>): String? = ContentNegotiator.negotiate(headers["accept"], types.toList())

    /**
     * Checks whether the request accepts the given language.
     *
     * Language negotiation is performed against the `Accept-Language` header
     * using quality factors (`q`) and language specificity.
     *
     * Matching rules:
     * - `"*"` matches any language
     * - `"zh"` matches `"zh"` and `"zh-CN"`
     * - `"zh-CN"` matches only `"zh-CN"`
     *
     * If the `Accept-Language` header is missing or empty, the language
     * is treated as accepted.
     *
     * ### Example
     * ```kotlin
     * // Request header:
     * // Accept-Language: zh-CN, en;q=0.8
     *
     * req.acceptsLang("zh")     // true
     * req.acceptsLang("zh-CN")  // true
     * req.acceptsLang("en")     // true
     * req.acceptsLang("ja")     // false
     * ```
     *
     * @param lang language tag (e.g. `"en"`, `"en-US"`, `"zh-CN"`)
     * @return true if an acceptable match is found, false otherwise
     */
    fun acceptsLang(lang: String): Boolean = acceptsLang(listOf(lang)) != null

    /**
     * Returns the best matching acceptable language from the given list.
     *
     * The negotiation is performed against the `Accept-Language` header using
     * quality factors (`q`) and language specificity.
     *
     * If the `Accept-Language` header is missing or empty, the first provided
     * language is treated as acceptable and returned.
     *
     * ### Example
     * ```kotlin
     * // Request header:
     * // Accept-Language: zh-CN, en;q=0.8
     *
     * req.acceptsLang(listOf("en", "zh"))
     * // -> "zh"
     *
     * req.acceptsLang(listOf("ja", "en"))
     * // -> "en"
     *
     * req.acceptsLang(listOf("ja"))
     * // -> null
     * ```
     *
     * @param lang list of language tags
     * @return the matched language tag, or null if none match
     */
    fun acceptsLang(lang: List<String>): String? =
        LanguageNegotiator.negotiate(headers["accept-language"], lang.toList())

    // ========================================================================
    // JSON Body Parsing
    // ========================================================================

    /**
     * Parses the request body as JSON into type [T].
     *
     * Returns null if the body is empty.
     * Throws [BadRequest] if parsing fails.
     */
    inline fun <reified T> json(mapper: JsonMapper): T? {
        val data = text() ?: return null
        try {
            return mapper.fromJsonString(data, object : TypeRef<T>() {}.type)
        } catch (e: Exception) {
            val failure = mapper.classifyError(e) ?: BindingFailure.InvalidStructure(e)
            throw failure.toHttpException()
        }
    }

    // ========================================================================
    // Query Parameters
    // ========================================================================

    /** Parsed query parameters (supports repeated keys). */
    val queries: Map<String, List<String>> by lazy { QueryString.parse(queryString) }

    /** Maps query parameters to a typed object. */
    inline fun <reified T> queries(mapper: JsonMapper): T? = mapToClass(queries, T::class.java, mapper)

    /** Returns the first value of a query parameter. */
    fun query(key: String): String? = queries[key]?.firstOrNull()

    /** Returns all values of a query parameter. */
    fun queryList(key: String): List<String> = queries[key] ?: emptyList()

    // ========================================================================
    // Form Data
    // ========================================================================

    /**
     * Parsed form parameters.
     *
     * Supports both application/x-www-form-urlencoded and multipart/form-data.
     * The cache is shared with copies of this request (see [BodyCache]).
     */
    val forms: Map<String, List<String>>
        get() = bodyCache.forms.getOrCompute {
            val contentType = this.contentType ?: ""
            when {
                contentType.startsWith("application/x-www-form-urlencoded") -> {
                    val formString = text() ?: ""
                    QueryString.parse(formString)
                }

                contentType.startsWith("multipart/form-data") -> {
                    val map = mutableMapOf<String, MutableList<String>>()
                    parts.forEach { part ->
                        if (part is FormField) {
                            map.computeIfAbsent(part.name) { mutableListOf() }.add(part.value)
                        }
                    }
                    map
                }

                else -> emptyMap()
            }
        }

    /** Maps form parameters to a typed object. */
    inline fun <reified T> forms(mapper: JsonMapper): T? = mapToClass(forms, T::class.java, mapper)

    /** Returns the first value of a form field. */
    fun form(key: String): String? = forms[key]?.firstOrNull()

    /** Returns all values of a form field. */
    fun formList(key: String): List<String> = forms[key] ?: emptyList()

    // ========================================================================
    // Cookie
    // ========================================================================

    /** Parsed cookies from the Cookie header. */
    val cookies: Map<String, String> by lazy { Cookie.parse(headers["cookie"]) }

    /** Returns the value of the specified cookie. */
    fun cookie(name: String): String? = cookies[name]

    // ========================================================================
    // Multipart File
    // ========================================================================

    /** Parsed multipart parts (evaluated lazily, cache shared with copies). */
    private val parts: List<Part>
        get() = bodyCache.parts.getOrCompute { multipartSupplier.invoke() }

    /** Returns all uploaded files with the given field name. */
    fun files(name: String): List<FilePart> = parts
        .filterIsInstance<FilePart>()
        .filter { it.name == name }

    /** Returns the first uploaded file with the given field name. */
    fun file(name: String): FilePart? =
        parts.filterIsInstance<FilePart>()
            .firstOrNull { it.name == name }

    // ========================================================================
    // Headers & Metadata
    // ========================================================================

    /** Returns the value of the specified request header. */
    operator fun get(key: String) = headers[key]
    fun header(key: String) = headers[key]

    val contentType: String? get() = header("content-type")
    val contentLength: Long by lazy { header("content-length")?.toLongOrNull() ?: -1L }

    /** Remote address reported by the underlying server. */
    val remoteAddr = serverInfo.remoteAddr

    /**
     * Client IP address.
     *
     * By default this is simply [remoteAddr]: `X-Forwarded-For` is client-supplied
     * and trivially forged, so it is only consulted when the server is explicitly
     * configured with a trusted proxy count
     * ([io.github.cymoo.colleen.ServerConfig.trustedProxyCount]). Otherwise
     * rate limiting / audit logging keyed on this value could be bypassed by a
     * spoofed header.
     */
    val ip: String by lazy {
        resolveClientIp(headers, remoteAddr, serverInfo.trustedProxyCount)
    }

    data class ServerInfo(
        val remoteAddr: String = "",
        val remoteHost: String = "",
        val remotePort: Int = 0,
        val scheme: String = "http",
        val serverName: String = "localhost",
        val serverPort: Int = 8080,
        val isSecure: Boolean = false,
        val protocol: String = "HTTP/1.1",
        /** Number of trusted reverse proxies in front of the server (0 = trust none). */
        val trustedProxyCount: Int = 0,
    )

    // ========================================================================
    // Helper / Internal APIs
    // ========================================================================

    /** Maps a parameter map into a typed object using the given JSON mapper. */
    @PublishedApi
    internal fun <T> mapToClass(
        data: Map<String, List<String>>,
        clazz: Class<T>,
        mapper: JsonMapper,
    ): T? {
        if (data.isEmpty()) return null

        // Convert Map<String, List<String>> to Map<String, Any>
        // If there is only one value, take it directly; otherwise, retain it as a List.
        val convertedData = data.mapValues { (_, values) ->
            if (values.size == 1) values[0] else values
        }
        return try {
            mapper.convertValue(convertedData, clazz)
        } catch (e: Exception) {
            val root = unwrapIllegalArgumentException(e)
            val ex = root as? Exception ?: e

            val failure = mapper.classifyError(ex)
                ?: BindingFailure.InvalidStructure(ex)

            throw failure.toHttpException()
        }
    }

    private fun unwrapIllegalArgumentException(e: Throwable): Throwable {
        return when (e) {
            is IllegalArgumentException -> e.cause ?: e
            else -> e
        }
    }

    companion object {
        /**
         * Resolves the client IP address, honoring at most [trustedProxyCount]
         * reverse proxies.
         *
         * Algorithm (standard "strip trusted proxies from the right"):
         * 1. Build the hop chain: X-Forwarded-For entries + the direct peer address.
         *    Each trusted proxy appends the address of ITS peer, so only the
         *    rightmost [trustedProxyCount] entries are trustworthy — everything
         *    to the left is client-controlled.
         * 2. Remove [trustedProxyCount] entries from the right; the rightmost
         *    remaining entry is the client address as seen by the outermost
         *    trusted proxy.
         *
         * Falls back to X-Real-IP (commonly set by Nginx) when no X-Forwarded-For
         * is present, and to [remoteAddr] when nothing else applies.
         * With [trustedProxyCount] <= 0 the headers are ignored entirely — they
         * are client-supplied and trivially forged.
         */
        internal fun resolveClientIp(headers: Headers, remoteAddr: String, trustedProxyCount: Int): String {
            if (trustedProxyCount <= 0) return remoteAddr

            val forwardedFor = headers["x-forwarded-for"]
            if (!forwardedFor.isNullOrBlank()) {
                val chain = forwardedFor.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "unknown" } + remoteAddr

                val clientIndex = (chain.size - 1 - trustedProxyCount).coerceAtLeast(0)
                return chain[clientIndex]
            }

            val realIp = headers["x-real-ip"]
            if (!realIp.isNullOrBlank() && realIp != "unknown") {
                return realIp
            }

            return remoteAddr
        }
    }

    // ========================================================================
    // Java Compatibility
    // ========================================================================

    fun <T> json(mapper: JsonMapper, typeRef: TypeRef<T>): T? {
        val data = text() ?: return null
        try {
            @Suppress("UNCHECKED_CAST")
            return mapper.fromJsonString(data, typeRef.type) as T
        } catch (e: Exception) {
            val failure = mapper.classifyError(e) ?: BindingFailure.InvalidStructure(e)
            throw failure.toHttpException()
        }
    }

    fun <T> json(mapper: JsonMapper, clazz: Class<T>): T? {
        val data = text() ?: return null
        try {
            @Suppress("UNCHECKED_CAST")
            return mapper.fromJsonString(data, clazz) as T
        } catch (e: Exception) {
            val failure = mapper.classifyError(e) ?: BindingFailure.InvalidStructure(e)
            throw failure.toHttpException()
        }
    }

    fun <T> queries(clazz: Class<T>, mapper: JsonMapper): T? = mapToClass(queries, clazz, mapper)
    fun <T> forms(clazz: Class<T>, mapper: JsonMapper): T? = mapToClass(forms, clazz, mapper)
}

/** Base type for multipart request parts. */
sealed interface Part {
    val name: String
}

/** Represents a simple form field in a multipart request. */
data class FormField(
    override val name: String,
    val value: String
) : Part

/**
 * Represents an uploaded file with single-use semantics.
 *
 * Once you read the stream the file becomes consumed and cannot be read again.
 */
data class FilePart(
    override val name: String,
    @JvmField
    val filename: String,
    @JvmField
    val contentType: String?,
    @JvmField
    val size: Long,
    val inputStream: InputStream
) : Part {
    @Volatile
    private var consumed = false

    /**
     * Saves the file to the specified path.
     * This can only be called once.
     */
    fun save(path: String) {
        check(!consumed) { "File already consumed" }
        consumed = true

        val target = Paths.get(path).normalize()
        // parent is null for bare filenames like "upload.bin" (current directory)
        target.parent?.let { Files.createDirectories(it) }


        try {
            inputStream.use { ins ->
                Files.newOutputStream(target).use { out ->
                    ins.copyTo(out)
                }
            }
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(target)
            } catch (_: Exception) {
            }
            throw e
        }
    }
}
