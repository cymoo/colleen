package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

class RateLimiterTest {

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
        ip: String = "127.0.0.1",
        headers: Map<String, String> = emptyMap()
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers().apply {
                headers.forEach { (key, value) -> set(key, value) }
            },
            metadata = Request.RequestMetadata(remoteAddr = ip)
        )
        return Context(request = request, app = app)
    }

    // ========================================================================
    // Basic Rate Limiting Tests
    // ========================================================================

    @Test
    fun `should allow request when under rate limit`() {
        // Arrange
        val middleware = RateLimiter(capacity = 10, refillRate = 1.0)
        val ctx = createContext()

        // Act
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(nextCalled, "next() should be called")
        assertEquals("10", ctx.response.headers["X-RateLimit-Limit"])
        assertEquals("9", ctx.response.headers["X-RateLimit-Remaining"])
        assertNotNull(ctx.response.headers["X-RateLimit-Reset"])
    }

    @Test
    fun `should block request when rate limit exceeded`() {
        // Arrange
        val capacity = 5L
        val middleware = RateLimiter(capacity = capacity, refillRate = 1.0)
        val ctx = createContext()

        // Act: consume all tokens
        repeat(capacity.toInt()) {
            middleware.invoke(createContext(), ::next)
        }

        nextCalled = false
        // Assert: next request should be blocked
        assertThrows<TooManyRequests> {
            middleware.invoke(ctx, ::next)
        }
        assertFalse(nextCalled, "next() should not be called when rate limited")
        assertEquals("0", ctx.response.headers["X-RateLimit-Remaining"])
    }

    @Test
    fun `should set correct rate limit headers`() {
        // Arrange
        val middleware = RateLimiter(capacity = 100, refillRate = 10.0)
        val ctx = createContext()

        // Act
        repeat(90) {
            middleware.invoke(ctx, ::next)
        }

        // Assert
        assertEquals("100", ctx.response.headers["X-RateLimit-Limit"])
        assertEquals("10", ctx.response.headers["X-RateLimit-Remaining"])

        val resetTime = ctx.response.headers["X-RateLimit-Reset"]?.toLongOrNull()
        assertNotNull(resetTime)
        assertTrue(resetTime > System.currentTimeMillis() / 1000, "Reset time should be in the future")
    }

    // ========================================================================
    // Token Refill Tests
    // ========================================================================

    @Test
    fun `should refill tokens over time`() {
        // Arrange
        val middleware = RateLimiter(capacity = 10, refillRate = 10.0) // 10 tokens/second
        val ctx = createContext()

        // Act: consume all tokens
        repeat(10) {
            middleware.invoke(createContext(), ::next)
        }

        // Assert: should be blocked immediately
        assertThrows<TooManyRequests> {
            middleware.invoke(ctx, ::next)
        }

        // Act: wait for refill (100ms = 1 token at 10 tokens/second)
        Thread.sleep(150)

        // Assert: should allow 1 request after refill
        nextCalled = false
        middleware.invoke(createContext(), ::next)
        assertTrue(nextCalled, "Should allow request after refill")
    }

    @Test
    fun `should not exceed capacity when refilling`() {
        // Arrange
        val middleware = RateLimiter(capacity = 10, refillRate = 100.0)
        val ctx = createContext()

        // Act: consume 5 tokens
        repeat(5) {
            middleware.invoke(createContext(), ::next)
        }

        // Wait long enough to refill more than capacity
        Thread.sleep(200) // Would refill 20 tokens if no cap

        // Consume 10 tokens (should succeed if capped at capacity)
        repeat(10) { i ->
            nextCalled = false
            middleware.invoke(createContext(), ::next)
            assertTrue(nextCalled, "Request $i should succeed")
        }

        // Assert: 11th request should fail
        assertThrows<TooManyRequests> {
            middleware.invoke(ctx, ::next)
        }
    }

    // ========================================================================
    // Key Extraction Tests
    // ========================================================================

    @Test
    fun `should rate limit per IP address by default`() {
        // Arrange
        val middleware = RateLimiter(capacity = 2, refillRate = 1.0)

        // Act & Assert: IP 1 can make 2 requests
        middleware.invoke(createContext(ip = "192.168.1.1"), ::next)
        middleware.invoke(createContext(ip = "192.168.1.1"), ::next)
        assertThrows<TooManyRequests> {
            middleware.invoke(createContext(ip = "192.168.1.1"), ::next)
        }

        // Assert: IP 2 can also make 2 requests (separate bucket)
        nextCalled = false
        middleware.invoke(createContext(ip = "192.168.1.2"), ::next)
        assertTrue(nextCalled, "Different IP should have separate rate limit")
    }

    @Test
    fun `should use custom key extractor`() {
        // Arrange
        val middleware = RateLimiter(
            capacity = 2,
            refillRate = 1.0,
            keyExtractor = { ctx -> ctx.request.headers["X-User-ID"] ?: "anonymous" }
        )

        // Act & Assert: Same user ID should share rate limit
        middleware.invoke(createContext(headers = mapOf("X-User-ID" to "user123")), ::next)
        middleware.invoke(createContext(headers = mapOf("X-User-ID" to "user123")), ::next)
        assertThrows<TooManyRequests> {
            middleware.invoke(createContext(headers = mapOf("X-User-ID" to "user123")), ::next)
        }

        // Assert: Different user should have separate limit
        nextCalled = false
        middleware.invoke(createContext(headers = mapOf("X-User-ID" to "user456")), ::next)
        assertTrue(nextCalled, "Different user should have separate rate limit")
    }

    // ========================================================================
    // Custom Handler Tests
    // ========================================================================

    @Test
    fun `should use custom onLimitExceeded handler`() {
        // Arrange
        var handlerCalled = false
        var capturedContext: Context? = null
        val middleware = RateLimiter(
            capacity = 1,
            refillRate = 1.0,
            onLimitExceeded = { ctx ->
                handlerCalled = true
                capturedContext = ctx
                ctx.response.status = 429
                ctx.status(429).text("Rate limit exceeded")
            }
        )

        // Act: consume token and trigger limit
        middleware.invoke(createContext(), ::next)
        val ctx = createContext()
        middleware.invoke(ctx, ::next)

        // Assert
        assertTrue(handlerCalled, "Custom handler should be called")
        assertSame(ctx, capturedContext, "Handler should receive context")
        assertEquals(429, ctx.response.status)
        assertEquals("Rate limit exceeded", (ctx.response.body as ResponseBody.Text).value)
    }

    // ========================================================================
    // Concurrent Access Tests
    // ========================================================================

    @Test
    fun `should handle concurrent requests correctly`() {
        // Arrange
        val capacity = 100L
        val middleware = RateLimiter(capacity = capacity, refillRate = 10.0)
        val threadCount = 20
        val requestsPerThread = 10
        val totalRequests = threadCount * requestsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val latch = CountDownLatch(totalRequests)

        // Act: submit concurrent requests
        repeat(totalRequests) {
            executor.submit {
                try {
                    val ctx = createContext()
                    middleware.invoke(ctx) {}
                    successCount.incrementAndGet()
                } catch (_: TooManyRequests) {
                    failCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All requests should complete")
        assertEquals(totalRequests, successCount.get() + failCount.get(), "All requests should be counted")
        assertTrue(successCount.get() <= capacity.toInt(), "Success count should not exceed capacity")
        assertTrue(failCount.get() >= totalRequests - capacity.toInt(), "Excess requests should fail")

        executor.shutdown()
    }

    @Test
    fun `should maintain consistent state under high concurrency`() {
        // Arrange
        val capacity = 50L
        val middleware = RateLimiter(capacity = capacity, refillRate = 1.0)
        val threadCount = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)

        // Act: all threads compete for same IP
        repeat(threadCount) {
            executor.submit {
                try {
                    middleware.invoke(createContext(ip = "same-ip")) {}
                    successCount.incrementAndGet()
                } catch (_: TooManyRequests) {
                    // Expected for some threads
                } finally {
                    latch.countDown()
                }
            }
        }

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All requests should complete")
        assertEquals(capacity.toInt(), successCount.get(), "Should allow exactly capacity requests")

        executor.shutdown()
    }

    @Test
    fun `should isolate rate limits between different keys under concurrency`() {
        // Arrange
        val capacity = 10L
        val middleware = RateLimiter(capacity = capacity, refillRate = 1.0)
        val ipCount = 5
        val requestsPerIp = 15
        val executor = Executors.newFixedThreadPool(ipCount * 2)
        val successPerIp = ConcurrentHashMap<String, AtomicInteger>()
        val latch = CountDownLatch(ipCount * requestsPerIp)

        // Act: concurrent requests from multiple IPs
        repeat(ipCount) { ipIndex ->
            val ip = "192.168.1.$ipIndex"
            successPerIp[ip] = AtomicInteger(0)

            repeat(requestsPerIp) {
                executor.submit {
                    try {
                        middleware.invoke(createContext(ip = ip)) {}
                        successPerIp[ip]!!.incrementAndGet()
                    } catch (_: TooManyRequests) {
                        // Expected
                    } finally {
                        latch.countDown()
                    }
                }
            }
        }

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All requests should complete")
        successPerIp.forEach { (ip, count) ->
            assertEquals(capacity.toInt(), count.get(), "Each IP should have independent limit: $ip")
        }

        executor.shutdown()
    }

    // ========================================================================
    // Edge Cases Tests
    // ========================================================================

    @Test
    fun `should handle zero capacity`() {
        // Arrange
        val middleware = RateLimiter(capacity = 0, refillRate = 1.0)
        val ctx = createContext()

        // Act & Assert
        assertThrows<TooManyRequests> {
            middleware.invoke(ctx, ::next)
        }
        assertFalse(nextCalled, "Should not call next with zero capacity")
    }

    @Test
    fun `should handle very high capacity`() {
        // Arrange
        val middleware = RateLimiter(capacity = 1_000_000, refillRate = 1.0)

        // Act: make many requests quickly
        repeat(1000) {
            nextCalled = false
            middleware.invoke(createContext(), ::next)
            assertTrue(nextCalled, "Should handle high capacity")
        }
    }

    @Test
    fun `should handle very fast refill rate`() {
        // Arrange
        val middleware = RateLimiter(capacity = 10, refillRate = 1000.0) // 1000 tokens/second

        // Act: consume all tokens
        repeat(10) {
            middleware.invoke(createContext(), ::next)
        }

        // Wait minimal time (10ms = 10 tokens at 1000/sec)
        Thread.sleep(10)

        // Assert: should be fully refilled
        repeat(10) { i ->
            nextCalled = false
            middleware.invoke(createContext(), ::next)
            assertTrue(nextCalled, "Request $i should succeed after fast refill")
        }
    }

    @Test
    fun `should handle fractional tokens correctly`() {
        // Arrange
        val middleware = RateLimiter(capacity = 10, refillRate = 2.5) // 2.5 tokens/second

        // Act: consume all tokens
        repeat(10) {
            middleware.invoke(createContext(), ::next)
        }

        // Wait for fractional refill (200ms = 0.5 tokens)
        Thread.sleep(200)

        // Assert: should still be blocked (< 1 token)
        assertThrows<TooManyRequests> {
            middleware.invoke(createContext(), ::next)
        }

        // Wait more (200ms more = 1 token total)
        Thread.sleep(200)

        // Assert: should allow 1 request
        nextCalled = false
        middleware.invoke(createContext(), ::next)
        assertTrue(nextCalled, "Should allow request after fractional refill accumulates to 1")
    }

    @Test
    fun `should handle requests at exact capacity boundary`() {
        // Arrange
        val capacity = 5L
        val middleware = RateLimiter(capacity = capacity, refillRate = 1.0)

        // Act: consume exactly capacity tokens
        repeat(capacity.toInt()) { i ->
            nextCalled = false
            middleware.invoke(createContext(), ::next)
            assertTrue(nextCalled, "Request $i should succeed")
        }

        // Assert: next request should fail
        assertThrows<TooManyRequests> {
            middleware.invoke(createContext(), ::next)
        }
    }

    // ========================================================================
    // Memory Management Tests
    // ========================================================================

    @Test
    fun `should cleanup expired buckets probabilistically`() {
        // Arrange
        val middleware = RateLimiter(
            capacity = 10,
            refillRate = 1.0,
            bucketTtlSeconds = 1, // 1 second TTL
            cleanupProbability = 1.0 // Always cleanup for testing
        )

        // Act: create buckets for multiple IPs
        repeat(10) { i ->
            middleware.invoke(createContext(ip = "192.168.1.$i"), ::next)
        }

        // Wait for TTL to expire
        Thread.sleep(1100)

        // Trigger cleanup by making a request
        middleware.invoke(createContext(ip = "192.168.1.100"), ::next)

        // Note: We can't directly assert on internal bucket map size,
        // but we can verify the system still works correctly
        nextCalled = false
        middleware.invoke(createContext(ip = "192.168.1.1"), ::next)
        assertTrue(nextCalled, "Should recreate expired bucket and work correctly")
    }

    @Test
    fun `should not cleanup active buckets`() {
        // Arrange
        val middleware = RateLimiter(
            capacity = 10,
            refillRate = 1.0,
            bucketTtlSeconds = 2,
            cleanupProbability = 1.0
        )
        val ip = "192.168.1.1"

        // Act: make requests periodically to keep bucket active
        repeat(5) {
            middleware.invoke(createContext(ip = ip), ::next)
            Thread.sleep(500) // Sleep less than TTL
        }

        // Assert: bucket should still have consumed tokens (not reset to full capacity)
        val ctx = createContext(ip = ip)
        middleware.invoke(ctx, ::next)
        val remaining = ctx.response.headers["X-RateLimit-Remaining"]?.toIntOrNull()
        assertNotNull(remaining)
        assertTrue(remaining < 10, "Active bucket should not be cleaned up")
    }

    // ========================================================================
    // Reset Time Calculation Tests
    // ========================================================================

    @Test
    fun `should calculate correct reset time`() {
        // Arrange
        val capacity = 10L
        val refillRate = 2.0 // 2 tokens per second
        val middleware = RateLimiter(capacity = capacity, refillRate = refillRate)
        val ctx = createContext()

        // Act: consume 6 tokens (4 remaining)
        repeat(6) {
            middleware.invoke(createContext(), ::next)
        }
        middleware.invoke(ctx, ::next)

        // Assert: reset time should be ~3 seconds from now (6 tokens / 2 per second)
        val resetTime = ctx.response.headers["X-RateLimit-Reset"]?.toLongOrNull()
        assertNotNull(resetTime)
        val expectedResetTime = System.currentTimeMillis() / 1000 + 3
        assertTrue(
            resetTime in (expectedResetTime - 1)..(expectedResetTime + 1),
            "Reset time should be approximately 3 seconds from now"
        )
    }

    @Test
    fun `should update reset time as tokens are consumed`() {
        // Arrange
        val middleware = RateLimiter(capacity = 10, refillRate = 1.0)

        // Act & Assert: reset time should increase as more tokens are consumed
        val resetTimes = mutableListOf<Long>()
        repeat(5) {
            val ctx = createContext()
            middleware.invoke(ctx, ::next)
            resetTimes.add(ctx.response.headers["X-RateLimit-Reset"]!!.toLong())
        }

        // Verify reset times are increasing (or staying the same due to timing)
        for (i in 1 until resetTimes.size) {
            assertTrue(
                resetTimes[i] >= resetTimes[i - 1],
                "Reset time should not decrease as tokens are consumed"
            )
        }
    }
}