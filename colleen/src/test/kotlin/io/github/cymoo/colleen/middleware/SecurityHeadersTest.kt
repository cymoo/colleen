package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SecurityHeadersTest {

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

    private fun createContext(isSecure: Boolean = false): Context {
        val request = Request(
            method = "GET",
            path = "/",
            serverInfo = Request.ServerInfo(isSecure = isSecure)
        )
        return Context(request = request, app = app)
    }

    // ========================================================================
    // SecurityHeaders Middleware Tests
    // ========================================================================

    @Test
    fun `should set basic security headers with defaults`() {
        // Arrange
        val middleware = SecurityHeaders()
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        assertEquals("DENY", ctx.response.headers["X-Frame-Options"])
        assertEquals("nosniff", ctx.response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", ctx.response.headers["Referrer-Policy"])
    }

    @Test
    fun `should set custom X-Frame-Options`() {
        // Arrange
        val middleware = SecurityHeaders(xFrameOptions = "SAMEORIGIN")
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals("SAMEORIGIN", ctx.response.headers["X-Frame-Options"])
    }

    @Test
    fun `should set Content-Security-Policy when provided`() {
        // Arrange
        val csp = "default-src 'self'; script-src 'self' 'unsafe-inline'"
        val middleware = SecurityHeaders(contentSecurityPolicy = csp)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(csp, ctx.response.headers["Content-Security-Policy"])
    }

    @Test
    fun `should not set Content-Security-Policy when null`() {
        // Arrange
        val middleware = SecurityHeaders(contentSecurityPolicy = null)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertNull(ctx.response.headers["Content-Security-Policy"])
    }

    @Test
    fun `should set Permissions-Policy when provided`() {
        // Arrange
        val policy = "geolocation=(), microphone=(), camera=()"
        val middleware = SecurityHeaders(permissionsPolicy = policy)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(policy, ctx.response.headers["Permissions-Policy"])
    }

    @Test
    fun `should set HSTS on HTTPS connections`() {
        // Arrange
        val middleware = SecurityHeaders(strictTransportSecurity = "max-age=31536000; includeSubDomains")
        val ctx = createContext(isSecure = true)

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertEquals(
            "max-age=31536000; includeSubDomains",
            ctx.response.headers["Strict-Transport-Security"]
        )
    }

    @Test
    fun `should not set HSTS on HTTP connections`() {
        // Arrange
        val middleware = SecurityHeaders(strictTransportSecurity = "max-age=31536000")
        val ctx = createContext(isSecure = false)

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertNull(
            ctx.response.headers["Strict-Transport-Security"],
            "HSTS should not be set on non-HTTPS connections"
        )
    }

    @Test
    fun `should set headers after next is called`() {
        // Arrange
        val middleware = SecurityHeaders()
        val ctx = createContext()
        var headersSetBeforeNext = false

        // Act
        middleware.invoke(ctx) {
            headersSetBeforeNext = ctx.response.headers["X-Frame-Options"] != null
            next()
        }

        // Assert
        assertFalse(headersSetBeforeNext, "Headers should not be set before next()")
        assertNotNull(ctx.response.headers["X-Frame-Options"], "Headers should be set after next()")
    }
}