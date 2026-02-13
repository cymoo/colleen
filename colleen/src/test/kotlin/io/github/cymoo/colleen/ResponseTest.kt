package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.test.assertContains

class ResponseTest {
    @Test
    fun `test default response values`() {
        val response = Response()
        assertEquals(200, response.status)
        assertTrue(response.headers.isEmpty())
        assertTrue(response.body is ResponseBody.Unset)
        assertFalse(response.isBodySet)
    }

    @Test
    fun `test status method`() {
        val response = Response()
        val result = response.status(404)

        assertEquals(404, response.status)
        assertSame(response, result) // Test fluent interface
    }

    @Test
    fun `test header getter and setter`() {
        val response = Response()

        assertNull(response.header("Content-Type"))

        response.header("Content-Type", "application/json")
        assertEquals("application/json", response.header("Content-Type"))
    }

    @Test
    fun `test empty body`() {
        val response = Response()
        response.empty()

        assertTrue(response.body is ResponseBody.Empty)
        assertTrue(response.isBodySet)
    }

    @Test
    fun `test text body with default content type`() {
        val response = Response()
        response.text("Hello World")

        assertTrue(response.body is ResponseBody.Text)
        assertEquals("Hello World", (response.body as ResponseBody.Text).value)
        assertEquals("text/plain; charset=utf-8", response.header("Content-Type"))
    }

    @Test
    fun `test text body with custom content type`() {
        val response = Response()
        response.text("Custom content", "text/custom; charset=utf-8")

        assertEquals("text/custom; charset=utf-8", response.header("Content-Type"))
    }

    @Test
    fun `test html body`() {
        val response = Response()
        response.html("<h1>Title</h1>")

        assertTrue(response.body is ResponseBody.Text)
        assertEquals("<h1>Title</h1>", (response.body as ResponseBody.Text).value)
        assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
    }

    @Test
    fun `test json body with object`() {
        val response = Response()
        val data = mapOf("key" to "value")
        response.json(data)

        assertTrue(response.body is ResponseBody.Json)
        val body = (response.body as ResponseBody.Json).value as Map<*, *>
        assertEquals("value", body["key"])
        assertEquals("application/json; charset=utf-8", response.header("Content-Type"))
    }

    @Test
    fun `test json body with null`() {
        val response = Response()
        response.json(null)

        assertEquals((response.body as ResponseBody.Json).value, null)
    }

    @Test
    fun `test bytes body with default content type`() {
        val response = Response()
        val data = byteArrayOf(1, 2, 3)
        response.bytes(data)

        assertTrue(response.body is ResponseBody.Bytes)
        assertArrayEquals(data, (response.body as ResponseBody.Bytes).value)
        assertEquals("application/octet-stream", response.header("Content-Type"))
    }

    @Test
    fun `test bytes body with custom content type`() {
        val response = Response()
        val data = byteArrayOf(1, 2, 3)
        response.bytes(data, "image/png")

        assertEquals("image/png", response.header("Content-Type"))
    }

    @Test
    fun `test stream body`() {
        val response = Response()
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        response.stream(stream)

        assertTrue(response.body is ResponseBody.Stream)
        assertSame(stream, (response.body as ResponseBody.Stream).input)
        assertEquals("application/octet-stream", response.header("Content-Type"))
    }

    @Test
    fun `test sse body sets correct headers`() {
        val response = Response()
        response.sse { }

        assertTrue(response.body is ResponseBody.Sse)
        assertEquals("text/event-stream; charset=utf-8", response.header("Content-Type"))
        assertEquals("no-cache", response.header("Cache-Control"))
        assertEquals("keep-alive", response.header("Connection"))
        assertEquals("no", response.header("X-Accel-Buffering"))
    }

    @Test
    fun `test redirect with default status code`() {
        val response = Response()
        response.redirect("/new-location")

        assertEquals(302, response.status)
        assertEquals("/new-location", response.header("Location"))
        assertTrue(response.body is ResponseBody.Empty)
    }

    @Test
    fun `test redirect with custom status code`() {
        val response = Response()
        response.redirect("/permanent", 301)

        assertEquals(301, response.status)
        assertEquals("/permanent", response.header("Location"))
    }

    @Test
    fun `test cookie validation - blank name`() {
        val response = Response()

        val exception = assertThrows<IllegalArgumentException> {
            response.cookie("", "value")
        }
        assertContains(exception.message!!, "Cookie name cannot be blank")
    }

    @Test
    fun `test cookie validation - invalid characters`() {
        val response = Response()

        val exception = assertThrows<IllegalArgumentException> {
            response.cookie("invalid name", "value")
        }
        assertContains(exception.message!!, "Invalid cookie name")
    }


    @Test
    fun `test applyHandlerResult with Unit and body set`() {
        val response = Response()
        response.text("already set")

        response.applyHandlerResult(Unit)

        // Body should remain unchanged
        assertTrue(response.body is ResponseBody.Text)
    }

    @Test
    fun `test applyHandlerResult with Unit and no body`() {
        val response = Response()

        response.applyHandlerResult(Unit)

        assertTrue(response.body is ResponseBody.Empty)
    }

    @Test
    fun `test applyHandlerResult with null`() {
        val response = Response()

        assertThrows<IllegalStateException> {
            response.applyHandlerResult(null)
        }
    }

    @Test
    fun `test applyHandlerResult with Int sets status`() {
        val response = Response()

        response.applyHandlerResult(404)

        assertEquals(404, response.status)
        assertTrue(response.body is ResponseBody.Empty)
    }

    @Test
    fun `test applyHandlerResult with String`() {
        val response = Response()

        response.applyHandlerResult("Hello")

        assertTrue(response.body is ResponseBody.Text)
        assertEquals("Hello", (response.body as ResponseBody.Text).value)
        assertEquals("text/plain; charset=utf-8", response.header("Content-Type"))
    }

    @Test
    fun `test applyHandlerResult with String when content type exists`() {
        val response = Response()
        response.header("Content-Type", "custom/type")

        response.applyHandlerResult("Hello")

        assertTrue(response.body is ResponseBody.Text)
        assertEquals("custom/type", response.header("Content-Type"))
    }

    @Test
    fun `test applyHandlerResult with List`() {
        val response = Response()
        val list = listOf(1, 2, 3)

        response.applyHandlerResult(list)

        assertTrue(response.body is ResponseBody.Json)
    }

    @Test
    fun `test applyHandlerResult with ByteArray`() {
        val response = Response()
        val bytes = byteArrayOf(1, 2, 3)

        response.applyHandlerResult(bytes)

        assertTrue(response.body is ResponseBody.Bytes)
        assertArrayEquals(bytes, (response.body as ResponseBody.Bytes).value)
    }

    @Test
    fun `test applyHandlerResult with InputStream`() {
        val response = Response()
        val stream = ByteArrayInputStream(byteArrayOf())

        response.applyHandlerResult(stream)

        assertTrue(response.body is ResponseBody.Stream)
        assertSame(stream, (response.body as ResponseBody.Stream).input)
    }

    @Test
    fun `test applyHandlerResult with Result object`() {
        val response = Response()
        val result = Result.ok("success").header("X-Custom", "value")

        response.applyHandlerResult(result)

        assertEquals(200, response.status)
        assertEquals("value", response.header("X-Custom"))
        assertTrue(response.body is ResponseBody.Text)
    }

    @Test
    fun `test applyHandlerResult with custom object defaults to JSON`() {
        val response = Response()

        data class CustomObject(val name: String)

        val obj = CustomObject("test")

        response.applyHandlerResult(obj)
        assertTrue(response.body is ResponseBody.Json)
    }

    @Test
    fun `test handleSseResponse async execution`() {
        val resp = Response()
        resp.sse { emitter ->
            emitter.send("test")
        }
        // This would need proper mocking of servlet components
        // Simplified test to verify the method exists and basic structure
        assertNotNull(resp.body)
        assertTrue(resp.body is ResponseBody.Sse)
    }

    @Test
    fun `test Result ok`() {
        val result = Result.ok("success")

        assertEquals(200, result.status)
        assertEquals("success", result.body)
    }

    @Test
    fun `test Result created`() {
        val result = Result.created("resource")

        assertEquals(201, result.status)
        assertEquals("resource", result.body)
    }

    @Test
    fun `test Result noContent`() {
        val result = Result.noContent()

        assertEquals(204, result.status)
        assertEquals(Unit, result.body)
    }

    @Test
    fun `test Result redirect temporary`() {
        val result = Result.redirect("/new-url")

        assertEquals(302, result.status)
        assertEquals("/new-url", result.headers["Location"])
    }

    @Test
    fun `test Result redirect permanent`() {
        val result = Result.redirect("/new-url", permanent = true)

        assertEquals(301, result.status)
    }

    @Test
    fun `test Result badRequest`() {
        val result = Result.badRequest("error")

        assertEquals(400, result.status)
        assertEquals("error", result.body)
    }

    @Test
    fun `test Result unauthorized`() {
        val result = Result.unauthorized("auth required")

        assertEquals(401, result.status)
    }

    @Test
    fun `test Result forbidden`() {
        val result = Result.forbidden("no access")

        assertEquals(403, result.status)
    }

    @Test
    fun `test Result notFound`() {
        val result = Result.notFound("not found")

        assertEquals(404, result.status)
    }

    @Test
    fun `test Result custom status`() {
        val result = Result.of(418, "I'm a teapot")

        assertEquals(418, result.status)
        assertEquals("I'm a teapot", result.body)
    }

    @Test
    fun `test Result withHeader`() {
        val result = Result.ok("data")
            .header("X-Custom", "value1")
            .header("X-Custom", "value2")

        assertEquals("value1", result.headers["X-Custom"])
    }

    @Test
    fun `test fluent interface chaining`() {
        val response = Response()
            .status(201)
            .header("X-Custom", "value")
            .cookie("session", "abc123")

        assertEquals(201, response.status)
        assertEquals("value", response.header("X-Custom"))
    }

    class ResponseCookieTest {

        @Test
        fun `test basic cookie with all parameters`() {
            val response = Response()
            response.cookie(
                name = "session_id",
                value = "abc123",
                maxAge = 3600,
                path = "/",
                domain = "example.com",
                secure = true,
                httpOnly = true,
                sameSite = Cookie.SameSite.STRICT
            )

            val setCookieHeaders = response.headers.getAll("Set-Cookie")
            assertEquals(1, setCookieHeaders.size)

            val cookieHeader = setCookieHeaders[0]
            assertTrue(cookieHeader.contains("session_id=abc123"))
            assertTrue(cookieHeader.contains("Max-Age=3600"))
            assertTrue(cookieHeader.contains("Path=/"))
            assertTrue(cookieHeader.contains("Domain=example.com"))
            assertTrue(cookieHeader.contains("Secure"))
            assertTrue(cookieHeader.contains("HttpOnly"))
            assertTrue(cookieHeader.contains("SameSite=Strict"))
        }

        @Test
        fun `test cookie with minimal parameters`() {
            val response = Response()
            response.cookie(name = "token", value = "xyz789")

            val setCookieHeaders = response.headers.getAll("Set-Cookie")
            assertEquals(1, setCookieHeaders.size)

            val cookieHeader = setCookieHeaders[0]
            assertTrue(cookieHeader.contains("token=xyz789"))
            assertTrue(cookieHeader.contains("SameSite=Lax")) // Default SameSite
            assertFalse(cookieHeader.contains("Secure"))
            assertFalse(cookieHeader.contains("HttpOnly"))
        }

        @Test
        fun `test cookie returns fluent interface`() {
            val response = Response()
            val result = response.cookie(name = "test", value = "value")

            assertSame(response, result)
        }

        @Test
        fun `test multiple cookies can be set`() {
            val response = Response()
            response.cookie(name = "cookie1", value = "value1")
            response.cookie(name = "cookie2", value = "value2")
            response.cookie(name = "cookie3", value = "value3")

            val setCookieHeaders = response.headers.getAll("Set-Cookie")
            assertEquals(3, setCookieHeaders.size)
            assertTrue(setCookieHeaders.any { it.contains("cookie1=value1") })
            assertTrue(setCookieHeaders.any { it.contains("cookie2=value2") })
            assertTrue(setCookieHeaders.any { it.contains("cookie3=value3") })
        }

        @Test
        fun `test cookie name validation - blank name`() {
            val response = Response()

            val exception = assertThrows<IllegalArgumentException> {
                response.cookie(name = "", value = "value")
            }
            assertTrue(exception.message!!.contains("Cookie name cannot be blank"))
        }

        @Test
        fun `test cookie name validation - invalid characters`() {
            val response = Response()

            val exception = assertThrows<IllegalArgumentException> {
                response.cookie(name = "invalid name", value = "value")
            }
            assertTrue(exception.message!!.contains("Invalid cookie name"))

            assertThrows<IllegalArgumentException> {
                response.cookie(name = "cookie@name", value = "value")
            }

            assertThrows<IllegalArgumentException> {
                response.cookie(name = "cookie=name", value = "value")
            }
        }

        @Test
        fun `test cookie name validation - valid names`() {
            val response = Response()

            // These should not throw
            response.cookie(name = "simple", value = "value")
            response.cookie(name = "with_underscore", value = "value")
            response.cookie(name = "with-hyphen", value = "value")
            response.cookie(name = "MixedCase123", value = "value")

            assertEquals(4, response.headers.getAll("Set-Cookie").size)
        }

        @Test
        fun `test cookie value validation - invalid characters`() {
            val response = Response()

            // Semicolon breaks cookie syntax
            val exception1 = assertThrows<IllegalArgumentException> {
                response.cookie(name = "test", value = "value;invalid")
            }
            assertTrue(exception1.message!!.contains("Cookie value must not contain ';'"))

            // Newline breaks cookie syntax
            val exception2 = assertThrows<IllegalArgumentException> {
                response.cookie(name = "test", value = "value\ninvalid")
            }
            assertTrue(exception2.message!!.contains("Cookie value must not contain control characters"))

            // Carriage return breaks cookie syntax
            val exception3 = assertThrows<IllegalArgumentException> {
                response.cookie(name = "test", value = "value\rinvalid")
            }
            assertTrue(exception3.message!!.contains("Cookie value must not contain control characters"))
        }

        @Test
        fun `test cookie value validation - valid values`() {
            val response = Response()

            // These should not throw
            response.cookie(name = "test1", value = "simple123")
            response.cookie(name = "test2", value = "with-special!@#$%^&*()")
            response.cookie(name = "test3", value = "with:colon")
            response.cookie(name = "test4", value = "with=equals")
            response.cookie(name = "test5", value = "")

            assertEquals(5, response.headers.getAll("Set-Cookie").size)
        }

        @Test
        fun `test SameSite None requires Secure flag`() {
            val response = Response()

            val exception = assertThrows<IllegalArgumentException> {
                response.cookie(
                    name = "test",
                    value = "value",
                    sameSite = Cookie.SameSite.NONE,
                    secure = false
                )
            }
            assertTrue(exception.message!!.contains("SameSite=None requires Secure"))
        }

        @Test
        fun `test SameSite None with Secure flag works`() {
            val response = Response()

            response.cookie(
                name = "test",
                value = "value",
                sameSite = Cookie.SameSite.NONE,
                secure = true
            )

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertTrue(cookieHeader.contains("SameSite=None"))
            assertTrue(cookieHeader.contains("Secure"))
        }

        @Test
        fun `test SameSite values`() {
            val response = Response()

            response.cookie(name = "strict", value = "v1", sameSite = Cookie.SameSite.STRICT)
            response.cookie(name = "lax", value = "v2", sameSite = Cookie.SameSite.LAX)
            response.cookie(name = "none", value = "v3", sameSite = Cookie.SameSite.NONE, secure = true)

            val headers = response.headers.getAll("Set-Cookie")
            assertTrue(headers[0].contains("SameSite=Strict"))
            assertTrue(headers[1].contains("SameSite=Lax"))
            assertTrue(headers[2].contains("SameSite=None"))
        }

        @Test
        fun `test default SameSite is Lax`() {
            val response = Response()
            response.cookie(name = "test", value = "value")

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertTrue(cookieHeader.contains("SameSite=Lax"))
        }

        @Test
        fun `test cookie with null SameSite`() {
            val response = Response()
            response.cookie(name = "test", value = "value", sameSite = null)

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertFalse(cookieHeader.contains("SameSite"))
        }

        @Test
        fun `test cookie with maxAge zero adds Expires header`() {
            val response = Response()
            response.cookie(name = "test", value = "value", maxAge = 0)

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertTrue(cookieHeader.contains("Max-Age=0"))
            assertTrue(cookieHeader.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        }

        @Test
        fun `test cookie with negative maxAge adds Expires header`() {
            val response = Response()
            response.cookie(name = "test", value = "value", maxAge = -1)

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertTrue(cookieHeader.contains("Max-Age=-1"))
            println(cookieHeader)
            assertTrue(cookieHeader.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        }

        @Test
        fun `test deleteCookie with path`() {
            val response = Response()
            response.deleteCookie(name = "session", path = "/")

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertTrue(cookieHeader.contains("session="))
            assertTrue(cookieHeader.contains("Max-Age=0"))
            assertTrue(cookieHeader.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
            assertTrue(cookieHeader.contains("Path=/"))
        }

        @Test
        fun `test deleteCookie with path and domain`() {
            val response = Response()
            response.deleteCookie(name = "token", path = "/api", domain = "example.com")

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertTrue(cookieHeader.contains("token="))
            assertTrue(cookieHeader.contains("Max-Age=0"))
            assertTrue(cookieHeader.contains("Path=/api"))
            assertTrue(cookieHeader.contains("Domain=example.com"))
        }

        @Test
        fun `test deleteCookie returns fluent interface`() {
            val response = Response()
            val result = response.deleteCookie(name = "test", path = "/")

            assertSame(response, result)
        }

        @Test
        fun `test deleteCookie does not require SameSite None to be Secure`() {
            val response = Response()

            // Should not throw even though SameSite=None without Secure
            // because deletion (maxAge=0) bypasses the validation
            response.deleteCookie(name = "test", path = "/")

            assertEquals(1, response.headers.getAll("Set-Cookie").size)
        }

        @Test
        fun `test cookie header format`() {
            val response = Response()
            response.cookie(
                name = "test",
                value = "value",
                maxAge = 3600,
                path = "/app",
                domain = ".example.com",
                secure = true,
                httpOnly = true,
                sameSite = Cookie.SameSite.STRICT
            )

            val expected = "test=value; Max-Age=3600; Path=/app; Domain=.example.com; Secure; HttpOnly; SameSite=Strict"
            assertEquals(expected, response.headers.getAll("Set-Cookie")[0])
        }

        @Test
        fun `test empty cookie value`() {
            val response = Response()
            response.cookie(name = "empty", value = "")

            val cookieHeader = response.headers.getAll("Set-Cookie")[0]
            assertTrue(cookieHeader.startsWith("empty="))
        }

        @Test
        fun `test cookie chaining`() {
            val response = Response()

            response
                .cookie(name = "cookie1", value = "value1", path = "/")
                .cookie(name = "cookie2", value = "value2", path = "/")
                .status(201)

            assertEquals(201, response.status)
            assertEquals(2, response.headers.getAll("Set-Cookie").size)
        }
    }
}