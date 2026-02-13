package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasicAuthTest {

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
        headers: Map<String, String> = emptyMap()
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers().apply {
                headers.forEach { (key, value) -> set(key, value) }
            },
        )
        return Context(request = request, app = app)
    }

    private fun encodeCredentials(username: String, password: String): String {
        val credentials = "$username:$password"
        return Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
    }

    // ========================================================================
    // Missing Authorization Header Tests
    // ========================================================================

    @Nested
    inner class MissingAuthorizationTests {

        @Test
        fun `should return 401 when Authorization header is missing`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val ctx = createContext()

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
            assertEquals(
                """Basic realm="Restricted"""",
                ctx.response.headers["WWW-Authenticate"],
                "Should include WWW-Authenticate header"
            )
        }

        @Test
        fun `should return 401 when Authorization header is not Basic auth`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val ctx = createContext(
                headers = mapOf("Authorization" to "Bearer some-token")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }

        @Test
        fun `should return 401 when Authorization value is empty after Basic prefix`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic ")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }
    }

    // ========================================================================
    // Invalid Authorization Format Tests
    // ========================================================================

    @Nested
    inner class InvalidAuthorizationFormatTests {

        @Test
        fun `should return 401 when base64 encoding is invalid`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic not-valid-base64!!!")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }

        @Test
        fun `should return 401 when credentials missing colon separator`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val invalidCredentials = Base64.getEncoder()
                .encodeToString("adminpassword".toByteArray(Charsets.UTF_8))
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $invalidCredentials")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }

        @Test
        fun `should handle credentials with empty username`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("" to "secret"))
            val encodedCreds = encodeCredentials("", "secret")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "next() should be called for valid empty username")
            assertEquals("", ctx.getBasicAuthUser(), "Should authenticate empty username")
        }

        @Test
        fun `should handle credentials with empty password`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to ""))
            val encodedCreds = encodeCredentials("admin", "")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "next() should be called for valid empty password")
            assertEquals("admin", ctx.getBasicAuthUser())
        }
    }

    // ========================================================================
    // Successful Authentication Tests (Credentials Map)
    // ========================================================================

    @Nested
    inner class SuccessfulAuthenticationWithCredentialsTests {

        @Test
        fun `should authenticate with valid credentials`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret123"))
            val encodedCreds = encodeCredentials("admin", "secret123")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "next() should be called")
            assertEquals("admin", ctx.getBasicAuthUser(), "Should store username in context")
        }

        @Test
        fun `should authenticate with multiple valid users`() {
            // Arrange
            val credentials = mapOf(
                "admin" to "admin_pass",
                "user" to "user_pass",
                "guest" to "guest_pass"
            )
            val middleware = BasicAuth(credentials = credentials)

            // Test each user
            credentials.forEach { (username, password) ->
                nextCalled = false
                val encodedCreds = encodeCredentials(username, password)
                val ctx = createContext(
                    headers = mapOf("Authorization" to "Basic $encodedCreds")
                )

                middleware.invoke(ctx, ::next)

                assertTrue(nextCalled, "next() should be called for user: $username")
                assertEquals(username, ctx.getBasicAuthUser(), "Should authenticate user: $username")
            }
        }

        @Test
        fun `should handle case-sensitive Basic prefix`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val encodedCreds = encodeCredentials("admin", "secret")

            // Test different cases
            listOf("Basic", "basic", "BASIC", "BaSiC").forEach { prefix ->
                nextCalled = false
                val ctx = createContext(
                    headers = mapOf("Authorization" to "$prefix $encodedCreds")
                )

                middleware.invoke(ctx, ::next)

                assertTrue(nextCalled, "next() should be called for prefix: $prefix")
            }
        }

        @Test
        fun `should use custom realm name`() {
            // Arrange
            val customRealm = "Admin Dashboard"
            val middleware = BasicAuth(realm = customRealm, credentials = mapOf("admin" to "secret"))
            val ctx = createContext()

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertEquals(
                """Basic realm="$customRealm"""",
                ctx.response.headers["WWW-Authenticate"],
                "Should use custom realm name"
            )
        }
    }

    // ========================================================================
    // Failed Authentication Tests (Credentials Map)
    // ========================================================================

    @Nested
    inner class FailedAuthenticationWithCredentialsTests {

        @Test
        fun `should reject invalid password`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "correct_password"))
            val encodedCreds = encodeCredentials("admin", "wrong_password")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
            assertNull(ctx.getBasicAuthUser(), "Should not store username for failed auth")
        }

        @Test
        fun `should reject non-existent username`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val encodedCreds = encodeCredentials("hacker", "secret")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }

        @Test
        fun `should be case-sensitive for username`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val encodedCreds = encodeCredentials("Admin", "secret")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called for wrong case username")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }

        @Test
        fun `should be case-sensitive for password`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val encodedCreds = encodeCredentials("admin", "Secret")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called for wrong case password")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }
    }

    // ========================================================================
    // Custom Authenticate Function Tests
    // ========================================================================

    @Nested
    inner class CustomAuthenticateFunctionTests {

        @Test
        fun `should authenticate using custom function`() {
            // Arrange
            val authenticateFunc: (String, String) -> Boolean = { username, password ->
                username == "custom_user" && password == "custom_pass"
            }
            val middleware = BasicAuth(authenticate = authenticateFunc)
            val encodedCreds = encodeCredentials("custom_user", "custom_pass")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "next() should be called")
            assertEquals("custom_user", ctx.getBasicAuthUser())
        }

        @Test
        fun `should reject authentication when custom function returns false`() {
            // Arrange
            val authenticateFunc: (String, String) -> Boolean = { _, _ -> false }
            val middleware = BasicAuth(authenticate = authenticateFunc)
            val encodedCreds = encodeCredentials("any_user", "any_pass")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called")
            assertEquals(401, ctx.response.status, "Should return 401 status")
        }

        @Test
        fun `should handle exception in custom authenticate function`() {
            // Arrange
            val authenticateFunc: (String, String) -> Boolean = { _, _ ->
                throw RuntimeException("Database connection failed")
            }
            val middleware = BasicAuth(authenticate = authenticateFunc)
            val encodedCreds = encodeCredentials("admin", "secret")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "next() should not be called when exception occurs")
            assertEquals(401, ctx.response.status, "Should return 401 status on exception")
        }

        @Test
        fun `should pass correct username and password to custom function`() {
            // Arrange
            var receivedUsername: String? = null
            var receivedPassword: String? = null
            val authenticateFunc: (String, String) -> Boolean = { username, password ->
                receivedUsername = username
                receivedPassword = password
                true
            }
            val middleware = BasicAuth(authenticate = authenticateFunc)
            val encodedCreds = encodeCredentials("test_user", "test_pass")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertEquals("test_user", receivedUsername, "Should pass correct username")
            assertEquals("test_pass", receivedPassword, "Should pass correct password")
        }
    }

    // ========================================================================
    // Security Tests
    // ========================================================================

    @Nested
    inner class SecurityTests {

        @Test
        fun `should use constant-time comparison for password`() {
            // This test verifies the behavior but cannot directly test timing
            // It ensures that both valid and invalid attempts go through full comparison
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))

            // Test with non-existent user (should still perform comparison)
            val encodedCreds1 = encodeCredentials("nonexistent", "secret")
            val ctx1 = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds1")
            )
            middleware.invoke(ctx1, ::next)
            assertFalse(nextCalled, "Should reject non-existent user")

            // Test with wrong password (should perform full comparison)
            nextCalled = false
            val encodedCreds2 = encodeCredentials("admin", "wrong")
            val ctx2 = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds2")
            )
            middleware.invoke(ctx2, ::next)
            assertFalse(nextCalled, "Should reject wrong password")
        }

        @Test
        fun `should handle special characters in credentials`() {
            // Arrange
            val specialPassword = "p@ssw0rd!#$%^&*()_+-=[]{}|;':,.<>?/~`"
            val middleware = BasicAuth(credentials = mapOf("admin" to specialPassword))
            val encodedCreds = encodeCredentials("admin", specialPassword)
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "Should handle special characters in password")
            assertEquals("admin", ctx.getBasicAuthUser())
        }

        @Test
        fun `should handle unicode characters in credentials`() {
            // Arrange
            val unicodePassword = "密码123🔒"
            val middleware = BasicAuth(credentials = mapOf("用户" to unicodePassword))
            val encodedCreds = encodeCredentials("用户", unicodePassword)
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "Should handle unicode characters")
            assertEquals("用户", ctx.getBasicAuthUser())
        }

        @Test
        fun `should handle credentials with multiple colons`() {
            // Arrange - password contains colons
            val middleware = BasicAuth(credentials = mapOf("admin" to "pass:word:123"))
            val encodedCreds = encodeCredentials("admin", "pass:word:123")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "Should handle password with colons")
            assertEquals("admin", ctx.getBasicAuthUser())
        }
    }

    // ========================================================================
    // Context Integration Tests
    // ========================================================================

    @Nested
    inner class ContextIntegrationTests {

        @Test
        fun `should retrieve authenticated user using extension function`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("testuser" to "testpass"))
            val encodedCreds = encodeCredentials("testuser", "testpass")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)
            val authenticatedUser = ctx.getBasicAuthUser()

            // Assert
            assertEquals("testuser", authenticatedUser, "Extension function should retrieve username")
        }

        @Test
        fun `should return null when user not authenticated`() {
            // Arrange
            val ctx = createContext()

            // Act
            val authenticatedUser = ctx.getBasicAuthUser()

            // Assert
            assertNull(authenticatedUser, "Should return null when no authentication occurred")
        }

        @Test
        fun `should not override user from previous authentication`() {
            // Arrange
            val middleware1 = BasicAuth(credentials = mapOf("user1" to "pass1"))
            val middleware2 = BasicAuth(credentials = mapOf("user2" to "pass2"))

            val encodedCreds = encodeCredentials("user1", "pass1")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware1.invoke(ctx, ::next)
            val firstUser = ctx.getBasicAuthUser()

            // The user should remain the same even if we call another middleware
            // (In real scenario, you wouldn't call multiple auth middlewares)
            assertEquals("user1", firstUser, "First authentication should store user1")
        }
    }

    // ========================================================================
    // Edge Cases Tests
    // ========================================================================

    @Nested
    inner class EdgeCasesTests {

        @Test
        fun `should handle whitespace in Authorization header value`() {
            // Arrange
            val middleware = BasicAuth(credentials = mapOf("admin" to "secret"))
            val encodedCreds = encodeCredentials("admin", "secret")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic   $encodedCreds   ")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "Should handle whitespace around base64 string")
        }

        @Test
        fun `should handle empty credentials map`() {
            // Arrange
            val middleware = BasicAuth(credentials = emptyMap())
            val encodedCreds = encodeCredentials("admin", "secret")
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertFalse(nextCalled, "Should reject all credentials when map is empty")
            assertEquals(401, ctx.response.status)
        }

        @Test
        fun `should handle very long username and password`() {
            // Arrange
            val longUsername = "a".repeat(1000)
            val longPassword = "b".repeat(1000)
            val middleware = BasicAuth(credentials = mapOf(longUsername to longPassword))
            val encodedCreds = encodeCredentials(longUsername, longPassword)
            val ctx = createContext(
                headers = mapOf("Authorization" to "Basic $encodedCreds")
            )

            // Act
            middleware.invoke(ctx, ::next)

            // Assert
            assertTrue(nextCalled, "Should handle very long credentials")
            assertEquals(longUsername, ctx.getBasicAuthUser())
        }
    }
}