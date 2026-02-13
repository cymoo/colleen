import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.TestClient

/**
 * TestClient Usage Examples
 *
 * Demonstrates some testing patterns for Colleen applications.
 */

fun main() {
    simpleGetRequest()
    postJsonAndAssert()
    fileUploadExample()
    commonPatterns()
}

// ============================================================================
// 1. Basic GET Request
// ============================================================================

fun simpleGetRequest() {
    val app = Colleen()
    app.get("/hello") { "Hello, World!" }

    val response = TestClient(app).get("/hello").send()

    println(response.text()) // Hello, World!
}

// ============================================================================
// 2. POST JSON with Assertions
// ============================================================================

fun postJsonAndAssert() {

    val app = Colleen()
    app.post("/api/users") { ctx ->
        val user = ctx.json<Map<String, Any>>()
        mapOf("id" to 1, "name" to user?.get("name"))
    }

    val client = TestClient(app)

    client.post("/api/users")
        .json(mapOf("name" to "Alice", "email" to "alice@example.com"))
        .send()
        .expect {
            assertStatus(200)
            assertContentType("application/json")
            assertBodyContains("Alice")
        }
}

// ============================================================================
// 3. File Upload with Multipart
// ============================================================================

fun fileUploadExample() {
    val app = Colleen()
    app.post("/upload") { ctx ->
        val file = ctx.file("document")
        val title = ctx.form("title")
        mapOf("title" to title, "filename" to file?.filename)
    }

    val client = TestClient(app)

    val response = client.post("/upload")
        .field("title", "My Document")
        .file("document", "test.txt", "content".toByteArray(), "text/plain")
        .send()

    println(response.text()) // {"title":"My Document","filename":"test.txt"}
}

// ============================================================================
// Common Patterns
// ============================================================================

fun commonPatterns() {
    val app = Colleen()
    app.get("/search") { ctx -> ctx.query("q") }
    app.post("/login") { ctx -> ctx.form("username") }

    val client = TestClient(app)

    // Query parameters
    client.get("/search").query("q", "kotlin").send()

    // Headers and cookies
    client.get("/api/data")
        .header("Authorization", "Bearer token")
        .cookie("session", "abc123")
        .send()

    // Form submission
    client.post("/login")
        .form(mapOf("username" to "admin", "password" to "secret"))
        .send()
}