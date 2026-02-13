import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import redis.clients.jedis.*
import java.net.URI
import kotlin.test.Test

/**
 * Test suite for Redis utilities and extension functions.
 * 
 * Test focus:
 * - Factory functions for client creation
 * - Transaction extension functions
 * - Pipeline extension functions
 * - Common pattern extension functions
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled
class RedisUtilTest {

    private lateinit var client: RedisClient
    private val testHost = "localhost"
    private val testPort = 6379

    @BeforeAll
    fun setup() {
        client = newRedisClient(
            host = testHost,
            port = testPort,
            maxTotal = 10,
            maxIdle = 5,
            minIdle = 2,
            maxWaitMillis = 3000
        )
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    @BeforeEach
    fun cleanupBefore() {
        client.flushDB()
    }

    // ========== Factory Function Tests ==========

    @Test
    fun `newRedisClient should create client with default parameters`() {
        newRedisClient().use { testClient ->
            assertNotNull(testClient)
            assertTrue(testClient.isAlive())
        }
    }

    @Test
    fun `newRedisClient should create client with custom parameters`() {
        newRedisClient(
            host = testHost,
            port = testPort,
            database = 0,
            timeout = 5000,
            maxWaitMillis = 5000
        ).use { testClient ->
            assertNotNull(testClient)
            testClient.set("test_key", "test_value")
            assertEquals("test_value", testClient.get("test_key"))
        }
    }

    @Test
    fun `newRedisClient should create client from URI string`() {
        val uri = "redis://$testHost:$testPort"
        newRedisClient(uri).use { testClient ->
            assertNotNull(testClient)
            assertTrue(testClient.isAlive())
        }
    }

    @Test
    fun `newRedisClient should create client from URI instance`() {
        val uri = URI("redis://$testHost:$testPort")
        newRedisClient(uri).use { testClient ->
            assertNotNull(testClient)
            assertTrue(testClient.isAlive())
        }
    }

    @Test
    fun `newRedisClusterClient should create cluster client from node addresses`() {
        try {
            newRedisClusterClient(
                "127.0.0.1:7000",
                "127.0.0.1:7001",
                "127.0.0.1:7002",
            ).use { clusterClient ->
                assertNotNull(clusterClient)
                assertTrue(clusterClient.isAlive())
            }
        } catch (e: Exception) {
            println("Skipping cluster test - Redis cluster not available: ${e.message}")
        }
    }

    @Test
    fun `newRedisClusterClient should validate node address format`() {
        assertThrows<IllegalArgumentException> {
            newRedisClusterClient("invalid-address")
        }

        assertThrows<IllegalArgumentException> {
            newRedisClusterClient("127.0.0.1:7000:extra")
        }
    }

    @Test
    fun `newRedisClient should support custom pool configuration`() {
        newRedisClient(
            host = testHost,
            port = testPort,
            maxTotal = 20,
            maxIdle = 10,
            minIdle = 5,
            maxWaitMillis = 5000
        ).use { testClient ->
            assertNotNull(testClient)
            // Verify pool works with custom settings
            repeat(15) { i ->
                testClient.set("pool_$i", "value_$i")
            }
        }
    }

    // ========== Transaction Extension Tests ==========

    @Test
    fun `transaction should execute commands atomically`() {
        val results = client.transaction { tx ->
            tx.set("tx_key1", "value1")
            tx.set("tx_key2", "value2")
            tx.incr("tx_counter")
        }

        assertNotNull(results)
        assertEquals("value1", client.get("tx_key1"))
        assertEquals("value2", client.get("tx_key2"))
        assertEquals(1L, client.get("tx_counter")?.toLong())
    }

    @Test
    fun `transaction should rollback on exception`() {
        client.set("rollback_key", "initial")

        assertThrows<RuntimeException> {
            client.transaction { tx ->
                tx.set("rollback_key", "updated")
                tx.set("new_key", "new_value")
                throw RuntimeException("Force rollback")
            }
        }

        assertEquals("initial", client.get("rollback_key"))
        assertNull(client.get("new_key"))
    }

    @Test
    fun `transaction should return Response objects`() {
        val responses = client.transaction { tx ->
            listOf(
                tx.set("tx_resp_key", "tx_value"),
                tx.get("tx_resp_key"),
                tx.incr("tx_counter")
            )
        }

        assertEquals("OK", responses[0].get())
        assertEquals("tx_value", responses[1].get())
        assertEquals(1L, responses[2].get())
    }

    @Test
    fun `transactionReturning should rollback on exception`() {
        client.set("key", "initial")

        assertThrows<RuntimeException> {
            client.transaction { tx ->
                tx.set("key", "updated")
                throw RuntimeException("Test exception")
            }
        }

        assertEquals("initial", client.get("key"))
    }

    @Test
    fun `transaction should support multiple operations`() {
        client.set("counter", "0")

        client.transaction { tx ->
            repeat(10) {
                tx.incr("counter")
            }
        }

        assertEquals(10L, client.get("counter")?.toLong())
    }

    // ========== Pipeline Extension Tests ==========

    @Test
    fun `pipeline should execute commands in batch and return Response objects`() {
        val responses = client.pipeline { pipe ->
            repeat(100) { i ->
                pipe.set("pipe_key_$i", "value_$i")
            }
            // Return empty list since we don't need results
            emptyList<Response<String>>()
        }

        assertEquals(emptyList<Response<String>>(), responses)

        // Verify all commands were executed
        repeat(100) { i ->
            assertEquals("value_$i", client.get("pipe_key_$i"))
        }
    }

    @Test
    fun `pipeline should return Response objects for retrieving results`() {
        client.set("resp_key1", "value1")
        client.set("resp_key2", "value2")

        val responses = client.pipeline { pipe ->
            listOf(
                pipe.get("resp_key1"),
                pipe.get("resp_key2"),
                pipe.get("non_existent")
            )
        }

        assertEquals("value1", responses[0].get())
        assertEquals("value2", responses[1].get())
        assertNull(responses[2].get())
    }

    @Test
    fun `pipeline should improve performance`() {
        val normalStart = System.currentTimeMillis()
        repeat(1000) { i ->
            client.set("normal_$i", "value_$i")
        }
        val normalTime = System.currentTimeMillis() - normalStart

        client.flushDB()

        val pipelineStart = System.currentTimeMillis()
        client.pipeline { pipe ->
            repeat(1000) { i ->
                pipe.set("pipeline_$i", "value_$i")
            }
        }
        val pipelineTime = System.currentTimeMillis() - pipelineStart

        println("Normal: ${normalTime}ms, Pipeline: ${pipelineTime}ms")
        assertTrue(pipelineTime < normalTime)
    }

    @Test
    fun `pipeline should handle mixed Response types`() {
        client.set("string_key", "string_val")
        client.sadd("set_key", "member1", "member2")

        val (stringResp, setResp, existsResp) = client.pipeline { pipe ->
            Triple(
                pipe.get("string_key"),
                pipe.smembers("set_key"),
                pipe.exists("string_key")
            )
        }

        assertEquals("string_val", stringResp.get())
        assertEquals(setOf("member1", "member2"), setResp.get())
        assertTrue(existsResp.get())
    }

    @Test
    fun `pipeline should handle exception`() {
        assertThrows<RuntimeException> {
            client.pipeline { pipe ->
                pipe.set("key1", "value1")
                throw RuntimeException("Pipeline error")
            }
        }
    }

    @Test
    fun `pipeline should support mixed operations`() {
        client.pipeline { pipe ->
            pipe.set("string_key", "string_value")
            pipe.lpush("list_key", "item1", "item2")
            pipe.sadd("set_key", "member1", "member2")
            pipe.hset("hash_key", "field1", "value1")
        }

        assertEquals("string_value", client.get("string_key"))
        assertEquals(2L, client.llen("list_key"))
        assertEquals(2L, client.scard("set_key"))
        assertEquals("value1", client.hget("hash_key", "field1"))
    }

    @Test
    fun `pipeline should support batch get operations`() {
        // Prepare data
        repeat(10) { i ->
            client.set("batch_key_$i", "value_$i")
        }

        // Batch get using pipeline
        val responses = client.pipeline { pipe ->
            (0 until 10).map { i ->
                pipe.get("batch_key_$i")
            }
        }

        // Verify results
        responses.forEachIndexed { index, response ->
            assertEquals("value_$index", response.get())
        }
    }

    @Test
    fun `pipeline should support complex return types`() {
        client.set("a", "1")
        client.set("b", "2")
        client.sadd("set", "x", "y")

        data class Results(
            val a: Response<String>,
            val b: Response<String>,
            val setMembers: Response<Set<String>>
        )

        val results = client.pipeline { pipe ->
            Results(
                a = pipe.get("a"),
                b = pipe.get("b"),
                setMembers = pipe.smembers("set")
            )
        }

        assertEquals("1", results.a.get())
        assertEquals("2", results.b.get())
        assertEquals(setOf("x", "y"), results.setMembers.get())
    }

    // ========== Common Pattern Extension Tests ==========

    @Test
    fun `isAlive should return true for healthy connection`() {
        assertTrue(client.isAlive())
    }

    @Test
    fun `isAlive should return false for closed connection`() {
        val testClient = newRedisClient(host = testHost, port = testPort)
        testClient.close()

        assertFalse(testClient.isAlive())
    }

    @Test
    fun `delAll should delete multiple keys from collection`() {
        client.set("key1", "value1")
        client.set("key2", "value2")
        client.set("key3", "value3")

        val deleted = client.delAll(listOf("key1", "key2", "key3", "non_existent"))

        assertEquals(3L, deleted)
        assertNull(client.get("key1"))
        assertNull(client.get("key2"))
        assertNull(client.get("key3"))
    }

    @Test
    fun `delAll should handle empty collection`() {
        val deleted = client.delAll(emptyList())
        assertEquals(0L, deleted)
    }

    @Test
    fun `existsAll should return true when all keys exist`() {
        client.set("key1", "value1")
        client.set("key2", "value2")
        client.set("key3", "value3")

        assertTrue(client.existsAll("key1", "key2", "key3"))
    }

    @Test
    fun `existsAll should return false when some keys missing`() {
        client.set("key1", "value1")
        client.set("key2", "value2")

        assertFalse(client.existsAll("key1", "key2", "key3"))
    }

    @Test
    fun `existsAll should return true for empty keys`() {
        assertTrue(client.existsAll())
    }

    @Test
    fun `existsAny should return true when any key exists`() {
        client.set("key1", "value1")

        assertTrue(client.existsAny("key1", "key2", "key3"))
    }

    @Test
    fun `existsAny should return false when no keys exist`() {
        assertFalse(client.existsAny("key1", "key2", "key3"))
    }

    @Test
    fun `existsAny should return false for empty keys`() {
        assertFalse(client.existsAny())
    }

    @Test
    fun `incrWithExpiry should increment and set expiry for new key`() {
        val (value, isNew) = client.incrWithExpiry("new_counter", 60)

        assertEquals(1L, value)
        assertTrue(isNew)

        val ttl = client.ttl("new_counter")
        assertTrue(ttl in 1..60)
    }

    @Test
    fun `incrWithExpiry should increment without setting expiry for existing key`() {
        client.set("existing_counter", "5")

        val (value, isNew) = client.incrWithExpiry("existing_counter", 60)

        assertEquals(6L, value)
        assertFalse(isNew)

        val ttl = client.ttl("existing_counter")
        assertEquals(-1L, ttl) // No expiry set
    }

    // ========== Integration Tests ==========

    @Test
    fun `should work with real-world scenario - user session management`() {
        val userId = "user123"
        val sessionId = "session_${System.currentTimeMillis()}"

        client.transaction { tx ->
            tx.hset("session:$sessionId", "userId", userId)
            tx.hset("session:$sessionId", "createdAt", System.currentTimeMillis().toString())
            tx.expire("session:$sessionId", 3600)
        }

        val storedUserId = client.hget("session:$sessionId", "userId")
        assertEquals(userId, storedUserId)

        val ttl = client.ttl("session:$sessionId")
        assertTrue(ttl in 1..3600)
    }

    @Test
    fun `should work with real-world scenario - distributed counter`() {
        val counterKey = "page_views"

        val threads = List(10) {
            Thread {
                repeat(100) {
                    client.incr(counterKey)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1000L, client.get(counterKey)?.toLong())
    }

    @Test
    fun `should work with real-world scenario - leaderboard`() {
        client.pipeline { pipe ->
            pipe.zadd("leaderboard", 100.0, "player1")
            pipe.zadd("leaderboard", 200.0, "player2")
            pipe.zadd("leaderboard", 150.0, "player3")
            pipe.zadd("leaderboard", 300.0, "player4")
        }

        val topPlayers = client.zrevrange("leaderboard", 0, 2)
        assertEquals(listOf("player4", "player2", "player3"), topPlayers)
    }

    @Test
    fun `should work with real-world scenario - cache with expiration`() {
        val cacheKey = "cache:user:123"
        val userData = """{"id":123,"name":"John","email":"john@example.com"}"""

        // Use native setex
        client.setex(cacheKey, 5, userData)

        assertEquals(userData, client.get(cacheKey))

        val ttl = client.ttl(cacheKey)
        assertTrue(ttl in 1..5)

        client.del(cacheKey)
        assertNull(client.get(cacheKey))
    }

    @Test
    fun `should work with real-world scenario - rate limiting`() {
        val userId = "user456"
        val rateLimitKey = "rate_limit:$userId"
        val maxRequests = 10L

        val (currentRequests, isNew) = client.incrWithExpiry(rateLimitKey, 60)

        assertTrue(currentRequests <= maxRequests)

        if (isNew) {
            val ttl = client.ttl(rateLimitKey)
            assertTrue(ttl > 0)
        }
    }

    @Test
    fun `should work with real-world scenario - batch operations`() {
        // Batch set using native mset
        client.mset("user:1:name", "Alice", "user:2:name", "Bob", "user:3:name", "Charlie")

        // Batch get using native mget
        val names = client.mget("user:1:name", "user:2:name", "user:3:name")
        assertEquals(listOf("Alice", "Bob", "Charlie"), names)

        // Batch delete using extension
        val deleted = client.delAll(listOf("user:1:name", "user:2:name", "user:3:name"))
        assertEquals(3L, deleted)
    }

    @Test
    fun `should work with real-world scenario - distributed lock simulation`() {
        val lockKey = "lock:resource:123"
        val lockValue = "lock_${System.currentTimeMillis()}"

        val acquired = client.setnx(lockKey, lockValue)
        assertEquals(1L, acquired)

        client.expire(lockKey, 10)

        val acquiredAgain = client.setnx(lockKey, "another_value")
        assertEquals(0L, acquiredAgain)

        client.del(lockKey)

        val acquiredAfterRelease = client.setnx(lockKey, lockValue)
        assertEquals(1L, acquiredAfterRelease)
    }

    @Test
    fun `should handle concurrent pipeline operations`() {
        val threads = List(5) { threadIndex ->
            Thread {
                client.pipeline { pipe ->
                    repeat(100) { i ->
                        pipe.set("concurrent_pipe_${threadIndex}_$i", "value_$i")
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        repeat(5) { threadIndex ->
            repeat(100) { i ->
                assertEquals("value_$i", client.get("concurrent_pipe_${threadIndex}_$i"))
            }
        }
    }

    // ========== Edge Cases Tests ==========

    @Test
    fun `should handle connection timeout gracefully`() {
        assertThrows<Exception> {
            newRedisClient(
                host = "192.0.2.1",
                port = 6379,
                timeout = 100,
                maxWaitMillis = 500
            ).use { testClient ->
                testClient.set("key", "value")
            }
        }
    }

    @Test
    fun `should handle special characters in keys and values`() {
        val specialKey = "key:with:special:chars:🔑"
        val specialValue = "value with spaces, symbols !@#$%^&*(), and emoji 🎉"

        client.set(specialKey, specialValue)
        assertEquals(specialValue, client.get(specialKey))
    }

    @Test
    fun `should handle very long keys and values`() {
        val longKey = "k" + "x".repeat(1000)
        val longValue = "v" + "y".repeat(10000)

        client.set(longKey, longValue)
        assertEquals(longValue, client.get(longKey))
    }
}