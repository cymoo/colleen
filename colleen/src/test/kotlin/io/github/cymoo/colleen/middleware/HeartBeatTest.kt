package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import io.github.cymoo.colleen.ResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HeartBeatTest {

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
        path: String = "/"
    ): Context {
        val request = Request(method = method, path = path)
        return Context(request = request, app = app)
    }

    // ========================================================================
    // Basic HeartBeat Tests
    // ========================================================================

    @Test
    fun `should respond 200 for GET request to heartbeat endpoint`() {
        // Arrange
        val middleware = HeartBeat("/health")
        val ctx = createContext(method = "GET", path = "/health")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled, "next() should not be called")
        assertEquals(200, ctx.response.status)
        assertEquals("text/plain; charset=utf-8", ctx.response.headers["Content-Type"])
        assertEquals("ok", (ctx.response.body as ResponseBody.Text).value)
    }

    @Test
    fun `should respond 200 for HEAD request to heartbeat endpoint`() {
        // Arrange
        val middleware = HeartBeat("/health")
        val ctx = createContext(method = "HEAD", path = "/health")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled, "next() should not be called")
        assertEquals(200, ctx.response.status)
        assertEquals("text/plain; charset=utf-8", ctx.response.headers["Content-Type"])
        assertEquals(ctx.response.body, ResponseBody.Unset)
    }

    @Test
    fun `should call next for non-heartbeat endpoints`() {
        // Arrange
        val middleware = HeartBeat("/health")
        val ctx = createContext(method = "GET", path = "/api/users")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called for non-heartbeat paths")
    }

    @Test
    fun `should call next for POST request to heartbeat endpoint`() {
        // Arrange
        val middleware = HeartBeat("/health")
        val ctx = createContext(method = "POST", path = "/health")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called for non-GET/HEAD methods")
    }

    @Test
    fun `should support custom endpoint and response text`() {
        // Arrange
        val middleware = HeartBeat("/ping", "pong")
        val ctx = createContext(method = "GET", path = "/ping")

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertFalse(nextCalled, "next() should not be called")
        assertEquals(200, ctx.response.status)
        assertEquals("pong", (ctx.response.body as ResponseBody.Text).value)
    }
}