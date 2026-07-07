package io.github.cymoo.colleen.json

import io.github.cymoo.colleen.util.TypeRef
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.stream.Stream
import kotlin.reflect.javaType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ========================================================================
// Test Data Classes
// ========================================================================

data class SimpleUser(
    val id: Int,
    val name: String
)

data class UserWithNullable(
    val id: Int,
    val name: String,
    val email: String? = null,
    val age: Int? = null
)

data class UserWithDefaults(
    val id: Int,
    val name: String = "Unknown",
    val active: Boolean = true,
    val score: Double = 0.0
)

data class NestedUser(
    val id: Int,
    val name: String,
    val address: Address?
)

data class Address(
    val street: String,
    val city: String,
    val zipCode: String? = null
)

data class GenericContainer<T>(
    val value: T,
    val metadata: Map<String, String>? = null
)

data class ComplexGeneric<K, V>(
    val data: Map<K, List<V>>,
    val count: Int
)

// ========================================================================
// Helper Functions - Combining TypeRef and JsonMapper
// ========================================================================

// === Deserialization (JSON String -> Object) ===

/**
 * Deserialize using reified type (inline function with typeOf)
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Any> fromJson(mapper: JsonMapper, data: String): T {
    return mapper.fromJsonString(data, typeOf<T>().javaType)
}

/**
 * Deserialize using TypeRef
 */
fun <T : Any> fromJson(mapper: JsonMapper, data: String, typeRef: TypeRef<T>): T {
    return mapper.fromJsonString(data, typeRef.type)
}

/**
 * Deserialize using Class
 */
fun <T : Any> fromJson(mapper: JsonMapper, data: String, clazz: Class<T>): T {
    return mapper.fromJsonString(data, clazz)
}

/**
 * Deserialize from InputStream using reified type
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Any> fromJsonStream(mapper: JsonMapper, stream: InputStream): T {
    return mapper.fromJsonStream(stream, typeOf<T>().javaType)
}

/**
 * Deserialize from InputStream using TypeRef
 */
fun <T : Any> fromJsonStream(mapper: JsonMapper, stream: InputStream, typeRef: TypeRef<T>): T {
    return mapper.fromJsonStream(stream, typeRef.type)
}

// === ConvertValue ===

/**
 * Convert value using reified type
 */
@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Any> convertValue(mapper: JsonMapper, obj: Any): T {
    return mapper.convertValue(obj, typeOf<T>().javaType)
}

/**
 * Convert value using TypeRef
 */
fun <T : Any> convertValue(mapper: JsonMapper, obj: Any, typeRef: TypeRef<T>): T {
    return mapper.convertValue(obj, typeRef.type)
}

// ========================================================================
// Combined TypeRef and JsonMapper Tests
// ========================================================================

class TypeRefJsonMapperIntegrationTest {

    private lateinit var mapper: JsonMapper

    @BeforeEach
    fun setup() {
        mapper = JacksonMapper()
    }

    // ========================================================================
    // Reified Type Tests (inline + typeOf)
    // ========================================================================

    @Nested
    inner class ReifiedTypeTests {

        @Test
        fun `should deserialize simple object using reified type`() {
            // Arrange
            val json = """{"id":1,"name":"Alice"}"""

            // Act
            val user = fromJson<SimpleUser>(mapper, json)

            // Assert
            assertEquals(1, user.id)
            assertEquals("Alice", user.name)
        }

        @Test
        fun `should deserialize List using reified type`() {
            // Arrange
            val json = """[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]"""

            // Act
            val users = fromJson<List<SimpleUser>>(mapper, json)

            // Assert
            assertEquals(2, users.size)
            assertEquals("Alice", users[0].name)
            assertEquals("Bob", users[1].name)
        }

        @Test
        fun `should deserialize Map using reified type`() {
            // Arrange
            val json = """{"user1":{"id":1,"name":"Alice"},"user2":{"id":2,"name":"Bob"}}"""

            // Act
            val userMap = fromJson<Map<String, SimpleUser>>(mapper, json)

            // Assert
            assertEquals(2, userMap.size)
            assertEquals("Alice", userMap["user1"]?.name)
            assertEquals("Bob", userMap["user2"]?.name)
        }

        @Test
        fun `should deserialize nested generics using reified type`() {
            // Arrange
            val json = """{"value":{"id":1,"name":"Alice"},"metadata":{"key":"value"}}"""

            // Act
            val container = fromJson<GenericContainer<SimpleUser>>(mapper, json)

            // Assert
            assertEquals(1, container.value.id)
            assertEquals("Alice", container.value.name)
            assertEquals("value", container.metadata?.get("key"))
        }

        @Test
        fun `should handle nullable fields with reified type`() {
            // Arrange
            val json = """{"id":1,"name":"Bob","email":"bob@example.com"}"""

            // Act
            val user = fromJson<UserWithNullable>(mapper, json)

            // Assert
            assertEquals(1, user.id)
            assertEquals("bob@example.com", user.email)
            assertEquals(null, user.age)
        }

        @Test
        fun `should handle default values with reified type`() {
            // Arrange
            val json = """{"id":1}"""

            // Act
            val user = fromJson<UserWithDefaults>(mapper, json)

            // Assert
            assertEquals(1, user.id)
            assertEquals("Unknown", user.name)
            assertEquals(true, user.active)
        }
    }

    // ========================================================================
    // TypeRef Tests (anonymous object syntax)
    // ========================================================================

    @Nested
    inner class TypeRefTests {

        @Test
        fun `should deserialize simple object using TypeRef`() {
            // Arrange
            val json = """{"id":1,"name":"Alice"}"""
            val typeRef = TypeRef.of(SimpleUser::class.java)

            // Act
            val user = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(1, user.id)
            assertEquals("Alice", user.name)
        }

        @Test
        fun `should deserialize List using TypeRef factory method`() {
            // Arrange
            val json = """[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]"""
            val typeRef = TypeRef.listOf(SimpleUser::class.java)

            // Act
            val users = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(2, users.size)
            assertEquals("Alice", users[0].name)
            assertEquals("Bob", users[1].name)
        }

        @Test
        fun `should deserialize Set using TypeRef factory method`() {
            // Arrange
            val json = """["Alice","Bob","Charlie"]"""
            val typeRef = TypeRef.setOf(String::class.java)

            // Act
            val names = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(3, names.size)
            assertTrue(names.contains("Alice"))
            assertTrue(names.contains("Bob"))
        }

        @Test
        fun `should deserialize Map using TypeRef factory method`() {
            // Arrange
            val json = """{"user1":{"id":1,"name":"Alice"},"user2":{"id":2,"name":"Bob"}}"""
            val typeRef = TypeRef.mapOf(String::class.java, SimpleUser::class.java)

            // Act
            val userMap = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(2, userMap.size)
            assertEquals("Alice", userMap["user1"]?.name)
            assertEquals("Bob", userMap["user2"]?.name)
        }

        @Test
        fun `should deserialize nested List using anonymous TypeRef`() {
            // Arrange
            val json = """[["a","b"],["c","d"],["e","f"]]"""
            val typeRef = object : TypeRef<List<List<String>>>() {}

            // Act
            val nestedList = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(3, nestedList.size)
            assertEquals(2, nestedList[0].size)
            assertEquals("a", nestedList[0][0])
            assertEquals("f", nestedList[2][1])
        }

        @Test
        fun `should deserialize Map with List values using anonymous TypeRef`() {
            // Arrange
            val json =
                """{"team1":[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}],"team2":[{"id":3,"name":"Charlie"}]}"""
            val typeRef = object : TypeRef<Map<String, List<SimpleUser>>>() {}

            // Act
            val teams = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(2, teams.size)
            assertEquals(2, teams["team1"]?.size)
            assertEquals("Alice", teams["team1"]?.get(0)?.name)
            assertEquals(1, teams["team2"]?.size)
        }

        @Test
        fun `should deserialize generic container using anonymous TypeRef`() {
            // Arrange
            val json = """{"value":{"id":1,"name":"Alice"},"metadata":{"version":"1.0"}}"""
            val typeRef = object : TypeRef<GenericContainer<SimpleUser>>() {}

            // Act
            val container = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(1, container.value.id)
            assertEquals("Alice", container.value.name)
            assertEquals("1.0", container.metadata?.get("version"))
        }

        @Test
        fun `should deserialize complex nested generic using anonymous TypeRef`() {
            // Arrange
            val json = """{"data":{"group1":[{"id":1,"name":"Alice"}],"group2":[{"id":2,"name":"Bob"}]},"count":2}"""
            val typeRef = object : TypeRef<ComplexGeneric<String, SimpleUser>>() {}

            // Act
            val complex = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(2, complex.count)
            assertEquals(1, complex.data["group1"]?.size)
            assertEquals("Alice", complex.data["group1"]?.get(0)?.name)
        }

        @Test
        fun `should deserialize List of Maps using anonymous TypeRef`() {
            // Arrange
            val json = """[{"key1":"value1"},{"key2":"value2"}]"""
            val typeRef = object : TypeRef<List<Map<String, String>>>() {}

            // Act
            val listOfMaps = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(2, listOfMaps.size)
            assertEquals("value1", listOfMaps[0]["key1"])
            assertEquals("value2", listOfMaps[1]["key2"])
        }

        @Test
        fun `should deserialize Map of Lists of Maps using anonymous TypeRef`() {
            // Arrange
            val json = """{"group1":[{"id":1},{"id":2}],"group2":[{"id":3}]}"""
            val typeRef = object : TypeRef<Map<String, List<Map<String, Int>>>>() {}

            // Act
            val complexMap = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(2, complexMap.size)
            assertEquals(2, complexMap["group1"]?.size)
            assertEquals(1, complexMap["group1"]?.get(0)?.get("id"))
        }
    }

    // ========================================================================
    // Class-based Tests
    // ========================================================================

    @Nested
    inner class ClassBasedTests {

        @Test
        fun `should deserialize using Class parameter`() {
            // Arrange
            val json = """{"id":1,"name":"Alice"}"""

            // Act
            val user = fromJson(mapper, json, SimpleUser::class.java)

            // Assert
            assertEquals(1, user.id)
            assertEquals("Alice", user.name)
        }

        @Test
        fun `should serialize and deserialize using Class parameter`() {
            // Arrange
            val original = SimpleUser(1, "Alice")
            val json = mapper.toJsonString(original)

            // Act
            val deserialized = fromJson(mapper, json, SimpleUser::class.java)

            // Assert
            assertEquals(original, deserialized)
        }
    }

    // ========================================================================
    // Stream Tests with TypeRef
    // ========================================================================

    @Nested
    inner class StreamTests {

        @Test
        fun `should deserialize from InputStream using reified type`() {
            // Arrange
            val json = """{"id":1,"name":"Alice"}"""
            val stream = json.byteInputStream()

            // Act
            val user = fromJsonStream<SimpleUser>(mapper, stream)

            // Assert
            assertEquals(1, user.id)
            assertEquals("Alice", user.name)
        }

        @Test
        fun `should deserialize from InputStream using TypeRef`() {
            // Arrange
            val json = """[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]"""
            val stream = json.byteInputStream()
            val typeRef = TypeRef.listOf(SimpleUser::class.java)

            // Act
            val users = fromJsonStream(mapper, stream, typeRef)

            // Assert
            assertEquals(2, users.size)
            assertEquals("Alice", users[0].name)
        }
    }

    // ========================================================================
    // Comparison Tests - Different Type Specification Methods
    // ========================================================================

    @Nested
    inner class ComparisonTests {

        @Test
        fun `all three methods should produce same result for simple type`() {
            // Arrange
            val json = """{"id":1,"name":"Alice"}"""

            // Act
            val result1 = fromJson<SimpleUser>(mapper, json)
            val result2 = fromJson(mapper, json, TypeRef.of(SimpleUser::class.java))
            val result3 = fromJson(mapper, json, SimpleUser::class.java)

            // Assert
            assertEquals(result1, result2)
            assertEquals(result2, result3)
        }

        @Test
        fun `reified and TypeRef should produce same result for List`() {
            // Arrange
            val json = """[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]"""

            // Act
            val result1 = fromJson<List<SimpleUser>>(mapper, json)
            val result2 = fromJson(mapper, json, TypeRef.listOf(SimpleUser::class.java))

            // Assert
            assertEquals(result1, result2)
        }

        @Test
        fun `reified and TypeRef should produce same result for Map`() {
            // Arrange
            val json = """{"user1":{"id":1,"name":"Alice"}}"""

            // Act
            val result1 = fromJson<Map<String, SimpleUser>>(mapper, json)
            val result2 = fromJson(mapper, json, TypeRef.mapOf(String::class.java, SimpleUser::class.java))

            // Assert
            assertEquals(result1, result2)
        }

        @Test
        fun `reified and anonymous TypeRef should produce same result for nested generics`() {
            // Arrange
            val json = """{"value":{"id":1,"name":"Alice"},"metadata":{"key":"value"}}"""

            // Act
            val result1 = fromJson<GenericContainer<SimpleUser>>(mapper, json)
            val result2 = fromJson(mapper, json, object : TypeRef<GenericContainer<SimpleUser>>() {})

            // Assert
            assertEquals(result1, result2)
        }
    }

    // ========================================================================
    // ConvertValue Tests with TypeRef
    // ========================================================================

    @Nested
    inner class ConvertValueTests {

        @Test
        fun `should convert Map to object using reified type`() {
            // Arrange
            val map = mapOf("id" to 1, "name" to "Alice")

            // Act
            val user = convertValue<SimpleUser>(mapper, map)

            // Assert
            assertEquals(1, user.id)
            assertEquals("Alice", user.name)
        }

        @Test
        fun `should convert Map to object using TypeRef`() {
            // Arrange
            val map = mapOf("id" to 1, "name" to "Alice")
            val typeRef = TypeRef.of(SimpleUser::class.java)

            // Act
            val user = convertValue(mapper, map, typeRef)

            // Assert
            assertEquals(1, user.id)
            assertEquals("Alice", user.name)
        }

        @Test
        fun `should convert object to Map using reified type`() {
            // Arrange
            val user = SimpleUser(1, "Alice")

            // Act
            val map = convertValue<Map<String, Any>>(mapper, user)

            // Assert
            assertEquals(1, map["id"])
            assertEquals("Alice", map["name"])
        }

        @Test
        fun `should convert List of Maps to List of objects using reified type`() {
            // Arrange
            val maps = listOf(
                mapOf("id" to 1, "name" to "Alice"),
                mapOf("id" to 2, "name" to "Bob")
            )

            // Act
            val users = convertValue<List<SimpleUser>>(mapper, maps)

            // Assert
            assertEquals(2, users.size)
            assertEquals("Alice", users[0].name)
            assertEquals("Bob", users[1].name)
        }

        @Test
        fun `should convert List of Maps to List of objects using TypeRef`() {
            // Arrange
            val maps = listOf(
                mapOf("id" to 1, "name" to "Alice"),
                mapOf("id" to 2, "name" to "Bob")
            )
            val typeRef = TypeRef.listOf(SimpleUser::class.java)

            // Act
            val users = convertValue(mapper, maps, typeRef)

            // Assert
            assertEquals(2, users.size)
            assertEquals("Alice", users[0].name)
        }

        @Test
        fun `should convert nested Map to nested object using anonymous TypeRef`() {
            // Arrange
            val map = mapOf(
                "id" to 1,
                "name" to "Charlie",
                "address" to mapOf(
                    "street" to "123 Main St",
                    "city" to "Springfield",
                    "zipCode" to "12345"
                )
            )
            val typeRef = TypeRef.of(NestedUser::class.java)

            // Act
            val user = convertValue(mapper, map, typeRef)

            // Assert
            assertEquals(1, user.id)
            assertNotNull(user.address)
            assertEquals("123 Main St", user.address.street)
        }

        @Test
        fun `should convert Map with generic container using anonymous TypeRef`() {
            // Arrange
            val map = mapOf(
                "value" to mapOf("id" to 1, "name" to "Alice"),
                "metadata" to mapOf("version" to "1.0")
            )
            val typeRef = object : TypeRef<GenericContainer<SimpleUser>>() {}

            // Act
            val container = convertValue(mapper, map, typeRef)

            // Assert
            assertEquals(1, container.value.id)
            assertEquals("1.0", container.metadata?.get("version"))
        }

        @Test
        fun `should convert complex nested structures using anonymous TypeRef`() {
            // Arrange
            val map = mapOf(
                "team1" to listOf(
                    mapOf("id" to 1, "name" to "Alice"),
                    mapOf("id" to 2, "name" to "Bob")
                ),
                "team2" to listOf(
                    mapOf("id" to 3, "name" to "Charlie")
                )
            )
            val typeRef = object : TypeRef<Map<String, List<SimpleUser>>>() {}

            // Act
            val teams = convertValue(mapper, map, typeRef)

            // Assert
            assertEquals(2, teams.size)
            assertEquals(2, teams["team1"]?.size)
            assertEquals("Alice", teams["team1"]?.get(0)?.name)
        }

        @Test
        fun `should handle nullable fields in conversion using reified type`() {
            // Arrange
            val map = mapOf("id" to 1, "name" to "Bob", "email" to "bob@example.com")

            // Act
            val user = convertValue<UserWithNullable>(mapper, map)

            // Assert
            assertEquals("bob@example.com", user.email)
            assertEquals(null, user.age)
        }

        @Test
        fun `should convert between different collection types using TypeRef`() {
            // Arrange
            val list = listOf("Alice", "Bob", "Charlie")
            val typeRef = TypeRef.setOf(String::class.java)

            // Act
            val set = convertValue(mapper, list, typeRef)

            // Assert
            assertEquals(3, set.size)
            assertTrue(set.contains("Alice"))
        }

        @Test
        fun `should chain convertValue operations with different TypeRefs`() {
            // Arrange
            val user = SimpleUser(1, "Alice")

            // Act - Convert to Map then back to object
            val map = convertValue<Map<String, Any>>(mapper, user)
            val typeRef = TypeRef.of(SimpleUser::class.java)
            val converted = convertValue(mapper, map, typeRef)

            // Assert
            assertEquals(user, converted)
        }
    }

    // ========================================================================
    // WriteToOutputStream Tests with TypeRef
    // ========================================================================

    @Nested
    inner class WriteToOutputStreamTests {

        @Test
        fun `should write Stream of simple objects to OutputStream`() {
            // Arrange
            val users = Stream.of(
                SimpleUser(1, "Alice"),
                SimpleUser(2, "Bob"),
                SimpleUser(3, "Charlie")
            )
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(users, outputStream)

            // Assert
            val json = outputStream.toString()
            assertTrue(json.startsWith("["))
            assertTrue(json.endsWith("]"))
            assertTrue(json.contains("\"name\":\"Alice\""))
            assertTrue(json.contains("\"name\":\"Bob\""))
            assertTrue(json.contains("\"name\":\"Charlie\""))
        }

        @Test
        fun `should write Stream of objects with nullable fields to OutputStream`() {
            // Arrange
            val users = Stream.of(
                UserWithNullable(1, "Alice", "alice@example.com", null),
                UserWithNullable(2, "Bob", null, 25)
            )
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(users, outputStream)

            // Assert
            val json = outputStream.toString()
            assertTrue(json.contains("\"email\":\"alice@example.com\""))
            assertTrue(json.contains("\"age\":25"))
        }

        @Test
        fun `should write Stream of nested objects to OutputStream`() {
            // Arrange
            val users = Stream.of(
                NestedUser(1, "Alice", Address("123 Main St", "Springfield", "12345")),
                NestedUser(2, "Bob", null)
            )
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(users, outputStream)

            // Assert
            val json = outputStream.toString()
            assertTrue(json.contains("\"address\""))
            assertTrue(json.contains("\"street\":\"123 Main St\""))
        }

        @Test
        fun `should write empty Stream to OutputStream`() {
            // Arrange
            val users = Stream.empty<SimpleUser>()
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(users, outputStream)

            // Assert
            val json = outputStream.toString()
            assertEquals("[]", json)
        }

        @Test
        fun `should write single element Stream to OutputStream`() {
            // Arrange
            val users = Stream.of(SimpleUser(1, "Alice"))
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(users, outputStream)

            // Assert
            val json = outputStream.toString()
            assertTrue(json.startsWith("["))
            assertTrue(json.contains("\"name\":\"Alice\""))
        }

        @Test
        fun `should write Stream of generic containers to OutputStream`() {
            // Arrange
            val containers = Stream.of(
                GenericContainer(SimpleUser(1, "Alice"), mapOf("version" to "1.0")),
                GenericContainer(SimpleUser(2, "Bob"), mapOf("version" to "2.0"))
            )
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(containers, outputStream)

            // Assert
            val json = outputStream.toString()
            assertTrue(json.contains("\"version\":\"1.0\""))
            assertTrue(json.contains("\"version\":\"2.0\""))
        }

        @Test
        fun `should write Stream and then deserialize result using TypeRef`() {
            // Arrange
            val original = listOf(
                SimpleUser(1, "Alice"),
                SimpleUser(2, "Bob")
            )
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(original.stream(), outputStream)
            val json = outputStream.toString()
            val typeRef = TypeRef.listOf(SimpleUser::class.java)
            val deserialized = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(original, deserialized)
        }

        @Test
        fun `should handle large Stream efficiently`() {
            // Arrange
            val largeStream = (1..1000).map { SimpleUser(it, "User$it") }.stream()
            val outputStream = ByteArrayOutputStream()

            // Act
            mapper.writeToOutputStream(largeStream, outputStream)

            // Assert
            val json = outputStream.toString()
            assertTrue(json.contains("\"name\":\"User1\""))
            assertTrue(json.contains("\"name\":\"User1000\""))
            val typeRef = TypeRef.listOf(SimpleUser::class.java)
            val deserialized = fromJson(mapper, json, typeRef)
            assertEquals(1000, deserialized.size)
        }
    }

    // ========================================================================
    // Edge Cases with TypeRef
    // ========================================================================

    @Nested
    inner class EdgeCasesWithTypeRef {

        @Test
        fun `should handle empty List with TypeRef`() {
            // Arrange
            val json = """[]"""
            val typeRef = TypeRef.listOf(SimpleUser::class.java)

            // Act
            val users = fromJson(mapper, json, typeRef)

            // Assert
            assertTrue(users.isEmpty())
        }

        @Test
        fun `should handle empty Map with TypeRef`() {
            // Arrange
            val json = """{}"""
            val typeRef = TypeRef.mapOf(String::class.java, SimpleUser::class.java)

            // Act
            val userMap = fromJson(mapper, json, typeRef)

            // Assert
            assertTrue(userMap.isEmpty())
        }

        @Test
        fun `should handle nullable nested object with TypeRef`() {
            // Arrange
            val json = """{"id":1,"name":"Alice","address":null}"""
            val typeRef = TypeRef.of(NestedUser::class.java)

            // Act
            val user = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(1, user.id)
            assertEquals(null, user.address)
        }

        @Test
        fun `should handle List with nullable elements`() {
            // Arrange
            val json = """[{"id":1,"name":"Alice","email":null}]"""
            val typeRef = TypeRef.listOf(UserWithNullable::class.java)

            // Act
            val users = fromJson(mapper, json, typeRef)

            // Assert
            assertEquals(1, users.size)
            assertEquals(null, users[0].email)
        }

        @Test
        fun `should quote plain String and pass RawJson through in toJsonString`() {
            // Plain strings are JSON string VALUES and must be quoted; only
            // RawJson marks pre-serialized JSON to be passed through verbatim.
            val jsonString = """{"id":1,"name":"Test"}"""

            // Act & Assert
            assertEquals("\"hello\"", mapper.toJsonString("hello"))
            assertEquals(jsonString, mapper.toJsonString(RawJson(jsonString)))
        }

        @Test
        fun `should quote plain String and pass RawJson through in toJsonStream`() {
            // Arrange
            val jsonString = """{"id":1,"name":"Test"}"""

            // Act
            val quoted = mapper.toJsonStream("hello").bufferedReader().use { it.readText() }
            val raw = mapper.toJsonStream(RawJson(jsonString)).bufferedReader().use { it.readText() }

            // Assert
            assertEquals("\"hello\"", quoted)
            assertEquals(jsonString, raw)
        }

        @Test
        fun `should serialize and deserialize object with defaults using all methods`() {
            // Arrange
            val original = UserWithDefaults(id = 1, name = "Custom", active = false)

            // Act & Assert - toJsonString + fromJsonString
            val json1 = mapper.toJsonString(original)
            val result1 = fromJson<UserWithDefaults>(mapper, json1)
            assertEquals(original, result1)

            // Act & Assert - toJsonStream + fromJsonStream
            val stream = mapper.toJsonStream(original)
            val result2 = fromJsonStream<UserWithDefaults>(mapper, stream)
            assertEquals(original, result2)

            // Act & Assert - convertValue
            val map = convertValue<Map<String, Any>>(mapper, original)
            val result3 = convertValue<UserWithDefaults>(mapper, map)
            assertEquals(original, result3)
        }
    }

    // ========================================================================
    // Type-Inference Serialization Tests (without explicit type specification)
    // ========================================================================

    @Nested
    inner class TypeInferenceSerializationTests {

        @Test
        fun `should serialize simple object without type specification`() {
            // Arrange
            val user = SimpleUser(1, "Alice")

            // Act - Use object's actual class
            val json = mapper.toJsonString(user)

            // Assert
            assertTrue(json.contains("\"id\":1"))
            assertTrue(json.contains("\"name\":\"Alice\""))
        }

        @Test
        fun `should serialize List without type specification`() {
            // Arrange
            val users = listOf(
                SimpleUser(1, "Alice"),
                SimpleUser(2, "Bob")
            )

            // Act - Use actual runtime class
            val json = mapper.toJsonString(users)

            // Assert
            assertTrue(json.startsWith("["))
            assertTrue(json.contains("\"name\":\"Alice\""))
            assertTrue(json.contains("\"name\":\"Bob\""))
        }

        @Test
        fun `should serialize Map without type specification`() {
            // Arrange
            val userMap = mapOf(
                "user1" to SimpleUser(1, "Alice"),
                "user2" to SimpleUser(2, "Bob")
            )

            // Act
            val json = mapper.toJsonString(userMap)

            // Assert
            assertTrue(json.contains("\"user1\""))
            assertTrue(json.contains("\"name\":\"Alice\""))
        }

        @Test
        fun `should serialize nested object without type specification`() {
            // Arrange
            val user = NestedUser(1, "Charlie", Address("123 Main St", "Springfield", "12345"))

            // Act
            val json = mapper.toJsonString(user)

            // Assert
            assertTrue(json.contains("\"address\""))
            assertTrue(json.contains("\"street\":\"123 Main St\""))
        }

        @Test
        fun `should serialize generic container without explicit type parameter`() {
            // Arrange
            val container = GenericContainer(
                value = SimpleUser(1, "Alice"),
                metadata = mapOf("version" to "1.0")
            )

            // Act - Runtime type erasure, but should still work
            val json = mapper.toJsonString(container)

            // Assert
            assertTrue(json.contains("\"value\""))
            assertTrue(json.contains("\"name\":\"Alice\""))
            assertTrue(json.contains("\"metadata\""))
        }

        @Test
        fun `should serialize to stream without type specification`() {
            // Arrange
            val user = SimpleUser(1, "Alice")

            // Act
            val stream = mapper.toJsonStream(user)
            val json = stream.bufferedReader().use { it.readText() }

            // Assert
            assertTrue(json.contains("\"name\":\"Alice\""))
        }

        @Test
        fun `should handle null value serialization - framework emits JSON null literal`() {
            // ResponseBody.Json handles null itself (emitting the literal `null`);
            // a String "null" reaching the mapper is a string VALUE and gets quoted.
            assertEquals("\"null\"", mapper.toJsonString("null"))
            // Pre-serialized null passes through via RawJson
            assertEquals("null", mapper.toJsonString(RawJson("null")))
        }

        @Test
        fun `should compare null handling strategies`() {
            // Strategy 1: RawJson "null" literal
            assertEquals("null", mapper.toJsonString(RawJson("null")))

            // Strategy 2: Serialize an empty object instead of null
            val data2: Any? = null
            val value2 = data2 ?: mapOf<String, Any?>()
            assertEquals("{}", mapper.toJsonString(value2))
        }

        @Test
        fun `should serialize primitive wrapper types without type specification`() {
            // Arrange
            val number: Any = 42
            val text: Any = "Hello"
            val flag: Any = true

            // Act
            val jsonNumber = mapper.toJsonString(number)
            val jsonText = mapper.toJsonString(text)
            val jsonFlag = mapper.toJsonString(flag)

            // Assert
            assertEquals("42", jsonNumber)
            // Strings are serialized as JSON string values (quoted)
            assertEquals("\"Hello\"", jsonText)
            assertEquals("true", jsonFlag)
        }

        @Test
        fun `should differentiate between JSON string and plain string`() {
            // Arrange
            val plainString = "Hello"
            val jsonString = """{"message":"Hello"}"""

            // Act
            val result1 = mapper.toJsonString(plainString)
            val result2 = mapper.toJsonString(RawJson(jsonString))

            // Assert
            // Plain strings are quoted; RawJson passes through untouched
            assertEquals("\"Hello\"", result1)
            assertEquals("""{"message":"Hello"}""", result2)
        }

        @Test
        fun `should serialize non-string primitives correctly`() {
            // Arrange
            val user = SimpleUser(42, "Test")

            // Act - When we serialize an actual object
            val json = mapper.toJsonString(user)

            // Assert - This is properly JSON-encoded
            assertTrue(json.contains("\"name\":\"Test\""))  // String IS quoted in object context
            assertTrue(json.contains("\"id\":42"))  // Number is not quoted
        }

        @Test
        fun `should serialize object with nullable fields without type specification`() {
            // Arrange
            val user = UserWithNullable(1, "Bob", "bob@example.com", null)

            // Act
            val json = mapper.toJsonString(user)

            // Assert
            assertTrue(json.contains("\"email\":\"bob@example.com\""))
            assertTrue(json.contains("\"age\"")) // null fields not omitted
        }

        @Test
        fun `should round-trip serialize and deserialize without explicit types`() {
            // Arrange
            val original = SimpleUser(1, "Alice")

            // Act - Serialize without type specification
            val json = mapper.toJsonString(original)

            // Deserialize with TypeRef to verify
            val deserialized = fromJson<SimpleUser>(mapper, json)

            // Assert
            assertEquals(original, deserialized)
        }

        @Test
        fun `should handle complex collection types without type specification`() {
            // Arrange
            val data = mapOf(
                "users" to listOf(SimpleUser(1, "Alice"), SimpleUser(2, "Bob")),
                "count" to 2,
                "active" to true
            )

            // Act
            val json = mapper.toJsonString(data)

            // Assert
            assertTrue(json.contains("\"users\""))
            assertTrue(json.contains("\"count\":2"))
            assertTrue(json.contains("\"active\":true"))
        }

        @Test
        fun `should serialize empty collections without type specification`() {
            // Arrange
            val emptyList = emptyList<SimpleUser>()
            val emptyMap = emptyMap<String, Any>()

            // Act
            val jsonList = mapper.toJsonString(emptyList)
            val jsonMap = mapper.toJsonString(emptyMap)

            // Assert
            assertEquals("[]", jsonList)
            assertEquals("{}", jsonMap)
        }

        @Test
        fun `should demonstrate ResponseBody Json class scenario - with null handling`() {
            // Scenario 1: null is handled by ResponseBody.Json itself, which
            // emits the JSON literal `null` without calling the mapper

            // Scenario 2: data is an object
            val data2: Any = SimpleUser(1, "Alice")
            val json2 = mapper.toJsonString(data2)
            assertTrue(json2.contains("\"name\":\"Alice\""))

            // Scenario 3: pre-serialized JSON must be wrapped in RawJson
            val data3: Any = RawJson("""{"status":"ok"}""")
            val json3 = mapper.toJsonString(data3)
            assertEquals("""{"status":"ok"}""", json3) // Passed through as-is

            // Scenario 4: a plain string is a JSON string VALUE
            assertEquals("\"ok\"", mapper.toJsonString("ok"))
        }

        @Test
        fun `should demonstrate ResponseBody Json class scenario - stream mode`() {
            // Arrange - Simulate the ResponseBody.Json scenario
            val data: Any = listOf(SimpleUser(1, "Alice"), SimpleUser(2, "Bob"))

            // Act - Stream mode
            val stream = mapper.toJsonStream(data)
            val json = stream.bufferedReader().use { it.readText() }

            // Assert
            assertTrue(json.startsWith("["))
            assertTrue(json.contains("\"name\":\"Alice\""))
        }

        @Test
        fun `should demonstrate ResponseBody Json class scenario - bytes mode`() {
            // Arrange - Simulate the ResponseBody.Json scenario
            val data: Any = SimpleUser(1, "Alice")

            // Act - Bytes mode
            val json = mapper.toJsonString(data)
            val bytes = json.toByteArray(Charsets.UTF_8)

            // Assert
            assertNotNull(bytes)
            assertTrue(bytes.isNotEmpty())
            val result = String(bytes, Charsets.UTF_8)
            assertTrue(result.contains("\"name\":\"Alice\""))
        }

        @Test
        fun `should handle polymorphic collections without type specification`() {
            // Arrange - Mixed types in collection
            val mixedList: List<Any> = listOf(
                SimpleUser(1, "Alice"),
                mapOf("key" to "value"),
                "plain string",
                42
            )

            // Act
            val json = mapper.toJsonString(mixedList)

            // Assert
            assertTrue(json.startsWith("["))
            assertTrue(json.contains("\"name\":\"Alice\""))
            assertTrue(json.contains("\"key\":\"value\""))
            assertTrue(json.contains("\"plain string\""))
            assertTrue(json.contains("42"))
        }
    }

    // ========================================================================
    // Error Handling with TypeRef
    // ========================================================================

    @Nested
    inner class ErrorHandlingWithTypeRef {

        @Test
        fun `should throw exception for invalid JSON with reified type`() {
            // Arrange
            val invalidJson = """{"id":1,"name":}"""

            // Act & Assert
            assertThrows<Exception> {
                fromJson<SimpleUser>(mapper, invalidJson)
            }
        }

        @Test
        fun `should throw exception for invalid JSON with TypeRef`() {
            // Arrange
            val invalidJson = """{"id":1,"name":}"""
            val typeRef = TypeRef.of(SimpleUser::class.java)

            // Act & Assert
            assertThrows<Exception> {
                fromJson(mapper, invalidJson, typeRef)
            }
        }

        @Test
        fun `should throw exception for type mismatch with TypeRef`() {
            // Arrange
            val json = """{"id":"not_a_number","name":"Alice"}"""
            val typeRef = TypeRef.of(SimpleUser::class.java)

            // Act & Assert
            assertThrows<Exception> {
                fromJson(mapper, json, typeRef)
            }
        }
    }
}