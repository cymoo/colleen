import redis.clients.jedis.*
import java.net.URI
import java.time.Duration

/**
 * Redis client utilities for simplified client creation and common operations.
 *
 * This file provides:
 * 1. Factory functions for easy client creation
 * 2. Extension functions for transactions and pipelines
 * 3. Direct access to native Jedis RedisClient/RedisClusterClient
 */

// ========== Factory Functions for Client Creation ==========

/**
 * Create a standalone RedisClient with simplified configuration.
 */
fun newRedisClient(
    host: String = "localhost",
    port: Int = 6379,
    password: String? = null,
    database: Int = 0,
    timeout: Int = 2000,
    maxTotal: Int = 8,
    maxIdle: Int = 8,
    minIdle: Int = 0,
    maxWaitMillis: Long = 3000
): RedisClient {
    val poolConfig = buildPoolConfig(maxTotal, maxIdle, minIdle, maxWaitMillis)

    val clientConfig = DefaultJedisClientConfig.builder()
        .apply {
            password?.let { password(it) }
            database(database)
            socketTimeoutMillis(timeout)
            connectionTimeoutMillis(timeout)
        }
        .build()

    return RedisClient.builder()
        .hostAndPort(host, port)
        .clientConfig(clientConfig)
        .poolConfig(poolConfig)
        .build()
}

/**
 * Create a standalone RedisClient from a URI.
 */
fun newRedisClient(uri: URI): RedisClient {
    return RedisClient.create(uri.toString())
}

/**
 * Create a standalone RedisClient from a URI string.
 */
fun newRedisClient(uri: String): RedisClient {
    return RedisClient.create(uri)
}

/**
 * Create a Redis cluster client from individual node addresses.
 *
 * Example:
 * ```
 * val cluster = newRedisClusterClient(
 *     "127.0.0.1:6379",
 *     "127.0.0.1:6380"
 * )
 * ```
 *
 * @param nodeAddresses Vararg of "host:port" strings
 * @throws IllegalArgumentException if node address format is invalid
 */
fun newRedisClusterClient(
    vararg nodeAddresses: String,
    password: String? = null,
    timeout: Int = 2000,
    maxTotal: Int = 8,
    maxIdle: Int = 8,
    minIdle: Int = 0,
    maxWaitMillis: Long = 3000
): RedisClusterClient {

    require(nodeAddresses.isNotEmpty()) {
        "At least one Redis cluster node address must be provided"
    }

    val nodes = nodeAddresses.map { address ->
        val parts = address.split(":")
        require(parts.size == 2) {
            "Invalid node address format: $address. Expected 'host:port'"
        }
        HostAndPort(parts[0], parts[1].toInt())
    }.toSet()

    val poolConfig = buildPoolConfig(maxTotal, maxIdle, minIdle, maxWaitMillis)

    val clientConfig = DefaultJedisClientConfig.builder()
        .apply {
            password?.let { password(it) }
            socketTimeoutMillis(timeout)
            connectionTimeoutMillis(timeout)
        }
        .build()

    return RedisClusterClient.builder()
        .nodes(nodes)
        .clientConfig(clientConfig)
        .poolConfig(poolConfig)
        .build()
}


private fun buildPoolConfig(
    maxTotal: Int,
    maxIdle: Int,
    minIdle: Int,
    maxWaitMillis: Long
) = ConnectionPoolConfig().apply {
    this.maxTotal = maxTotal
    this.maxIdle = maxIdle
    this.minIdle = minIdle

    this.testOnBorrow = true
    this.testWhileIdle = true
    this.testOnReturn = false

    this.blockWhenExhausted = true
    this.setMaxWait(Duration.ofMillis(maxWaitMillis))
}

// ========== Extension Functions for Transactions ==========

/**
 * Execute a Redis transaction with automatic commit or rollback.
 *
 * All commands executed inside the block are queued as part of a Redis MULTI/EXEC transaction.
 * The transaction is automatically committed when the block completes successfully.
 * If any exception occurs during execution, the transaction is automatically discarded.
 *
 * This function is flexible and can return any custom result, such as:
 * - Response objects for deferred result retrieval
 * - Aggregated values
 * - Domain-specific data structures
 *
 * Example:
 * ```
 * val result = redis.transaction { tx ->
 *     val r1 = tx.get("key1")
 *     val r2 = tx.incr("counter")
 *     r1.get() to r2.get()
 * }
 * ```
 *
 * @param action block in which transactional commands are executed
 * @return the value returned by the block
 * @throws Exception if any error occurs during transaction execution
 */
inline fun <T> UnifiedJedis.transaction(
    crossinline action: (AbstractTransaction) -> T
): T {
    return multi().use { tx ->
        try {
            val result = action(tx)
            tx.exec()
            result
        } catch (e: Exception) {
            tx.discard()
            throw e
        }
    }
}

// ========== Extension Functions for Pipelines ==========

/**
 * Execute Redis commands using a pipeline and return a custom result.
 *
 * All commands issued inside the block are buffered and sent to Redis in a single batch,
 * significantly reducing network round trips.
 *
 * The block typically returns Response objects, which can be resolved after sync().
 *
 * Example:
 * ```
 * val responses = redis.pipeline { pipe ->
 *     val r1 = pipe.get("key1")
 *     val r2 = pipe.get("key2")
 *     listOf(r1, r2)
 * }
 *
 * val values = responses.map { it.get() }
 * ```
 *
 * Important notes:
 * - Commands are not executed immediately; they are queued until sync() is called.
 * - The function ensures sync() is always invoked before returning.
 * - This method is thread-safe as each pipeline instance is used exclusively within the block.
 *
 * @param action block in which pipelined commands are executed
 * @return the value returned by the block (commonly Response objects or custom structures)
 */
inline fun <T> UnifiedJedis.pipeline(
    crossinline action: (AbstractPipeline) -> T
): T {
    return pipelined().use { pipe ->
        val result = action(pipe)
        pipe.sync()
        result
    }
}

// ========== Extension Functions for Common Patterns ==========

/**
 * Check if the client connection is still alive.
 */
fun UnifiedJedis.isAlive(): Boolean {
    return try {
        ping()
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Delete multiple keys from a collection and return the count of deleted keys.
 */
fun UnifiedJedis.delAll(keys: Collection<String>): Long {
    if (keys.isEmpty()) return 0L
    return del(*keys.toTypedArray())
}

/**
 * Check if all given keys exist.
 */
fun UnifiedJedis.existsAll(vararg keys: String): Boolean {
    if (keys.isEmpty()) return true
    return exists(*keys) == keys.size.toLong()
}

/**
 * Check if any of the given keys exist.
 */
fun UnifiedJedis.existsAny(vararg keys: String): Boolean {
    if (keys.isEmpty()) return false
    return exists(*keys) > 0L
}

/**
 * Increment a counter and set expiration if it's a new key.
 *
 * This is useful for rate limiting scenarios.
 * Returns the new value and whether the key was newly created.
 */
fun UnifiedJedis.incrWithExpiry(key: String, ttlSeconds: Long): Pair<Long, Boolean> {
    val value = incr(key)
    val isNew = value == 1L

    if (isNew) {
        expire(key, ttlSeconds)
    }

    return value to isNew
}

/**
 * Get the value of a key, or return a default value if the key does not exist.
 */
fun UnifiedJedis.getOrDefault(key: String, default: String): String =
    get(key) ?: default

/**
 * Set a key with a value only if it does not already exist, and set an expiration time.
 *
 * This method is useful for simple distributed locking or one-time initialization scenarios.
 *
 * Internally uses SETNX to ensure atomic "set if absent" semantics,
 * and sets an expiration time only when the key is newly created.
 *
 * @return true if the key was newly set, false if it already existed
 */
fun UnifiedJedis.setIfAbsent( key: String, value: String, ttlSeconds: Long ): Boolean {
    val result = setnx(key, value) == 1L
    if (result) {
        expire(key, ttlSeconds)
    }
    return result
}