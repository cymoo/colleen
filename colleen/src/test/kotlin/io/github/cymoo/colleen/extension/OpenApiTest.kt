package io.github.cymoo.colleen.extension

import io.github.cymoo.colleen.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for OpenAPI extension.
 *
 * These tests verify the OpenAPI spec generation and serving
 * through observable framework behavior using TestClient.
 */
class OpenApiTest {

    @Test
    fun `serves OpenAPI spec at default path`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/hello") { "Hello!" }

        val response = TestClient(app).get("/openapi.json").send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("\"openapi\""))
        assertTrue(text.contains("3.0.3"))
    }

    @Test
    fun `spec contains correct info section`() {
        val app = Colleen()
        app.enableOpenApi(
            title = "My API",
            version = "2.0.0",
            description = "A test API"
        )
        app.get("/hello") { "Hello!" }

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val info = spec["info"] as Map<String, Any>
        assertEquals("My API", info["title"])
        assertEquals("2.0.0", info["version"])
        assertEquals("A test API", info["description"])
    }

    @Test
    fun `spec includes route from KFunction handler`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/users/{id}", ::testGetUser)

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        assertTrue(paths.containsKey("/users/{id}"))

        @Suppress("UNCHECKED_CAST")
        val pathItem = paths["/users/{id}"] as Map<String, Any>
        assertTrue(pathItem.containsKey("get"))

        @Suppress("UNCHECKED_CAST")
        val operation = pathItem["get"] as Map<String, Any>
        assertEquals("testGetUser", operation["operationId"])
    }

    @Test
    fun `spec includes path parameters from KFunction`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/users/{id}", ::testGetUser)

        val spec = getSpec(app)
        val operation = getOperation(spec, "/users/{id}", "get")

        @Suppress("UNCHECKED_CAST")
        val parameters = operation["parameters"] as List<Map<String, Any>>
        assertEquals(1, parameters.size)

        val param = parameters[0]
        assertEquals("id", param["name"])
        assertEquals("path", param["in"])
        assertEquals(true, param["required"])

        @Suppress("UNCHECKED_CAST")
        val schema = param["schema"] as Map<String, Any>
        assertEquals("integer", schema["type"])
    }

    @Test
    fun `spec includes query parameters with nullability`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/search", ::testSearch)

        val spec = getSpec(app)
        val operation = getOperation(spec, "/search", "get")

        @Suppress("UNCHECKED_CAST")
        val parameters = operation["parameters"] as List<Map<String, Any>>
        assertEquals(2, parameters.size)

        // Required query parameter
        val qParam = parameters.first { (it["name"] as String) == "q" }
        assertEquals("query", qParam["in"])
        assertEquals(true, qParam["required"])

        // Nullable query parameter
        val pageParam = parameters.first { (it["name"] as String) == "page" }
        assertEquals("query", pageParam["in"])
        assertEquals(false, pageParam["required"])
    }

    @Test
    fun `spec includes JSON request body`() {
        val app = Colleen()
        app.enableOpenApi()
        app.post("/users", ::testCreateUser)

        val spec = getSpec(app)
        val operation = getOperation(spec, "/users", "post")

        @Suppress("UNCHECKED_CAST")
        val requestBody = operation["requestBody"] as Map<String, Any>
        assertEquals(true, requestBody["required"])

        @Suppress("UNCHECKED_CAST")
        val content = requestBody["content"] as Map<String, Any>
        assertTrue(content.containsKey("application/json"))

        @Suppress("UNCHECKED_CAST")
        val jsonContent = content["application/json"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val schema = jsonContent["schema"] as Map<String, Any>
        assertEquals("object", schema["type"])
        assertTrue(schema.containsKey("properties"))
    }

    @Test
    fun `spec excludes OpenAPI internal routes`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/hello") { "Hello!" }

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        assertFalse(paths.containsKey("/openapi.json"))
        assertFalse(paths.containsKey("/swagger-ui"))
    }

    @Test
    fun `custom spec path`() {
        val app = Colleen()
        app.enableOpenApi(path = "/api-docs")
        app.get("/hello") { "Hello!" }

        val response = TestClient(app).get("/api-docs").send()
        assertEquals(200, response.status)

        // Default path should not work
        val defaultResponse = TestClient(app).get("/openapi.json").send()
        assertEquals(404, defaultResponse.status)
    }

    @Test
    fun `serves Swagger UI page`() {
        val app = Colleen()
        app.enableOpenApi()

        val response = TestClient(app).get("/swagger-ui").send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("swagger-ui"))
        assertTrue(text.contains("SwaggerUIBundle"))
    }

    @Test
    fun `Swagger UI can be disabled`() {
        val app = Colleen()
        app.enableOpenApi(uiPath = null)

        val response = TestClient(app).get("/swagger-ui").send()
        assertEquals(404, response.status)
    }

    @Test
    fun `spec includes lambda handler routes with minimal metadata`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/hello") { "Hello!" }

        val spec = getSpec(app)
        val operation = getOperation(spec, "/hello", "get")

        // Lambda handlers should still have default responses
        assertTrue(operation.containsKey("responses"))
        // But no operationId
        assertFalse(operation.containsKey("operationId"))
    }

    @Test
    fun `spec includes multiple routes with different methods`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/users/{id}", ::testGetUser)
        app.post("/users", ::testCreateUser)

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        assertTrue(paths.containsKey("/users/{id}"))
        assertTrue(paths.containsKey("/users"))
    }

    @Test
    fun `spec includes sub-app routes with mount prefix`() {
        val app = Colleen()
        app.enableOpenApi()

        val apiApp = Colleen()
        apiApp.get("/users", ::testListUsers)
        app.mount("/api", apiApp)

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        assertTrue(paths.containsKey("/api/users"))
    }

    @Test
    fun `spec includes header and cookie parameters`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/protected", ::testProtected)

        val spec = getSpec(app)
        val operation = getOperation(spec, "/protected", "get")

        @Suppress("UNCHECKED_CAST")
        val parameters = operation["parameters"] as List<Map<String, Any>>
        assertEquals(2, parameters.size)

        val headerParam = parameters.first { it["in"] == "header" }
        assertEquals("token", headerParam["name"])

        val cookieParam = parameters.first { it["in"] == "cookie" }
        assertEquals("session", cookieParam["name"])
    }

    @Test
    fun `spec includes wildcard method routes as all methods`() {
        val app = Colleen()
        app.enableOpenApi()
        app.all("/catch-all") { "OK" }

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pathItem = paths["/catch-all"] as Map<String, Any>

        assertTrue(pathItem.containsKey("get"))
        assertTrue(pathItem.containsKey("post"))
        assertTrue(pathItem.containsKey("put"))
        assertTrue(pathItem.containsKey("delete"))
        assertTrue(pathItem.containsKey("patch"))
        assertTrue(pathItem.containsKey("head"))
        assertTrue(pathItem.containsKey("options"))
    }

    @Test
    fun `spec includes response schema for typed return values`() {
        val app = Colleen()
        app.enableOpenApi()
        app.get("/users/{id}", ::testGetUser)

        val spec = getSpec(app)
        val operation = getOperation(spec, "/users/{id}", "get")

        @Suppress("UNCHECKED_CAST")
        val responses = operation["responses"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val okResponse = responses["200"] as Map<String, Any>
        assertTrue(okResponse.containsKey("content"))
    }

    @Test
    fun `routes registered before enableOpenApi are included`() {
        val app = Colleen()
        app.get("/early") { "Early!" }
        app.enableOpenApi()

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        assertTrue(paths.containsKey("/early"))
    }

    @Test
    fun `controller routes are included`() {
        val app = Colleen()
        app.enableOpenApi()
        app.addController(TestController())

        val spec = getSpec(app)

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        assertTrue(paths.containsKey("/test/hello"))
    }

    // ========================================================================
    // Test Helpers
    // ========================================================================

    private fun getSpec(app: Colleen, path: String = "/openapi.json"): Map<String, Any> {
        val response = TestClient(app).get(path).send()
        assertEquals(200, response.status)
        @Suppress("UNCHECKED_CAST")
        return response.json<Map<String, Any>>()!!
    }

    private fun getOperation(
        spec: Map<String, Any>,
        path: String,
        method: String
    ): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val pathItem = paths[path] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        return pathItem[method] as Map<String, Any>
    }
}

// ========================================================================
// Test Handler Functions and Types
// ========================================================================

data class CreateUserRequest(val name: String, val email: String)

fun testGetUser(id: Path<Int>): Map<String, Any> {
    return mapOf("id" to id.value)
}

fun testSearch(q: Query<String>, page: Query<Int?>): Map<String, Any?> {
    return mapOf("q" to q.value, "page" to page.value)
}

fun testCreateUser(body: Json<CreateUserRequest>): Map<String, Any> {
    return mapOf("name" to body.value.name)
}

fun testListUsers(): List<Map<String, Any>> {
    return listOf(mapOf("id" to 1))
}

fun testProtected(token: Header, session: Cookie): String {
    return "OK"
}

@Controller("/test")
class TestController {
    @Get("/hello")
    fun hello(): String = "Hello from controller!"
}
