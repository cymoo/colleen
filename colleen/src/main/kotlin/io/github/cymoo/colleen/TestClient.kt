package io.github.cymoo.colleen

import io.github.cymoo.colleen.json.fromJsonString
import io.github.cymoo.colleen.util.http.Headers
import io.github.cymoo.colleen.util.http.UrlPath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.*
import kotlin.time.measureTime

/**
 * Test client for testing Colleen applications.
 *
 * Usage examples:
 * ```
 * val app = Colleen()
 * app.get("/hello") { ctx -> "Hello World" }
 *
 * val client = TestClient(app)
 *
 * // Hello world
 * val response = client.get("/hello").send()
 * assert(response.status == 200)
 * assert(response.text() == "Hello World")
 *
 * // With headers
 * val response = client.get("/api/users")
 *     .query("page", "1")
 *     .header("Authorization", "Bearer token")
 *     .send()
 *
 * // With cookies
 * val response = client.post("/login")
 *     .cookie("session", "abc123")
 *     .json(mapOf("username" to "alice"))
 *     .send()
 *
 * // File upload
 * val response = client.post("/upload")
 *     .file("file", "test.txt", "Hello".toByteArray(), "text/plain")
 *     .send()
 * ```
 */

class TestClient(private val app: Colleen) {
    fun get(path: String) = RequestBuilder("GET", path, app)
    fun post(path: String) = RequestBuilder("POST", path, app)
    fun put(path: String) = RequestBuilder("PUT", path, app)
    fun delete(path: String) = RequestBuilder("DELETE", path, app)
    fun patch(path: String) = RequestBuilder("PATCH", path, app)
    fun head(path: String) = RequestBuilder("HEAD", path, app)
    fun options(path: String) = RequestBuilder("OPTIONS", path, app)

    class RequestBuilder(
        private val method: String,
        private val path: String,
        private val app: Colleen
    ) {
        private val jsonMapper = app.config.jsonMapper
        private val headers = Headers()
        private var body: ByteArray? = null
        private val queryParams = mutableMapOf<String, MutableList<String>>()
        private val cookies = mutableMapOf<String, String>()
        private val multipart = mutableListOf<Part>()
        private var multipartBoundary: String? = null
        private var debugMode: Boolean = false

        /**
         * Set an HTTP request header.
         */
        fun header(key: String, value: String): RequestBuilder {
            headers[key] = value
            return this
        }

        /**
         * Add a query parameter.
         */
        fun query(key: String, value: String): RequestBuilder {
            queryParams.getOrPut(key) { mutableListOf() }.add(value)
            return this
        }

        /**
         * Add a cookie.
         */
        fun cookie(name: String, value: String): RequestBuilder {
            cookies[name] = value
            return this
        }

        /**
         * Set the request body as a UTF-8 string.
         */
        fun body(content: String): RequestBuilder {
            body = content.toByteArray(Charsets.UTF_8)
            return this
        }

        /**
         * Set the request body as raw bytes.
         */
        fun body(content: ByteArray): RequestBuilder {
            body = content
            return this
        }

        /**
         * Set a JSON request body.
         */
        fun json(data: Any): RequestBuilder {
            body = jsonMapper.toJsonString(data).encodeToByteArray()
            header("Content-Type", "application/json; charset=utf-8")
            return this
        }

        /**
         * Set application/x-www-form-urlencoded form data.
         */
        fun form(data: Map<String, String>): RequestBuilder {
            val formData = data.toList().joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v)}"
            }
            body = formData.toByteArray(Charsets.UTF_8)
            header("Content-Type", "application/x-www-form-urlencoded")
            return this
        }

        /**
         * Set multipart/form-data parts.
         */
        fun multipart(vararg parts: Part): RequestBuilder {
            multipart.addAll(parts)
            return this
        }

        /**
         * Add a form field part to multipart (convenience method).
         */
        fun field(name: String, value: String): RequestBuilder {
            multipart(FormField(name, value))
            return this
        }

        /**
         * Add a file part to multipart (convenience method).
         */
        @JvmOverloads
        fun file(
            name: String,
            filename: String,
            content: ByteArray,
            contentType: String = "application/octet-stream"
        ): RequestBuilder {
            return multipart(FilePart(name, filename, contentType, content.size.toLong(), content.inputStream()))
        }

        /**
         * Add a file part to multipart (read from file).
         */
        @JvmOverloads
        fun file(name: String, file: File, contentType: String = "application/octet-stream"): RequestBuilder {
            return file(name, file.name, file.readBytes(), contentType)
        }

        /**
         * Send the request and return a test response.
         */
        fun send(): TestResponse {
            val multipartSupplierData: List<Part>? = if (multipart.isNotEmpty()) {
                // NOTE:
                // Multipart body construction consumes the original InputStreams.
                // Unconsumed copies are created for multipartSupplier, which is
                // intended for internal server-side multipart handling.
                val (multipartBody, unconsumedParts) = buildMultipartBody()
                body = multipartBody
                header("Content-Type", "multipart/form-data; boundary=$multipartBoundary")
                unconsumedParts
            } else {
                null
            }

            // Build query string
            val queryString = toQueryString(queryParams)

            // Build Cookie header
            if (cookies.isNotEmpty()) {
                val cookieHeader = cookies.entries.joinToString("; ") { (name, value) ->
                    "$name=$value"
                }
                headers["Cookie"] = cookieHeader
            }

            // Set Content-Length if not explicitly provided
            if (body != null && !headers.has("Content-Length")) {
                headers["Content-Length"] = body!!.size.toString()
            }

            val request = Request(
                method = method,
                path = UrlPath.normalize(path),
                queryString = queryString,
                headers = headers,
                stream = body?.let { ByteArrayInputStream(it) },
                multipartSupplier = if (multipartSupplierData != null) {
                    { multipartSupplierData }
                } else {
                    { emptyList() }
                }
            )

            val event = Event.RequestReceived(request)
            app.eventBus.emit(event)

            logRequest()

            val ctx = Context(event.request, app = app)
            val duration = measureTime { app.handleRequest(ctx) }

            app.eventBus.emit(Event.ResponseReady(ctx, duration))

            return TestResponse(ctx.response, app)
        }

        /**
         * Enable or disable debug logging.
         */
        fun debug(enabled: Boolean = true): RequestBuilder {
            this.debugMode = enabled
            return this
        }

        private fun logRequest() {
            if (debugMode) {
                println("=== REQUEST ===")
                val queryString = toQueryString(queryParams)
                if (queryString.isBlank()) {
                    println("$method $path")
                } else {
                    println("$method $path?$queryString")
                }
                headers.forEach { k, v -> println("$k: $v") }
                body?.let { println("Body: ${it.toString(Charsets.UTF_8)}") }
            }
        }

        /**
         * Convenience operator for requests that typically have no request body
         * (e.g. GET / DELETE).
         */
        operator fun invoke(): TestResponse = send()

        private fun urlEncode(s: String): String {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        }

        private fun toQueryString(queryParams: Map<String, MutableList<String>>): String {
            return if (queryParams.isEmpty()) {
                ""
            } else {
                queryParams.flatMap { (key, values) ->
                    values.map { value -> "${urlEncode(key)}=${urlEncode(value)}" }
                }.joinToString("&")
            }
        }

        /**
         * Build a multipart/form-data request body.
         *
         * Returns:
         * - `ByteArray`: the fully constructed multipart body
         * - `List<Part>`: unconsumed part copies for multipartSupplier
         */
        private fun buildMultipartBody(): Pair<ByteArray, List<Part>> {
            if (multipartBoundary == null) {
                multipartBoundary = "----TestBoundary${UUID.randomUUID()}"
            }

            val output = ByteArrayOutputStream()
            val unconsumedParts = mutableListOf<Part>()

            multipart.forEach { part ->
                output.write("--$multipartBoundary\r\n".toByteArray())

                when (part) {
                    is FormField -> {
                        output.write("Content-Disposition: form-data; name=\"${part.name}\"\r\n".toByteArray())
                        output.write("\r\n".toByteArray())
                        output.write(part.value.toByteArray(Charsets.UTF_8))
                        output.write("\r\n".toByteArray())

                        // FormField is a data class and can be safely copied
                        unconsumedParts.add(part.copy())
                    }

                    is FilePart -> {
                        val filename = part.filename
                        output.write("Content-Disposition: form-data; name=\"${part.name}\"; filename=\"$filename\"\r\n".toByteArray())
                        output.write("Content-Type: ${part.contentType}\r\n".toByteArray())
                        output.write("\r\n".toByteArray())

                        // Consume the original InputStream to build the multipart body
                        val fileBytes = part.inputStream.use { it.readAllBytes() }

                        output.write(fileBytes)
                        output.write("\r\n".toByteArray())

                        // Create a new, unconsumed InputStream for multipartSupplier
                        unconsumedParts.add(
                            FilePart(
                                name = part.name,
                                filename = part.filename,
                                contentType = part.contentType,
                                size = fileBytes.size.toLong(),
                                inputStream = fileBytes.inputStream()
                            )
                        )
                    }
                }
            }

            output.write("--$multipartBoundary--\r\n".toByteArray())

            return Pair(output.toByteArray(), unconsumedParts)
        }
    }

    /**
     * Test response wrapper providing convenient accessors and assertions.
     */
    class TestResponse(val response: Response, app: Colleen) {
        val jsonMapper = app.config.jsonMapper
        val status: Int get() = response.status
        val headers: Headers get() = response.headers

        /**
         * Get a response header value.
         */
        fun header(key: String): String? = headers[key]

        /**
         * Get all values for a response header.
         */
        fun headers(key: String): List<String> = headers.getAll(key)

        /**
         * Read the response body as text.
         */
        fun text(): String? {
            return when (val body = response.body) {
                is ResponseBody.Empty -> null
                is ResponseBody.Text -> body.value
                is ResponseBody.Json -> when (val value = body.value) {
                    null -> "null"
                    else -> jsonMapper.toJsonString(value)
                }

                is ResponseBody.Bytes -> body.value.toString(Charsets.UTF_8)
                is ResponseBody.Stream -> body.input.readAllBytes().toString(Charsets.UTF_8)
                is ResponseBody.Sse -> error("Cannot read SSE response as text in TestResponse")
                else -> error("Unsupported response body type: ${body::class}")
            }
        }

        /**
         * Read the response body as a JSON object.
         */
        inline fun <reified T : Any> json(): T? {
            return when (val body = response.body) {
                is ResponseBody.Stream -> {
                    jsonMapper.fromJsonString<T>(body.input.readAllBytes().toString(UTF_8))
                }

                is ResponseBody.Json -> {
                    body.value as T?
                }

                is ResponseBody.Text -> {
                    jsonMapper.fromJsonString<T>(body.value)
                }

                is ResponseBody.Bytes -> {
                    jsonMapper.fromJsonString<T>(body.value.toString(UTF_8))
                }

                else -> null
            }
        }

        /**
         * Read the response body as raw bytes.
         */
        fun bytes(): ByteArray? {
            return when (val body = response.body) {
                is ResponseBody.Empty -> null
                is ResponseBody.Bytes -> body.value
                is ResponseBody.Text -> body.value.toByteArray(Charsets.UTF_8)
                is ResponseBody.Json -> when (val value = body.value) {
                    null -> "null".encodeToByteArray()
                    else -> jsonMapper.toJsonString(value).encodeToByteArray()
                }

                is ResponseBody.Stream -> body.input.readAllBytes()
                is ResponseBody.Sse -> error("Cannot read SSE response as bytes in TestResponse")
                else -> error("Unsupported response body type: ${body::class}")
            }
        }

        /**
         * Get the response body as an InputStream.
         */
        fun stream(): InputStream? {
            return when (val body = response.body) {
                is ResponseBody.Stream -> body.input
                else -> bytes()?.inputStream()
            }
        }

        // ========================================================================
        // Assertion helpers
        // ========================================================================

        fun expect(block: TestResponse.() -> Unit): TestResponse {
            this.block()
            return this
        }

        private fun fail(message: String): Nothing = throw AssertionError(message)


        /**
         * Assert HTTP status code.
         */
        fun assertStatus(expected: Int): TestResponse {
            if (status != expected) {
                fail("Expected status $expected but got $status")
            }
            return this
        }

        /**
         * Assert response body contains the given substring.
         */
        fun assertBodyContains(substring: String): TestResponse {
            val bodyText = text()
            if (bodyText == null || substring !in bodyText) {
                fail("Expected body to contain '$substring' but got: $bodyText")
            }
            return this
        }

        /**
         * Assert response body matches the expected file content.
         */
        fun assertFileContent(expected: ByteArray): TestResponse {
            val actual = bytes()
            if (!actual.contentEquals(expected)) {
                fail("File content mismatch")
            }
            return this
        }

        /**
         * Assert response body equals the given text.
         */
        fun assertBodyEquals(expected: String): TestResponse {
            val bodyText = text()
            if (bodyText != expected) {
                fail("Expected body '$expected' but got: $bodyText")
            }
            return this
        }

        /**
         * Assert a response header exists.
         */
        fun assertHeader(key: String): TestResponse {
            if (!headers.has(key)) {
                fail("Expected header '$key' to exist")
            }
            return this
        }

        /**
         * Assert a response header has the expected value.
         */
        fun assertHeader(key: String, expected: String): TestResponse {
            val actual = header(key)
            if (actual != expected) {
                fail("Expected header '$key' to be '$expected' but got: $actual")
            }
            return this
        }

        /**
         * Assert a successful (2xx) response.
         */
        fun assertSuccess(): TestResponse {
            if (status !in 200..299) {
                fail("Expected success status (2xx) but got $status")
            }
            return this
        }

        /**
         * Assert a client error (4xx) response.
         */
        fun assertClientError(): TestResponse {
            if (status !in 400..499) {
                fail("Expected client error status (4xx) but got $status")
            }
            return this
        }

        /**
         * Assert a server error (5xx) response.
         */
        fun assertServerError(): TestResponse {
            if (status !in 500..599) {
                fail("Expected server error status (5xx) but got $status")
            }
            return this
        }

        /**
         * Assert Content-Type header.
         */
        fun assertContentType(expected: String): TestResponse {
            val actual = header("Content-Type")
            if (actual?.startsWith(expected) != true) {
                fail("Expected Content-Type '$expected' but got: $actual")
            }
            return this
        }

        /**
         * Print the response for debugging purposes.
         */
        fun dump(): TestResponse {
            println("Status: $status")
            println("Headers:")
            headers.forEach { key, values ->
                values.forEach { value ->
                    println("  $key: $value")
                }
            }
            text()?.let {
                println("Body:")
                println(it)
            }
            return this
        }
    }
}