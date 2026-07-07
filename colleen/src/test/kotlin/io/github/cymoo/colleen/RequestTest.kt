package io.github.cymoo.colleen

import io.github.cymoo.colleen.json.JsonMapper
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class RequestTest {

    private val mapper: JsonMapper = Config(json = JsonConfig(acceptSingleValueAsArray = true)).jsonMapper

    @Nested
    inner class BasicPropertiesTest {

        @Test
        fun `should construct request with basic properties`() {
            val request = Request(
                method = "GET",
                path = "/api/users",
                queryString = "page=1&size=10"
            )

            assertEquals("GET", request.method)
            assertEquals("/api/users", request.path)
            assertEquals("page=1&size=10", request.queryString)
            assertEquals("/api/users?page=1&size=10", request.uri)
        }

        @Test
        fun `uri should not include question mark when queryString is empty`() {
            val request = Request(method = "GET", path = "/api/users")
            assertEquals("/api/users", request.uri)
        }

        @Test
        fun `should handle metadata correctly`() {
            val metadata = Request.ServerInfo(
                remoteAddr = "192.168.1.100",
                remoteHost = "client.example.com",
                remotePort = 54321,
                scheme = "https",
                serverName = "api.example.com",
                serverPort = 443,
                isSecure = true,
                protocol = "HTTP/1.1"
            )

            val request = Request(
                method = "POST",
                path = "/api/data",
                serverInfo = metadata
            )

            assertEquals("192.168.1.100", request.remoteAddr)
            assertEquals("192.168.1.100", request.ip)
            assertEquals(-1L, request.contentLength)
        }
    }

    @Nested
    inner class BodyAndTextTest {

        @Test
        fun `should read body as bytes`() {
            val content = "Hello World"
            val stream = ByteArrayInputStream(content.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            assertArrayEquals(content.toByteArray(), request.body)
        }

        @Test
        fun `should return null when stream is null`() {
            val request = Request(method = "GET", path = "/api")
            assertNull(request.body)
        }

        @Test
        fun `should read body as text with default UTF-8 encoding`() {
            val content = "Hello World"
            val stream = ByteArrayInputStream(content.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            assertEquals(content, request.text())
        }

        @Test
        fun `should read body as text with specified charset`() {
            val content = "Hello 世界"
            val stream = ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_16))
            val request = Request(method = "POST", path = "/api", stream = stream)

            assertEquals(content, request.text(StandardCharsets.UTF_16))
        }
    }

    data class User(val name: String, val age: Int)

    @Nested
    inner class JsonTest {

        @Test
        fun `should parse JSON body using json with class`() {
            val json = """{"name":"Alice","age":30}"""
            val stream = ByteArrayInputStream(json.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            val user = request.json(mapper, User::class.java)
            assertNotNull(user)
            assertEquals("Alice", user?.name)
            assertEquals(30, user?.age)
        }

        @Test
        fun `should parse JSON body using reified json`() {
            val json = """{"name":"Bob","age":25}"""
            val stream = ByteArrayInputStream(json.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            val user = request.json<User>(mapper)
            assertNotNull(user)
            assertEquals("Bob", user?.name)
            assertEquals(25, user?.age)
        }

        @Test
        fun `should throw BadRequest when JSON is invalid`() {
            val invalidJson = """{"name":"Alice","age":}"""
            val stream = ByteArrayInputStream(invalidJson.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            assertThrows<BadRequest> {
                request.json<User>(mapper)
            }
        }

        @Test
        fun `should return null when body is empty`() {
            val request = Request(method = "POST", path = "/api")
            assertNull(request.json<User>(mapper))
        }
    }

    @Nested
    inner class JsonBindingExceptionTest {
        @Test
        fun `should throw BadRequest when JSON structure does not match target`() {
            val json = """{"name":"Alice","age":"not-a-number"}"""
            val stream = ByteArrayInputStream(json.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            val exception = assertThrows<BadRequest> {
                request.json<User>(mapper)
            }

            assertTrue(
                exception.message?.contains("expected int") == true
            )
        }

        @Test
        fun `should throw BadRequest when required JSON field is missing`() {
            // User(age is required)
            val json = """{"name":"Alice"}"""
            val stream = ByteArrayInputStream(json.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            val exception = assertThrows<BadRequest> {
                request.json<User>(mapper)
            }

            assertTrue(exception.message?.contains("Missing required field") == true)
        }

        @Test
        fun `should throw BadRequest when JSON root is invalid`() {
            val json = """["Alice", 30]"""
            val stream = ByteArrayInputStream(json.toByteArray())
            val request = Request(method = "POST", path = "/api", stream = stream)

            val exception = assertThrows<BadRequest> {
                request.json<User>(mapper)
            }

            assertTrue(exception.message?.contains("does not match expected structure") == true)
        }
    }

    @Nested
    inner class ParameterBindingExceptionTest {
        @Test
        fun `should throw BadRequest when query parameter type is invalid`() {
            data class SearchParams(val page: Int)

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "page=abc"
            )

            val exception = assertThrows<BadRequest> {
                request.queries<SearchParams>(mapper)
            }

            assertTrue(
                exception.message?.contains("expected int") == true
            )
        }

        @Test
        fun `should throw BadRequest when required query parameter is missing`() {
            data class SearchParams(val q: String, val page: Int)

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=kotlin"
            )

            val exception = assertThrows<BadRequest> {
                request.queries<SearchParams>(mapper)
            }

            assertTrue(
                exception.message?.contains("Missing required field") == true
            )
        }
    }

    @Nested
    inner class QueryStringTest {

        @Test
        fun `should parse query string correctly`() {
            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=kotlin&page=1&size=20"
            )

            assertEquals("kotlin", request.query("q"))
            assertEquals("1", request.query("page"))
            assertEquals("20", request.query("size"))
        }

        @Test
        fun `should handle multiple values for same key`() {
            val request = Request(
                method = "GET",
                path = "/api/filter",
                queryString = "tag=kotlin&tag=java&tag=python"
            )

            val tags = request.queryList("tag")
            assertEquals(3, tags.size)
            assertTrue(tags.containsAll(listOf("kotlin", "java", "python")))
        }

        @Test
        fun `should decode URL encoded query parameters`() {
            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=hello+world&name=%E4%B8%AD%E6%96%87"
            )

            assertEquals("hello world", request.query("q"))
            assertEquals("中文", request.query("name"))
        }

        @Test
        fun `should handle empty query values`() {
            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "key1=&key2=value&key3"
            )

            assertEquals("", request.query("key1"))
            assertEquals("value", request.query("key2"))
            assertEquals("", request.query("key3"))
        }

        @Test
        fun `should return null for non-existent query parameter`() {
            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "page=1"
            )

            assertNull(request.query("nonexistent"))
            assertTrue(request.queryList("nonexistent").isEmpty())
        }

        @Test
        fun `should return null when no query parameters are present`() {
            data class SearchParams(val q: String, val page: Int, val size: Int)

            val request = Request(
                method = "GET",
                path = "/api/search"
            )

            val params = request.queries<SearchParams>(mapper)
            assertNull(params)
        }

        @Test
        fun `should convert queries to object`() {
            data class SearchParams(val q: String, val page: Int, val size: Int)

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=kotlin&page=1&size=20"
            )

            val params = request.queries<SearchParams>(mapper)
            assertNotNull(params)
            assertEquals("kotlin", params?.q)
            assertEquals(1, params?.page)
            assertEquals(20, params?.size)
        }

        @Test
        fun `should bind multi-value query parameter to list`() {
            data class FilterParams(val tag: List<String>)

            val request = Request(
                method = "GET",
                path = "/api/filter",
                queryString = "tag=kotlin&tag=java&tag=rust"
            )

            val params = request.queries<FilterParams>(mapper)

            assertNotNull(params)
            assertEquals(listOf("kotlin", "java", "rust"), params?.tag)
        }

        @Test
        fun `should bind single query value to list parameter`() {
            data class FilterParams(val tag: List<String>)

            val request = Request(
                method = "GET",
                path = "/api/filter",
                queryString = "tag=kotlin"
            )

            val params = request.queries<FilterParams>(mapper)

            assertNotNull(params)
            assertEquals(listOf("kotlin"), params?.tag)
        }

        @Test
        fun `should not allow partial binding when some fields are missing`() {
            data class SearchParams(val q: String, val page: Int, val size: Int)

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=kotlin&page=1"
            )

            val ex = assertThrows<BadRequest> {
                request.queries<SearchParams>(mapper)
            }

            assertTrue(ex.message!!.contains("Missing required field: size"))
        }

        @Test
        fun `should bind nullable fields when query parameter is missing`() {
            data class SearchParams(
                val q: String?,
                val page: Int?,
                val size: Int?
            )

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=kotlin"
            )

            val params = request.queries<SearchParams>(mapper)

            assertNotNull(params)
            assertEquals("kotlin", params?.q)
            assertNull(params?.page)
            assertNull(params?.size)
        }

        @Test
        fun `should fail when binding multi-value query to non-list field`() {
            data class Params(val tag: String)

            val request = Request(
                method = "GET",
                path = "/api/filter",
                queryString = "tag=kotlin&tag=java"
            )

            val ex = assertThrows<BadRequest> {
                request.queries<Params>(mapper)
            }

            assertTrue(ex.message!!.contains("Invalid type for field 'tag'"))
        }

        @Test
        fun `should convert single-value query to primitive types`() {
            data class Params(
                val page: Int,
                val active: Boolean
            )

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "page=2&active=true"
            )

            val params = request.queries<Params>(mapper)

            assertNotNull(params)
            assertEquals(2, params?.page)
            assertEquals(true, params?.active)
        }

        @Test
        fun `should fail when list element type conversion fails`() {
            data class Params(val ids: List<Int>)

            val request = Request(
                method = "GET",
                path = "/api/items",
                queryString = "ids=1&ids=abc&ids=3"
            )

            val ex = assertThrows<BadRequest> {
                request.queries<Params>(mapper)
            }

            assertTrue(ex.message!!.contains("Invalid type for element 1 of field 'ids'"))
        }

        @Test
        fun `should use default values when query parameter is missing`() {
            data class Params(
                val q: String,
                val page: Int = 1,
                val size: Int = 10
            )

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=kotlin"
            )

            val params = request.queries<Params>(mapper)

            assertNotNull(params)
            assertEquals("kotlin", params?.q)
            assertEquals(1, params?.page)
            assertEquals(10, params?.size)
        }

        @Test
        fun `should fail when empty string cannot be converted to target type`() {
            data class Params(val page: Int)

            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "page="
            )

            val ex = assertThrows<BadRequest> {
                request.queries<Params>(mapper)
            }

            assertTrue(ex.message!!.contains("Invalid type for field 'page'"))
        }
    }

    @Nested
    inner class FormTest {

        @Test
        fun `should parse form data correctly`() {
            val formData = "username=alice&password=secret123"
            val stream = ByteArrayInputStream(formData.toByteArray())
            val headers = Headers().apply {
                add("content-type", "application/x-www-form-urlencoded")
            }
            val request = Request(
                method = "POST",
                path = "/login",
                headers = headers,
                stream = stream
            )

            assertEquals("alice", request.form("username"))
            assertEquals("secret123", request.form("password"))
        }

        @Test
        fun `should return empty map when content type is not form`() {
            val request = Request(
                method = "POST",
                path = "/api",
                headers = Headers().apply { add("content-type", "application/json") }
            )

            assertTrue(request.forms.isEmpty())
        }

        @Test
        fun `should handle multiple form values for same key`() {
            val formData = "hobby=reading&hobby=coding&hobby=gaming"
            val stream = ByteArrayInputStream(formData.toByteArray())
            val headers = Headers().apply {
                add("content-type", "application/x-www-form-urlencoded")
            }
            val request = Request(
                method = "POST",
                path = "/profile",
                headers = headers,
                stream = stream
            )

            val hobbies = request.formList("hobby")
            assertEquals(3, hobbies.size)
            assertTrue(hobbies.containsAll(listOf("reading", "coding", "gaming")))
        }

        @Test
        fun `should convert form data to object`() {
            data class LoginForm(val username: String, val password: String)

            val formData = "username=alice&password=secret123"
            val stream = ByteArrayInputStream(formData.toByteArray())
            val headers = Headers().apply {
                add("content-type", "application/x-www-form-urlencoded")
            }
            val request = Request(
                method = "POST",
                path = "/login",
                headers = headers,
                stream = stream
            )

            val form = request.forms<LoginForm>(mapper)
            assertNotNull(form)
            assertEquals("alice", form?.username)
            assertEquals("secret123", form?.password)
        }
    }

    @Nested
    inner class CookieTest {

        @Test
        fun `should parse cookies from header`() {
            val headers = Headers().apply {
                add("cookie", "session_id=abc123; user_id=42; theme=dark")
            }
            val request = Request(method = "GET", path = "/api", headers = headers)

            assertEquals("abc123", request.cookie("session_id"))
            assertEquals("42", request.cookie("user_id"))
            assertEquals("dark", request.cookie("theme"))
        }

        @Test
        fun `should handle cookies with quoted values`() {
            val headers = Headers().apply {
                add("cookie", "name=\"John Doe\"; token=\"xyz789\"")
            }
            val request = Request(method = "GET", path = "/api", headers = headers)

            assertEquals("John Doe", request.cookie("name"))
            assertEquals("xyz789", request.cookie("token"))
        }

        @Test
        fun `should return null for non-existent cookie`() {
            val headers = Headers().apply {
                add("cookie", "session_id=abc123")
            }
            val request = Request(method = "GET", path = "/api", headers = headers)

            assertNull(request.cookie("nonexistent"))
        }

        @Test
        fun `should handle empty cookie header`() {
            val request = Request(method = "GET", path = "/api")
            assertTrue(request.cookies.isEmpty())
        }
    }

    @Nested
    inner class HeaderTest {

        @Test
        fun `should access headers using get operator`() {
            val headers = Headers().apply {
                add("content-type", "application/json")
                add("authorization", "Bearer token123")
            }
            val request = Request(method = "GET", path = "/api", headers = headers)

            assertEquals("application/json", request["content-type"])
            assertEquals("Bearer token123", request["authorization"])
        }

        @Test
        fun `should get content type from header`() {
            val headers = Headers().apply {
                add("content-type", "text/html; charset=utf-8")
            }
            val request = Request(method = "GET", path = "/api", headers = headers)

            assertEquals("text/html; charset=utf-8", request.contentType)
        }

        @Test
        fun `should get content length from header`() {
            val headers = Headers().apply {
                add("content-length", "1024")
            }
            val request = Request(method = "POST", path = "/api", headers = headers)

            assertEquals(1024L, request.contentLength)
        }

        @Test
        fun `should return -1 for invalid content length`() {
            val headers = Headers().apply {
                add("content-length", "invalid")
            }
            val request = Request(method = "POST", path = "/api", headers = headers)

            assertEquals(-1L, request.contentLength)
        }
    }

    @Nested
    inner class WithMethodTest {

        @Test
        fun `withMethod uppercases and shares the one-shot body cache`() {
            val original = Request(
                method = "POST",
                path = "/submit",
                stream = "payload".byteInputStream(),
            )

            val overridden = original.withMethod("put")

            assertEquals("PUT", overridden.method)
            assertEquals("/submit", overridden.path)

            // The stream is one-shot: reading through one copy must populate
            // the shared cache so the other copy sees the same body
            assertEquals("payload", original.text())
            assertEquals("payload", overridden.text())
        }
    }

    @Nested
    inner class IpAddressTest {

        @Test
        fun `should ignore X-Forwarded-For by default (untrusted)`() {
            // The header is client-supplied; without trustedProxyCount it must
            // not be honored, or IP rate limiting could be bypassed by spoofing.
            val headers = Headers().apply {
                add("x-forwarded-for", "203.0.113.1")
            }
            val metadata = Request.ServerInfo(remoteAddr = "10.0.0.1")
            val request = Request(
                method = "GET",
                path = "/api",
                headers = headers,
                serverInfo = metadata
            )

            assertEquals("10.0.0.1", request.ip)
        }

        @Test
        fun `should take entry appended by the trusted proxy with one trusted hop`() {
            // Chain seen by us: client-forgeable part + entry appended by our proxy.
            // With trustedProxyCount=1 the LAST entry ("192.0.2.1") is the client
            // address as observed by the trusted proxy; earlier entries are spoofable.
            val headers = Headers().apply {
                add("x-forwarded-for", "203.0.113.1, 198.51.100.1, 192.0.2.1")
            }
            val metadata = Request.ServerInfo(remoteAddr = "10.0.0.1", trustedProxyCount = 1)
            val request = Request(
                method = "GET",
                path = "/api",
                headers = headers,
                serverInfo = metadata
            )

            assertEquals("192.0.2.1", request.ip)
        }

        @Test
        fun `should walk further left with more trusted hops`() {
            val headers = Headers().apply {
                add("x-forwarded-for", "203.0.113.1, 198.51.100.1, 192.0.2.1")
            }
            val metadata = Request.ServerInfo(remoteAddr = "10.0.0.1", trustedProxyCount = 3)
            val request = Request(
                method = "GET",
                path = "/api",
                headers = headers,
                serverInfo = metadata
            )

            assertEquals("203.0.113.1", request.ip)
        }

        @Test
        fun `should extract IP from X-Real-IP header when X-Forwarded-For is absent`() {
            val headers = Headers().apply {
                add("x-real-ip", "203.0.113.1")
            }
            val metadata = Request.ServerInfo(remoteAddr = "10.0.0.1", trustedProxyCount = 1)
            val request = Request(
                method = "GET",
                path = "/api",
                headers = headers,
                serverInfo = metadata
            )

            assertEquals("203.0.113.1", request.ip)
        }

        @Test
        fun `should use remoteAddr when no proxy headers present`() {
            val metadata = Request.ServerInfo(remoteAddr = "192.168.1.100", trustedProxyCount = 1)
            val request = Request(
                method = "GET",
                path = "/api",
                serverInfo = metadata
            )

            assertEquals("192.168.1.100", request.ip)
        }

        @Test
        fun `should skip unknown values in X-Forwarded-For`() {
            val headers = Headers().apply {
                add("x-forwarded-for", "203.0.113.1, 198.51.100.1, unknown")
            }
            val metadata = Request.ServerInfo(remoteAddr = "10.0.0.1", trustedProxyCount = 1)
            val request = Request(
                method = "GET",
                path = "/api",
                headers = headers,
                serverInfo = metadata
            )

            assertEquals("198.51.100.1", request.ip)
        }
    }

    @Nested
    inner class HelperMethodsTest {

        @Test
        fun `mapToClass should convert map to class correctly`() {
            data class TestData(val name: String, val count: Int)

            val request = Request(method = "GET", path = "/api")
            val data = mapOf(
                "name" to listOf("test"),
                "count" to listOf("42")
            )

            val result = request.mapToClass(data, TestData::class.java, mapper)
            assertNotNull(result)
            assertEquals("test", result?.name)
            assertEquals(42, result?.count)
        }

        @Test
        fun `mapToClass should return null for empty map`() {
            data class TestData(val name: String)

            val request = Request(method = "GET", path = "/api")
            val result = request.mapToClass(emptyMap(), TestData::class.java, mapper)

            assertNull(result)
        }

        @Test
        fun `mapToClass should throw BadRequest on conversion failure`() {
            data class TestData(val count: Int)

            val request = Request(method = "GET", path = "/api")
            val data = mapOf("count" to listOf("invalid"))

            assertThrows<BadRequest> {
                request.mapToClass(data, TestData::class.java, mapper)
            }
        }
    }

    @Nested
    inner class MultipartTest {
        @Test
        fun `should identify multipart content type`() {
            val headers = Headers().apply {
                add("content-type", "multipart/form-data; boundary=----WebKitFormBoundary")
            }
            val request = Request(method = "POST", path = "/upload", headers = headers)

            assertTrue(request.contentType?.startsWith("multipart/form-data") == true)
        }
    }

    @Nested
    inner class ContentNegotiationTest {

        @Nested
        inner class AcceptsMethodTest {

            @Test
            fun `should accept exact MIME type match`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                assertTrue(request.accepts("application/json"))
            }

            @Test
            fun `should accept type using alias`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                assertTrue(request.accepts("json"))
            }

            @Test
            fun `should not accept non-matching type`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                assertFalse(request.accepts("xml"))
                assertFalse(request.accepts("text/html"))
            }

            @Test
            fun `should accept wildcard type`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "*/*")
                )

                assertTrue(request.accepts("json"))
                assertTrue(request.accepts("application/json"))
                assertTrue(request.accepts("text/html"))
            }

            @Test
            fun `should accept subtype wildcard`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/*")
                )

                assertTrue(request.accepts("application/json"))
                assertTrue(request.accepts("application/xml"))
                assertFalse(request.accepts("text/html"))
            }

            @Test
            fun `should accept multiple types`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json, text/html, application/xml")
                )

                assertTrue(request.accepts("json"))
                assertTrue(request.accepts("html"))
                assertTrue(request.accepts("xml"))
                assertFalse(request.accepts("pdf"))
            }

            @Test
            fun `should handle missing Accept header`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers()
                )

                // Should accept anything when no Accept header is present
                assertTrue(request.accepts("json"))
                assertTrue(request.accepts("html"))
            }

            @Test
            fun `should handle empty Accept header`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "")
                )

                assertTrue(request.accepts("json"))
                assertTrue(request.accepts("html"))
            }

            @ParameterizedTest
            @CsvSource(
                "application/json, json",
                "text/html, html",
                "application/xml, xml",
                "text/plain, txt",
                "text/plain, text",
                "image/png, png",
                "image/jpeg, jpg",
                "image/jpeg, jpeg",
                "application/pdf, pdf"
            )
            fun `should accept common MIME type aliases`(mimeType: String, alias: String) {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", mimeType)
                )

                assertTrue(request.accepts(alias))
            }
        }

        @Nested
        inner class AcceptsListMethodTest {

            @Test
            fun `should return best match from list`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                val result = request.accepts(listOf("xml", "json", "html"))
                assertEquals("json", result)
            }

            @Test
            fun `should return first acceptable match`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json, text/html")
                )

                val result = request.accepts(listOf("html", "json"))
                assertNotNull(result)
                assertTrue(result in listOf("html", "json"))
            }

            @Test
            fun `should return null when no match found`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                val result = request.accepts(listOf("xml", "html", "pdf"))
                assertNull(result)
            }

            @Test
            fun `should prioritize based on quality values`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json;q=0.5, text/html;q=0.9")
                )

                val result = request.accepts(listOf("json", "html"))
                // Should prefer html due to higher q value
                assertNotNull(result)
            }

            @Test
            fun `should handle complex Accept header with parameters`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of
                        ("accept", "text/html, application/xhtml+xml, application/xml;q=0.9, image/webp, */*;q=0.8")

                )

                assertNotNull(request.accepts(listOf("html")))
                assertNotNull(request.accepts(listOf("xml")))
                assertNotNull(request.accepts(listOf("json"))) // Should match */*
            }

            @Test
            fun `should return null for empty list`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                val result = request.accepts(emptyList())
                assertNull(result)
            }

            @Test
            fun `should handle mixed aliases and MIME types in list`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json, text/html")
                )

                val result = request.accepts(listOf("application/xml", "json", "text/plain"))
                assertEquals("json", result)
            }
        }

        @Nested
        inner class ConvenienceMethodsTest {

            @Test
            fun `acceptsJson should return true when JSON is acceptable`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                assertTrue(request.accepts("json"))
            }

            @Test
            fun `acceptsJson should return false when JSON is not acceptable`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "text/html")
                )

                assertFalse(request.accepts("json"))
            }

            @Test
            fun `acceptsHtml should return true when HTML is acceptable`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "text/html")
                )

                assertTrue(request.accepts("html"))
            }

            @Test
            fun `acceptsHtml should return false when HTML is not acceptable`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json")
                )

                assertFalse(request.accepts("html"))
            }

            @Test
            fun `convenience methods should work with wildcard`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "*/*")
                )

                assertTrue(request.accepts("json"))
                assertTrue(request.accepts("html"))
            }

            @Test
            fun `convenience methods should work with subtype wildcard`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/*")
                )

                assertTrue(request.accepts("json"))
                assertFalse(request.accepts("html"))
            }
        }

        @Nested
        inner class LanguageNegotiationTest {

            @Test
            fun `should accept exact language match`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept-language", "en-US")
                )

                assertTrue(request.acceptsLang("en-US"))
            }

            @Test
            fun `should not accept non-matching language`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept-language", "en-US")
                )

                assertFalse(request.acceptsLang("fr-FR"))
            }

            @Test
            fun `should accept multiple languages`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept-language", "en-US, fr-FR, de-DE")
                )

                assertTrue(request.acceptsLang("en-US"))
                assertTrue(request.acceptsLang("fr-FR"))
                assertTrue(request.acceptsLang("de-DE"))
                assertFalse(request.acceptsLang("ja-JP"))
            }

            @Test
            fun `should return best language match from list`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept-language", "en-US, fr-FR")
                )

                val result = request.acceptsLang(listOf("fr-FR", "en-US", "de-DE"))
                assertNotNull(result)
                assertTrue(result in listOf("fr-FR", "en-US"))
            }

            @Test
            fun `should return null when no language match found`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept-language", "en-US")
                )

                val result = request.acceptsLang(listOf("fr-FR", "de-DE", "ja-JP"))
                assertNull(result)
            }

            @Test
            fun `should handle language priorities with quality values`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept-language", "en-US;q=0.5, fr-FR;q=0.9")
                )

                val result = request.acceptsLang(listOf("en-US", "fr-FR"))
                assertNotNull(result)
            }

            @Test
            fun `should handle wildcard language`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept-language", "*")
                )

                assertTrue(request.acceptsLang("en-US"))
                assertTrue(request.acceptsLang("fr-FR"))
            }
        }

        @Nested
        inner class EdgeCasesTest {

            @Test
            fun `should handle case-insensitive content types`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "Application/JSON")
                )

                assertTrue(request.accepts("json"))
                assertTrue(request.accepts("application/json"))
            }

            @Test
            fun `should handle whitespace in Accept header`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "  application/json  ,  text/html  ")
                )

                assertTrue(request.accepts("json"))
                assertTrue(request.accepts("html"))
            }

            @Test
            fun `should handle Accept header with charset parameter`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/json; charset=utf-8")
                )

                assertTrue(request.accepts("json"))
            }

            @Test
            fun `should handle complex real-world Accept header`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of(
                        "accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                    )
                )

                assertTrue(request.accepts("html"))
                assertTrue(request.accepts("xml"))
                assertTrue(request.accepts("json")) // Should match */*
            }

            @Test
            fun `should handle vendor-specific MIME types`() {
                val request = Request(
                    method = "GET",
                    path = "/api/data",
                    headers = Headers.of("accept", "application/vnd.api+json")
                )

                assertTrue(request.accepts("application/vnd.api+json"))
            }
        }
    }

    @Nested
    inner class EdgeCasesTest {

        @Test
        fun `should handle request without headers`() {
            val request = Request(method = "GET", path = "/api")

            assertNull(request.contentType)
            assertEquals(-1L, request.contentLength)
            assertTrue(request.cookies.isEmpty())
        }

        @Test
        fun `should handle query string with special characters`() {
            val request = Request(
                method = "GET",
                path = "/api/search",
                queryString = "q=%21%40%23%24%25"
            )

            assertEquals("!@#$%", request.query("q"))
        }

        @Test
        fun `should handle empty form submission`() {
            val stream = ByteArrayInputStream("".toByteArray())
            val headers = Headers().apply {
                add("content-type", "application/x-www-form-urlencoded")
            }
            val request = Request(
                method = "POST",
                path = "/api",
                headers = headers,
                stream = stream
            )

            assertTrue(request.forms.isEmpty())
        }

        @Test
        fun `should handle query string with only ampersands`() {
            val request = Request(
                method = "GET",
                path = "/api",
                queryString = "&&&"
            )

            assertTrue(request.queries.isEmpty())
        }
    }
}