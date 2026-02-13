package io.github.cymoo.colleen

import io.github.cymoo.colleen.json.JsonMapper
import io.github.cymoo.colleen.util.TypeRef
import io.github.cymoo.colleen.util.http.ContentNegotiator
import io.github.cymoo.colleen.util.http.Cookie
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.util.http.LanguageNegotiator
import io.github.cymoo.colleen.util.http.QueryString
import io.github.cymoo.colleen.util.lazyLoom
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
data class Request(
    val method: String,
    val path: String,
    val queryString: String = "",
    val headers: Headers = Headers(),
    val stream: InputStream? = null,
    val metadata: RequestMetadata = RequestMetadata(),
    private val multipartSupplier: () -> List<Part> = { emptyList() },
) {
    /** Full request URI (path + query string). */
    val uri: String by lazy { if (queryString.isEmpty()) path else "$path?$queryString" }

    /** Whether the request uses multipart/form-data. */
    val isMultipart = contentType?.lowercase()?.startsWith("multipart/form-data") == true

    /**
     * Request body as raw bytes.
     *
     * This property lazily consumes the underlying request [stream] and
     * caches the result. The stream is closed after reading.
     *
     * Not available for `multipart/form-data` requests.
     *
     * @throws IllegalStateException if accessed for multipart requests.
     */
    val body: ByteArray? by lazyLoom {
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
     */
    val forms: Map<String, List<String>> by lazyLoom {
        val contentType = this.contentType ?: ""
        when {
            contentType.startsWith("application/x-www-form-urlencoded") -> {
                val formString = text() ?: ""
                QueryString.parse(formString)
            }

            contentType.startsWith("multipart/form-data") -> {
                val map = mutableMapOf<String, MutableList<String>>()
                parts.forEach { part ->
                    if (part is FormItem) {
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

    /** Parsed multipart parts (evaluated lazily). */
    private val parts: List<Part> by lazyLoom { multipartSupplier.invoke() }

    /** Returns all uploaded files with the given field name. */
    fun files(name: String): List<FileItem> = parts
        .filterIsInstance<FileItem>()
        .filter { it.name == name }

    /** Returns the first uploaded file with the given field name. */
    fun file(name: String): FileItem? =
        parts.filterIsInstance<FileItem>()
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
    val remoteAddr = metadata.remoteAddr

    /** Client IP address, resolved via proxy headers if present. */
    val ip: String by lazy { getRealIp(headers) ?: remoteAddr }

    data class RequestMetadata(
        val remoteAddr: String = "",
        val remoteHost: String = "",
        val remotePort: Int = 0,
        val scheme: String = "http",
        val serverName: String = "localhost",
        val serverPort: Int = 8080,
        val isSecure: Boolean = false,
        val protocol: String = "HTTP/1.1"
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
         * Returns the client's real IP address.
         *
         * The following headers are checked in order of priority:
         * 1. X-Forwarded-For — standard proxy header, may contain multiple IPs (use the first one)
         * 2. X-Real-IP — commonly used by Nginx
         */
        internal fun getRealIp(headers: Headers): String? {
            val xForwardedFor = headers["x-forwarded-for"]
            if (!xForwardedFor.isNullOrBlank()) {
                // X-Forwarded-For may contain multiple IPs, e.g. "client, proxy1, proxy2"
                // Use the first non-"unknown" IP
                val firstIp = xForwardedFor.split(",")
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && it != "unknown" }
                if (!firstIp.isNullOrBlank()) {
                    return firstIp
                }
            }

            val ip = headers["x-real-ip"]
            if (!ip.isNullOrBlank() && ip != "unknown") {
                return ip
            }

            return null
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
data class FormItem(
    override val name: String,
    val value: String
) : Part

/**
 * Represents an uploaded file with single-use semantics.
 *
 * Once you read the stream the file becomes consumed and cannot be read again.
 */
data class FileItem(
    override val name: String,
    @JvmField
    val filename: String,
    @JvmField
    val contentType: String?,
    @JvmField
    val size: Long,
    val inputStream: InputStream
) : Part {
    /**
     * Saves the file to the specified path.
     * This can only be called once.
     */
    fun save(path: String) {
        val target = Paths.get(path).normalize()
        Files.createDirectories(target.parent)

        Files.newOutputStream(target).use { out ->
            inputStream.copyTo(out)
        }
    }

    /** Closes the underlying file stream. */
    fun close() {
        inputStream.close()
    }
}
