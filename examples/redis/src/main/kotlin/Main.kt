import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.ResponseBody
import redis.clients.jedis.RedisClient

/**
 * Redis Cache Middleware Demo
 *
 * Features demonstrated:
 * - Integration of Redis with Colleen framework
 * - Dependency injection via app.provide()
 * - Middleware-based HTTP response caching
 * - Different TTL configuration per route
 * - Automatic caching for GET requests
 * - Support for text and JSON responses
 */
fun main() {
    val redis = newRedisClient()
    val app = Colleen()

    // Register Redis client as a global service,
    // so it can be accessed inside middleware and handlers
    app.provide(redis)

    // Route with short cache TTL (10 seconds)
    app.get("/").use(RedisCache(10)).handle {
        println("Enter /")
        "hello world"
    }

    // Route with longer cache TTL (15 seconds)
    // Demonstrates JSON response caching
    app.get("/hello").use(RedisCache(15)).handle {
        println("Enter /hello")
        mapOf("msg" to "hello world")
    }

    app.listen()
    println("Server running on http://localhost:8000")
}

/**
 * Middleware that provides simple Redis-based HTTP response caching.
 *
 * Features:
 * - Only caches successful GET requests
 * - Supports text and JSON responses
 * - Adds "X-Cache: HIT" header when cache is used
 */
class RedisCache(
    private val ttlSeconds: Long = 60,
    private val keyGenerator: (Context) -> String = { "cache:${it.request.uri}" }
) : Middleware {

    override fun invoke(ctx: Context, next: Next) {
        val redis = ctx.getService<RedisClient>()

        // // Skip caching for non-GET requests
        if (ctx.request.method != "GET") {
            next()
            return
        }

        val cacheKey = keyGenerator(ctx)

        // Attempt to serve response from cache
        if (tryServeFromCache(ctx, redis, cacheKey)) {
            return
        }

        // Execute next middleware / handler
        next()

        // Cache the response if possible
        cacheIfPossible(ctx, redis, cacheKey)
    }

    /**
     * Try to read cached response from Redis and write it directly to the context.
     *
     * @return true if cache hit, false otherwise
     */
    private fun tryServeFromCache(ctx: Context, redis: RedisClient, key: String): Boolean {
        val cached = redis.hgetAll(key)

        val contentType = cached["contentType"]
        val body = cached["body"]

        // Cache hit: return cached response
        if (contentType != null && body != null) {
            ctx.header("X-Cache", "HIT")
                .text(body, contentType)
            return true
        }

        return false
    }

    /**
     * Cache response only when:
     * - status is 200
     * - response type is supported (text or JSON)
     */
    private fun cacheIfPossible(ctx: Context, redis: RedisClient, key: String) {
        val response = ctx.response

        // Only cache successful responses
        if (ctx.error != null || response.status != 200) return

        val contentType = response.header("Content-Type") ?: return

        // Extract response body as string
        val body = when (val b = response.body) {
            is ResponseBody.Text -> b.value
            is ResponseBody.Json -> ctx.jsonMapper.toJsonString(b.value ?: "null")
            // Unsupported response type
            else -> return
        }

        // Store cache data atomically using pipeline
        // Serialize and store response into Redis with TTL
        redis.pipeline { pipe ->
            pipe.hset(key, mapOf("contentType" to contentType, "body" to body))
            pipe.expire(key, ttlSeconds)
        }
    }
}
