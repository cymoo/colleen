package io.github.cymoo.colleen

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Validation Tests")
class ValidationTests {

    @Nested
    @DisplayName("ValidationResult Tests")
    inner class ValidationResultTests {

        @Test
        fun `success result should be success type`() {
            val result = ValidationResult.Success()
            assertTrue(result.isSuccess())
            assertFalse(result.isFailure())
            assertTrue(result.errors().isEmpty())
        }

        @Test
        fun `failure result should be failure type`() {
            val errors = mapOf("field" to listOf("error"))
            val result = ValidationResult.Failure(errors)
            assertTrue(result.isFailure())
            assertFalse(result.isSuccess())
            assertEquals(errors, result.errors())
        }

        @Test
        fun `success errors should return empty map`() {
            val result = ValidationResult.Success()
            assertTrue(result.errors().isEmpty())
        }
    }

    @Nested
    @DisplayName("StringValidator Tests")
    inner class StringValidatorTests {

        @Test
        fun `required should fail on null value`() {
            val result = validate {
                field("name", null as String?).required()
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("is required"), result.errors()["name"])
        }

        @Test
        fun `required should pass on non-null value`() {
            val result = validate {
                field("name", "John").required()
            }
            assertTrue(result.isSuccess())
        }

        @Test
        fun `required with custom message`() {
            val result = validate {
                field("name", null as String?).required("Name is mandatory")
            }
            assertEquals(listOf("Name is mandatory"), result.errors()["name"])
        }

        @Test
        fun `notBlank should fail on blank string`() {
            val result = validate {
                field("name", "   ").notBlank()
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("cannot be blank"), result.errors()["name"])
        }

        @Test
        fun `notBlank should pass on non-blank string`() {
            val result = validate {
                field("name", "John").notBlank()
            }
            assertTrue(result.isSuccess())
        }

        @Test
        fun `minSize should enforce minimum length`() {
            val result = validate {
                field("username", "ab").minSize(3)
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("minimum length is 3"), result.errors()["username"])
        }

        @Test
        fun `maxSize should enforce maximum length`() {
            val result = validate {
                field("bio", "a".repeat(201)).maxSize(200)
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("maximum length is 200"), result.errors()["bio"])
        }

        @Test
        fun `size should enforce exact length`() {
            val result = validate {
                field("code", "12345").size(4)
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("length must be exactly 4"), result.errors()["code"])
        }

        @Test
        fun `email should validate email format`() {
            val validResult = validate {
                field("email", "test@example.com").email()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("email", "invalid-email").email()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must be a valid email address"), invalidResult.errors()["email"])
        }

        @Test
        fun `url should validate URL format`() {
            val validResult = validate {
                field("website", "https://example.com").url()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("website", "not-a-url").url()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must be a valid URL"), invalidResult.errors()["website"])
        }

        @Test
        fun `date should validate ISO date format`() {
            val validResult = validate {
                field("birthdate", "2023-01-15").date()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("birthdate", "15/01/2023").date()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must be a valid date (yyyy-MM-dd)"), invalidResult.errors()["birthdate"])
        }

        @Test
        fun `datetime should validate ISO datetime format`() {
            val validResult = validate {
                field("timestamp", "2023-01-15T10:30:00").datetime()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("timestamp", "2023-01-15 10:30:00").datetime()
            }
            assertTrue(invalidResult.isFailure())
        }

        @Test
        fun `matches should validate regex pattern`() {
            val pattern = Regex("^[A-Z]{3}\\d{3}$")
            val validResult = validate {
                field("code", "ABC123").matches(pattern)
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("code", "abc123").matches(pattern)
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("format is invalid"), invalidResult.errors()["code"])
        }

        @Test
        fun `alphanumeric should allow only letters and digits`() {
            val validResult = validate {
                field("username", "User_123").alphanumeric()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("username", "User-123").alphanumeric()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must contain only letters and numbers"), invalidResult.errors()["username"])
        }

        @Test
        fun `numeric should allow only digits`() {
            val validResult = validate {
                field("pin", "1234").numeric()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("pin", "12a4").numeric()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must contain only digits"), invalidResult.errors()["pin"])
        }

        @Test
        fun `in should validate against allowed values`() {
            val validResult = validate {
                field("status", "active").`in`("active", "inactive", "pending")
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("status", "deleted").`in`("active", "inactive", "pending")
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must be one of: active, inactive, pending"), invalidResult.errors()["status"])
        }

        @Test
        fun `notIn should validate against forbidden values`() {
            val validResult = validate {
                field("username", "john").notIn("admin", "root", "system")
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("username", "admin").notIn("admin", "root", "system")
            }
            assertTrue(invalidResult.isFailure())
        }

        @Test
        fun `custom validation should work`() {
            val result = validate {
                field("password", "weak").custom(
                    { it.length >= 8 && it.any { c -> c.isUpperCase() } },
                    "must be at least 8 characters with one uppercase letter"
                )
            }
            assertTrue(result.isFailure())
        }

        @Test
        fun `nullable field should skip validation when null`() {
            val result = validate {
                field("bio", null as String?).maxSize(200)
            }
            assertTrue(result.isSuccess())
        }
    }

    @Nested
    @DisplayName("NumberValidator Tests")
    inner class NumberValidatorTests {

        @Test
        fun `required should fail on null value`() {
            val result = validate {
                field("age", null as Int?).required()
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("is required"), result.errors()["age"])
        }

        @Test
        fun `between should validate range`() {
            val validResult = validate {
                field("age", 25).between(18, 65)
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("age", 70).between(18, 65)
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must be between 18 and 65"), invalidResult.errors()["age"])
        }

        @Test
        fun `min should enforce minimum value`() {
            val result = validate {
                field("quantity", 5).min(10)
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("minimum value is 10"), result.errors()["quantity"])
        }

        @Test
        fun `max should enforce maximum value`() {
            val result = validate {
                field("quantity", 150).max(100)
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("maximum value is 100"), result.errors()["quantity"])
        }

        @Test
        fun `positive should require positive numbers`() {
            val validResult = validate {
                field("amount", 10).positive()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("amount", -5).positive()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must be positive"), invalidResult.errors()["amount"])
        }

        @Test
        fun `negative should require negative numbers`() {
            val validResult = validate {
                field("balance", -100).negative()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("balance", 100).negative()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("must be negative"), invalidResult.errors()["balance"])
        }

        @Test
        fun `in should validate against allowed values`() {
            val result = validate {
                field("rating", 3).`in`(1, 2, 3, 4, 5)
            }
            assertTrue(result.isSuccess())
        }

        @Test
        fun `notIn should validate against forbidden values`() {
            val result = validate {
                field("port", 80).notIn(80, 443, 8080)
            }
            assertTrue(result.isFailure())
        }

        @Test
        fun `custom validation should work`() {
            val result = validate {
                field("score", 75).custom(
                    { it.toInt() % 10 == 0 },
                    "must be a multiple of 10"
                )
            }
            assertTrue(result.isFailure())
        }

        @Test
        fun `should work with different number types`() {
            val result = validate {
                field("price", 19.99).between(0, 100)
                field("count", 5L).positive()
            }
            assertTrue(result.isSuccess())
        }
    }

    @Nested
    @DisplayName("CollectionValidator Tests")
    inner class CollectionValidatorTests {

        @Test
        fun `required should fail on null collection`() {
            val result = validate {
                field("tags", null as List<String>?).required()
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("is required"), result.errors()["tags"])
        }

        @Test
        fun `notEmpty should fail on empty collection`() {
            val result = validate {
                field("tags", emptyList<String>()).notEmpty()
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("cannot be empty"), result.errors()["tags"])
        }

        @Test
        fun `minSize should enforce minimum collection size`() {
            val result = validate {
                field("items", listOf("a", "b")).minSize(3)
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("minimum size is 3"), result.errors()["items"])
        }

        @Test
        fun `maxSize should enforce maximum collection size`() {
            val result = validate {
                field("items", listOf("a", "b", "c", "d")).maxSize(3)
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("maximum size is 3"), result.errors()["items"])
        }

        @Test
        fun `noDuplicates should detect duplicates`() {
            val validResult = validate {
                field("ids", listOf(1, 2, 3)).noDuplicates()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("ids", listOf(1, 2, 2, 3)).noDuplicates()
            }
            assertTrue(invalidResult.isFailure())
            assertEquals(listOf("cannot have duplicate elements"), invalidResult.errors()["ids"])
        }

        @Test
        fun `allIn should validate all elements against allowed values`() {
            val validResult = validate {
                field("roles", listOf("admin", "user")).allIn("admin", "user", "guest")
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("roles", listOf("admin", "superuser")).allIn("admin", "user", "guest")
            }
            assertTrue(invalidResult.isFailure())
        }

        @Test
        fun `notIn should detect forbidden values`() {
            val result = validate {
                field("tags", listOf("safe", "unsafe")).notIn("unsafe", "banned")
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("contains forbidden values"), result.errors()["tags"])
        }

        @Test
        fun `custom validation should work`() {
            val result = validate {
                field("scores", listOf(85, 90, 78)).custom(
                    { it.all { score -> score >= 80 } },
                    "all scores must be 80 or above"
                )
            }
            assertTrue(result.isFailure())
        }
    }

    @Nested
    @DisplayName("DateTimeValidator Tests")
    inner class DateTimeValidatorTests {

        @Test
        fun `required should fail on null datetime`() {
            val result = validate {
                field("timestamp", null as LocalDateTime?).required()
            }
            assertTrue(result.isFailure())
            assertEquals(listOf("is required"), result.errors()["timestamp"])
        }

        @Test
        fun `isBefore should validate temporal ordering`() {
            val now = LocalDateTime.now()
            val future = now.plusDays(1)

            val result = validate {
                field("deadline", future).isBefore(now)
            }
            assertTrue(result.isFailure())
        }

        @Test
        fun `isAfter should validate temporal ordering`() {
            val now = LocalDateTime.now()
            val past = now.minusDays(1)

            val result = validate {
                field("startDate", past).isAfter(now)
            }
            assertTrue(result.isFailure())
        }

        @Test
        fun `between should validate datetime range`() {
            val start = LocalDateTime.of(2024, 1, 1, 0, 0)
            val end = LocalDateTime.of(2024, 12, 31, 23, 59)
            val date = LocalDateTime.of(2024, 6, 15, 12, 0)

            val validResult = validate {
                field("date", date).between(start, end)
            }
            assertTrue(validResult.isSuccess())
        }

        @Test
        fun `inFuture should validate future dates`() {
            val future = LocalDateTime.now().plusDays(1)
            val past = LocalDateTime.now().minusDays(1)

            val validResult = validate {
                field("appointment", future).inFuture()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("appointment", past).inFuture()
            }
            assertTrue(invalidResult.isFailure())
        }

        @Test
        fun `inPast should validate past dates`() {
            val past = LocalDateTime.now().minusDays(1)
            val future = LocalDateTime.now().plusDays(1)

            val validResult = validate {
                field("birthdate", past).inPast()
            }
            assertTrue(validResult.isSuccess())

            val invalidResult = validate {
                field("birthdate", future).inPast()
            }
            assertTrue(invalidResult.isFailure())
        }

        @Test
        fun `custom validation should work`() {
            val date = LocalDateTime.of(2024, 6, 15, 10, 0)
            val result = validate {
                field("businessHours", date).custom(
                    { it.hour in 9..17 },
                    "must be during business hours (9-17)"
                )
            }
            assertTrue(result.isSuccess())
        }
    }

    @Nested
    @DisplayName("Message Override Tests")
    inner class MessageOverrideTests {

        @Test
        fun `message string should override last error`() {
            val result = validate {
                field("age", 15).min(18).message("You must be 18 or older")
            }
            assertEquals(listOf("You must be 18 or older"), result.errors()["age"])
        }

        @Test
        fun `message function should override last error with value`() {
            val result = validate {
                field("age", 15).min(18).message { "Age $it is too young" }
            }
            assertEquals(listOf("Age 15 is too young"), result.errors()["age"])
        }

        @Test
        fun `message should only override if validation failed`() {
            val result = validate {
                field("age", 25).min(18).message("This should not appear")
            }
            assertTrue(result.isSuccess())
        }

        @Test
        fun `message should work with multiple validations`() {
            val result = validate {
                field("password", "weak")
                    .minSize(8).message("Password is too short")
                    .matches(Regex(".*[A-Z].*")).message("Password needs uppercase letter")
            }
            val errors = result.errors()["password"]!!
            assertEquals(2, errors.size)
            assertTrue(errors.contains("Password is too short"))
            assertTrue(errors.contains("Password needs uppercase letter"))
        }
    }

    @Nested
    @DisplayName("Validation Context and Groups Tests")
    inner class ValidationContextTests {

        @Test
        fun `multiple fields should accumulate errors`() {
            val result = validate {
                field("username", "ab").minSize(3)
                field("email", "invalid").email()
                field("age", 15).min(18)
            }
            assertTrue(result.isFailure())
            assertEquals(3, result.errors().size)
        }

        @Test
        fun `nested groups should create hierarchical field names`() {
            val result = validate {
                group("user") {
                    field("name", "").notBlank()
                    group("address") {
                        field("street", "").notBlank()
                        field("city", "").notBlank()
                    }
                }
            }
            assertTrue(result.isFailure())
            assertTrue(result.errors().containsKey("user.name"))
            assertTrue(result.errors().containsKey("user.address.street"))
            assertTrue(result.errors().containsKey("user.address.city"))
        }

        @Test
        fun `deeply nested groups should work`() {
            val result = validate {
                group("company") {
                    group("department") {
                        group("team") {
                            field("name", "").notBlank()
                        }
                    }
                }
            }
            assertTrue(result.errors().containsKey("company.department.team.name"))
        }

        @Test
        fun `mixed flat and nested validations should work`() {
            val result = validate {
                field("id", null as Int?).required()
                group("profile") {
                    field("bio", "a".repeat(201)).maxSize(200)
                }
            }
            assertTrue(result.errors().containsKey("id"))
            assertTrue(result.errors().containsKey("profile.bio"))
        }
    }

    data class User(
        val username: String?,
        val email: String?,
        val age: Int?,
        val bio: String?,
        val tags: List<String>?
    )

    @Nested
    @DisplayName("Complex Validation Scenarios")
    inner class ComplexScenariosTests {

        @Test
        fun `complete user validation`() {
            val user = User(
                username = "ab",
                email = "invalid-email",
                age = 15,
                bio = "a".repeat(201),
                tags = emptyList()
            )

            val result = validate {
                field("username", user.username).required().minSize(3).alphanumeric()
                field("email", user.email).required().email()
                field("age", user.age).required().between(18, 100)
                field("bio", user.bio).maxSize(200)
                field("tags", user.tags).notEmpty()
            }

            assertTrue(result.isFailure())
            assertTrue(result.errors().containsKey("username"))
            assertTrue(result.errors().containsKey("email"))
            assertTrue(result.errors().containsKey("age"))
            assertTrue(result.errors().containsKey("bio"))
            assertTrue(result.errors().containsKey("tags"))
        }

        @Test
        fun `valid user should pass all validations`() {
            val user = User(
                username = "john_doe",
                email = "john@example.com",
                age = 25,
                bio = "Software developer",
                tags = listOf("developer", "kotlin")
            )

            val result = validate {
                field("username", user.username).required().minSize(3).alphanumeric()
                field("email", user.email).required().email()
                field("age", user.age).required().between(18, 100)
                field("bio", user.bio).maxSize(200)
                field("tags", user.tags).notEmpty()
            }

            assertTrue(result.isSuccess())
        }

        @Test
        fun `chaining multiple validations on same field`() {
            val result = validate {
                field("password", "weak")
                    .required()
                    .minSize(8)
                    .matches(Regex(".*[A-Z].*"))
                    .matches(Regex(".*[0-9].*"))
                    .matches(Regex(".*[!@#$%^&*].*"))
            }

            assertTrue(result.isFailure())
            val errors = result.errors()["password"]!!
            assertTrue(errors.size >= 4) // Multiple validation failures
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    inner class EdgeCasesTests {

        @Test
        fun `empty validation should succeed`() {
            val result = validate { }
            assertTrue(result.isSuccess())
        }

        @Test
        fun `validation with only null optional fields should succeed`() {
            val result = validate {
                field("bio", null as String?).maxSize(200)
                field("age", null as Int?).positive()
                field("tags", null as List<String>?).notEmpty()
            }
            assertTrue(result.isSuccess())
        }

        @Test
        fun `empty string validations`() {
            val result = validate {
                field("name", "").notBlank()
            }
            assertTrue(result.isFailure())
        }

        @Test
        fun `zero value validations`() {
            val result = validate {
                field("count", 0).positive()
            }
            assertTrue(result.isFailure())
        }

        @Test
        fun `boundary value testing for between`() {
            val result = validate {
                field("min", 18).between(18, 65)
                field("max", 65).between(18, 65)
            }
            assertTrue(result.isSuccess())
        }
    }
}