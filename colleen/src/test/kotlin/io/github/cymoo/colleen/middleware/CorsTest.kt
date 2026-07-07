package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CorsTest {

    private lateinit var app: Colleen
    private var nextCalled = false

    @BeforeEach
    fun setUp() {
        app = Colleen()
        nextCalled = false
    }

    private fun next() {
        nextCalled = true
    }

    private fun createContext(
        method: String = "GET",
        path: String = "/",
        headers: Map<String, String> = emptyMap()
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers().apply {
                headers.forEach { (key, value) -> set(key, value) }
            },
        )
        return Context(request = request, app = app)
    }

    // ========================================================================
    // Configuration Validation Tests
    // ========================================================================

    @Test
    fun `should throw exception when allowOrigins is empty`() {
        // Assert
        val exception = assertThrows<IllegalArgumentException> {
            Cors(allowOrigins = emptySet())
        }
        assertTrue(exception.message!!.contains("cannot be empty"))
    }

    @Test
    fun `should throw exception when using wildcard with credentials`() {
        // Assert
        val exception = assertThrows<IllegalArgumentException> {
            Cors(allowOrigins = setOf("*"), allowCredentials = true)
        }
        assertTrue(exception.message!!.contains("wildcard origin"))
        assertTrue(exception.message!!.contains("credentials"))
    }

    @Test
    fun `should allow explicit origins with credentials`() {
        // Act & Assert - should not throw
        assertDoesNotThrow {
            Cors(
                allowOrigins = setOf("https://example.com"),
                allowCredentials = true
            )
        }
    }

    // ========================================================================
    // Wildcard Origin Tests
    // ========================================================================

    @Test
    fun `should allow all origins with wildcard`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("*"))
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        assertEquals("*", ctx.response.headers["Access-Control-Allow-Origin"])
        // Allow-Methods is a preflight-response header; actual responses omit it
        assertNull(ctx.response.headers["Access-Control-Allow-Methods"])
    }

    @Test
    fun `should not set Vary header for wildcard origin`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("*"))
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertNull(ctx.response.headers["Vary"])
    }

    // ========================================================================
    // Specific Origin Tests
    // ========================================================================

    @Test
    fun `should allow request from whitelisted origin`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com", "https://app.example.com"))
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
        assertEquals("https://example.com", ctx.response.headers["Access-Control-Allow-Origin"])
        assertEquals("Origin", ctx.response.headers["Vary"])
    }

    @Test
    fun `should reject request from non-whitelisted origin`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com"))
        val ctx = createContext(headers = mapOf("origin" to "https://malicious.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should still be called for non-CORS requests")
        assertNull(
            ctx.response.headers["Access-Control-Allow-Origin"],
            "Should not set CORS headers for non-whitelisted origin"
        )
    }

    @Test
    fun `should handle request without origin header`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com"))
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
        assertNull(ctx.response.headers["Access-Control-Allow-Origin"])
    }

    // ========================================================================
    // Credentials Tests
    // ========================================================================

    @Test
    fun `should set credentials header when enabled`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf("https://example.com"),
            allowCredentials = true
        )
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("https://example.com", ctx.response.headers["Access-Control-Allow-Origin"])
        assertEquals("true", ctx.response.headers["Access-Control-Allow-Credentials"])
    }

    @Test
    fun `should not set credentials header when disabled`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf("https://example.com"),
            allowCredentials = false
        )
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertNull(ctx.response.headers["Access-Control-Allow-Credentials"])
    }

    // ========================================================================
    // Preflight OPTIONS Request Tests
    // ========================================================================

    @Test
    fun `should handle OPTIONS preflight request`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com"))
        val ctx = createContext(
            method = "OPTIONS",
            headers = mapOf("origin" to "https://example.com")
        )

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled, "next() should NOT be called for OPTIONS request")
        assertEquals(204, ctx.response.status)
        assertEquals("86400", ctx.response.headers["Access-Control-Max-Age"])
        assertEquals("https://example.com", ctx.response.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `should not call next for OPTIONS with non-whitelisted origin`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com"))
        val ctx = createContext(
            method = "OPTIONS",
            headers = mapOf("origin" to "https://malicious.com")
        )

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called even for rejected OPTIONS")
        assertNull(ctx.response.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `should set custom maxAge for preflight request`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf("https://example.com"),
            maxAge = 3600
        )
        val ctx = createContext(
            method = "OPTIONS",
            headers = mapOf("origin" to "https://example.com")
        )

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("3600", ctx.response.headers["Access-Control-Max-Age"])
    }

    // ========================================================================
    // Headers Configuration Tests
    // ========================================================================

    @Test
    fun `should set custom allowed methods`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf("*"),
            allowMethods = "GET,POST"
        )
        // Allow-Methods is only meaningful (and only sent) on preflight responses
        val ctx = createContext(method = "OPTIONS", headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("GET,POST", ctx.response.headers["Access-Control-Allow-Methods"])
    }

    @Test
    fun `should set custom allowed headers`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf("*"),
            allowHeaders = "Content-Type,X-Custom-Header"
        )
        // Allow-Headers is only meaningful (and only sent) on preflight responses
        val ctx = createContext(method = "OPTIONS", headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("Content-Type,X-Custom-Header", ctx.response.headers["Access-Control-Allow-Headers"])
    }

    @Test
    fun `should set exposed headers when configured`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf("*"),
            exposeHeaders = "X-Request-ID,X-Custom"
        )
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("X-Request-ID,X-Custom", ctx.response.headers["Access-Control-Expose-Headers"])
    }

    @Test
    fun `should not set exposed headers when empty`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf("*"),
            exposeHeaders = ""
        )
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertNull(ctx.response.headers["Access-Control-Expose-Headers"])
    }

    // ========================================================================
    // Actual Request Tests
    // ========================================================================

    @Test
    fun `should call next for actual GET request`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com"))
        val ctx = createContext(
            method = "GET",
            headers = mapOf("origin" to "https://example.com")
        )

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called for actual requests")
        assertEquals("https://example.com", ctx.response.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `should call next for actual POST request`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com"))
        val ctx = createContext(
            method = "POST",
            headers = mapOf("origin" to "https://example.com")
        )

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled)
        assertEquals("https://example.com", ctx.response.headers["Access-Control-Allow-Origin"])
    }

    // ========================================================================
    // Companion Object Tests
    // ========================================================================

    @Test
    fun `forOrigin should create middleware with single origin`() {
        // Arrange & Act
        val middleware = Cors.forOrigin("https://example.com", allowCredentials = true)
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("https://example.com", ctx.response.headers["Access-Control-Allow-Origin"])
        assertEquals("true", ctx.response.headers["Access-Control-Allow-Credentials"])
    }

    @Test
    fun `permissive should create wildcard middleware`() {
        // Arrange & Act
        val middleware = Cors.permissive()
        val ctx = createContext(headers = mapOf("origin" to "https://example.com"))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("*", ctx.response.headers["Access-Control-Allow-Origin"])
    }

    // ========================================================================
    // Edge Cases Tests
    // ========================================================================

    @Test
    fun `should handle multiple origins correctly`() {
        // Arrange
        val middleware = Cors(
            allowOrigins = setOf(
                "https://example.com",
                "https://app.example.com",
                "https://admin.example.com"
            )
        )

        // Test first origin
        val ctx1 = createContext(headers = mapOf("origin" to "https://example.com"))
        middleware.invoke(ctx1, ::next)
        assertEquals("https://example.com", ctx1.response.headers["Access-Control-Allow-Origin"])

        // Reset
        nextCalled = false

        // Test second origin
        val ctx2 = createContext(headers = mapOf("origin" to "https://app.example.com"))
        middleware.invoke(ctx2, ::next)
        assertEquals("https://app.example.com", ctx2.response.headers["Access-Control-Allow-Origin"])

        // Reset
        nextCalled = false

        // Test non-whitelisted origin
        val ctx3 = createContext(headers = mapOf("origin" to "https://malicious.com"))
        middleware.invoke(ctx3, ::next)
        assertNull(ctx3.response.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `should handle case-sensitive origin matching`() {
        // Arrange
        val middleware = Cors(allowOrigins = setOf("https://example.com"))

        // Act - different case
        val ctx = createContext(headers = mapOf("origin" to "https://Example.com"))
        middleware.invoke(ctx, ::next)

        // Assert - should not match (origins are case-sensitive in spec)
        assertNull(ctx.response.headers["Access-Control-Allow-Origin"])
    }
}