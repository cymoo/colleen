import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.TooManyRequests
import io.github.cymoo.colleen.middleware.RateLimiter

/**
 * RateLimiter Demo
 *
 * Demonstrates the RateLimiter middleware which uses a Token Bucket algorithm to
 * enforce request rate limits. It is thread-safe via lock-free CAS operations
 * and uses probabilistic cleanup to prevent memory leaks.
 *
 * Three response headers are added to every request:
 * - X-RateLimit-Limit     — max tokens (capacity)
 * - X-RateLimit-Remaining — tokens remaining after this request
 * - X-RateLimit-Reset     — Unix timestamp when the bucket will be full again
 *
 * When the limit is exceeded the default behaviour is to throw TooManyRequests (HTTP 429).
 * This can be overridden with a custom onLimitExceeded handler.
 *
 * Try (run the command multiple times rapidly to trigger 429):
 *   for i in {1..15}; do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8085/rate-limit/strict; done
 *
 *   # Custom key: rate limit by API key header instead of IP
 *   curl -i -H 'X-API-Key: user-abc' http://localhost:8085/rate-limit/by-key
 */
fun rateLimiterApp(): Colleen {
    val app = Colleen()

    // 1. Default: 100 req capacity, refills at 10 tokens/sec, keyed by IP
    app.get("/default")
        .use(RateLimiter(capacity = 100, refillRate = 10.0))
        .handle {
            mapOf(
                "message" to "Default rate limiter: 100 req capacity, refills 10/sec.",
                "hint" to "Check X-RateLimit-* headers in the response."
            )
        }

    // 2. Strict: very low capacity to make triggering a 429 easy during demo
    app.get("/strict")
        .use(RateLimiter(capacity = 10, refillRate = 1.0))
        .handle {
            mapOf(
                "message" to "Strict rate limiter: 10 req capacity, refills 1/sec.",
                "hint" to "Hit this endpoint quickly to trigger a 429 Too Many Requests."
            )
        }

    // 3. Custom onLimitExceeded: return JSON instead of throwing an exception
    app.get("/custom-response")
        .use(
            RateLimiter(
                capacity = 5,
                refillRate = 0.5,
                onLimitExceeded = { ctx ->
                    ctx.status(429).json(
                        mapOf(
                            "error" to "rate_limit_exceeded",
                            "message" to "Too many requests. Please slow down.",
                            "retryAfter" to ctx.response.header("X-RateLimit-Reset")
                        )
                    )
                }
            )
        )
        .handle {
            mapOf("message" to "Custom error response on rate limit exceeded.")
        }

    // 4. Custom key extractor: rate limit by API key header (not by IP)
    //    Each unique X-API-Key value has its own independent token bucket
    app.get("/by-key")
        .use(
            RateLimiter(
                capacity = 20,
                refillRate = 5.0,
                keyExtractor = { ctx ->
                    ctx.header("X-API-Key") ?: ctx.request.ip
                }
            )
        )
        .handle { ctx ->
            mapOf(
                "message" to "Rate-limited per API key (falls back to IP if header absent).",
                "key" to (ctx.header("X-API-Key") ?: "IP: ${ctx.request.ip}")
            )
        }

    return app
}
