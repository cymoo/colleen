package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test


class NoCacheTest {

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
    // NoCache Middleware Tests
    // ========================================================================

    @Test
    fun `should set no-cache headers`() {
        // Arrange
        val middleware = NoCache()
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        assertEquals(
            "no-store, no-cache, must-revalidate, private, max-age=0",
            ctx.response.headers["Cache-Control"]
        )
        assertEquals("no-cache", ctx.response.headers["Pragma"])
        assertNotNull(ctx.response.headers["Expires"])
    }

    @Test
    fun `should call next middleware`() {
        // Arrange
        val middleware = NoCache()
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
    }

    @Test
    fun `should set headers after next is called`() {
        // Arrange
        val middleware = NoCache()
        val ctx = createContext()
        var headersSetBeforeNext = false

        // Act
        middleware.invoke(ctx) {
            // Check if headers are set before next() completes
            headersSetBeforeNext = ctx.response.headers["Cache-Control"] != null
            next()
        }

        // Assert
        assertFalse(headersSetBeforeNext, "Headers should not be set before next()")
        assertNotNull(ctx.response.headers["Cache-Control"], "Headers should be set after next()")
    }
}