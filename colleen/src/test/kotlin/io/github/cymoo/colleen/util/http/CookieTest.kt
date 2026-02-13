package io.github.cymoo.colleen.util.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CookieTest {

    @Nested
    inner class ParseTests {

        @Test
        fun `should return empty map when cookie header is null`() {
            // Arrange
            val cookieHeader: String? = null

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return empty map when cookie header is blank`() {
            // Arrange
            val cookieHeader = "   "

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertTrue(result.isEmpty())
        }

        @Test
        fun `should parse single cookie`() {
            // Arrange
            val cookieHeader = "session=abc123"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals(1, result.size)
            assertEquals("abc123", result["session"])
        }

        @Test
        fun `should parse multiple cookies`() {
            // Arrange
            val cookieHeader = "session=abc123; user=john; theme=dark"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals(3, result.size)
            assertEquals("abc123", result["session"])
            assertEquals("john", result["user"])
            assertEquals("dark", result["theme"])
        }

        @Test
        fun `should not decode URL-encoded cookie values`() {
            // Arrange
            val cookieHeader = "message=hello%20world; data=foo%3Dbar"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals("hello%20world", result["message"])
            assertEquals("foo%3Dbar", result["data"])
        }

        @Test
        fun `should not decode URL-encoded cookie names`() {
            // Arrange
            val cookieHeader = "my%2Dkey=value"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals("value", result["my%2Dkey"])
        }

        @Test
        fun `should strip quotes from cookie values`() {
            // Arrange
            val cookieHeader = "session=\"abc123\"; user=\"john doe\""

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals("abc123", result["session"])
            assertEquals("john doe", result["user"])
        }

        @Test
        fun `should handle cookies with spaces around equals sign`() {
            // Arrange
            val cookieHeader = "session = abc123 ; user = john"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals("abc123", result["session"])
            assertEquals("john", result["user"])
        }

        @Test
        fun `should ignore malformed cookie without equals sign`() {
            // Arrange
            val cookieHeader = "session=abc123; malformed; user=john"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals(2, result.size)
            assertEquals("abc123", result["session"])
            assertEquals("john", result["user"])
            assertNull(result["malformed"])
        }

        @Test
        fun `should ignore cookie with empty name`() {
            // Arrange
            val cookieHeader = "=value; session=abc123"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals(1, result.size)
            assertEquals("abc123", result["session"])
        }

        @Test
        fun `should ignore cookie with empty value`() {
            // Arrange
            val cookieHeader = "session=abc123; empty="

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals(2, result.size)
            assertEquals("", result["empty"])
            assertEquals("abc123", result["session"])
        }

        @Test
        fun `should handle cookie with only quotes as value`() {
            // Arrange
            val cookieHeader = "session=\"\""

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals(1, result.size)
            assertEquals("", result["session"])
        }

        @Test
        fun `should not strip single quote`() {
            // Arrange
            val cookieHeader = "session=\"abc"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals("\"abc", result["session"])
        }

        @Test
        fun `should handle duplicate cookie names with last value winning`() {
            // Arrange
            val cookieHeader = "session=first; session=second"

            // Act
            val result = Cookie.parse(cookieHeader)

            // Assert
            assertEquals(1, result.size)
            assertEquals("second", result["session"])
        }
    }

    @Nested
    inner class BuildSetCookieTests {

        @Test
        fun `should build basic session cookie`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertEquals("session=abc123; Path=/", result)
        }

        @Test
        fun `should not URL-encode cookie value`() {
            // Arrange
            val name = "message"
            val value = "hello world"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertTrue(result.startsWith("message=hello world"))
        }

        @Test
        fun `should build persistent cookie with maxAge`() {
            // Arrange
            val name = "session"
            val value = "abc123"
            val maxAge = 3600

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = maxAge,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertTrue(result.contains("Max-Age=3600"))
            assertFalse(result.contains("Expires="))
        }

        @Test
        fun `should build deletion cookie with maxAge zero`() {
            // Arrange
            val name = "session"
            val value = ""
            val maxAge = 0

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = maxAge,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertTrue(result.contains("Max-Age=0"))
            assertTrue(result.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        }

        @Test
        fun `should include path when provided`() {
            // Arrange
            val name = "session"
            val value = "abc123"
            val path = "/api"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = path,
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertTrue(result.contains("Path=/api"))
        }

        @Test
        fun `should omit path when null`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = null,
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertFalse(result.contains("Path="))
        }

        @Test
        fun `should include domain when provided`() {
            // Arrange
            val name = "session"
            val value = "abc123"
            val domain = "example.com"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = domain,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertTrue(result.contains("Domain=example.com"))
        }

        @Test
        fun `should include Secure flag when true`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = true,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertTrue(result.contains("Secure"))
        }

        @Test
        fun `should omit Secure flag when false`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = null
            )

            // Assert
            assertFalse(result.contains("Secure"))
        }

        @Test
        fun `should include HttpOnly flag when true`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = true,
                sameSite = null
            )

            // Assert
            assertTrue(result.contains("HttpOnly"))
        }

        @Test
        fun `should include SameSite=Strict when specified`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = Cookie.SameSite.STRICT
            )

            // Assert
            assertTrue(result.contains("SameSite=Strict"))
        }

        @Test
        fun `should include SameSite=Lax when specified`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = Cookie.SameSite.LAX
            )

            // Assert
            assertTrue(result.contains("SameSite=Lax"))
        }

        @Test
        fun `should build secure cookie with SameSite=None`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = null,
                path = "/",
                domain = null,
                secure = true,
                httpOnly = false,
                sameSite = Cookie.SameSite.NONE
            )

            // Assert
            assertTrue(result.contains("SameSite=None"))
            assertTrue(result.contains("Secure"))
        }

        @Test
        fun `should build complete cookie with all attributes`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = 3600,
                path = "/api",
                domain = "example.com",
                secure = true,
                httpOnly = true,
                sameSite = Cookie.SameSite.STRICT
            )

            // Assert
            assertTrue(result.contains("session=abc123"))
            assertTrue(result.contains("Max-Age=3600"))
            assertTrue(result.contains("Path=/api"))
            assertTrue(result.contains("Domain=example.com"))
            assertTrue(result.contains("Secure"))
            assertTrue(result.contains("HttpOnly"))
            assertTrue(result.contains("SameSite=Strict"))
        }

        @Test
        fun `should throw when cookie name is invalid`() {
            // Arrange
            val name = "invalid name"
            val value = "abc123"

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                Cookie.buildSetCookie(
                    name = name,
                    value = value,
                    maxAge = null,
                    path = "/",
                    domain = null,
                    secure = false,
                    httpOnly = false,
                    sameSite = null
                )
            }
        }

        @Test
        fun `should throw when cookie value contains control characters`() {
            // Arrange
            val name = "session"
            val value = "abc\n123"

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                Cookie.buildSetCookie(
                    name = name,
                    value = value,
                    maxAge = null,
                    path = "/",
                    domain = null,
                    secure = false,
                    httpOnly = false,
                    sameSite = null
                )
            }
        }

        @Test
        fun `should throw when path is invalid`() {
            // Arrange
            val name = "session"
            val value = "abc123"
            val path = "invalid"

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                Cookie.buildSetCookie(
                    name = name,
                    value = value,
                    maxAge = null,
                    path = path,
                    domain = null,
                    secure = false,
                    httpOnly = false,
                    sameSite = null
                )
            }
        }

        @Test
        fun `should throw when domain is invalid`() {
            // Arrange
            val name = "session"
            val value = "abc123"
            val domain = "com"

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                Cookie.buildSetCookie(
                    name = name,
                    value = value,
                    maxAge = null,
                    path = "/",
                    domain = domain,
                    secure = false,
                    httpOnly = false,
                    sameSite = null
                )
            }
        }

        @Test
        fun `should throw when SameSite=None without Secure`() {
            // Arrange
            val name = "session"
            val value = "abc123"

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                Cookie.buildSetCookie(
                    name = name,
                    value = value,
                    maxAge = null,
                    path = "/",
                    domain = null,
                    secure = false,
                    httpOnly = false,
                    sameSite = Cookie.SameSite.NONE
                )
            }
        }

        @Test
        fun `should allow SameSite=None without Secure when deleting cookie`() {
            // Arrange
            val name = "session"
            val value = ""
            val maxAge = 0

            // Act
            val result = Cookie.buildSetCookie(
                name = name,
                value = value,
                maxAge = maxAge,
                path = "/",
                domain = null,
                secure = false,
                httpOnly = false,
                sameSite = Cookie.SameSite.NONE
            )

            // Assert
            assertTrue(result.contains("SameSite=None"))
            assertFalse(result.contains("Secure"))
        }
    }

    @Nested
    inner class ValidateNameTests {

        @Test
        fun `should accept valid alphanumeric name`() {
            // Arrange
            val name = "sessionId123"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateName(name)
            }
        }

        @Test
        fun `should accept name with underscores`() {
            // Arrange
            val name = "session_id"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateName(name)
            }
        }

        @Test
        fun `should accept name with hyphens`() {
            // Arrange
            val name = "session-id"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateName(name)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "  ", "\t"])
        fun `should reject blank names`(name: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validateName(name)
            }
            assertTrue(exception.message!!.contains("cannot be blank"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["session id", "session.id", "session@id", "session=id"])
        fun `should reject names with invalid characters`(name: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validateName(name)
            }
            assertTrue(exception.message!!.contains("Invalid cookie name"))
        }
    }

    @Nested
    inner class ValidateValueTests {

        @Test
        fun `should accept valid value`() {
            // Arrange
            val value = "abc123"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateValue(value)
            }
        }

        @Test
        fun `should accept empty value`() {
            // Arrange
            val value = ""

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateValue(value)
            }
        }

        @Test
        fun `should accept value with special characters`() {
            // Arrange
            val value = "hello world! @#$%"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateValue(value)
            }
        }

        @Test
        fun `should reject value with semicolon`() {
            // Arrange
            val value = "hello;world"

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                Cookie.validateValue(value)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["abc\n123", "abc\r123", "abc\t123", "abc\u0000123"])
        fun `should reject value with control characters`(value: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validateValue(value)
            }
            assertTrue(exception.message!!.contains("control characters"))
        }
    }

    @Nested
    inner class ValidatePathTests {

        @Test
        fun `should accept root path`() {
            // Arrange
            val path = "/"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validatePath(path)
            }
        }

        @Test
        fun `should accept valid nested path`() {
            // Arrange
            val path = "/api/v1"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validatePath(path)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "  "])
        fun `should reject blank path`(path: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validatePath(path)
            }
            assertTrue(exception.message!!.contains("cannot be blank"))
        }

        @Test
        fun `should reject path not starting with slash`() {
            // Arrange
            val path = "api/v1"

            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validatePath(path)
            }
            assertTrue(exception.message!!.contains("must start with '/'"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["/api/../admin", "/../etc", "/api/.."])
        fun `should reject path with traversal`(path: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validatePath(path)
            }
            assertTrue(exception.message!!.contains("cannot contain '..'"))
        }
    }

    @Nested
    inner class ValidateDomainTests {

        @Test
        fun `should accept localhost`() {
            // Arrange
            val domain = "localhost"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateDomain(domain)
            }
        }

        @Test
        fun `should accept localhost case-insensitive`() {
            // Arrange
            val domain = "LOCALHOST"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateDomain(domain)
            }
        }

        @Test
        fun `should accept valid domain`() {
            // Arrange
            val domain = "example.com"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateDomain(domain)
            }
        }

        @Test
        fun `should accept subdomain`() {
            // Arrange
            val domain = "api.example.com"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateDomain(domain)
            }
        }

        @Test
        fun `should accept domain with leading dot`() {
            // Arrange
            val domain = ".example.com"

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateDomain(domain)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "  "])
        fun `should reject blank domain`(domain: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validateDomain(domain)
            }
            assertTrue(exception.message!!.contains("cannot be blank"))
        }

        @ParameterizedTest
        @ValueSource(strings = [".", "..", "..."])
        fun `should reject domain with only dots`(domain: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validateDomain(domain)
            }
            assertTrue(exception.message!!.contains("cannot be only dots"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["com", "org", "net"])
        fun `should reject TLD without subdomain`(domain: String) {
            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validateDomain(domain)
            }
            assertTrue(exception.message!!.contains("must be localhost or contain a dot"))
        }
    }

    @Nested
    inner class ValidateMaxAgeTests {

        @Test
        fun `should accept null maxAge`() {
            // Arrange
            val maxAge: Int? = null

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateMaxAge(maxAge)
            }
        }

        @Test
        fun `should accept zero maxAge`() {
            // Arrange
            val maxAge = 0

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateMaxAge(maxAge)
            }
        }

        @Test
        fun `should accept positive maxAge`() {
            // Arrange
            val maxAge = 3600

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateMaxAge(maxAge)
            }
        }

        @Test
        fun `should not reject negative maxAge`() {
            // Arrange
            val maxAge = -1
            Cookie.validateMaxAge(maxAge)
        }
    }

    @Nested
    inner class ValidateSameSiteSecureTests {

        @Test
        fun `should accept SameSite=None with Secure flag`() {
            // Arrange
            val sameSite = Cookie.SameSite.NONE
            val secure = true
            val maxAge: Int? = null

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateSameSiteSecure(sameSite, secure, maxAge)
            }
        }

        @Test
        fun `should accept SameSite=Strict without Secure flag`() {
            // Arrange
            val sameSite = Cookie.SameSite.STRICT
            val secure = false
            val maxAge: Int? = null

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateSameSiteSecure(sameSite, secure, maxAge)
            }
        }

        @Test
        fun `should accept SameSite=Lax without Secure flag`() {
            // Arrange
            val sameSite = Cookie.SameSite.LAX
            val secure = false
            val maxAge: Int? = null

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateSameSiteSecure(sameSite, secure, maxAge)
            }
        }

        @Test
        fun `should accept null SameSite`() {
            // Arrange
            val sameSite: Cookie.SameSite? = null
            val secure = false
            val maxAge: Int? = null

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateSameSiteSecure(sameSite, secure, maxAge)
            }
        }

        @Test
        fun `should reject SameSite=None without Secure flag`() {
            // Arrange
            val sameSite = Cookie.SameSite.NONE
            val secure = false
            val maxAge: Int? = null

            // Act & Assert
            val exception = assertThrows<IllegalArgumentException> {
                Cookie.validateSameSiteSecure(sameSite, secure, maxAge)
            }
            assertTrue(exception.message!!.contains("SameSite=None requires Secure"))
        }

        @Test
        fun `should allow SameSite=None without Secure when deleting cookie`() {
            // Arrange
            val sameSite = Cookie.SameSite.NONE
            val secure = false
            val maxAge = 0

            // Act & Assert
            assertDoesNotThrow {
                Cookie.validateSameSiteSecure(sameSite, secure, maxAge)
            }
        }
    }

    @Nested
    inner class SameSiteEnumTests {

        @Test
        fun `should have correct value for STRICT`() {
            // Assert
            assertEquals("Strict", Cookie.SameSite.STRICT.value)
        }

        @Test
        fun `should have correct value for LAX`() {
            // Assert
            assertEquals("Lax", Cookie.SameSite.LAX.value)
        }

        @Test
        fun `should have correct value for NONE`() {
            // Assert
            assertEquals("None", Cookie.SameSite.NONE.value)
        }
    }
}