package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class SunsetTest {

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

    private fun createContext(): Context {
        val request = Request(method = "GET", path = "/")
        return Context(request = request, app = app)
    }

    // ========================================================================
    // Sunset Middleware Tests
    // ========================================================================

    @Test
    fun `should set Sunset header`() {
        // Arrange
        val sunsetAt = Instant.parse("2026-06-01T00:00:00Z")
        val middleware = Sunset(sunsetAt)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        assertNotNull(ctx.response.headers["Sunset"], "Sunset header should be set")
        assertTrue(ctx.response.headers["Sunset"]!!.contains("2026"))
    }

    @Test
    fun `should set both Sunset and Deprecation headers when deprecatedAt provided`() {
        // Arrange
        val sunsetAt = Instant.parse("2026-06-01T00:00:00Z")
        val deprecatedAt = Instant.parse("2026-01-01T00:00:00Z")
        val middleware = Sunset(sunsetAt, deprecatedAt)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertNotNull(ctx.response.headers["Sunset"], "Sunset header should be set")
        assertNotNull(ctx.response.headers["Deprecation"], "Deprecation header should be set")
    }

    @Test
    fun `should not set Deprecation header when deprecatedAt is null`() {
        // Arrange
        val sunsetAt = Instant.parse("2026-06-01T00:00:00Z")
        val middleware = Sunset(sunsetAt, deprecatedAt = null)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertNotNull(ctx.response.headers["Sunset"], "Sunset header should be set")
        assertNull(ctx.response.headers["Deprecation"], "Deprecation header should not be set")
    }

    @Test
    fun `should add Link headers`() {
        // Arrange
        val sunsetAt = Instant.parse("2026-06-01T00:00:00Z")
        val links = listOf(
            "<https://api.example.com/v2>; rel=\"successor-version\"",
            "<https://docs.example.com/migration>; rel=\"deprecation\""
        )
        val middleware = Sunset(sunsetAt, links = links)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val linkHeaders = ctx.response.headers.getAll("Link")
        assertEquals(2, linkHeaders.size, "Should have 2 Link headers")
        assertTrue(linkHeaders.contains(links[0]))
        assertTrue(linkHeaders.contains(links[1]))
    }

    @Test
    fun `should format dates in RFC 1123 format`() {
        // Arrange
        val sunsetAt = Instant.parse("2026-06-01T12:00:00Z")
        val middleware = Sunset(sunsetAt)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        val sunsetHeader = ctx.response.headers["Sunset"]
        assertNotNull(sunsetHeader)
        assertTrue(sunsetHeader!!.contains("Jun 2026"), "Should contain month and year")
        assertTrue(sunsetHeader.endsWith("GMT"), "Should end with GMT")
        assertTrue(sunsetHeader.contains("12:00:00"), "Should contain time")
    }
}