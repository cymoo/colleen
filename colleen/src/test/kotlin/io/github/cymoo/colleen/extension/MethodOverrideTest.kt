package io.github.cymoo.colleen.extension

import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.TestClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for HTTP method override extension.
 *
 * These tests verify only observable framework behavior
 * through routing and responses.
 */
class HttpMethodOverrideTest {

    @Test
    fun `POST request is overridden by header`() {
        // Arrange
        val app = Colleen()
        app.httpMethodOverride()

        app.put("/resource") {
            "PUT_OK"
        }

        // Act
        val response = TestClient(app)
            .post(path = "/resource")
            .header("x-http-method-override", "PUT")
            .send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("PUT_OK", response.text())
    }

    @Test
    fun `POST request is overridden by query parameter`() {
        // Arrange
        val app = Colleen()
        app.httpMethodOverride()

        app.delete("/resource") {
            "DELETE_OK"
        }

        // Act
        val response = TestClient(app)
            .post(path = "/resource")
            .query("_method", "DELETE")
            .send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("DELETE_OK", response.text())
    }

    @Test
    fun `header takes precedence over query parameter`() {
        // Arrange
        val app = Colleen()
        app.httpMethodOverride()

        app.patch("/resource") {
            "PATCH_OK"
        }

        app.delete("/resource") {
            "DELETE_OK"
        }

        // Act
        val response = TestClient(app)
            .post(path = "/resource")
            .query("_method", "DELETE")
            .header("x-http-method-override", "PATCH")
            .send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("PATCH_OK", response.text())
    }

    @Test
    fun `method override is applied only to POST by default`() {
        // Arrange
        val app = Colleen()
        app.httpMethodOverride()

        app.delete("/resource") {
            "DELETE_OK"
        }

        // Act
        val response = TestClient(app)
            .get(path = "/resource")
            .header("x-http-method-override", "DELETE")
            .send()

        // Assert
        // Override should NOT be applied, DELETE handler must not be matched
        assertEquals(405, response.status)
    }

    @Test
    fun `custom override policy allows non POST requests`() {
        // Arrange
        val app = Colleen()
        app.httpMethodOverride(
            allowOverride = { true }
        )

        app.patch("/resource") {
            "PATCH_OK"
        }

        // Act
        val response = TestClient(app)
            .put(path = "/resource")
            .header("x-http-method-override", "PATCH")
            .send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("PATCH_OK", response.text())
    }

    @Test
    fun `invalid overridden method is ignored`() {
        // Arrange
        val app = Colleen()
        app.httpMethodOverride()

        app.get("/resource") {
            "GET_OK"
        }

        // Act
        val response = TestClient(app)
            .post(path = "/resource")
            .header("x-http-method-override", "TRACE")
            .send()

        // Assert
        // TRACE is not in the whitelist, override ignored,
        // original POST does not match GET handler
        assertEquals(405, response.status)
    }

    @Test
    fun `overridden method is normalized to uppercase`() {
        // Arrange
        val app = Colleen()
        app.httpMethodOverride()

        app.put("/resource") {
            "PUT_OK"
        }

        // Act
        val response = TestClient(app)
            .post(path = "/resource")
            .header("x-http-method-override", "put")
            .send()

        // Assert
        assertEquals(200, response.status)
        assertEquals("PUT_OK", response.text())
    }
}
