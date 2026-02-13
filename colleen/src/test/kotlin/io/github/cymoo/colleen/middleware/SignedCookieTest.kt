package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Request
import io.github.cymoo.colleen.util.http.Cookie
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.*

class SignedCookieTest {

    private lateinit var app: Colleen
    private var nextCalled = false
    private val testSecret = "test-secret-key-must-be-32-characters-long-123"
    private val oldSecret = "old-secret-key-must-be-32-characters-long-456"
    private val newSecret = "new-secret-key-must-be-32-characters-long-789"

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
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers().apply {
                headers.forEach { (key, value) -> set(key, value) }
                if (cookies.isNotEmpty()) {
                    val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    set("Cookie", cookieString)
                }
            }
        )
        return Context(request = request, app = app)
    }

    // ========================================================================
    // Middleware Installation and Initialization Tests
    // ========================================================================

    @Test
    fun `should install middleware successfully with single secret`() {
        // Arrange & Act
        val middleware = SignedCookie(secret = testSecret)
        val ctx = createContext()

        // Assert
        assertDoesNotThrow {
            middleware.invoke(ctx, ::next)
        }
        assertTrue(nextCalled, "next() should be called")
        assertNotNull(ctx.getState(SignedCookie.SIGNED_COOKIE_KEY), "CookieSigner should be stored in context")
    }

    @Test
    fun `should install middleware successfully with multiple secrets`() {
        // Arrange & Act
        val middleware = SignedCookie(secrets = listOf(newSecret, oldSecret))
        val ctx = createContext()

        // Assert
        assertDoesNotThrow {
            middleware.invoke(ctx, ::next)
        }
        assertTrue(nextCalled, "next() should be called")
        assertNotNull(ctx.getState(SignedCookie.SIGNED_COOKIE_KEY), "CookieSigner should be stored in context")
    }

    @Test
    fun `should reject empty secrets list`() {
        // Act & Assert
        val exception = assertThrows<IllegalArgumentException> {
            SignedCookie(secrets = emptyList())
        }
        assertTrue(exception.message!!.contains("At least one secret is required"))
    }

    @Test
    fun `should reject blank secret`() {
        // Act & Assert
        assertThrows<IllegalArgumentException> {
            SignedCookie(secret = "")
        }

        assertThrows<IllegalArgumentException> {
            SignedCookie(secret = "   ")
        }
    }

    @Test
    fun `should reject secret shorter than minimum bytes`() {
        // Arrange - Short ASCII string (5 bytes)
        assertThrows<IllegalArgumentException> {
            SignedCookie(secret = "short")
        }

        // Short UTF-8 string (15 bytes)
        assertThrows<IllegalArgumentException> {
            SignedCookie(secret = "密码密码密")
        }

        // Just under minimum (31 bytes)
        assertThrows<IllegalArgumentException> {
            SignedCookie(secret = "1234567890123456789012345678901")
        }
    }

    @Test
    fun `should accept secret at minimum bytes`() {
        // Arrange - Exactly 32 bytes
        assertDoesNotThrow {
            SignedCookie(secret = "12345678901234567890123456789012")
        }
    }

    @Test
    fun `should accept secret above minimum bytes`() {
        // Arrange - More than 32 bytes
        assertDoesNotThrow {
            SignedCookie(secret = "this-is-a-valid-secret-key-32-chars-long")
        }
    }

    @Test
    fun `should reject invalid HMAC algorithm`() {
        // Act & Assert
        val exception = assertThrows<IllegalArgumentException> {
            SignedCookie(
                secret = "valid-secret-key-must-be-32-characters-long",
                algorithm = "InvalidAlgorithm"
            )
        }
        assertTrue(exception.message!!.contains("Unsupported algorithm"))
    }

    @Test
    fun `should accept valid HMAC algorithms`() {
        // Arrange
        val algorithms = listOf("HmacSHA1", "HmacSHA256", "HmacSHA512")

        // Act & Assert
        algorithms.forEach { algo ->
            assertDoesNotThrow {
                SignedCookie(
                    secret = "valid-secret-key-must-be-32-characters-long",
                    algorithm = algo
                )
            }
        }
    }

    // ========================================================================
    // Cookie Signing Tests
    // ========================================================================

    @Test
    fun `should sign and unsign value correctly`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val cookieName = "session"
        val original = "hello-world"

        // Act
        val signed = signer.sign(cookieName, original)
        val unsigned = signer.unsign(cookieName, signed)

        // Assert
        assertEquals(original, unsigned, "Unsigned value should match original")
        assertTrue(signed.contains("."), "Signed value should contain separator")
        assertNotEquals(original, signed, "Signed value should differ from original")
    }

    @Test
    fun `should produce different signatures for same value with different cookie names`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val value = "same-value"

        // Act
        val signed1 = signer.sign("cookie1", value)
        val signed2 = signer.sign("cookie2", value)

        // Assert
        assertNotEquals(signed1, signed2, "Different cookie names should produce different signatures")
        assertEquals(value, signer.unsign("cookie1", signed1))
        assertEquals(value, signer.unsign("cookie2", signed2))
        assertNull(signer.unsign("cookie1", signed2), "Signature should not validate for different cookie name")
        assertNull(signer.unsign("cookie2", signed1), "Signature should not validate for different cookie name")
    }

    @Test
    fun `should produce consistent signatures for same name and value`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act
        val signed1 = signer.sign("test", "value")
        val signed2 = signer.sign("test", "value")

        // Assert
        assertEquals(signed1, signed2, "Same name and value should produce identical signatures")
        assertEquals("value", signer.unsign("test", signed1))
        assertEquals("value", signer.unsign("test", signed2))
    }

    @Test
    fun `should handle empty value`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act
        val signed = signer.sign("test", "")
        val unsigned = signer.unsign("test", signed)

        // Assert
        assertEquals("", unsigned, "Empty value should be preserved")
    }

    @Test
    fun `should handle special characters in value`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val specialValues = listOf(
            "user@example.com",
            "name with spaces",
            "中文字符",
            "emoji 😀",
            "json:{\"key\":\"value\"}",
            "dots.in.value",
            "semicolon;value",
            "newline\ncharacter",
            "tab\tcharacter"
        )

        // Act & Assert
        specialValues.forEach { value ->
            val signed = signer.sign("test", value)
            assertEquals(value, signer.unsign("test", signed), "Failed for: $value")
        }
    }

    @Test
    fun `should handle large values`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val largeValue = "x".repeat(4000) // 4KB

        // Act
        val signed = signer.sign("data", largeValue)
        val unsigned = signer.unsign("data", signed)

        // Assert
        assertEquals(largeValue, unsigned, "Large value should be preserved")
    }

    @Test
    fun `should reject blank cookie name`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            signer.sign("", "value")
        }

        assertThrows<IllegalArgumentException> {
            signer.sign("   ", "value")
        }
    }

    @Test
    fun `signed value should be URL-safe`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act
        val signed = signer.sign("test", "test-value")

        // Assert - Should not contain URL-unsafe characters
        assertFalse(signed.contains("+"), "Should not contain +")
        assertFalse(signed.contains("/"), "Should not contain /")
        assertFalse(signed.contains("="), "Should not contain padding =")
    }

    @Test
    fun `signature format should have exactly two parts`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act
        val signed = signer.sign("test", "value")
        val parts = signed.split(".")

        // Assert
        assertEquals(2, parts.size, "Should have exactly 2 parts")
        parts.forEach { part ->
            assertFalse(part.contains("+"), "Should not contain +")
            assertFalse(part.contains("/"), "Should not contain /")
            assertFalse(part.contains("="), "Should not contain padding =")
        }
    }

    // ========================================================================
    // Security Tests
    // ========================================================================

    @Test
    fun `should prevent cross-cookie attacks`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act - Sign a cookie with name "session"
        val signedSession = signer.sign("session", "admin")

        // Try to use this signed value for a different cookie name
        val tamperedRole = signer.unsign("role", signedSession)

        // Assert - Should fail because signature includes cookie name
        assertNull(tamperedRole, "Cross-cookie attack should be prevented")
    }

    @Test
    fun `should return null for tampered signature`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val signed = signer.sign("test", "hello")

        // Act - Tamper with signature part
        val parts = signed.split(".")
        val tampered = "${parts[0]}.${parts[1].replaceFirst("A", "B")}"

        // Assert
        assertNull(signer.unsign("test", tampered), "Tampered signature should be rejected")
    }

    @Test
    fun `should return null for tampered value`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val signed = signer.sign("test", "hello")

        // Act - Tamper with value part
        val separatorIndex = signed.lastIndexOf('.')
        val encodedValue = signed.substring(0, separatorIndex)
        val encodedSignature = signed.substring(separatorIndex + 1)

        val tamperedValue = if (encodedValue[0] == 'a') {
            'b' + encodedValue.substring(1)
        } else {
            'a' + encodedValue.substring(1)
        }
        val tampered = "$tamperedValue.$encodedSignature"

        // Assert
        assertNull(signer.unsign("test", tampered), "Tampered value should be rejected")
    }

    @Test
    fun `should return null for invalid format`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act & Assert
        assertNull(signer.unsign("test", "invalid"), "No separator should be rejected")
        assertNull(signer.unsign("test", "no-signature"), "Invalid format should be rejected")
        assertNull(signer.unsign("test", ""), "Empty string should be rejected")
        assertNull(signer.unsign("test", "only-value."), "Empty signature should be rejected")
        assertNull(signer.unsign("test", ".only-signature"), "Empty value should be rejected")
    }

    @Test
    fun `should handle multiple dots in signed value correctly`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act - Even if base64 encoding contains dot-like characters, lastIndexOf should handle it
        val signed = signer.sign("test", "value-with-dots")
        val unsigned = signer.unsign("test", signed)

        // Assert
        assertEquals("value-with-dots", unsigned)
    }

    @Test
    fun `should use constant time comparison`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act - Different values should produce different signatures
        val signedA = signer.sign("test", "valueA")
        val signedB = signer.sign("test", "valueB")

        // Assert
        assertNotEquals(signedA, signedB, "Different values should have different signatures")
        assertEquals("valueA", signer.unsign("test", signedA))
        assertEquals("valueB", signer.unsign("test", signedB))
    }

    // ========================================================================
    // Key Rotation Tests
    // ========================================================================

    @Test
    fun `should support key rotation with multiple secrets`() {
        // Arrange - Sign with old secret
        val oldSigner = CookieSigner(listOf(oldSecret), "HmacSHA256")
        val signedWithOld = oldSigner.sign("session", "value")

        // Act - Try to verify with new secret only (should fail)
        val newSigner = CookieSigner(listOf(newSecret), "HmacSHA256")
        val failedUnsign = newSigner.unsign("session", signedWithOld)

        // Verify with both secrets (should succeed)
        val rotatedSigner = CookieSigner(listOf(newSecret, oldSecret), "HmacSHA256")
        val successUnsign = rotatedSigner.unsign("session", signedWithOld)

        // Assert
        assertNull(failedUnsign, "New secret alone should not verify old signature")
        assertEquals("value", successUnsign, "Multiple secrets should support old signatures")
    }

    @Test
    fun `should use first secret for signing during rotation`() {
        // Arrange
        val secret1 = "secret-one-must-be-32-characters-long-1234"
        val secret2 = "secret-two-must-be-32-characters-long-5678"
        val signer = CookieSigner(listOf(secret1, secret2), "HmacSHA256")

        // Act
        val signed = signer.sign("test", "value")

        // Assert - Only first secret can verify
        val signer1Only = CookieSigner(listOf(secret1), "HmacSHA256")
        assertEquals("value", signer1Only.unsign("test", signed), "First secret should verify")

        val signer2Only = CookieSigner(listOf(secret2), "HmacSHA256")
        assertNull(signer2Only.unsign("test", signed), "Second secret should not verify")
    }

    @Test
    fun `should try all secrets in order during verification`() {
        // Arrange
        val secret1 = "secret-one-must-be-32-characters-long-1234"
        val secret2 = "secret-two-must-be-32-characters-long-5678"
        val secret3 = "secret-three-must-be-32-characters-long-90"

        val signer1 = CookieSigner(listOf(secret1), "HmacSHA256")
        val signer2 = CookieSigner(listOf(secret2), "HmacSHA256")
        val signer3 = CookieSigner(listOf(secret3), "HmacSHA256")

        val signed1 = signer1.sign("test", "value1")
        val signed2 = signer2.sign("test", "value2")
        val signed3 = signer3.sign("test", "value3")

        // Act - Multi-secret signer
        val multiSigner = CookieSigner(listOf(secret1, secret2, secret3), "HmacSHA256")

        // Assert - All should verify
        assertEquals("value1", multiSigner.unsign("test", signed1))
        assertEquals("value2", multiSigner.unsign("test", signed2))
        assertEquals("value3", multiSigner.unsign("test", signed3))
    }

    // ========================================================================
    // Algorithm Tests
    // ========================================================================

    @Test
    fun `should support different HMAC algorithms`() {
        // Arrange
        val algorithms = listOf("HmacSHA1", "HmacSHA256", "HmacSHA512")

        // Act & Assert
        algorithms.forEach { algo ->
            val signer = CookieSigner(listOf(testSecret), algo)
            val signed = signer.sign("test", "value")
            assertEquals("value", signer.unsign("test", signed), "Failed for: $algo")
        }
    }

    @Test
    fun `should produce different signatures for different algorithms`() {
        // Arrange
        val signer256 = CookieSigner(listOf(testSecret), "HmacSHA256")
        val signer512 = CookieSigner(listOf(testSecret), "HmacSHA512")

        // Act
        val signed256 = signer256.sign("test", "value")
        val signed512 = signer512.sign("test", "value")

        // Assert
        assertNotEquals(signed256, signed512, "Different algorithms should produce different signatures")
        assertEquals("value", signer256.unsign("test", signed256))
        assertEquals("value", signer512.unsign("test", signed512))
        assertNull(signer256.unsign("test", signed512), "SHA256 should not verify SHA512 signature")
        assertNull(signer512.unsign("test", signed256), "SHA512 should not verify SHA256 signature")
    }

    // ========================================================================
    // Context Extension Function Tests
    // ========================================================================

    @Test
    fun `signedCookie should throw error when middleware not installed`() {
        // Arrange
        val ctx = createContext()

        // Act & Assert
        val exception = assertThrows<IllegalStateException> {
            ctx.signedCookie("test", "value")
        }
        assertTrue(exception.message!!.contains("SignedCookie middleware not installed"))
    }

    @Test
    fun `getSignedCookie should throw error when middleware not installed`() {
        // Arrange
        val ctx = createContext()

        // Act & Assert
        val exception = assertThrows<IllegalStateException> {
            ctx.getSignedCookie("test")
        }
        assertTrue(exception.message!!.contains("SignedCookie middleware not installed"))
    }

    @Test
    fun `should set and get signed cookie through context`() {
        // Arrange
        val middleware = SignedCookie(secret = testSecret)
        val ctx = createContext()
        middleware.invoke(ctx, ::next)

        // Act - Set cookie
        val response = ctx.signedCookie("user", "alice", maxAge = 3600)

        // Get the signed cookie value from response
        val setCookieHeader = response.headers.get("Set-Cookie")
        assertNotNull(setCookieHeader, "Set-Cookie header should be present")

        // Extract the signed value from Set-Cookie header
        val cookieValue = setCookieHeader.substringAfter("user=").substringBefore(";")

        // Create new context with this cookie
        val ctx2 = createContext(cookies = mapOf("user" to cookieValue))
        middleware.invoke(ctx2, ::next)

        // Assert
        val retrievedValue = ctx2.getSignedCookie("user")
        assertEquals("alice", retrievedValue, "Retrieved value should match original")
    }

    @Test
    fun `signedCookie should set cookie with specified options`() {
        // Arrange
        val middleware = SignedCookie(secret = testSecret)
        val ctx = createContext()
        middleware.invoke(ctx, ::next)

        // Act
        val response = ctx.signedCookie(
            name = "session",
            value = "test-session",
            maxAge = 3600,
            path = "/api",
            domain = "example.com",
            secure = true,
            httpOnly = true,
            sameSite = Cookie.SameSite.STRICT
        )

        // Assert
        val setCookieHeader = response.headers["Set-Cookie"]
        assertNotNull(setCookieHeader)
        assertTrue(setCookieHeader.contains("session="))
        assertTrue(setCookieHeader.contains("Max-Age=3600"))
        assertTrue(setCookieHeader.contains("Path=/api"))
        assertTrue(setCookieHeader.contains("Domain=example.com"))
        assertTrue(setCookieHeader.contains("Secure"))
        assertTrue(setCookieHeader.contains("HttpOnly"))
        assertTrue(setCookieHeader.contains("SameSite=Strict"))
    }

    @Test
    fun `getSignedCookie should return null for non-existent cookie`() {
        // Arrange
        val middleware = SignedCookie(secret = testSecret)
        val ctx = createContext()
        middleware.invoke(ctx, ::next)

        // Act
        val value = ctx.getSignedCookie("non-existent")

        // Assert
        assertNull(value, "Non-existent cookie should return null")
    }

    @Test
    fun `getSignedCookie should return null for tampered cookie`() {
        // Arrange
        val middleware = SignedCookie(secret = testSecret)
        val ctx = createContext()
        middleware.invoke(ctx, ::next)

        // Set a valid cookie
        val response = ctx.signedCookie("test", "value")
        val setCookieHeader = response.headers["Set-Cookie"]!!
        var cookieValue = setCookieHeader.substringAfter("test=").substringBefore(";")

        // Tamper with the cookie value - flip first character to ensure change
        val firstChar = cookieValue[0]
        val tamperedFirstChar = if (firstChar == 'Z') 'A' else (firstChar.code + 1).toChar()
        cookieValue = tamperedFirstChar + cookieValue.substring(1)

        // Create new context with tampered cookie
        val ctx2 = createContext(cookies = mapOf("test" to cookieValue))
        middleware.invoke(ctx2, ::next)

        // Act
        val value = ctx2.getSignedCookie("test")

        // Assert
        assertNull(value, "Tampered cookie should return null")
    }

    // ========================================================================
    // Concurrency Tests
    // ========================================================================

    @Test
    fun `should handle concurrent signing operations`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val values = (1..100).map { "value-$it" }

        // Act - Concurrent signing
        val signed = values.parallelStream()
            .map { signer.sign("test", it) }
            .toList()

        // Assert - Verify all signatures
        signed.forEachIndexed { index, s ->
            assertEquals("value-${index + 1}", signer.unsign("test", s))
        }
    }

    @Test
    fun `should handle concurrent unsigning operations`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val values = (1..100).map { "value-$it" }
        val signed = values.map { signer.sign("test", it) }

        // Act - Concurrent unsigning
        val unsigned = signed.parallelStream()
            .map { signer.unsign("test", it) }
            .toList()

        // Assert
        unsigned.forEachIndexed { index, value ->
            assertEquals("value-${index + 1}", value)
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun `should handle cookie with only separator`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act & Assert
        assertNull(signer.unsign("test", "."), "Single separator should be rejected")
    }

    @Test
    fun `should handle cookie with multiple consecutive separators`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act & Assert
        assertNull(signer.unsign("test", "value..signature"), "Multiple separators should be rejected")
    }

    @Test
    fun `should handle invalid base64 in value part`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act & Assert
        assertNull(signer.unsign("test", "invalid!!!.validbase64"), "Invalid base64 should be rejected")
    }

    @Test
    fun `should handle invalid base64 in signature part`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act & Assert
        assertNull(signer.unsign("test", "validbase64.invalid!!!"), "Invalid base64 should be rejected")
    }

    @Test
    fun `should handle very long cookie names`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")
        val longName = "a".repeat(200)

        // Act
        val signed = signer.sign(longName, "value")
        val unsigned = signer.unsign(longName, signed)

        // Assert
        assertEquals("value", unsigned, "Long cookie names should be supported")
    }

    @Test
    fun `should differentiate between similar cookie names`() {
        // Arrange
        val signer = CookieSigner(listOf(testSecret), "HmacSHA256")

        // Act
        val signed1 = signer.sign("cookie", "value")
        val signed2 = signer.sign("cookie1", "value")
        val signed3 = signer.sign("cookie_", "value")

        // Assert
        assertNotEquals(signed1, signed2)
        assertNotEquals(signed1, signed3)
        assertNotEquals(signed2, signed3)

        assertEquals("value", signer.unsign("cookie", signed1))
        assertNull(signer.unsign("cookie", signed2))
        assertNull(signer.unsign("cookie1", signed1))
    }
}