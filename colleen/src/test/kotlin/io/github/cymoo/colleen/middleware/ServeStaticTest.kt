package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.*

class ServeStaticTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testDir: File
    private lateinit var app: Colleen
    private lateinit var ctx: Context
    private var nextCalled = false

    @BeforeEach
    fun setUp() {
        testDir = tempDir.toFile()
        app = Colleen()
        nextCalled = false
    }

    @AfterEach
    fun tearDown() {
        if (::ctx.isInitialized) {
            (ctx.response.body as? ResponseBody.Stream)?.input?.close()
        }
    }

    private fun next() {
        nextCalled = true
    }

    private fun createContext(method: String, path: String, headers: Map<String, String> = emptyMap()): Context {
        val request = Request(
            method = method,
            path = path,
            queryString = "",
            headers = Headers().apply {
                headers.forEach { (key, value) -> set(key, value) }
            },
            stream = ByteArray(0).inputStream(),
        )
        return Context(request = request, app = app).apply { ctx = this }
    }

    // ========================================================================
    // Basic File Serving Tests
    // ========================================================================

    @Test
    fun `should serve existing file`() {
        // Arrange
        File(testDir, "test.txt").apply {
            writeText("Hello, World!")
        }
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/test.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled, "next() should not be called for existing file")
        assertEquals(200, ctx.response.status)
        assertTrue(ctx.response.headers.has("Content-Type"))
        assertTrue(ctx.response.headers.has("Content-Length"))
        assertEquals("13", ctx.response.headers["Content-Length"])
    }

    @Test
    fun `should serve file with custom base URL`() {
        // Arrange
        File(testDir, "asset.css").writeText("body { margin: 0; }")
        val middleware = ServeStatic(testDir.path, baseUrl = "/assets")
        val ctx = createContext("GET", "/assets/asset.css")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should serve index file for directory request`() {
        // Arrange
        val subDir = File(testDir, "docs").apply { mkdir() }
        File(subDir, "index.html").writeText("<h1>Index</h1>")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/docs")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
        assertEquals("14", ctx.response.headers["Content-Length"])
    }

    @Test
    fun `should serve custom index file`() {
        // Arrange
        val subDir = File(testDir, "app").apply { mkdir() }
        File(subDir, "default.html").writeText("<h1>Default</h1>")
        val middleware = ServeStatic(testDir.path, index = "default.html")
        val ctx = createContext("GET", "/static/app")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    // ========================================================================
    // HTTP Method Tests
    // ========================================================================

    @Test
    fun `should handle HEAD request`() {
        // Arrange
        File(testDir, "data.json").writeText("""{"key": "value"}""")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("HEAD", "/static/data.json")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
        assertTrue(ctx.response.headers.has("Content-Length"))
    }

    @Test
    fun `should call next for POST request`() {
        // Arrange
        File(testDir, "test.txt").writeText("content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("POST", "/static/test.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called for non-GET/HEAD methods")
    }

    @Test
    fun `should call next for PUT request`() {
        // Arrange
        File(testDir, "test.txt").writeText("content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("PUT", "/static/test.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
    }

    @Test
    fun `should call next for DELETE request`() {
        // Arrange
        File(testDir, "test.txt").writeText("content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("DELETE", "/static/test.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
    }

    // ========================================================================
    // Path Matching Tests
    // ========================================================================

    @Test
    fun `should call next for non-matching base URL`() {
        // Arrange
        File(testDir, "test.txt").writeText("content")
        val middleware = ServeStatic(testDir.path, baseUrl = "/static")
        val ctx = createContext("GET", "/api/test.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called for non-matching paths")
    }

    @Test
    fun `should handle root path request`() {
        // Arrange
        File(testDir, "index.html").writeText("<h1>Home</h1>")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should handle path with trailing slash`() {
        // Arrange
        val subDir = File(testDir, "folder").apply { mkdir() }
        File(subDir, "index.html").writeText("<h1>Folder</h1>")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/folder/")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    // ========================================================================
    // Security Tests
    // ========================================================================

    @Test
    fun `should prevent directory traversal attacks`() {
        // Arrange
        File(File(System.getProperty("java.io.tmpdir")), "secret.txt")
            .writeText("Secret data")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/../../../tmp/secret.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "Should call next() for invalid path")
    }

    @Test
    fun `should reject null byte in path`() {
        // Arrange
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/test\u0000.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
    }

    @Test
    fun `should prevent path traversal with encoded dots`() {
        // Arrange
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/%2e%2e/secret.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "Should reject encoded path traversal")
    }

    @Test
    fun `should handle dot files with DENY policy`() {
        // Arrange
        File(testDir, ".htaccess").writeText("config")
        val middleware = ServeStatic(testDir.path, dotFiles = ServeStatic.DotFilesPolicy.DENY)
        val ctx = createContext("GET", "/static/.htaccess")

        // Act & Assert
        assertFailsWith<Forbidden> {
            middleware.invoke(ctx, ::next)
        }
    }

    @Test
    fun `should handle dot files with IGNORE policy`() {
        // Arrange
        File(testDir, ".hidden").writeText("secret")
        val middleware = ServeStatic(testDir.path, dotFiles = ServeStatic.DotFilesPolicy.IGNORE)
        val ctx = createContext("GET", "/static/.hidden")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "Should call next() for ignored dot files")
    }

    @Test
    fun `should serve dot files with ALLOW policy`() {
        // Arrange
        File(testDir, ".config").writeText("settings")
        val middleware = ServeStatic(testDir.path, dotFiles = ServeStatic.DotFilesPolicy.ALLOW)
        val ctx = createContext("GET", "/static/.config")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should ignore dot files in subdirectories with IGNORE policy`() {
        // Arrange
        val subDir = File(testDir, "subdir").apply { mkdir() }
        File(subDir, ".secret").writeText("hidden")
        val middleware = ServeStatic(testDir.path, dotFiles = ServeStatic.DotFilesPolicy.IGNORE)
        val ctx = createContext("GET", "/static/subdir/.secret")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
    }

    // ========================================================================
    // Extension Handling Tests
    // ========================================================================

    @Test
    fun `should try extensions for missing files`() {
        // Arrange
        File(testDir, "page.html").writeText("<h1>Page</h1>")
        val middleware = ServeStatic(testDir.path, extensions = listOf(".html", ".htm"))
        val ctx = createContext("GET", "/static/page")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should try multiple extensions in order`() {
        // Arrange
        File(testDir, "script.js").writeText("console.log('test');")
        val middleware = ServeStatic(
            testDir.path,
            extensions = listOf(".css", ".js", ".json")
        )
        val ctx = createContext("GET", "/static/script")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should not try extensions for existing files`() {
        // Arrange
        File(testDir, "file.txt").writeText("original")
        File(testDir, "file.txt.html").writeText("with extension")
        val middleware = ServeStatic(testDir.path, extensions = listOf(".html"))
        val ctx = createContext("GET", "/static/file.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
        // Verify it served the original file, not the .html version
        assertTrue(ctx.response.body is ResponseBody.Stream)
        assertEquals("8", ctx.response.headers["Content-Length"])
    }

    @Test
    fun `should call next when no extension matches`() {
        // Arrange
        File(testDir, "script.js").writeText("code")
        val middleware = ServeStatic(testDir.path, extensions = listOf(".css", ".html"))
        val ctx = createContext("GET", "/static/script")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "Should call next() when no extension matches")
    }

    // ========================================================================
    // Caching Tests
    // ========================================================================

    @Test
    fun `should return 304 for not modified request`() {
        // Arrange
        val file = File(testDir, "cached.txt").apply {
            writeText("Cached content")
        }
        val middleware = ServeStatic(testDir.path)

        val lastModified = Instant.ofEpochMilli(file.lastModified())
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val ctx = createContext("GET", "/static/cached.txt", mapOf("if-modified-since" to lastModified))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(304, ctx.response.status)
        assertNull(ctx.response.headers["Content-Length"])
    }

    @Test
    fun `should serve file for modified request`() {
        // Arrange
        val file = File(testDir, "updated.txt").apply {
            writeText("Updated content")
        }
        val middleware = ServeStatic(testDir.path)

        val oldDate = Instant.ofEpochMilli(file.lastModified() - 10000)
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val ctx = createContext("GET", "/static/updated.txt", mapOf("if-modified-since" to oldDate))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
        assertTrue(ctx.response.headers.has("Content-Type"))
    }

    @Test
    fun `should ignore invalid if-modified-since header`() {
        // Arrange
        File(testDir, "file.txt").writeText("content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/file.txt", mapOf("if-modified-since" to "invalid-date"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status, "Should serve file when if-modified-since is invalid")
    }

    @Test
    fun `should set Cache-Control header with maxAge`() {
        // Arrange
        File(testDir, "static.css").writeText("body { }")
        val middleware = ServeStatic(testDir.path, maxAge = 3600)
        val ctx = createContext("GET", "/static/static.css")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
        assertEquals("public, max-age=3600", ctx.response.headers["Cache-Control"])
    }

    @Test
    fun `should not set Cache-Control when maxAge is 0`() {
        // Arrange
        File(testDir, "dynamic.js").writeText("alert('hi');")
        val middleware = ServeStatic(testDir.path, maxAge = 0)
        val ctx = createContext("GET", "/static/dynamic.js")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
        assertFalse(ctx.response.headers.has("Cache-Control"))
    }

    @Test
    fun `should include Last-Modified header`() {
        // Arrange
        File(testDir, "timestamped.txt").writeText("content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/timestamped.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
        assertTrue(ctx.response.headers.has("Last-Modified"))

        // Verify Last-Modified format is valid
        val lastModified = ctx.response.headers["Last-Modified"]
        assertNotNull(lastModified)
        assertDoesNotThrow {
            DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModified)
        }
    }

    // ========================================================================
    // Content Type Tests
    // ========================================================================

    @Test
    fun `should set correct content type for HTML files`() {
        // Arrange
        File(testDir, "page.html").writeText("<html></html>")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/page.html")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val contentType = ctx.response.headers["Content-Type"]
        assertNotNull(contentType)
        assertTrue(contentType.startsWith("text/html"))
    }

    @Test
    fun `should add charset to text content types`() {
        // Arrange
        File(testDir, "script.js").writeText("console.log('test');")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/script.js")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val contentType = ctx.response.headers["Content-Type"]
        assertNotNull(contentType)
        assertTrue(contentType.contains("charset=utf-8"), "Expected charset in: $contentType")
    }

    @Test
    fun `should set X-Content-Type-Options header`() {
        // Arrange
        File(testDir, "data.bin").writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/data.bin")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("nosniff", ctx.response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `should set correct content type for JSON files`() {
        // Arrange
        File(testDir, "data.json").writeText("""{"test": true}""")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/data.json")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val contentType = ctx.response.headers["Content-Type"]
        assertNotNull(contentType)
        assertTrue(contentType.startsWith("application/json"))
        assertTrue(contentType.contains("charset=utf-8"))
    }

    @Test
    fun `should use default content type for unknown file types`() {
        // Arrange
        File(testDir, "file.unknown").writeText("content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/file.unknown")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val contentType = ctx.response.headers["Content-Type"]
        assertNotNull(contentType)
        assertTrue(contentType.startsWith("application/octet-stream"))
    }

    // ========================================================================
    // Fallthrough Tests
    // ========================================================================

    @Test
    fun `should call next when file not found and fallthrough is true`() {
        // Arrange
        val middleware = ServeStatic(testDir.path, fallthrough = true)
        val ctx = createContext("GET", "/static/nonexistent.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called when fallthrough is true")
    }

    @Test
    fun `should throw NotFound when file not found and fallthrough is false`() {
        // Arrange
        val middleware = ServeStatic(testDir.path, fallthrough = false)
        val ctx = createContext("GET", "/static/missing.txt")

        // Act & Assert
        assertFailsWith<NotFound> {
            middleware.invoke(ctx, ::next)
        }
        assertFalse(nextCalled, "next() should not be called when exception is thrown")
    }

    @Test
    fun `should call next for directory without index when fallthrough is true`() {
        // Arrange
        File(testDir, "empty").apply { mkdir() }
        val middleware = ServeStatic(testDir.path, fallthrough = true)
        val ctx = createContext("GET", "/static/empty")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
    }

    @Test
    fun `should throw NotFound for directory without index when fallthrough is false`() {
        // Arrange
        File(testDir, "empty").apply { mkdir() }
        val middleware = ServeStatic(testDir.path, fallthrough = false)
        val ctx = createContext("GET", "/static/empty")

        // Act & Assert
        assertFailsWith<NotFound> {
            middleware.invoke(ctx, ::next)
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun `should handle empty relative path`() {
        // Arrange
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
    }

    @Test
    fun `should handle files with special characters in name`() {
        // Arrange
        File(testDir, "file with spaces.txt").writeText("content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/file with spaces.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should handle nested directory structure`() {
        // Arrange
        val nestedDir = File(testDir, "a/b/c").apply { mkdirs() }
        File(nestedDir, "deep.txt").writeText("deep content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/a/b/c/deep.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should handle large files`() {
        // Arrange
        val largeContent = "x".repeat(10 * 1024 * 1024) // 10MB
        File(testDir, "large.txt").writeText(largeContent)
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/large.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
        assertEquals((10 * 1024 * 1024).toString(), ctx.response.headers["Content-Length"])
    }

    @Test
    fun `should reject invalid root directory`() {
        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            ServeStatic("/nonexistent/directory/path")
        }
    }

    @Test
    fun `should reject file as root directory`() {
        // Arrange
        val file = File(testDir, "notadir.txt").apply { writeText("content") }

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            ServeStatic(file.path)
        }
    }

    @Test
    fun `should handle files with unicode names`() {
        // Arrange
        File(testDir, "文件.txt").writeText("unicode content")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/文件.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should set correct Content-Length header`() {
        // Arrange
        val content = "Hello, World! 你好世界"
        val file = File(testDir, "utf8.txt").apply { writeText(content) }
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/utf8.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
        assertEquals(file.length().toString(), ctx.response.headers["Content-Length"])
    }

    @Test
    fun `should handle zero-byte files`() {
        // Arrange
        File(testDir, "empty.txt").apply { createNewFile() }
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/empty.txt")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
        assertEquals("0", ctx.response.headers["Content-Length"])
    }

    @Test
    fun `should serve file with multiple dots in name`() {
        // Arrange
        File(testDir, "archive.tar.gz").writeBytes(byteArrayOf(1, 2, 3))
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("GET", "/static/archive.tar.gz")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(200, ctx.response.status)
    }
}

/**
 * Supplementary tests for [ServeStatic], covering gaps in the original suite:
 *
 * - baseUrl prefix collision (e.g. /staticx must not match /static)
 * - Exact baseUrl match without trailing slash serving the root index
 * - classpath resource serving (both happy path and error cases)
 * - 304 response must not carry a Content-Length header (RFC 7230 §3.3)
 * - HEAD response carries metadata headers but no body
 */
class ServeStaticSupplementaryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testDir: File
    private lateinit var app: Colleen
    private lateinit var ctx: Context
    private var nextCalled = false

    @BeforeEach
    fun setUp() {
        testDir = tempDir.toFile()
        app = Colleen()
        nextCalled = false
    }

    @AfterEach
    fun tearDown() {
        if (::ctx.isInitialized) {
            (ctx.response.body as? ResponseBody.Stream)?.input?.close()
        }
    }

    private fun next() {
        nextCalled = true
    }

    private fun createContext(method: String, path: String, headers: Map<String, String> = emptyMap()): Context {
        val request = Request(
            method = method,
            path = path,
            queryString = "",
            headers = Headers().apply { headers.forEach { (k, v) -> set(k, v) } },
            stream = ByteArray(0).inputStream(),
        )
        return Context(request = request, app = app).apply { ctx = this }
    }

    // ========================================================================
    // baseUrl Prefix Collision Tests
    // ========================================================================

    @Test
    fun `should not match path that merely starts with baseUrl string`() {
        // Regression test for the /staticx-matches-/static bug.
        // "/staticx/file.txt".startsWith("/static") is true, but "/staticx/" != "/static/"
        // so the middleware must pass through.
        File(testDir, "file.txt").writeText("content")
        val middleware = ServeStatic(testDir.path, baseUrl = "/static")
        val ctx = createContext("GET", "/staticx/file.txt")

        middleware.invoke(ctx, ::next)

        assertTrue(nextCalled, "Path /staticx/ must not match baseUrl /static")
    }

    @Test
    fun `should not match path whose name shares a prefix with baseUrl but has no separator`() {
        File(testDir, "file.txt").writeText("content")
        val middleware = ServeStatic(testDir.path, baseUrl = "/assets")
        val ctx = createContext("GET", "/assets2/file.txt")

        middleware.invoke(ctx, ::next)

        assertTrue(nextCalled)
    }

    @Test
    fun `should match exact baseUrl without trailing slash and serve root index`() {
        // GET /static  (no trailing slash) should resolve to index.html at the root.
        File(testDir, "index.html").writeText("<h1>Root</h1>")
        val middleware = ServeStatic(testDir.path, baseUrl = "/static")
        val ctx = createContext("GET", "/static")

        middleware.invoke(ctx, ::next)

        assertFalse(nextCalled, "Exact baseUrl match should be handled, not passed through")
        assertEquals(200, ctx.response.status)
    }

    // ========================================================================
    // 304 Response Header Tests
    // ========================================================================

    @Test
    fun `304 response must not include Content-Length header`() {
        // RFC 7230 §3.3: a 304 response must not include a message body,
        // and Content-Length would misrepresent the absent body.
        val file = File(testDir, "cached.css").apply { writeText("body{}") }
        val middleware = ServeStatic(testDir.path)
        val lastModified = Instant.ofEpochMilli(file.lastModified())
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val ctx = createContext("GET", "/static/cached.css", mapOf("if-modified-since" to lastModified))
        middleware.invoke(ctx, ::next)

        assertEquals(304, ctx.response.status)
        assertNull(
            ctx.response.headers["Content-Length"],
            "304 must not carry Content-Length (RFC 7230 §3.3)"
        )
        assertNull(
            ctx.response.headers["Content-Type"],
            "304 must not carry Content-Type"
        )
    }

    @Test
    fun `304 response must not include a body`() {
        val file = File(testDir, "style.css").apply { writeText("body{}") }
        val middleware = ServeStatic(testDir.path)
        val lastModified = Instant.ofEpochMilli(file.lastModified())
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val ctx = createContext("GET", "/static/style.css", mapOf("if-modified-since" to lastModified))
        middleware.invoke(ctx, ::next)

        assertEquals(304, ctx.response.status)
        // body should be null or empty — no stream should have been opened
        val body = ctx.response.body
        assertTrue(body is ResponseBody.Empty, "304 must have no body")
    }

    // ========================================================================
    // HEAD Response Tests
    // ========================================================================

    @Test
    fun `HEAD response must include metadata headers but no body`() {
        File(testDir, "data.txt").writeText("hello")
        val middleware = ServeStatic(testDir.path)
        val ctx = createContext("HEAD", "/static/data.txt")

        middleware.invoke(ctx, ::next)

        assertEquals(200, ctx.response.status)
        assertNotNull(ctx.response.headers["Content-Type"])
        assertNotNull(ctx.response.headers["Content-Length"])
        assertNotNull(ctx.response.headers["Last-Modified"])
        // No stream body should be opened for HEAD
        val body = ctx.response.body
        assertTrue(body is ResponseBody.Unset, "HEAD must have no body")
    }

    // ========================================================================
    // Classpath Resource Tests
    // ========================================================================

    @Test
    fun `should serve file from classpath`() {
        // Assumes src/test/resources/static-test/hello.txt exists with content "hello from classpath"
        val middleware = ServeStatic("classpath:static-test")
        val ctx = createContext("GET", "/static/hello.txt")

        middleware.invoke(ctx, ::next)

        assertFalse(nextCalled, "Classpath resource should be served, not passed through")
        assertEquals(200, ctx.response.status)
        assertNotNull(ctx.response.headers["Content-Type"])
    }

    @Test
    fun `should return not-found for missing classpath resource`() {
        val middleware = ServeStatic("classpath:static-test", fallthrough = true)
        val ctx = createContext("GET", "/static/nonexistent.txt")

        middleware.invoke(ctx, ::next)

        assertTrue(nextCalled, "Missing classpath resource should fall through")
    }

    @Test
    fun `should throw NotFound for missing classpath resource when fallthrough is false`() {
        val middleware = ServeStatic("classpath:static-test", fallthrough = false)
        val ctx = createContext("GET", "/static/missing.txt")

        assertFailsWith<NotFound> {
            middleware.invoke(ctx, ::next)
        }
    }

    @Test
    fun `should reject path traversal for classpath resolver`() {
        // normalizePath must block ".." escape before it reaches the classloader.
        val middleware = ServeStatic("classpath:static-test")
        val ctx = createContext("GET", "/static/../../secret")

        middleware.invoke(ctx, ::next)

        assertTrue(nextCalled, "Path traversal via classpath resolver must be rejected")
    }

    @Test
    fun `should serve classpath index file for directory-style URL`() {
        // Assumes src/test/resources/static-test/sub/index.html exists.
        val middleware = ServeStatic("classpath:static-test")
        val ctx = createContext("GET", "/static/sub")

        middleware.invoke(ctx, ::next)

        assertFalse(nextCalled)
        assertEquals(200, ctx.response.status)
    }

    @Test
    fun `should reject invalid classpath root`() {
        assertFailsWith<IllegalArgumentException> {
            ServeStatic("classpath:nonexistent-root-that-does-not-exist")
        }
    }
}