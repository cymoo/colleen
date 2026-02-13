package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class RequestIdTest {

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
    // Basic Request ID Generation Tests
    // ========================================================================

    @Test
    fun `should generate request ID when not present in request`() {
        // Arrange
        val middleware = RequestId()
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        val requestId = ctx.getRequestId()
        assertNotNull(requestId, "Request ID should be stored in context")
        assertTrue(requestId!!.isNotBlank(), "Generated request ID should not be blank")
    }

    @Test
    fun `should use existing request ID from header`() {
        // Arrange
        val existingId = "existing-request-id-123"
        val middleware = RequestId()
        val ctx = createContext(headers = mapOf("X-Request-ID" to existingId))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        val requestId = ctx.getRequestId()
        assertEquals(existingId, requestId, "Should use existing request ID from header")
    }

    @Test
    fun `should generate new ID when header value is blank`() {
        // Arrange
        val middleware = RequestId()
        val ctx = createContext(headers = mapOf("X-Request-ID" to "   "))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        val requestId = ctx.getRequestId()
        assertNotNull(requestId, "Request ID should be generated")
        assertTrue(requestId!!.isNotBlank(), "Generated request ID should not be blank")
    }

    // ========================================================================
    // Response Header Tests
    // ========================================================================

    @Test
    fun `should set response header by default`() {
        // Arrange
        val middleware = RequestId()
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(ctx.response.headers.has("X-Request-ID"), "Response should have X-Request-ID header")
        val responseId = ctx.response.headers["X-Request-ID"]
        val contextId = ctx.getRequestId()
        assertEquals(contextId, responseId, "Response header should match context request ID")
    }

    @Test
    fun `should not set response header when disabled`() {
        // Arrange
        val middleware = RequestId(setResponseHeader = false)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(ctx.response.headers.has("X-Request-ID"), "Response should not have X-Request-ID header")
        val contextId = ctx.getRequestId()
        assertNotNull(contextId, "Request ID should still be stored in context")
    }

    @Test
    fun `should set response header with existing request ID`() {
        // Arrange
        val existingId = "existing-id-456"
        val middleware = RequestId()
        val ctx = createContext(headers = mapOf("X-Request-ID" to existingId))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(ctx.response.headers.has("X-Request-ID"))
        assertEquals(existingId, ctx.response.headers["X-Request-ID"])
    }

    // ========================================================================
    // Custom Configuration Tests
    // ========================================================================

    @Test
    fun `should use custom header name`() {
        // Arrange
        val customHeaderName = "X-Custom-Request-ID"
        val customId = "custom-id-789"
        val middleware = RequestId(headerName = customHeaderName)
        val ctx = createContext(headers = mapOf(customHeaderName to customId))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val requestId = ctx.getRequestId()
        assertEquals(customId, requestId)
        assertTrue(ctx.response.headers.has(customHeaderName))
        assertEquals(customId, ctx.response.headers[customHeaderName])
    }

    @Test
    fun `should use custom generator`() {
        // Arrange
        val counter = AtomicInteger(0)
        val customGenerator = { "custom-${counter.incrementAndGet()}" }
        val middleware = RequestId(generator = customGenerator)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val requestId = ctx.getRequestId()
        assertEquals("custom-1", requestId)
        assertTrue(ctx.response.headers.has("X-Request-ID"))
        assertEquals("custom-1", ctx.response.headers["X-Request-ID"])
    }

    @Test
    fun `should generate unique IDs for multiple requests`() {
        // Arrange
        val middleware = RequestId()
        val ctx1 = createContext()
        val ctx2 = createContext()

        // Act
        middleware.invoke(ctx1, ::next)
        middleware.invoke(ctx2, ::next)

        // Assert
        val id1 = ctx1.getRequestId()
        val id2 = ctx2.getRequestId()
        assertNotNull(id1)
        assertNotNull(id2)
        assertNotEquals(id1, id2, "Each request should have a unique ID")
    }

    // ========================================================================
    // Validation Tests
    // ========================================================================

    @Test
    fun `should throw exception when header name is blank`() {
        // Act & Assert
        val exception = assertThrows<IllegalArgumentException> {
            RequestId(headerName = "")
        }
        assertTrue(exception.message!!.contains("blank"), "Error message should mention blank header name")
    }

    @Test
    fun `should throw exception when header name is whitespace`() {
        // Act & Assert
        val exception = assertThrows<IllegalArgumentException> {
            RequestId(headerName = "   ")
        }
        assertTrue(exception.message!!.contains("blank"))
    }

    @Test
    fun `should throw exception when generator returns blank string`() {
        // Arrange
        val middleware = RequestId(generator = { "" })
        val ctx = createContext()

        // Act & Assert
        val exception = assertThrows<IllegalArgumentException> {
            middleware.invoke(ctx, ::next)
        }
        assertTrue(exception.message!!.contains("blank"), "Error message should mention blank request ID")
    }

    @Test
    fun `should throw exception when generator returns whitespace`() {
        // Arrange
        val middleware = RequestId(generator = { "   " })
        val ctx = createContext()

        // Act & Assert
        val exception = assertThrows<IllegalArgumentException> {
            middleware.invoke(ctx, ::next)
        }
        assertTrue(exception.message!!.contains("blank"))
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    fun `should handle empty string header gracefully`() {
        // Arrange
        val middleware = RequestId()
        val ctx = createContext(headers = mapOf("X-Request-ID" to ""))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val requestId = ctx.getRequestId()
        assertNotNull(requestId)
        assertTrue(requestId!!.isNotBlank(), "Should generate new ID when header is empty")
    }

    @Test
    fun `should handle special characters in request ID`() {
        // Arrange
        val specialId = "req-id-!@#$%^&*()_+-=[]{}|;:',.<>?"
        val middleware = RequestId()
        val ctx = createContext(headers = mapOf("X-Request-ID" to specialId))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val requestId = ctx.getRequestId()
        assertEquals(specialId, requestId, "Should accept request ID with special characters")
    }

    @Test
    fun `should handle very long request ID`() {
        // Arrange
        val longId = "a".repeat(1000)
        val middleware = RequestId()
        val ctx = createContext(headers = mapOf("X-Request-ID" to longId))

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val requestId = ctx.getRequestId()
        assertEquals(longId, requestId, "Should handle very long request IDs")
    }

    @Test
    fun `should call next() exactly once`() {
        // Arrange
        val middleware = RequestId()
        val ctx = createContext()
        var callCount = 0
        val countingNext: () -> Unit = { callCount++ }

        // Act
        middleware.invoke(ctx, countingNext)

        // Assert
        assertEquals(1, callCount, "next() should be called exactly once")
    }
}