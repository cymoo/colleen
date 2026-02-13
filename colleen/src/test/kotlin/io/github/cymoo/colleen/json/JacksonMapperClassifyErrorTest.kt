package io.github.cymoo.colleen.json

import com.fasterxml.jackson.databind.DeserializationFeature
import io.github.cymoo.colleen.BindingFailure
import io.github.cymoo.colleen.Config
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("JacksonMapper.classifyError")
class JacksonMapperClassifyErrorTest {

    private val mapper = Config().jsonMapper

    // Test data classes defined at class level
    data class User(val name: String, val age: Int)
    data class StrictUser(val name: String, val age: Int)
    data class UserWithIds(val name: String, val ids: List<Int>)
    data class UserWithTags(val name: String, val tags: List<String>)
    data class Address(val street: String, val zipCode: Int)
    data class UserWithAddress(val name: String, val address: Address)
    data class NestedUser(val user: User, val id: Int)
    data class ComplexUser(
        val name: String,
        val tags: List<String>,
        val metadata: Map<String, Any>?
    )

    data class SimpleUser(val name: String, val age: Int)
    data class Team(val name: String, val members: List<User>)
    data class Organization(val teams: List<Team>)
    data class Project(val name: String, val tags: List<String>, val contributors: List<User>)
    data class ExtendedAddress(val street: String, val zipCode: Int, val coordinates: List<Double>)
    data class CompanyWithCoords(val name: String, val address: ExtendedAddress)

    @Nested
    @DisplayName("Malformed JSON")
    inner class MalformedJsonTests {

        @Test
        fun `should classify JsonParseException as Malformed`() {
            // Arrange
            val json = """{ "name": }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Map<String, Any>>(json, Map::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Malformed)
            assertNull(result!!.field)
        }

        @Test
        fun `should classify malformed array as Malformed`() {
            // Arrange
            val json = """[1, 2,]"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<List<Int>>(json, List::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Malformed)
        }

        @Test
        fun `should classify unclosed JSON object as Malformed`() {
            // Arrange
            val json = """{ "name": "John" """

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Map<String, Any>>(json, Map::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Malformed)
        }
    }

    @Nested
    @DisplayName("Unknown Properties")
    inner class UnknownPropertyTests {

        @Test
        fun `should classify UnrecognizedPropertyException as Unknown`() {
            // Arrange
            val strictMapper = JacksonMapper(
                JacksonMapper.defaultMapper().also {
                    it.configure(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        true
                    )
                }
            )
            val json = """{ "name": "John", "age": 25, "email": "john@example.com" }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                strictMapper.fromJsonString<StrictUser>(json, StrictUser::class.java)
            }
            val result = strictMapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Unknown)
            assertEquals("email", (result as BindingFailure.Unknown).field)
        }
    }

    @Nested
    @DisplayName("Invalid Type")
    inner class InvalidTypeTests {

        @Test
        fun `should classify string-to-int conversion failure as InvalidType`() {
            // Arrange
            val json = """{ "name": "John", "age": "not-a-number" }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            assertEquals("age", (result as BindingFailure.InvalidType).field)
            assertNotNull(result.expected)
        }

        @Test
        fun `should classify boolean-to-int conversion failure as InvalidType`() {
            // Arrange
            val json = """{ "name": "John", "age": true }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            assertEquals("age", (result as BindingFailure.InvalidType).field)
        }

        @Test
        fun `should classify object-to-primitive conversion failure as InvalidType`() {
            // Arrange
            val json = """{ "name": "John", "age": {"value": 25} }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            assertEquals("age", (result as BindingFailure.InvalidType).field)
        }

        @Test
        fun `should classify null value for primitive field as InvalidType`() {
            // Arrange
            val json = """{ "name": "John", "age": null }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            // null cannot be mapped to primitive int, so it's an InvalidType error
            assertTrue(result is BindingFailure.InvalidType)
            assertEquals("age", (result as BindingFailure.InvalidType).field)
        }
    }

    @Nested
    @DisplayName("Missing Required Fields")
    inner class MissingFieldTests {

        @Test
        fun `should classify missing required field as Missing`() {
            // Arrange
            val json = """{ "name": "John" }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Missing)
            assertEquals("age", (result as BindingFailure.Missing).field)
        }

        @Test
        fun `should classify empty object missing all fields as Missing`() {
            // Arrange
            val json = """{}"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Missing)
            assertNotNull((result as BindingFailure.Missing).field)
        }
    }

    @Nested
    @DisplayName("Invalid Element Type in Collections")
    inner class InvalidElementTypeTests {

        @Test
        fun `should classify invalid element in list as InvalidElementType`() {
            // Arrange
            val json = """{ "name": "John", "ids": [1, 2, "invalid", 4] }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<UserWithIds>(json, UserWithIds::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType)
            val elementError = result as BindingFailure.InvalidElementType
            assertEquals("ids", elementError.field)
            assertEquals(2, elementError.index)
            assertNotNull(elementError.expected)
        }

        @Test
        fun `should classify invalid element at first position as InvalidElementType`() {
            // Arrange
            val json = """{ "name": "John", "ids": ["invalid", 2, 3] }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<UserWithIds>(json, UserWithIds::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType)
            val elementError = result as BindingFailure.InvalidElementType
            assertEquals("ids", elementError.field)
            assertEquals(0, elementError.index)
        }

        @Test
        fun `should classify boolean element in int list as InvalidElementType`() {
            // Arrange
            val json = """{ "name": "John", "ids": [1, true, 3] }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<UserWithIds>(json, UserWithIds::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType)
            val elementError = result as BindingFailure.InvalidElementType
            assertEquals("ids", elementError.field)
            assertEquals(1, elementError.index)
        }

        @Test
        fun `should classify object element in string list as InvalidElementType`() {
            // Arrange
            val json = """{ "name": "John", "tags": ["valid", {"key": "value"}, "tag"] }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<UserWithTags>(json, UserWithTags::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType)
            val elementError = result as BindingFailure.InvalidElementType
            assertEquals("tags", elementError.field)
            assertEquals(1, elementError.index)
        }
    }

    @Nested
    @DisplayName("Invalid Structure")
    inner class InvalidStructureTests {

        @Test
        fun `should classify array-to-object mismatch as InvalidStructure`() {
            // Arrange
            val json = """["John", 25]"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidStructure || result is BindingFailure.InvalidType)
        }

        @Test
        fun `should classify primitive-to-object mismatch as InvalidStructure`() {
            // Arrange
            val json = """"just-a-string""""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidStructure || result is BindingFailure.InvalidType)
        }
    }

    @Nested
    @DisplayName("Non-Jackson Exceptions")
    inner class NonJacksonExceptionTests {

        @Test
        fun `should return null for non-Jackson exceptions`() {
            // Arrange
            val exception = IllegalArgumentException("Not a Jackson exception")

            // Act
            val result = mapper.classifyError(exception)

            // Assert
            assertNull(result)
        }

        @Test
        fun `should return null for RuntimeException`() {
            // Arrange
            val exception = RuntimeException("Generic runtime exception")

            // Act
            val result = mapper.classifyError(exception)

            // Assert
            assertNull(result)
        }

        @Test
        fun `should return null for NullPointerException`() {
            // Arrange
            val exception = NullPointerException("NPE")

            // Act
            val result = mapper.classifyError(exception)

            // Assert
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Nested Object Errors")
    inner class NestedObjectErrorsTests {

        @Test
        fun `should classify invalid type in nested object with full path`() {
            // Arrange
            val json = """{ "name": "John", "address": { "street": "Main St", "zipCode": "invalid" } }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<UserWithAddress>(json, UserWithAddress::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            // Should return full path: address.zipCode
            assertEquals("address.zipCode", (result as BindingFailure.InvalidType).field)
        }

        @Test
        fun `should classify missing field in nested object with full path`() {
            // Arrange
            val json = """{ "name": "John", "address": { "street": "Main St" } }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<UserWithAddress>(json, UserWithAddress::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Missing)
            // Should return full path: address.zipCode
            assertEquals("address.zipCode", (result as BindingFailure.Missing).field)
        }

        @Test
        fun `should classify missing nested object as Missing`() {
            // Arrange
            val json = """{ "id": 123 }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<NestedUser>(json, NestedUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Missing)
            assertEquals("user", (result as BindingFailure.Missing).field)
        }

        @Test
        fun `should classify invalid nested object structure as InvalidType`() {
            // Arrange
            val json = """{ "user": "invalid-should-be-object", "id": 123 }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<NestedUser>(json, NestedUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            assertEquals("user", (result as BindingFailure.InvalidType).field)
        }

        @Test
        fun `should classify array instead of nested object as InvalidType`() {
            // Arrange
            val json = """{ "user": [1, 2, 3], "id": 123 }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<NestedUser>(json, NestedUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType || result is BindingFailure.InvalidStructure)
        }

        @Test
        fun `should classify deeply nested field errors correctly with full path`() {
            // Arrange
            val json = """{ 
            "user": { "name": "John", "age": "invalid" }, 
            "id": 123 
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<NestedUser>(json, NestedUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            // Should report the full path to the deeply nested field
            assertEquals("user.age", (result as BindingFailure.InvalidType).field)
        }
    }

    @Nested
    @DisplayName("Complex Data Structures")
    inner class ComplexDataStructureTests {

        @Test
        fun `should handle optional fields correctly when missing`() {
            // Arrange
            val json = """{ "name": "John", "tags": ["tag1", "tag2"] }"""

            // Act & Assert
            // Should not throw exception because metadata is nullable
            assertDoesNotThrow {
                val result = mapper.fromJsonString<ComplexUser>(json, ComplexUser::class.java)
                assertNull(result.metadata)
            }
        }

        @Test
        fun `should classify invalid metadata type as InvalidType`() {
            // Arrange
            val json = """{ 
            "name": "John", 
            "tags": ["tag1", "tag2"],
            "metadata": "should-be-object"
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<ComplexUser>(json, ComplexUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            assertEquals("metadata", (result as BindingFailure.InvalidType).field)
        }

        @Test
        fun `should classify invalid element in tags list as InvalidElementType`() {
            // Arrange
            val json = """{ 
            "name": "John", 
            "tags": ["tag1", {"invalid": "object"}, "tag3"],
            "metadata": null
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<ComplexUser>(json, ComplexUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType)
            val elementError = result as BindingFailure.InvalidElementType
            assertEquals("tags", elementError.field)
            assertEquals(1, elementError.index)
        }

        @Test
        fun `should classify missing required field in complex object as Missing`() {
            // Arrange
            val json = """{ 
            "tags": ["tag1", "tag2"],
            "metadata": {"key": "value"}
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<ComplexUser>(json, ComplexUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Missing)
            assertEquals("name", (result as BindingFailure.Missing).field)
        }

        @Test
        fun `should handle valid complex object correctly`() {
            // Arrange
            val json = """{ 
            "name": "John", 
            "tags": ["tag1", "tag2"],
            "metadata": {"key1": "value1", "key2": 123}
        }"""

            // Act & Assert
            assertDoesNotThrow {
                val result = mapper.fromJsonString<ComplexUser>(json, ComplexUser::class.java)
                assertEquals("John", result.name)
                assertEquals(2, result.tags.size)
                assertNotNull(result.metadata)
                assertEquals(2, result.metadata!!.size)
            }
        }

        @Test
        fun `should classify null value for non-nullable complex field as InvalidType`() {
            // Arrange
            val json = """{ 
            "name": null, 
            "tags": ["tag1"],
            "metadata": null
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<ComplexUser>(json, ComplexUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            // null for String field could be Missing or InvalidType depending on Jackson's interpretation
            assertTrue(result is BindingFailure.Missing || result is BindingFailure.InvalidType)
            if (result is BindingFailure.Missing) {
                assertEquals("name", result.field)
            } else if (result is BindingFailure.InvalidType) {
                assertEquals("name", result.field)
            }
        }
    }

    @Nested
    @DisplayName("Nested Arrays and Collections")
    inner class NestedArrayTests {
        @Test
        fun `should classify invalid element in nested array with full path`() {
            // Arrange
            val json = """{ 
            "name": "Engineering", 
            "members": [
                {"name": "Alice", "age": 25},
                {"name": "Bob", "age": "invalid"}
            ]
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Team>(json, Team::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            val elementError = result as BindingFailure.InvalidType
            assertEquals("members[1].age", elementError.field)
        }

        @Test
        fun `should classify missing field in array element with full path`() {
            // Arrange
            val json = """{ 
        "name": "Engineering", 
        "members": [
            {"name": "Alice", "age": 25},
            {"name": "Bob"}
        ]
    }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Team>(json, Team::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Missing)
            // Should return the full path including array field
            assertEquals("members[1].age", (result as BindingFailure.Missing).field)
        }

        @Test
        fun `should handle three-level nested array errors with full path`() {
            // Arrange
            data class Department(val name: String, val organizations: List<Organization>)

            val json = """{ 
        "name": "IT Department",
        "organizations": [
            {
                "teams": [
                    {
                        "name": "Team A",
                        "members": [
                            {"name": "Alice", "age": "invalid"}
                        ]
                    }
                ]
            }
        ]
    }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Department>(json, Department::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            val elementError = result as BindingFailure.InvalidType
            assertEquals("organizations[0].teams[0].members[0].age", elementError.field)
        }

        @Test
        fun `should classify deeply nested array error with full path`() {
            // Arrange
            val json = """{ 
        "teams": [
            {
                "name": "Team A",
                "members": [
                    {"name": "Alice", "age": 25}
                ]
            },
            {
                "name": "Team B",
                "members": [
                    {"name": "Bob", "age": "invalid"}
                ]
            }
        ]
    }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Organization>(json, Organization::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            val elementError = result as BindingFailure.InvalidType
            assertEquals("teams[1].members[0].age", elementError.field)
        }

        @Test
        fun `should classify nested array in nested object with invalid element`() {
            // Arrange
            val json = """{ 
        "name": "Tech Corp",
        "address": {
            "street": "Main St",
            "zipCode": 12345,
            "coordinates": [40.7128, "invalid"]
        }
    }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<CompanyWithCoords>(json, CompanyWithCoords::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType)
            val elementError = result as BindingFailure.InvalidElementType
            // Should show full nested path including the parent object
            assertEquals("address.coordinates", elementError.field)
            assertEquals(1, elementError.index)
        }

        @Test
        fun `should classify invalid object in nested array as InvalidElementType`() {
            // Arrange
            val json = """{ 
            "name": "Engineering", 
            "members": [
                {"name": "Alice", "age": 25},
                "invalid-should-be-object"
            ]
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Team>(json, Team::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType || result is BindingFailure.InvalidType)
            if (result is BindingFailure.InvalidElementType) {
                assertEquals("members", result.field)
                assertEquals(1, result.index)
            }
        }

        @Test
        fun `should handle multiple arrays with errors correctly`() {
            // Arrange
            val json = """{ 
            "name": "MyProject",
            "tags": ["tag1", "tag2"],
            "contributors": [
                {"name": "Alice", "age": 30},
                {"name": "Bob", "age": true}
            ]
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Project>(json, Project::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            val elementError = result as BindingFailure.InvalidType
            assertEquals("contributors[1].age", elementError.field)
        }

        @Test
        fun `should classify nested array with null element as InvalidType or Missing`() {
            // Arrange
            val json = """{ 
            "name": "Engineering", 
            "members": [
                {"name": "Alice", "age": 25},
                null
            ]
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Team>(json, Team::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            // Depending on Jackson's configuration, this could be InvalidType or Missing
            assertTrue(
                result is BindingFailure.InvalidElementType ||
                        result is BindingFailure.InvalidType ||
                        result is BindingFailure.Missing
            )
        }

        @Test
        fun `should classify array of primitives with invalid element`() {
            // Arrange
            data class Config(val name: String, val ports: List<Int>)

            val json = """{ 
            "name": "Server Config",
            "ports": [8080, 9090, "invalid", 3000]
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<Config>(json, Config::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidElementType)
            val elementError = result as BindingFailure.InvalidElementType
            assertEquals("ports", elementError.field)
            assertEquals(2, elementError.index)
        }


    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `should preserve original cause in classified errors`() {
            // Arrange
            val json = """{ "name": "John", "age": "invalid" }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<SimpleUser>(json, SimpleUser::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertNotNull(result!!.cause)
            assertTrue(result is BindingFailure.InvalidType)
            assertNotNull((result as BindingFailure.InvalidType).originalCause)
        }

        @Test
        fun `should handle combination of errors and report first one`() {
            // Arrange
            val json = """{ "name": "John" }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<User>(json, User::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.Missing)
            assertEquals("age", (result as BindingFailure.Missing).field)
        }

        @Test
        fun `should classify error in complex nested structure with full path`() {
            // Arrange
            data class DeepNested(val nestedUser: NestedUser, val count: Int)

            val json = """{ 
            "nestedUser": { 
                "user": { "name": "John", "age": "invalid" }, 
                "id": 123 
            }, 
            "count": 5 
        }"""

            // Act
            val exception = assertThrows(Exception::class.java) {
                mapper.fromJsonString<DeepNested>(json, DeepNested::class.java)
            }
            val result = mapper.classifyError(exception)

            // Assert
            assertNotNull(result)
            assertTrue(result is BindingFailure.InvalidType)
            // Should report the full path to the deeply nested field
            assertEquals("nestedUser.user.age", (result as BindingFailure.InvalidType).field)
        }
    }
}