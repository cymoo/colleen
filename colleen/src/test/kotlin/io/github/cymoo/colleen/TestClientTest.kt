package io.github.cymoo.colleen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TestClientTest {
    private lateinit var app: Colleen
    private lateinit var client: TestClient


    @BeforeEach
    fun setup() {
        app = Colleen()
        app.config.server.useVirtualThreads = false
        client = TestClient(app)
    }

    // ========================================================================
    // Basic HTTP Method Tests
    // ========================================================================

    @Test
    fun `should handle GET request`() {
        // Arrange
        app.get("/hello") { "Hello World" }

        // Act
        val response = client.get("/hello").send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("Hello World", response.text())
    }

    @Test
    fun `should handle POST request`() {
        // Arrange
        app.post("/echo") { ctx ->
            ctx.text()
        }

        // Act
        val response = client.post("/echo")
            .body("test content")
            .send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("test content", response.text())
    }

    @Test
    fun `should handle PUT request`() {
        // Arrange
        app.put("/update") { "Updated" }

        // Act
        val response = client.put("/update").send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("Updated", response.text())
    }

    @Test
    fun `should handle DELETE request`() {
        // Arrange
        app.delete("/remove") { "Deleted" }

        // Act
        val response = client.delete("/remove").send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("Deleted", response.text())
    }

    @Test
    fun `should handle PATCH request`() {
        // Arrange
        app.patch("/modify") { "Patched" }

        // Act
        val response = client.patch("/modify").send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("Patched", response.text())
    }

    @Test
    fun `should handle HEAD request`() {
        // Arrange
        app.head("/check") { ctx ->
            ctx.header("X-Custom", "value")
        }

        // Act
        val response = client.head("/check").send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("value", response.header("X-Custom"))
    }

    @Test
    fun `should handle OPTIONS request`() {
        // Arrange
        app.options("/cors") { ctx ->
            ctx.header("Allow", "GET, POST, OPTIONS")
        }

        // Act
        val response = client.options("/cors").send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("GET, POST, OPTIONS", response.header("Allow"))
    }

    // ========================================================================
    // Header Tests
    // ========================================================================

    @Test
    fun `should send custom headers`() {
        // Arrange
        app.get("/headers") { ctx ->
            mapOf(
                "authorization" to ctx.header("Authorization"),
                "custom" to ctx.header("X-Custom-Header")
            )
        }

        // Act
        val response = client.get("/headers")
            .header("Authorization", "Bearer token123")
            .header("X-Custom-Header", "custom-value")
            .send()

        // Assert
        val json = response.json<Map<String, String>>()
        assertEquals("Bearer token123", json?.get("authorization"))
        assertEquals("custom-value", json?.get("custom"))
    }

    @Test
    fun `should override headers with same key`() {
        // Arrange
        app.get("/override") { ctx ->
            ctx.header("X-Test")
        }

        // Act
        val response = client.get("/override")
            .header("X-Test", "first")
            .header("X-Test", "second")
            .send()

        // Assert
        assertEquals("second", response.text())
    }

    // ========================================================================
    // Query Parameter Tests
    // ========================================================================

    @Test
    fun `should send single query parameter`() {
        // Arrange
        app.get("/search") { ctx ->
            ctx.query("q")
        }

        // Act
        val response = client.get("/search")
            .query("q", "kotlin")
            .send()

        // Assert
        assertEquals("kotlin", response.text())
    }

    @Test
    fun `should send multiple query parameters`() {
        // Arrange
        app.get("/filter") { ctx ->
            mapOf(
                "category" to ctx.query("category"),
                "page" to ctx.query("page"),
                "limit" to ctx.query("limit")
            )
        }

        // Act
        val response = client.get("/filter")
            .query("category", "books")
            .query("page", "2")
            .query("limit", "10")
            .send()

        // Assert
        val json = response.json<Map<String, String>>()
        assertEquals("books", json?.get("category"))
        assertEquals("2", json?.get("page"))
        assertEquals("10", json?.get("limit"))
    }

    @Test
    fun `should handle duplicate query parameter keys`() {
        // Arrange
        app.get("/tags") { ctx ->
            ctx.request.queryList("tag")
        }

        // Act
        val response = client.get("/tags")
            .query("tag", "kotlin")
            .query("tag", "java")
            .query("tag", "scala")
            .send()

        // Assert
        val tags = response.json<List<String>>()
        assertEquals(3, tags?.size)
        assertEquals(tags?.contains("kotlin"), true)
        assertEquals(tags?.contains("java"), true)
        assertEquals(tags?.contains("scala"), true)
    }

    @Test
    fun `should URL encode query parameters`() {
        // Arrange
        app.get("/encoded") { ctx ->
            ctx.query("text")
        }

        // Act
        val response = client.get("/encoded")
            .query("text", "hello world")
            .send()

        // Assert
        assertEquals("hello world", response.text())
    }

    @Test
    fun `should handle special characters in query parameters`() {
        // Arrange
        app.get("/special") { ctx ->
            ctx.query("value")
        }

        // Act
        val response = client.get("/special")
            .query("value", "a+b=c&d")
            .send()

        // Assert
        assertEquals("a+b=c&d", response.text())
    }

    // ========================================================================
    // Cookie Tests
    // ========================================================================

    @Test
    fun `should send single cookie`() {
        // Arrange
        app.get("/cookie") { ctx ->
            ctx.request.cookie("session")
        }

        // Act
        val response = client.get("/cookie")
            .cookie("session", "abc123")
            .send()

        // Assert
        assertEquals("abc123", response.text())
    }

    @Test
    fun `should send multiple cookies`() {
        // Arrange
        app.get("/cookies") { ctx ->
            mapOf(
                "session" to ctx.request.cookie("session"),
                "user" to ctx.request.cookie("user"),
                "theme" to ctx.request.cookie("theme")
            )
        }

        // Act
        val response = client.get("/cookies")
            .cookie("session", "abc123")
            .cookie("user", "alice")
            .cookie("theme", "dark")
            .send()

        // Assert
        val json = response.json<Map<String, String>>()
        logger.info("json: $json")
        assertEquals("abc123", json?.get("session"))
        assertEquals("alice", json?.get("user"))
        assertEquals("dark", json?.get("theme"))
    }

    // ========================================================================
    // JSON Body Tests
    // ========================================================================

    @Test
    fun `should send JSON body with map`() {
        // Arrange
        app.post("/json") { ctx ->
            ctx.json<Map<String, Any>>()
        }

        // Act
        val requestData = mapOf("name" to "Alice", "age" to 30)
        val response = client.post("/json")
            .json(requestData)
            .send()

        // Assert
        val json = response.json<Map<String, Any>>()
        assertEquals("Alice", json?.get("name"))
        assertEquals(30, json?.get("age"))
    }

    @Test
    fun `should send JSON body with list`() {
        // Arrange
        app.post("/list") { ctx ->
            ctx.json<List<String>>()
        }

        // Act
        val requestData = listOf("apple", "banana", "orange")
        val response = client.post("/list")
            .json(requestData)
            .send()

        // Assert
        val json = response.json<List<String>>()
        assertEquals(3, json?.size)
        assertEquals(json?.contains("apple"), true)
    }

    @Test
    fun `should set correct content-type for JSON`() {
        // Arrange
        app.post("/content-type") { ctx ->
            ctx.request.contentType
        }

        // Act
        val response = client.post("/content-type")
            .json(mapOf("key" to "value"))
            .send()

        // Assert
        assertEquals("application/json; charset=utf-8", response.text())
    }

    // ========================================================================
    // Form Data Tests
    // ========================================================================

    @Test
    fun `should send URL encoded form data`() {
        // Arrange
        app.post("/form") { ctx ->
            mapOf(
                "username" to ctx.form("username"),
                "password" to ctx.form("password")
            )
        }

        // Act
        val response = client.post("/form")
            .form(mapOf("username" to "alice", "password" to "secret123"))
            .send()

        // Assert
        val json = response.json<Map<String, String>>()
        assertEquals("alice", json?.get("username"))
        assertEquals("secret123", json?.get("password"))
    }

    @Test
    fun `should URL encode form values`() {
        // Arrange
        app.post("/encoded-form") { ctx ->
            ctx.form("message")
        }

        // Act
        val response = client.post("/encoded-form")
            .form(mapOf("message" to "hello world & more"))
            .send()

        // Assert
        assertEquals("hello world & more", response.text())
    }

    @Test
    fun `should set correct content-type for form data`() {
        // Arrange
        app.post("/form-type") { ctx ->
            ctx.request.contentType
        }

        // Act
        val response = client.post("/form-type")
            .form(mapOf("key" to "value"))
            .send()

        // Assert
        assertEquals("application/x-www-form-urlencoded", response.text())
    }

    // ========================================================================
    // Multipart Tests
    // ========================================================================

    @Test
    fun `should upload single file`() {
        // Arrange
        app.post("/upload") { ctx ->
            val file = ctx.file("file")
            mapOf(
                "name" to file?.name,
                "filename" to file?.filename,
                "contentType" to file?.contentType,
                "size" to file?.size
            )
        }

        // Act
        val content = "Hello, World!".toByteArray()
        val response = client.post("/upload")
            .file("file", "test.txt", content, "text/plain")
            .send()

        // Assert
        val json = response.json<Map<String, Any>>()
        assertEquals("file", json?.get("name"))
        assertEquals("test.txt", json?.get("filename"))
        assertEquals("text/plain", json?.get("contentType"))
        assertEquals(13L, json?.get("size"))
    }

    @Test
    fun `should upload multiple files`() {
        // Arrange
        app.post("/multi-upload") { ctx ->
            val files = ctx.request.files("documents")
            files.map { mapOf("filename" to it.filename, "size" to it.size) }
        }

        // Act
        val response = client.post("/multi-upload")
            .multipart(
                FileItem("documents", "file1.txt", "text/plain", 5, "file1".toByteArray().inputStream()),
                FileItem("documents", "file2.txt", "text/plain", 5, "file2".toByteArray().inputStream())
            )
            .send()

        // Assert
        val json = response.json<List<Map<String, Any>>>()
        assertEquals(2, json?.size)
        assertEquals("file1.txt", json?.get(0)?.get("filename"))
        assertEquals("file2.txt", json?.get(1)?.get("filename"))
    }

    @Test
    fun `should handle multipart with form fields and files`() {
        // Arrange
        app.post("/mixed") { ctx ->
            mapOf(
                "title" to ctx.form("title"),
                "description" to ctx.form("description"),
                "filename" to ctx.file("file")?.filename
            )
        }

        // Act
        val response = client.post("/mixed")
            .multipart(
                FormItem("title", "My Document"),
                FormItem("description", "This is a test"),
                FileItem("file", "doc.pdf", "application/pdf", 100, ByteArray(100).inputStream())
            )
            .send()

        // Assert
        val json = response.json<Map<String, String>>()
        assertEquals("My Document", json?.get("title"))
        assertEquals("This is a test", json?.get("description"))
        assertEquals("doc.pdf", json?.get("filename"))
    }

    @Test
    fun `should set correct content-type for multipart`() {
        // Arrange
        app.post("/multipart-type") { ctx ->
            ctx.request.contentType?.startsWith("multipart/form-data")
        }

        // Act
        val response = client.post("/multipart-type")
            .file("file", "test.txt", "content".toByteArray())
            .send()

        // Assert
        assertEquals("true", response.text())
    }

    // ========================================================================
    // Raw Body Tests
    // ========================================================================

    @Test
    fun `should send string body`() {
        // Arrange
        app.post("/text") { ctx ->
            ctx.text()
        }

        // Act
        val response = client.post("/text")
            .body("Plain text content")
            .send()

        // Assert
        assertEquals("Plain text content", response.text())
    }

    @Test
    fun `should send byte array body`() {
        // Arrange
        app.post("/bytes") { ctx ->
            ctx.request.body?.size?.toString()
        }

        // Act
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val response = client.post("/bytes")
            .body(bytes)
            .send()

        // Assert
        assertEquals("5", response.text())
    }

    @Test
    fun `should auto set content-length header`() {
        // Arrange
        app.post("/length") { ctx ->
            "${ctx.request.contentLength}b"
        }

        // Act
        val response = client.post("/length")
            .body("12345")
            .send()

        // Assert
        assertEquals("5b", response.text())
    }

    // ========================================================================
    // Response Assertion Tests
    // ========================================================================

    @Test
    fun `assertStatus should pass for matching status`() {
        // Arrange
        app.get("/ok") { ctx -> ctx.status(200) }

        // Act & Assert
        client.get("/ok").send().assertStatus(200)
    }

    @Test
    fun `assertStatus should fail for non-matching status`() {
        // Arrange
        app.get("/not-found") { ctx -> ctx.status(404) }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/not-found").send().assertStatus(200)
        }
    }

    @Test
    fun `assertSuccess should pass for 2xx status`() {
        // Arrange
        app.post("/created") { ctx -> ctx.status(201) }

        // Act & Assert
        client.post("/created").send()
            .assertSuccess()
    }

    @Test
    fun `assertSuccess should fail for non-2xx status`() {
        // Arrange
        app.get("/error") { ctx -> ctx.status(500) }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/error").send()
                .assertSuccess()
        }
    }

    @Test
    fun `assertClientError should pass for 4xx status`() {
        // Arrange
        app.get("/bad-request") { ctx -> ctx.status(400) }

        // Act & Assert
        client.get("/bad-request").send()
            .assertClientError()
    }

    @Test
    fun `assertClientError should fail for non-4xx status`() {
        // Arrange
        app.get("/ok") { ctx -> ctx.status(200) }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/ok").send()
                .assertClientError()
        }
    }

    @Test
    fun `assertServerError should pass for 5xx status`() {
        // Arrange
        app.get("/error") { ctx -> ctx.status(500) }

        // Act & Assert
        client.get("/error").send()
            .assertServerError()
    }

    @Test
    fun `assertServerError should fail for non-5xx status`() {
        // Arrange
        app.get("/ok") { ctx -> ctx.status(200) }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/ok").send()
                .assertServerError()
        }
    }

    @Test
    fun `assertBodyEquals should pass for matching body`() {
        // Arrange
        app.get("/text") { "Hello World" }

        // Act & Assert
        client.get("/text").send()
            .assertBodyEquals("Hello World")
    }

    @Test
    fun `assertBodyEquals should fail for non-matching body`() {
        // Arrange
        app.get("/text") { "Hello World" }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/text").send()
                .assertBodyEquals("Goodbye World")
        }
    }

    @Test
    fun `assertBodyContains should pass when substring exists`() {
        // Arrange
        app.get("/message") { "This is a test message" }

        // Act & Assert
        client.get("/message").send()
            .assertBodyContains("test")
    }

    @Test
    fun `assertBodyContains should fail when substring missing`() {
        // Arrange
        app.get("/message") { "This is a test message" }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/message").send()
                .assertBodyContains("missing")
        }
    }

    @Test
    fun `assertHeader should pass when header exists`() {
        // Arrange
        app.get("/header") { ctx ->
            ctx.header("X-Custom", "value")
        }

        // Act & Assert
        client.get("/header").send()
            .assertHeader("X-Custom")
    }

    @Test
    fun `assertHeader should fail when header missing`() {
        // Arrange
        app.get("/no-header") { "OK" }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/no-header").send()
                .assertHeader("X-Missing")
        }
    }

    @Test
    fun `assertHeader with value should pass for matching value`() {
        // Arrange
        app.get("/header") { ctx ->
            ctx.header("X-Token", "abc123")
        }

        // Act & Assert
        client.get("/header").send()
            .assertHeader("X-Token", "abc123")
    }

    @Test
    fun `assertHeader with value should fail for non-matching value`() {
        // Arrange
        app.get("/header") { ctx ->
            ctx.header("X-Token", "abc123")
        }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/header").send()
                .assertHeader("X-Token", "xyz789")
        }
    }

    @Test
    fun `assertContentType should pass for matching type`() {
        // Arrange
        app.get("/json") { ctx ->
            ctx.json(mapOf("key" to "value"))
        }

        // Act & Assert
        client.get("/json").send()
            .assertContentType("application/json")
    }

    @Test
    fun `assertContentType should fail for non-matching type`() {
        // Arrange
        app.get("/text") { "Plain text" }

        // Act & Assert
        assertThrows<AssertionError> {
            client.get("/text").send()
                .assertContentType("application/json")
        }
    }

    @Test
    fun `expect should allow chaining multiple assertions`() {
        // Arrange
        app.get("/test") { ctx ->
            ctx.header("X-Custom", "value")
            "Test Response"
        }

        // Act & Assert
        client.get("/test").send()
            .expect {
                assertStatus(200)
                assertBodyContains("Test")
                assertHeader("X-Custom", "value")
            }
    }

    // ========================================================================
    // Response Body Reading Tests
    // ========================================================================

    @Test
    fun `text() should return text body`() {
        // Arrange
        app.get("/text") { "Hello World" }

        // Act
        val response = client.get("/text").send()

        // Assert
        assertEquals("Hello World", response.text())
    }

    @Test
    fun `text() should return null for empty body`() {
        // Arrange
        app.get("/empty") { ctx -> ctx.response.empty() }

        // Act
        val response = client.get("/empty").send()

        // Assert
        assertNull(response.text())
    }

    @Test
    fun `json() should deserialize JSON body`() {
        // Arrange
        data class User(val name: String, val age: Int)
        app.get("/user") { ctx ->
            ctx.json(User("Alice", 30))
        }

        // Act
        val response = client.get("/user").send()

        // Assert
        val user = response.json<User>()
        assertNotNull(user)
        assertEquals("Alice", user.name)
        assertEquals(30, user.age)
    }

    @Test
    fun `bytes() should return byte array`() {
        // Arrange
        val data = byteArrayOf(1, 2, 3, 4, 5)
        app.get("/bytes") { ctx ->
            ctx.bytes(data)
        }

        // Act
        val response = client.get("/bytes").send()

        // Assert
        val bytes = response.bytes()
        assertNotNull(bytes)
        assertTrue(data.contentEquals(bytes))
    }

    @Test
    fun `stream() should return input stream`() {
        // Arrange
        app.get("/stream") { "Stream content" }

        // Act
        val response = client.get("/stream").send()

        // Assert
        val stream = response.stream()
        assertNotNull(stream)
        val content = stream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals("Stream content", content)
    }

    // ========================================================================
    // Invoke Operator Tests
    // ========================================================================

    @Test
    fun `invoke operator should send request without explicit send()`() {
        // Arrange
        app.get("/hello") { "Hello" }

        // Act
        val response = client.get("/hello")()

        // Assert
        assertEquals(200, response.status)
        assertEquals("Hello", response.text())
    }

    // ========================================================================
    // Edge Cases Tests
    // ========================================================================

    @Test
    fun `should handle empty query string`() {
        // Arrange
        app.get("/empty-query") { ctx ->
            ctx.request.queryString
        }

        // Act
        val response = client.get("/empty-query").send()

        // Assert
        assertEquals("", response.text())
    }

    @Test
    fun `should handle path with existing query string`() {
        // Arrange
        app.get("/with-query") { ctx ->
            ctx.query("existing")
        }

        // Act
        val response = client.get("/with-query")
            .query("new", "param")
            .send()

        // Assert
        // Should add new query params
        assertNotNull(response)
    }

    @Test
    fun `should handle empty cookie value`() {
        // Arrange
        app.get("/empty-cookie") { ctx ->
            ctx.request.cookie("empty") ?: "null"
        }

        // Act
        val response = client.get("/empty-cookie")
            .cookie("empty", "")
            .send()

        // Assert
        assertEquals("", response.text())
    }

    @Test
    fun `should handle multiple headers with same key`() {
        // Arrange
        app.get("/multi-header") { ctx ->
            ctx.request.headers.getAll("X-Multi")
        }

        // Act
        val response = client.get("/multi-header")
            .header("X-Multi", "first")
            .header("X-Multi", "second")
            .send()

        // Assert
        // Headers are overwritten, not accumulated in TestClient
        val values = response.json<List<String>>()
        assertEquals(1, values?.size)
    }

    @Test
    fun `should preserve special characters in body`() {
        // Arrange
        app.post("/special") { ctx ->
            ctx.text()
        }

        // Act
        val specialChars = "!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val response = client.post("/special")
            .body(specialChars)
            .send()

        // Assert
        assertEquals(specialChars, response.text())
    }

    @Test
    fun `should handle binary data correctly`() {
        // Arrange
        app.post("/binary") { ctx ->
            ctx.request.body
        }

        // Act
        val binaryData = ByteArray(256) { it.toByte() }
        val response = client.post("/binary")
            .body(binaryData)
            .send()

        // Assert
        val result = response.bytes()
        assertNotNull(result)
        assertTrue(binaryData.contentEquals(result))
    }
}