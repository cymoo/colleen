import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Cookie
import io.github.cymoo.colleen.Form
import io.github.cymoo.colleen.Header
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.Param
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.Query
import io.github.cymoo.colleen.Unauthorized
import io.github.cymoo.colleen.UploadedFile
import io.github.cymoo.colleen.middleware.RequestLogger

/**
 * Parameter Extraction Examples
 *
 * Features demonstrated:
 * - Path parameters
 * - Query parameters
 * - Form data and file uploads
 * - JSON request bodies
 * - Headers and cookies
 * - Custom parameter extraction
 */

// ========================================================================
// 1. Path Parameters
// ========================================================================

fun pathParameterExtraction() {
    val app = Colleen()

    app.use(RequestLogger())

    // Basic path parameter
    app.get("/users/{id}", ::getUser)

    // Multiple path parameters
    app.get("/posts/{id}/comments/{commentId}", ::getComment)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Path Parameters</title></head>
            <body>
                <h1>Path Parameter Examples</h1>
                <ul>
                    <li><a href="/users/123">/users/123</a> - Single path param</li>
                    <li><a href="/posts/42/comments/7">/posts/42/comments/7</a> - Type conversion</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ Path parameters running on http://localhost:8000")
}

fun getUser(id: Path<Int>): Map<String, Any> {
    return mapOf(
        "userId" to id.value,
        "name" to "User #${id.value}"
    )
}

fun getComment(id: Path<Int>, commentId: Path<Int>): Map<String, Any> {
    return mapOf(
        "postId" to id.value,
        "commentId" to commentId.value,
        "content" to "Comment #${commentId.value} on post #${id.value}"
    )
}

// ========================================================================
// 2. Query Parameters
// ========================================================================

fun queryParameterExtraction() {
    val app = Colleen()

    app.use(RequestLogger())

    // Single query parameter
    app.get("/search", ::search)

    // Multiple query parameters (list)
    app.get("/filter", ::filterItems)

    // Query parameters as map
    app.get("/config", ::getConfig)

    // Optional query with default value
    app.get("/paginate", ::paginate)

    // Custom DTO from query parameters
    app.get("/user-search", ::searchUsers)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Query Parameters</title></head>
            <body>
                <h1>Query Parameter Examples</h1>
                <ul>
                    <li><a href="/search?q=kotlin">/search?q=kotlin</a> - Single param</li>
                    <li><a href="/filter?tags=web&tags=framework&tags=kotlin">/filter?tags=web&tags=framework&tags=kotlin</a> - List params</li>
                    <li><a href="/config?theme=dark&lang=en">/config?theme=dark&lang=en</a> - Map params</li>
                    <li><a href="/paginate?page=2&size=50">/paginate?page=2&size=50</a> - Optional with defaults</li>
                    <li><a href="/user-search?name=john&age=25&active=true">/user-search?name=john&age=25&active=true</a> - Custom DTO</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8001)
    println("✅ Query parameters running on http://localhost:8001")
}

fun search(q: Query<String>): Map<String, Any?> {
    return mapOf(
        "query" to q.value,
        "results" to listOf("Result 1", "Result 2", "Result 3")
    )
}

fun filterItems(tags: Query<List<String>>): Map<String, Any?> {
    return mapOf(
        "tags" to tags.value,
        "count" to (tags.value.size)
    )
}

fun getConfig(settings: Query<Map<String, String>>): Map<String, Any?> {
    return mapOf("settings" to settings.value)
}

fun paginate(
    page: Query<Int> = Query(1),
    size: Query<Int> = Query(20)
): Map<String, Any> {
    return mapOf(
        "page" to (page.value),
        "size" to (size.value),
        "items" to listOf("Item 1", "Item 2", "Item 3")
    )
}

data class UserSearchQuery(
    val name: String?,
    val age: Int?,
    val active: Boolean?
)

fun searchUsers(criteria: Query<UserSearchQuery>): Map<String, Any?> {
    return mapOf(
        "criteria" to criteria.value,
        "results" to listOf(
            mapOf("id" to 1, "name" to criteria.value.name)
        )
    )
}

// ========================================================================
// 3. Form Data and File Uploads
// ========================================================================

fun formAndFileExtraction() {
    val app = Colleen()

    app.use(RequestLogger())

    // Simple form submission
    app.post("/login", ::login)

    // Form with DTO
    app.post("/register", ::register)

    // File upload
    app.post("/upload", ::uploadFile)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Form & File Upload</title></head>
            <body>
                <h1>Form Data & File Upload Examples</h1>
                
                <h2>Login Form</h2>
                <form action="/login" method="post">
                    <input name="username" placeholder="Username" required>
                    <input name="password" type="password" placeholder="Password" required>
                    <button type="submit">Login</button>
                </form>

                <h2>Registration Form</h2>
                <form action="/register" method="post">
                    <input name="username" placeholder="Username" required>
                    <input name="email" type="email" placeholder="Email" required>
                    <input name="age" type="number" placeholder="Age">
                    <button type="submit">Register</button>
                </form>

                <h2>File Upload</h2>
                <form action="/upload" method="post" enctype="multipart/form-data">
                    <input name="file" type="file" required>
                    <input name="description" placeholder="Description">
                    <button type="submit">Upload</button>
                </form>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8002)
    println("✅ Form & file upload running on http://localhost:8002")
}

fun login(username: Form<String>, password: Form<String>): Map<String, Any?> {
    return mapOf(
        "success" to true,
        "username" to username.value,
        "token" to "mock-jwt-token"
    )
}

data class RegisterForm(
    val username: String,
    val email: String,
    val age: Int?
)

fun register(form: Form<RegisterForm>): Map<String, Any?> {
    return mapOf(
        "success" to true,
        "user" to form.value
    )
}

fun uploadFile(
    file: UploadedFile,
    description: Form<String?>
): Map<String, Any?> {
    val fileInfo = file.value
    return mapOf(
        "uploaded" to (fileInfo != null),
        "filename" to fileInfo?.name,
        "size" to fileInfo?.size,
        "contentType" to fileInfo?.contentType,
        "description" to description.value
    )
}

// ========================================================================
// 4. JSON Request Bodies
// ========================================================================

fun jsonBodyExtraction() {
    val app = Colleen()

    app.use(RequestLogger())

    // JSON body as DTO
    app.post("/users", ::createUser)

    // Nested JSON objects
    app.post("/orders", ::createOrder)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>JSON Bodies</title></head>
            <body>
                <h1>JSON Request Body Examples</h1>
                
                <h2>Create User</h2>
                <pre>POST /users
Content-Type: application/json

{
  "name": "Alice",
  "email": "alice@example.com",
  "age": 28
}</pre>

                <h2>Create Order</h2>
                <pre>POST /orders
Content-Type: application/json

{
  "userId": 123,
  "items": [
    {"productId": 1, "quantity": 2},
    {"productId": 2, "quantity": 1}
  ],
  "shippingAddress": {
    "street": "123 Main St",
    "city": "Springfield"
  }
}</pre>

                <p>Use curl or Postman to test these endpoints</p>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8003)
    println("✅ JSON bodies running on http://localhost:8003")
}

data class CreateUserRequest(
    val name: String,
    val email: String,
    val age: Int?
)

fun createUser(user: Json<CreateUserRequest>): Map<String, Any> {
    return mapOf(
        "id" to 123,
        "created" to true,
        "user" to user.value
    )
}

data class OrderItem(
    val productId: Int,
    val quantity: Int
)

data class Address(
    val street: String,
    val city: String
)

data class CreateOrderRequest(
    val userId: Int,
    val items: List<OrderItem>,
    val shippingAddress: Address
)

fun createOrder(order: Json<CreateOrderRequest>): Map<String, Any> {
    return mapOf(
        "orderId" to 456,
        "status" to "pending",
        "order" to order.value
    )
}

// ========================================================================
// 5. Headers and Cookies
// ========================================================================

fun headersAndCookies() {
    val app = Colleen()

    app.use(RequestLogger())

    // Extract headers
    app.get("/protected", ::protectedEndpoint)

    // Extract cookies
    app.get("/preferences", ::getUserPreferences)

    // Mix headers and path params
    app.get("/api/{version}/data", ::getVersionedData)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Headers & Cookies</title></head>
            <body>
                <h1>Headers and Cookies Examples</h1>
                <ul>
                    <li><a href="/protected">/protected</a> - Requires Authorization header</li>
                    <li><a href="/preferences">/preferences</a> - Reads user_id cookie</li>
                    <li><a href="/api/v2/data">/api/v2/data</a> - API versioning</li>
                </ul>
                
                <h2>Test with curl:</h2>
                <pre>curl -H "Authorization: Bearer token123" http://localhost:8004/protected</pre>
                <pre>curl -b "user_id=42" http://localhost:8004/preferences</pre>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8004)
    println("✅ Headers & cookies running on http://localhost:8004")
}

fun protectedEndpoint(
    @Param("Authorization") auth: Header
): Map<String, Any?> {
    return if (auth.value != null) {
        mapOf(
            "authenticated" to true,
            "token" to auth.value
        )
    } else {
        throw Unauthorized("Authorization header required")
    }
}

fun getUserPreferences(
    @Param("user_id") userId: Cookie
): Map<String, Any?> {
    return mapOf(
        "userId" to userId.value,
        "theme" to "dark",
        "language" to "en"
    )
}

fun getVersionedData(
    version: Path<String>,
    @Param("X-Request-ID") requestId: Header
): Map<String, Any?> {
    return mapOf(
        "version" to version.value,
        "requestId" to requestId.value,
        "data" to listOf(1, 2, 3)
    )
}

// ========================================================================
// 6. Mixed Parameters & Context Injection
// ========================================================================

fun mixedParametersExample() {
    val app = Colleen()

    app.use(RequestLogger())

    // Mix different parameter types
    app.post("/api/{resource}", ::apiHandler)

    // Direct Context access when needed
    app.get("/debug", ::debugEndpoint)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Mixed Parameters</title></head>
            <body>
                <h1>Mixed Parameter Types</h1>
                
                <h2>API Handler</h2>
                <pre>POST http://localhost:8005/api/users?verbose=true
Authorization: Bearer token
Content-Type: application/json

{"name": "Bob"}</pre>

                <ul>
                    <li><a href="/debug">/debug</a> - Direct Context access</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8005)
    println("✅ Mixed parameters running on http://localhost:8005")
}

data class ApiPayload(val name: String)

fun apiHandler(
    resource: Path<String>,
    verbose: Query<Boolean?>,
    @Param("Authorization") auth: Header,
    body: Json<ApiPayload>
): Map<String, Any?> {
    return mapOf(
        "resource" to resource.value,
        "verbose" to (verbose.value ?: false),
        "authenticated" to (auth.value != null),
        "payload" to body.value
    )
}

fun debugEndpoint(ctx: Context): Map<String, Any> {
    return mapOf(
        "method" to ctx.method,
        "path" to ctx.path,
        "headers" to ctx.request.headers.toString(),
        "queries" to ctx.queries()
    )
}

// ========================================================================
// Main
// ========================================================================

fun main() {
    println("Choose an example to run:")
    println("1. Path parameters")
    println("2. Query parameters")
    println("3. Form data & file uploads")
    println("4. JSON request bodies")
    println("5. Headers and cookies")
    println("6. Mixed parameters & Context injection")
    println()
    print("Enter choice (1-6): ")

    when (readlnOrNull()?.trim()) {
        "1" -> pathParameterExtraction()
        "2" -> queryParameterExtraction()
        "3" -> formAndFileExtraction()
        "4" -> jsonBodyExtraction()
        "5" -> headersAndCookies()
        "6" -> mixedParametersExample()
        else -> {
            println("Running all examples on different ports...")
            pathParameterExtraction()
            queryParameterExtraction()
            formAndFileExtraction()
            jsonBodyExtraction()
            headersAndCookies()
            mixedParametersExample()
        }
    }
}
