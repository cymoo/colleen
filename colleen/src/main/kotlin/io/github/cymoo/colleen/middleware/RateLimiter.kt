package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.TooManyRequests
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable state of a token bucket
 *
 * @property tokens Current number of available tokens
 * @property lastRefillTime Last time tokens were refilled (in nanoseconds)
 */
private data class TokenBucketState(
    val tokens: Double,
    val lastRefillTime: Long
)

/**
 * Rate Limiter Middleware using Token Bucket algorithm
 *
 * This implementation uses lock-free CAS (Compare-And-Swap) operations for thread safety,
 * making it suitable for virtual threads. Each key (e.g., IP address) has its own
 * `AtomicReference<TokenBucketState>`, and probabilistic cleanup prevents memory leaks.
 *
 * @property capacity Maximum number of tokens in the bucket
 * @property refillRate Number of tokens added per second
 * @property keyExtractor Function to extract rate limit key from context (default: IP address)
 * @property onLimitExceeded Callback when rate limit is exceeded (default: throw TooManyRequests)
 * @property bucketTtlSeconds Time-to-live for inactive buckets in seconds (default: 1 hour)
 * @property cleanupProbability Probability of triggering cleanup per request (default: 1%)
 */
class RateLimiter @JvmOverloads constructor(
    private val capacity: Long = 100,
    private val refillRate: Double = 10.0,
    private val keyExtractor: (Context) -> String = { ctx -> ctx.request.ip },
    // Default 429 carries Retry-After = time to earn one token (rounded up),
    // which the framework's default error handler emits as a Retry-After header.
    private val onLimitExceeded: (Context) -> Unit = { _ ->
        throw TooManyRequests(retryAfter = kotlin.math.ceil(1.0 / refillRate).toInt().coerceAtLeast(1))
    },
    private val bucketTtlSeconds: Long = 3600,
    private val cleanupProbability: Double = 0.01
) : Middleware {

    // Each key maps to an atomic reference holding the bucket state
    private val buckets = ConcurrentHashMap<String, AtomicReference<TokenBucketState>>()

    // Ensures only one thread performs cleanup at a time
    private val cleanupInProgress = AtomicBoolean(false)

    override fun invoke(ctx: Context, next: Next) {
        val key = keyExtractor(ctx)

        // Probabilistic cleanup: trigger with configurable probability to amortize cost
        if (ThreadLocalRandom.current().nextDouble() < cleanupProbability) {
            tryCleanup()
        }

        // Get existing bucket or create new one with full capacity
        val bucketRef = buckets.computeIfAbsent(key) {
            AtomicReference(TokenBucketState(capacity.toDouble(), System.nanoTime()))
        }

        // Set rate limit headers
        ctx.response.header("X-RateLimit-Limit", capacity.toString())

        // Attempt to consume a token using CAS loop
        val (allowed, remaining) = consumeToken(bucketRef)

        ctx.response.header("X-RateLimit-Remaining", remaining.toString())

        // Calculate when the bucket will be full again
        val secondsToFull = ((capacity - remaining).toDouble() / refillRate).toLong()
        val resetTime = System.currentTimeMillis() / 1000 + secondsToFull
        ctx.response.header("X-RateLimit-Reset", resetTime.toString())

        if (!allowed) {
            onLimitExceeded(ctx)
            return
        }

        next()
    }

    /**
     * Attempts to consume one token from the bucket using CAS for thread safety
     *
     * This method implements a lock-free retry loop:
     * 1. Read current state
     * 2. Calculate refilled tokens based on elapsed time
     * 3. Check if consumption is possible
     * 4. If yes, create new state with one token consumed and CAS update
     * 5. If CAS fails (another thread updated first), retry from step 1
     *
     * Note: When tokens are insufficient, we return immediately without updating state.
     * This avoids unnecessary CAS contention among rejected requests and is acceptable
     * because the remaining count doesn't need to be perfectly accurate in real-time.
     *
     * @param bucketRef Atomic reference to the bucket state
     * @return Pair of (whether token was consumed, remaining tokens)
     */
    private fun consumeToken(bucketRef: AtomicReference<TokenBucketState>): Pair<Boolean, Long> {
        while (true) {
            val current = bucketRef.get()
            val now = System.nanoTime()

            // Calculate tokens after refill based on elapsed time
            val elapsedSeconds = (now - current.lastRefillTime) / 1_000_000_000.0
            val tokensToAdd = elapsedSeconds * refillRate
            val refilledTokens = minOf(capacity.toDouble(), current.tokens + tokensToAdd)

            // Check if we have enough tokens to consume
            if (refilledTokens < 1.0) {
                // Insufficient tokens: return immediately without updating state
                // This avoids CAS contention when multiple requests are being rejected
                return false to refilledTokens.toLong()
            }

            // Attempt to consume one token by creating new state
            val newState = TokenBucketState(
                tokens = refilledTokens - 1.0,
                lastRefillTime = now
            )

            // CAS: if successful, we're done; if failed, another thread updated first, retry
            if (bucketRef.compareAndSet(current, newState)) {
                return true to (refilledTokens - 1.0).toLong()
            }
        }
    }

    /**
     * Removes expired bucket entries based on TTL
     *
     * Entries are considered expired if they haven't been accessed within bucketTtlSeconds.
     * This prevents memory leaks from accumulating inactive rate limit keys.
     */
    private fun cleanup() {
        val now = System.nanoTime()
        val ttlNanos = bucketTtlSeconds * 1_000_000_000L

        buckets.entries.removeIf { (_, bucketRef) ->
            val state = bucketRef.get()
            (now - state.lastRefillTime) > ttlNanos
        }
    }

    /**
     * Attempts to trigger cleanup, ensuring only one thread performs cleanup at a time
     *
     * Uses an atomic flag to prevent multiple threads from cleaning up simultaneously,
     * which would waste CPU cycles scanning the same map.
     */
    private fun tryCleanup() {
        if (cleanupInProgress.compareAndSet(false, true)) {
            try {
                cleanup()
            } finally {
                cleanupInProgress.set(false)
            }
        }
    }
}