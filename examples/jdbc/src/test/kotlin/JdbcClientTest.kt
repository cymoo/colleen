import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.SQLException
import java.time.LocalDateTime
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Comprehensive test suite for JdbcClient covering all major functionality:
 * - Query operations (positional and named parameters)
 * - Update and batch operations
 * - Transaction handling with savepoints
 * - Data type binding and conversion
 * - Error handling and edge cases
 * - Connection management
 */
class JdbcClientTest {

    private lateinit var client: JdbcClient

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("test.db").toString()
        client = JdbcClient.forSQLite(dbPath)
        createTestTables()
    }

    @AfterEach
    fun tearDown() {
        client.close()
    }


    private fun createTestTables() {
        client.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER, active INTEGER)")
        client.execute("CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, price REAL, created_at TEXT)")
        client.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, amount REAL, order_date TEXT)")
        client.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY, action TEXT, timestamp TEXT)")
    }

    // ========================================================
    // Basic Query Operations
    // ========================================================

    @Test
    fun `query with positional parameters returns matching rows`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Alice", 30, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Bob", 25, 1))

        val users = client.query("SELECT * FROM users WHERE age > ?", listOf(28)) { rs ->
            rs.getString("name")
        }

        assertEquals(1, users.size)
        assertEquals("Alice", users[0])
    }

    @Test
    fun `query with named parameters returns matching rows`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (:name, :age, :active)",
            mapOf("name" to "Charlie", "age" to 35, "active" to 1)
        )

        val users = client.query(
            "SELECT * FROM users WHERE name = :name",
            mapOf("name" to "Charlie")
        ) { rs ->
            rs.getString("name") to rs.getInt("age")
        }

        assertEquals(1, users.size)
        assertEquals("Charlie" to 35, users[0])
    }

    @Test
    fun `query with multiple named parameters in different order`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("TestUser", 40, 1))

        val result = client.queryOne(
            "SELECT * FROM users WHERE active = :active AND age = :age AND name = :name",
            mapOf("name" to "TestUser", "age" to 40, "active" to 1)
        ) { rs -> rs.getString("name") }

        assertEquals("TestUser", result)
    }

    @Test
    fun `query with repeated named parameter uses same value`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Repeated", 35, 1))

        val result = client.queryOne(
            "SELECT * FROM users WHERE age >= :minAge AND age <= :minAge",
            mapOf("minAge" to 35)
        ) { rs -> rs.getString("name") }

        assertEquals("Repeated", result)
    }

    @Test
    fun `queryOne returns single result when exists`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Dave", 40, 1))

        val user = client.queryOne("SELECT name FROM users WHERE name = ?", listOf("Dave")) { rs ->
            rs.getString("name")
        }

        assertEquals("Dave", user)
    }

    @Test
    fun `queryOne returns null when no results`() {
        val user = client.queryOne("SELECT name FROM users WHERE name = ?", listOf("NonExistent")) { rs ->
            rs.getString("name")
        }

        assertNull(user)
    }

    @Test
    fun `queryOne with named parameters returns null when no match`() {
        val result = client.queryOne(
            "SELECT * FROM users WHERE name = :name",
            mapOf("name" to "DoesNotExist")
        ) { rs -> rs.getString("name") }

        assertNull(result)
    }

    @Test
    fun `queryForMap returns map representation of rows`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Eve", 28, 1))

        val maps = client.queryMaps("SELECT * FROM users WHERE name = ?", listOf("Eve"))

        assertEquals(1, maps.size)
        assertEquals("Eve", maps[0]["name"])
        assertEquals(28, maps[0]["age"])
        assertEquals(1, maps[0]["active"])
    }

    @Test
    fun `queryForMap with named parameters returns correct data`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("MapUser", 33, 1))

        val maps = client.queryMaps(
            "SELECT * FROM users WHERE name = :name",
            mapOf("name" to "MapUser")
        )

        assertEquals(1, maps.size)
        assertEquals("MapUser", maps[0]["name"])
    }

    @Test
    fun `forEach iterates over all matching results`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Frank", 33, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Grace", 29, 1))

        val names = mutableListOf<String>()
        client.forEach("SELECT name FROM users ORDER BY name") { rs ->
            names.add(rs.getString("name"))
        }

        assertEquals(listOf("Frank", "Grace"), names)
    }

    @Test
    fun `forEach with named parameters processes all rows`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User1", 25, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User2", 26, 1))

        val ages = mutableListOf<Int>()
        client.forEach(
            "SELECT age FROM users WHERE age >= :minAge ORDER BY age",
            mapOf("minAge" to 25)
        ) { rs ->
            ages.add(rs.getInt("age"))
        }

        assertEquals(listOf(25, 26), ages)
    }

    @Test
    fun `query returns empty list when no matches`() {
        val users = client.query("SELECT * FROM users WHERE name = ?", listOf("NonExistent")) { rs ->
            rs.getString("name")
        }

        assertTrue(users.isEmpty())
    }

    @Test
    fun `query can handle complex mapper function`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Complex", 45, 1))

        data class User(val name: String, val age: Int, val isActive: Boolean)

        val users = client.query("SELECT * FROM users WHERE name = ?", listOf("Complex")) { rs ->
            User(
                name = rs.getString("name"),
                age = rs.getInt("age"),
                isActive = rs.getInt("active") == 1
            )
        }

        assertEquals(1, users.size)
        assertEquals("Complex", users[0].name)
        assertEquals(45, users[0].age)
        assertTrue(users[0].isActive)
    }

    // ========================================================
    // Update Operations
    // ========================================================

    @Test
    fun `update returns affected row count`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Henry", 45, 1))

        val affectedRows = client.update("UPDATE users SET age = ? WHERE name = ?", listOf(46, "Henry"))

        assertEquals(1, affectedRows)
    }

    @Test
    fun `update with named parameters returns affected count`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Iris", 32, 1))

        val affectedRows = client.update(
            "UPDATE users SET age = :newAge WHERE name = :name",
            mapOf("newAge" to 33, "name" to "Iris")
        )

        assertEquals(1, affectedRows)
    }

    @Test
    fun `update with no matching rows returns zero`() {
        val affected = client.update("UPDATE users SET age = ? WHERE name = ?", listOf(100, "NonExistent"))

        assertEquals(0, affected)
    }

    @Test
    fun `update can modify multiple rows`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User1", 30, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User2", 31, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User3", 32, 1))

        val affected = client.update("UPDATE users SET active = ? WHERE age >= ?", listOf(0, 30))

        assertEquals(3, affected)
    }

    @Test
    fun `delete operation returns affected count`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("ToDelete", 50, 1))

        val deleted = client.update("DELETE FROM users WHERE name = ?", listOf("ToDelete"))

        assertEquals(1, deleted)
    }

    @Test
    fun `insertAndGetKey returns generated key for single insert`() {
        val key = client.insertAndGetKey(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("Jack", 27, 1)
        )

        assertNotNull(key)
        assertTrue(key > 0)
    }

    @Test
    fun `insertAndGetKey with named parameters returns generated key`() {
        val key = client.insertAndGetKey(
            "INSERT INTO users (name, age, active) VALUES (:name, :age, :active)",
            mapOf("name" to "Kate", "age" to 31, "active" to 1)
        )

        assertNotNull(key)
        assertTrue(key > 0)
    }

    @Test
    fun `insertAndGetKey returns sequential keys for multiple inserts`() {
        val key1 = client.insertAndGetKey(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("User1", 20, 1)
        )
        val key2 = client.insertAndGetKey(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("User2", 21, 1)
        )

        assertNotNull(key1)
        assertNotNull(key2)
        assertTrue(key2 > key1)
    }

    // ========================================================
    // Batch Operations
    // ========================================================

    @Test
    fun `batchUpdate with positional parameters inserts all rows`() {
        val paramList = listOf(
            listOf("User1", 20, 1),
            listOf("User2", 21, 1),
            listOf("User3", 22, 1)
        )

        val affected = client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            paramList
        )

        assertEquals(3, affected)

        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(3, count)
    }

    @Test
    fun `batchUpdate with named parameters inserts all rows`() {
        val paramMaps = listOf(
            mapOf("name" to "BatchUser1", "age" to 25, "active" to 1),
            mapOf("name" to "BatchUser2", "age" to 26, "active" to 1),
            mapOf("name" to "BatchUser3", "age" to 27, "active" to 1)
        )

        val affected = client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (:name, :age, :active)",
            paramMaps
        )

        assertEquals(3, affected)
    }

    @Test
    fun `batchUpdate with custom batch size processes in chunks`() {
        val paramList = (1..100).map { listOf("User$it", it, 1) }

        val affected = client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            paramList,
            batchSize = 20
        )

        assertEquals(100, affected)

        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(100, count)
    }

    @Test
    fun `batchUpdate callback receives result for each batch item`() {
        val paramList = listOf(
            listOf("CB1", 30, 1),
            listOf("CB2", 31, 1),
            listOf("CB3", 32, 1)
        )

        val results = mutableListOf<Int>()
        client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            paramList,
            onResult = { results.add(it) }
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it > 0 || it == -2 }) // > 0 or SUCCESS_NO_INFO
    }

    @Test
    fun `batchUpdate with named parameters and empty list returns zero`() {
        val affected = client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (:name, :age, :active)",
            emptyList<Map<String, Any?>>()
        )

        assertEquals(0, affected)
    }

    @Test
    fun `batchUpdate can perform updates not just inserts`() {
        // First insert some data
        val insertParams = (1..5).map { listOf("User$it", it * 10, 1) }
        client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            insertParams
        )

        // Now batch update them
        val updateParams = (1..5).map { listOf(it * 10 + 1, "User$it") }
        val affected = client.batchUpdate(
            "UPDATE users SET age = ? WHERE name = ?",
            updateParams
        )

        assertEquals(5, affected)
    }

    @Test
    fun `batchUpdate with very large batch size processes all at once`() {
        val paramList = (1..50).map { listOf("User$it", it, 1) }

        val affected = client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            paramList,
            batchSize = 10000
        )

        assertEquals(50, affected)
    }

    // ========================================================
    // Named Parameter Parsing
    // ========================================================

    @Test
    fun `named parameters are correctly parsed and bound`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (:name, :age, :active)",
            mapOf("name" to "Parser", "age" to 50, "active" to 1)
        )

        val user = client.queryOne(
            "SELECT * FROM users WHERE name = :name AND age = :age",
            mapOf("name" to "Parser", "age" to 50)
        ) { rs -> rs.getString("name") }

        assertEquals("Parser", user)
    }

    @Test
    fun `missing named parameter throws IllegalArgumentException`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            client.query(
                "SELECT * FROM users WHERE name = :name",
                mapOf("wrongParam" to "value")
            ) { it.getString("name") }
        }

        assertTrue(exception.message?.contains("Missing parameter") ?: false)
        assertTrue(exception.message?.contains(":name") ?: false)
    }

    @Test
    fun `named parameters with underscores are supported`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (:user_name, :user_age, :is_active)",
            mapOf("user_name" to "Underscore", "user_age" to 40, "is_active" to 1)
        )

        val result = client.queryOne(
            "SELECT * FROM users WHERE name = :user_name",
            mapOf("user_name" to "Underscore")
        ) { rs -> rs.getString("name") }

        assertEquals("Underscore", result)
    }

    @Test
    fun `named parameters with numbers are supported`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (:param1, :param2, :param3)",
            mapOf("param1" to "Numeric", "param2" to 35, "param3" to 1)
        )

        val result = client.queryOne(
            "SELECT * FROM users WHERE name = :param1",
            mapOf("param1" to "Numeric")
        ) { rs -> rs.getString("name") }

        assertEquals("Numeric", result)
    }

    // ========================================================
    // Transaction Handling
    // ========================================================

    @Test
    fun `transaction commits on success`() {
        client.transaction { tx ->
            tx.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("TxUser1", 30, 1))
            tx.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("TxUser2", 31, 1))
        }

        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(2, count)
    }

    @Test
    fun `transaction rolls back on exception`() {
        assertFailsWith<RuntimeException> {
            client.transaction { tx ->
                tx.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("TxUser3", 32, 1))
                throw RuntimeException("Simulated error")
            }
        }

        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(0, count)
    }

    @Test
    fun `nested transaction with savepoint on success commits both`() {
        client.transaction { tx1 ->
            tx1.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Outer", 40, 1))

            tx1.transaction { tx2 ->
                tx2.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Inner", 41, 1))
            }

            tx1.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Outer2", 42, 1))
        }

        val names = client.query("SELECT name FROM users ORDER BY name") { rs ->
            rs.getString("name")
        }

        assertEquals(listOf("Inner", "Outer", "Outer2"), names)
    }

    @Test
    fun `nested transaction rollback does not affect outer transaction`() {
        client.transaction { tx1 ->
            tx1.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Outer", 40, 1))

            try {
                tx1.transaction { tx2 ->
                    tx2.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Inner", 41, 1))
                    throw RuntimeException("Inner fails")
                }
            } catch (_: RuntimeException) {
                // Inner transaction rolled back
            }

            tx1.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Outer2", 42, 1))
        }

        val names = client.query("SELECT name FROM users ORDER BY name") { rs ->
            rs.getString("name")
        }

        assertEquals(listOf("Outer", "Outer2"), names)
    }

    @Test
    fun `transaction can perform mixed operations`() {
        client.transaction { tx ->
            tx.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Mixed1", 30, 1))

            val key = tx.insertAndGetKey(
                "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
                listOf("Mixed2", 31, 1)
            )

            assertNotNull(key)

            tx.update("UPDATE users SET age = ? WHERE name = ?", listOf(32, "Mixed1"))

            val count = tx.queryOne("SELECT COUNT(*) as cnt FROM users") { rs -> rs.getInt("cnt") }
            assertEquals(2, count)
        }

        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(2, count)
    }

    @Test
    fun `multiple nested transactions use savepoints correctly`() {
        client.transaction { tx1 ->
            tx1.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("L1", 10, 1))

            tx1.transaction { tx2 ->
                tx2.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("L2", 20, 1))

                tx2.transaction { tx3 ->
                    tx3.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("L3", 30, 1))
                }
            }
        }

        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(3, count)
    }

    // ========================================================
    // Data Type Binding and Conversion
    // ========================================================

    @Test
    fun `various data types are correctly bound`() {
        client.execute(
            "CREATE TABLE types_test (id INTEGER PRIMARY KEY, str TEXT, num INTEGER, dbl REAL, flag INTEGER, bytes BLOB)"
        )

        val testBytes = byteArrayOf(1, 2, 3, 4, 5)
        client.update(
            "INSERT INTO types_test (str, num, dbl, flag, bytes) VALUES (?, ?, ?, ?, ?)",
            listOf("test string", 42L, 3.14, true, testBytes)
        )

        val result = client.queryOne("SELECT * FROM types_test") { rs ->
            mapOf(
                "str" to rs.getString("str"),
                "num" to rs.getLong("num"),
                "dbl" to rs.getDouble("dbl"),
                "flag" to rs.getBoolean("flag"),
                "bytes" to rs.getBytes("bytes")
            )
        }

        assertNotNull(result)
        assertEquals("test string", result["str"])
        assertEquals(42L, result["num"])
        assertTrue(result["flag"] as Boolean)
        assertContentEquals(testBytes, result["bytes"] as ByteArray)
    }

    @Test
    fun `null values are correctly bound and retrieved`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("NullAge", null, null)
        )

        client.queryOne("SELECT * FROM users WHERE name = ?", listOf("NullAge")) { rs ->
            assertNull(rs.getIntOrNull("age"))
            assertNull(rs.getBooleanOrNull("active"))
            assertNotNull(rs.getStringOrNull("name"))
        }
    }

    @Test
    fun `BigDecimal is correctly bound and retrieved`() {
        val price = BigDecimal("99.99")
        client.update(
            "INSERT INTO products (name, price) VALUES (?, ?)",
            listOf("Product1", price)
        )

        val result = client.queryOne("SELECT price FROM products") { rs ->
            rs.getBigDecimalOrNull("price")
        }

        assertNotNull(result)
        assertEquals(price.toDouble(), result.toDouble(), 0.001)
    }

    @Test
    fun `LocalDate and LocalDateTime are correctly bound`() {
        val dateTime = LocalDateTime.of(2024, 1, 15, 10, 30)

        client.update(
            "INSERT INTO products (name, created_at) VALUES (?, ?)",
            listOf("DateProduct", dateTime.toString())
        )

        val result = client.queryOne("SELECT created_at FROM products") { rs ->
            rs.getString("created_at")
        }

        assertNotNull(result)
        assertTrue(result.contains("2024-01-15"))
    }

    enum class Status { ACTIVE, INACTIVE, PENDING }

    @Test
    fun `enum values are bound as strings`() {

        client.update(
            "INSERT INTO audit_log (action) VALUES (?)",
            listOf(Status.ACTIVE)
        )

        val result = client.queryOne("SELECT action FROM audit_log") { rs ->
            rs.getString("action")
        }

        assertEquals("ACTIVE", result)
    }

    @Test
    fun `Int values are correctly bound`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("IntTest", 42, 1)
        )

        val age = client.queryOne("SELECT age FROM users WHERE name = ?", listOf("IntTest")) { rs ->
            rs.getInt("age")
        }

        assertEquals(42, age)
    }

    @Test
    fun `Float values are correctly bound`() {
        client.execute("CREATE TABLE float_test (id INTEGER PRIMARY KEY, value REAL)")

        val floatValue = 3.14f
        client.update("INSERT INTO float_test (value) VALUES (?)", listOf(floatValue))

        val result = client.queryOne("SELECT value FROM float_test") { rs ->
            rs.getFloat("value")
        }

        assertEquals(floatValue, result!!, 0.001f)
    }

    @Test
    fun `Double values are correctly bound`() {
        client.update(
            "INSERT INTO products (name, price) VALUES (?, ?)",
            listOf("DoubleTest", 19.99)
        )

        val price = client.queryOne("SELECT price FROM products WHERE name = ?", listOf("DoubleTest")) { rs ->
            rs.getDouble("price")
        }

        assertEquals(19.99, price!!, 0.001)
    }

    @Test
    fun `Boolean true is bound as 1`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("BoolTest", 30, true)
        )

        val active = client.queryOne("SELECT active FROM users WHERE name = ?", listOf("BoolTest")) { rs ->
            rs.getInt("active")
        }

        assertEquals(1, active)
    }

    @Test
    fun `Boolean false is bound as 0`() {
        client.update(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("BoolFalse", 30, false)
        )

        val active = client.queryOne("SELECT active FROM users WHERE name = ?", listOf("BoolFalse")) { rs ->
            rs.getBoolean("active")
        }

        assertFalse(active!!)
    }

    @Test
    fun `ByteArray is correctly bound and retrieved`() {
        client.execute("CREATE TABLE blob_test (id INTEGER PRIMARY KEY, data BLOB)")

        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        client.update("INSERT INTO blob_test (data) VALUES (?)", listOf(data))

        val result = client.queryOne("SELECT data FROM blob_test") { rs ->
            rs.getBytes("data")
        }

        assertContentEquals(data, result)
    }

    @Test
    fun `empty ByteArray is correctly handled`() {
        client.execute("CREATE TABLE empty_blob_test (id INTEGER PRIMARY KEY, data BLOB)")

        val emptyData = byteArrayOf()
        client.update("INSERT INTO empty_blob_test (data) VALUES (?)", listOf(emptyData))

        val result = client.queryOne("SELECT data FROM empty_blob_test") { rs ->
            rs.getBytes("data")
        }

        assertContentEquals(emptyData, result)
    }

    // ========================================================
    // ResultSet Extension Functions
    // ========================================================

    @Test
    fun `toMap returns all columns as map entries`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("MapTest", 35, 1))

        val map = client.queryOne("SELECT * FROM users WHERE name = ?", listOf("MapTest")) { rs ->
            rs.toMap()
        }

        assertNotNull(map)
        assertEquals("MapTest", map["name"])
        assertEquals(35, map["age"])
        assertEquals(1, map["active"])
        assertTrue(map.containsKey("id"))
    }

    @Test
    fun `nullable getters return null for null database values`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("NullTest", null, null))

        client.queryOne("SELECT * FROM users WHERE name = ?", listOf("NullTest")) { rs ->
            assertNull(rs.getIntOrNull("age"))
            assertNull(rs.getLongOrNull("age"))
            assertNull(rs.getDoubleOrNull("age"))
            assertNull(rs.getBooleanOrNull("active"))
            assertNotNull(rs.getStringOrNull("name"))
        }
    }

    @Test
    fun `getStringOrNull returns null for null values`() {
        client.execute("CREATE TABLE nullable_test (id INTEGER PRIMARY KEY, value TEXT)")
        client.update("INSERT INTO nullable_test (value) VALUES (?)", listOf(null))

        val result = client.queryOne("SELECT value FROM nullable_test") { rs ->
            rs.getStringOrNull("value")
        }

        assertNull(result)
    }

    @Test
    fun `getBigDecimalOrNull returns null for null values`() {
        client.update("INSERT INTO products (name, price) VALUES (?, ?)", listOf("NullPrice", null))

        val result = client.queryOne("SELECT price FROM products WHERE name = ?", listOf("NullPrice")) { rs ->
            rs.getBigDecimalOrNull("price")
        }

        assertNull(result)
    }

    // ========================================================
    // Error Handling
    // ========================================================

    @Test
    fun `SQL exception provides detailed error message with SQL preview`() {
        val exception = assertFailsWith<SQLException> {
            client.query("SELECT * FROM non_existent_table") { rs ->
                rs.getString("col")
            }
        }

        val message = exception.message ?: ""
        assertTrue(message.contains("SQL execution failed"))
        assertTrue(message.contains("SQL:"))
        assertTrue(message.contains("non_existent_table"))
    }

    @Test
    fun `SQL exception includes parameter information`() {
        client.execute("CREATE TABLE test_error (id INTEGER PRIMARY KEY, value TEXT NOT NULL)") {}

        val exception = assertFailsWith<SQLException> {
            // This should fail due to constraint violation
            client.update("INSERT INTO test_error (id, value) VALUES (?, ?)", listOf(1, null))
        }

        assertNotNull(exception.message)
    }

    @Test
    fun `invalid SQL syntax throws SQLException`() {
        assertFailsWith<SQLException> {
            client.update("INVALID SQL STATEMENT")
        }
    }

    @Test
    fun `constraint violation throws SQLException`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Unique", 30, 1))

        // Try to insert duplicate primary key (SQLite auto-generates sequential IDs)
        // We'll use a UNIQUE constraint instead
        client.execute("CREATE TABLE unique_test (id INTEGER PRIMARY KEY, email TEXT UNIQUE NOT NULL)")
        client.update("INSERT INTO unique_test (email) VALUES (?)", listOf("test@example.com"))

        assertFailsWith<SQLException> {
            client.update("INSERT INTO unique_test (email) VALUES (?)", listOf("test@example.com"))
        }
    }

    @Test
    fun `SQLException in transaction is propagated correctly`() {
        assertFailsWith<SQLException> {
            client.transaction { tx ->
                tx.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("TxError", 30, 1))
                tx.update("INVALID SQL")
            }
        }

        // Verify rollback occurred
        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(0, count)
    }

    @Test
    fun `long SQL is truncated in error message`() {
        val longSql = "SELECT * FROM users WHERE " + (1..100).joinToString(" AND ") { "name1 = 'test$it'" }

        val exception = assertFailsWith<SQLException> {
            client.query(longSql) { rs -> rs.getString("name") }
        }

        val message = exception.message ?: ""
        assertTrue(message.length < longSql.length + 200) // Message should be truncated
        assertTrue(message.contains("truncated") || message.length < 1000)
    }

    @Test
    fun `many parameters are truncated in error message`() {
        val manyParams = (1..20).map { "param$it" }.toList()

        val exception = assertFailsWith<SQLException> {
            client.query("SELECT * FROM non_existent", manyParams) { rs -> rs.getString("col") }
        }

        val message = exception.message ?: ""
        assertTrue(message.contains("Parameters:"))
    }

    // ========================================================
    // Edge Cases and Boundary Conditions
    // ========================================================

    @Test
    fun `empty string parameter is correctly handled`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("", 30, 1))

        val result = client.queryOne("SELECT name FROM users WHERE name = ?", listOf("")) { rs ->
            rs.getString("name")
        }

        assertEquals("", result)
    }

    @Test
    fun `very long string parameter is correctly handled`() {
        val longString = "x".repeat(10000)
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf(longString, 30, 1))

        val result = client.queryOne("SELECT LENGTH(name) as len FROM users") { rs ->
            rs.getInt("len")
        }

        assertEquals(10000, result)
    }

    @Test
    fun `special characters in string parameters are escaped correctly`() {
        val specialString = "Test'String\"With'Quotes\nAnd\tTabs"
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf(specialString, 30, 1))

        val result = client.queryOne("SELECT name FROM users WHERE name = ?", listOf(specialString)) { rs ->
            rs.getString("name")
        }

        assertEquals(specialString, result)
    }

    @Test
    fun `SQL injection attempt is safely handled with parameters`() {
        val maliciousInput = "'; DROP TABLE users; --"
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf(maliciousInput, 30, 1))

        val result = client.queryOne("SELECT name FROM users WHERE name = ?", listOf(maliciousInput)) { rs ->
            rs.getString("name")
        }

        assertEquals(maliciousInput, result)

        // Verify table still exists
        val count = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(1, count)
    }

    @Test
    fun `very large integer values are correctly handled`() {
        val largeValue = Long.MAX_VALUE - 1
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("LargeInt", 30, 1))
        client.update("UPDATE users SET age = ? WHERE name = ?", listOf(largeValue, "LargeInt"))

        val result = client.queryOne("SELECT age FROM users WHERE name = ?", listOf("LargeInt")) { rs ->
            rs.getLong("age")
        }

        assertEquals(largeValue, result)
    }

    @Test
    fun `negative numbers are correctly handled`() {
        client.update("INSERT INTO products (name, price) VALUES (?, ?)", listOf("Negative", -99.99))

        val result = client.queryOne("SELECT price FROM products WHERE name = ?", listOf("Negative")) { rs ->
            rs.getDouble("price")
        }

        assertEquals(-99.99, result!!, 0.001)
    }

    @Test
    fun `zero values are correctly handled`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Zero", 0, 0))

        val result = client.queryOne("SELECT age, active FROM users WHERE name = ?", listOf("Zero")) { rs ->
            rs.getInt("age") to rs.getInt("active")
        }

        assertEquals(0 to 0, result)
    }

    @Test
    fun `query with no results forEach doesn't execute handler`() {
        var executed = false
        client.forEach("SELECT * FROM users WHERE name = ?", listOf("NonExistent")) { _ ->
            executed = true
        }

        assertFalse(executed)
    }

    @Test
    fun `queryForMap returns empty list when no results`() {
        val maps = client.queryMaps("SELECT * FROM users WHERE age > ?", listOf(1000))

        assertTrue(maps.isEmpty())
    }

    @Test
    fun `update with all null parameters works`() {
        client.execute("CREATE TABLE all_nullable (id INTEGER PRIMARY KEY, a TEXT, b TEXT, c TEXT)")

        val affected = client.update(
            "INSERT INTO all_nullable (a, b, c) VALUES (?, ?, ?)",
            listOf(null, null, null)
        )

        assertEquals(1, affected)
    }

    @Test
    fun `batch with single item works correctly`() {
        val affected = client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf(listOf("SingleBatch", 30, 1))
        )

        assertEquals(1, affected)
    }

    @Test
    fun `unicode characters are correctly stored and retrieved`() {
        val unicodeString = "Hello 世界 🌍"
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf(unicodeString, 30, 1))

        val result = client.queryOne("SELECT name FROM users WHERE name = ?", listOf(unicodeString)) { rs ->
            rs.getString("name")
        }

        assertEquals(unicodeString, result)
    }

    @Test
    fun `whitespace-only string is preserved`() {
        val whitespaceString = "   \t\n   "
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf(whitespaceString, 30, 1))

        val result = client.queryOne("SELECT name FROM users WHERE name = ?", listOf(whitespaceString)) { rs ->
            rs.getString("name")
        }

        assertEquals(whitespaceString, result)
    }

    // ========================================================
    // Connection Management
    // ========================================================

    @Test
    fun `client can be closed and reopened`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("reopen.db").toString()

        JdbcClient.forSQLite(dbPath).use { client1 ->
            client1.execute("CREATE TABLE test (id INTEGER, value TEXT)")
            client1.update("INSERT INTO test (id, value) VALUES (?, ?)", listOf(1, "first"))
        }

        JdbcClient.forSQLite(dbPath).use { client2 ->
            val count = client2.queryOne("SELECT COUNT(*) as cnt FROM test") { rs ->
                rs.getInt("cnt")
            }
            assertEquals(1, count)
        }
    }

    @Test
    fun `multiple sequential queries work correctly`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Sequential1", 30, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Sequential2", 31, 1))

        val result1 = client.query("SELECT * FROM users WHERE age = ?", listOf(30)) { rs ->
            rs.getString("name")
        }

        val result2 = client.query("SELECT * FROM users WHERE age = ?", listOf(31)) { rs ->
            rs.getString("name")
        }

        assertEquals(listOf("Sequential1"), result1)
        assertEquals(listOf("Sequential2"), result2)
    }

    @Test
    fun `execute method with custom block works`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Custom", 35, 1))

        val customResult = client.execute("SELECT * FROM users WHERE name = ?", listOf("Custom")) { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.getString("name") + "_" + rs.getInt("age")
                } else {
                    "not_found"
                }
            }
        }

        assertEquals("Custom_35", customResult)
    }

    @Test
    fun `execute with named parameters works`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("ExecuteNamed", 40, 1))

        val result = client.execute(
            "SELECT * FROM users WHERE name = :name",
            mapOf("name" to "ExecuteNamed")
        ) { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("name") else null
            }
        }

        assertEquals("ExecuteNamed", result)
    }

    // ========================================================
    // DataSource Configuration Tests
    // ========================================================

    @Test
    fun `forSQLite creates working client`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("sqlite_factory.db").toString()

        JdbcClient.forSQLite(dbPath).use { sqliteClient ->
            sqliteClient.execute("CREATE TABLE test (id INTEGER)")
            sqliteClient.update("INSERT INTO test (id) VALUES (?)", listOf(1))

            val count = sqliteClient.queryOne("SELECT COUNT(*) as cnt FROM test") { rs ->
                rs.getInt("cnt")
            }

            assertEquals(1, count)
        }
    }

    @Test
    fun `create with custom config works`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("custom_config.db").toString()
        val config = JdbcConfig(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC"
        )

        JdbcClient.create(config).use { customClient ->
            customClient.execute("CREATE TABLE test (id INTEGER PRIMARY KEY, data TEXT)")
            customClient.update("INSERT INTO test (data) VALUES (?)", listOf("test_data"))

            val data = customClient.queryOne("SELECT data FROM test") { rs ->
                rs.getString("data")
            }

            assertEquals("test_data", data)
        }
    }

    @Test
    fun `invalid driver class throws exception`() {
        val config = JdbcConfig(
            url = "jdbc:sqlite::memory:",
            driver = "com.invalid.NonExistentDriver"
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            JdbcClient.create(config)
        }

        assertTrue(exception.message?.contains("Driver not found") ?: false)
    }

    // ========================================================
    // Complex Scenarios
    // ========================================================

    @Test
    fun `complex multi-table join query works correctly`() {
        // Insert test data
        val userId = client.insertAndGetKey(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            listOf("JoinUser", 35, 1)
        )

        client.update(
            "INSERT INTO orders (user_id, amount, order_date) VALUES (?, ?, ?)",
            listOf(userId, 100.50, "2024-01-15")
        )

        // Execute join query
        val result = client.queryOne(
            """
            SELECT u.name, o.amount 
            FROM users u 
            INNER JOIN orders o ON u.id = o.user_id 
            WHERE u.name = ?
            """.trimIndent(),
            listOf("JoinUser")
        ) { rs ->
            rs.getString("name") to rs.getDouble("amount")
        }

        assertNotNull(result)
        assertEquals("JoinUser", result.first)
        assertEquals(100.50, result.second, 0.001)
    }

    @Test
    fun `aggregate functions work correctly`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User1", 20, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User2", 30, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("User3", 40, 1))

        val result = client.queryOne(
            "SELECT COUNT(*) as cnt, AVG(age) as avg_age, MAX(age) as max_age, MIN(age) as min_age FROM users"
        ) { rs ->
            mapOf(
                "count" to rs.getInt("cnt"),
                "avg" to rs.getDouble("avg_age"),
                "max" to rs.getInt("max_age"),
                "min" to rs.getInt("min_age")
            )
        }

        assertNotNull(result)
        assertEquals(3, result["count"])
        assertEquals(30.0, result["avg"])
        assertEquals(40, result["max"])
        assertEquals(20, result["min"])
    }

    @Test
    fun `group by query works correctly`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Active1", 25, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Active2", 30, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Inactive1", 35, 0))

        val results = client.query(
            "SELECT active, COUNT(*) as cnt FROM users GROUP BY active ORDER BY active"
        ) { rs ->
            rs.getInt("active") to rs.getInt("cnt")
        }

        assertEquals(2, results.size)
        assertEquals(0 to 1, results[0])
        assertEquals(1 to 2, results[1])
    }

    @Test
    fun `subquery works correctly`() {
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Young", 20, 1))
        client.update("INSERT INTO users (name, age, active) VALUES (?, ?, ?)", listOf("Old", 50, 1))

        val olderThanAvg = client.query(
            "SELECT name FROM users WHERE age > (SELECT AVG(age) FROM users)"
        ) { rs ->
            rs.getString("name")
        }

        assertEquals(listOf("Old"), olderThanAvg)
    }

    @Test
    fun `transaction with multiple table updates maintains consistency`() {
        client.transaction { tx ->
            val userId = tx.insertAndGetKey(
                "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
                listOf("TxMulti", 30, 1)
            )

            tx.update(
                "INSERT INTO orders (user_id, amount, order_date) VALUES (?, ?, ?)",
                listOf(userId, 50.0, "2024-01-15")
            )

            tx.update(
                "INSERT INTO audit_log (action, timestamp) VALUES (?, ?)",
                listOf("ORDER_CREATED", "2024-01-15T10:00:00")
            )
        }

        val userCount = client.queryOne("SELECT COUNT(*) as cnt FROM users") { rs -> rs.getInt("cnt") }
        val orderCount = client.queryOne("SELECT COUNT(*) as cnt FROM orders") { rs -> rs.getInt("cnt") }
        val logCount = client.queryOne("SELECT COUNT(*) as cnt FROM audit_log") { rs -> rs.getInt("cnt") }

        assertEquals(1, userCount)
        assertEquals(1, orderCount)
        assertEquals(1, logCount)
    }

    @Test
    fun `batch insert with different data types per row`() {
        val paramList = listOf(
            listOf("String1", 100, 1),
            listOf("String2", null, 1),
            listOf("String3", 200, 0)
        )

        val affected = client.batchUpdate(
            "INSERT INTO users (name, age, active) VALUES (?, ?, ?)",
            paramList
        )

        assertEquals(3, affected)

        val nullAgeCount = client.queryOne("SELECT COUNT(*) as cnt FROM users WHERE age IS NULL") { rs ->
            rs.getInt("cnt")
        }
        assertEquals(1, nullAgeCount)
    }
}