package io.github.cymoo.colleen

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControllerHandlerTest {

    // ========================================================================
    // Basic Controller Tests
    // ========================================================================

    @Test
    fun `Controller - basic GET route`() {
        val app = Colleen()
        app.addController(BasicController())

        val response = TestClient(app).get("/users").send()

        assertEquals(200, response.status)
        assertEquals("All users", response.text())
    }

    @Test
    fun `Controller - GET route with path parameter`() {
        val app = Colleen()
        app.addController(BasicController())

        val response = TestClient(app).get("/users/123").send()

        assertEquals(200, response.status)
        assertEquals("User ID: 123", response.text())
    }

    @Test
    fun `Controller - POST route with JSON body`() {
        val app = Colleen()
        app.addController(BasicController())

        val response = TestClient(app)
            .post("/users")
            .json(mapOf("name" to "Alice", "age" to 30, "email" to "foo@bar.com"))
            .send()

        assertEquals(200, response.status)
        assertEquals("Created user: Alice, age 30", response.text())
    }

    @Test
    fun `Controller - PUT route with path and body`() {
        val app = Colleen()
        app.addController(BasicController())

        val response = TestClient(app)
            .put("/users/456")
            .json(mapOf("name" to "Bob", "age" to 25))
            .send()

        assertEquals(200, response.status)
        assertEquals("Updated user 456: Bob, age 25", response.text())
    }

    @Test
    fun `Controller - DELETE route`() {
        val app = Colleen()
        app.addController(BasicController())

        val response = TestClient(app).delete("/users/789").send()

        assertEquals(200, response.status)
        assertEquals("Deleted user: 789", response.text())
    }

    @Test
    fun `Controller - PATCH route`() {
        val app = Colleen()
        app.addController(BasicController())

        val response = TestClient(app)
            .patch("/users/111")
            .json(mapOf("name" to "Charlie"))
            .send()

        assertEquals(200, response.status)
        assertEquals("Patched user 111: Charlie", response.text())
    }

    // ========================================================================
    // Controller with Query Parameters
    // ========================================================================

    @Test
    fun `Controller - query parameter - single value`() {
        val app = Colleen()
        app.addController(QueryController())

        val response = TestClient(app)
            .get("/search")
            .query("q", "kotlin")
            .send()

        assertEquals(200, response.status)
        assertEquals("Search: kotlin", response.text())
    }

    @Test
    fun `Controller - query parameter - multiple values`() {
        val app = Colleen()
        app.addController(QueryController())

        val response = TestClient(app)
            .get("/filter")
            .query("status", "active")
            .query("role", "admin")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("status=active"))
        assertTrue(response.text()!!.contains("role=admin"))
    }

    @Test
    fun `Controller - query parameter - nullable without value`() {
        val app = Colleen()
        app.addController(QueryController())

        val response = TestClient(app).get("/optional").send()

        assertEquals(200, response.status)
        assertEquals("Filter: null", response.text())
    }

    @Test
    fun `Controller - query parameter - with default value`() {
        val app = Colleen()
        app.addController(QueryController())

        val response = TestClient(app).get("/paginated").send()

        assertEquals(200, response.status)
        assertEquals("Page: 1, Size: 20", response.text())
    }

    @Test
    fun `Controller - query parameter - list`() {
        val app = Colleen()
        app.addController(QueryController())

        val response = TestClient(app)
            .get("/tags")
            .query("tags", "kotlin")
            .query("tags", "java")
            .query("tags", "scala")
            .send()

        assertEquals(200, response.status)
        assertEquals("Tags: [kotlin, java, scala]", response.text())
    }

    @Test
    fun `Controller - query parameter - DTO`() {
        val app = Colleen()
        app.addController(QueryController())

        val response = TestClient(app)
            .get("/products")
            .query("minPrice", "100")
            .query("maxPrice", "500")
            .query("inStock", "true")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("Price range: 100-500"))
        assertTrue(response.text()!!.contains("In stock: true"))
    }

    // ========================================================================
    // Controller with Headers and Cookies
    // ========================================================================

    @Test
    fun `Controller - header parameter`() {
        val app = Colleen()
        app.addController(HeaderController())

        val response = TestClient(app)
            .get("/auth")
            .header("Authorization", "Bearer token123")
            .send()

        assertEquals(200, response.status)
        assertEquals("Authorized with: Bearer token123", response.text())
    }

    @Test
    fun `Controller - cookie parameter`() {
        val app = Colleen()
        app.addController(HeaderController())

        val response = TestClient(app)
            .get("/session")
            .cookie("sessionId", "abc123")
            .send()

        assertEquals(200, response.status)
        assertEquals("Session: abc123", response.text())
    }

    @Test
    fun `Controller - multiple headers and cookies`() {
        val app = Colleen()
        app.addController(HeaderController())

        val response = TestClient(app)
            .get("/verify")
            .header("Authorization", "Bearer token")
            .header("X-Request-ID", "req-123")
            .cookie("sessionId", "session")
            .cookie("csrfToken", "csrf")
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("Auth: Bearer token"))
        assertTrue(text.contains("RequestID: req-123"))
        assertTrue(text.contains("Session: session"))
        assertTrue(text.contains("CSRF: csrf"))
    }

    // ========================================================================
    // Controller with Service Injection
    // ========================================================================

    @Test
    fun `Controller - inject service dependency`() {
        val app = Colleen()
        val userService = UserService()
        app.provide<UserService>(userService)
        app.addController(ServiceController())

        val response = TestClient(app).get("/users/123").send()

        assertEquals(200, response.status)
        assertEquals("User from service: 123", response.text())
    }

    @Test
    fun `Controller - inject multiple services`() {
        val app = Colleen()
        val userService = UserService()
        val authService = AuthService()
        app.provide<UserService>(userService)
        app.provide<AuthService>(authService)
        app.addController(ServiceController())

        val response = TestClient(app)
            .get("/secure/users/456")
            .header("Authorization", "Bearer token")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("Authenticated: true"))
        assertTrue(response.text()!!.contains("User: 456"))
    }

    @Test
    fun `Controller - service with all parameter types`() {
        val app = Colleen()
        val userService = UserService()
        val authService = AuthService()
        app.provide<UserService>(userService)
        app.provide<AuthService>(authService)
        app.addController(ServiceController())

        val response = TestClient(app)
            .post("/users/999")
            .query("notify", "true")
            .header("Authorization", "Bearer token")
            .json(mapOf("name" to "Updated"))
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("ID: 999"))
        assertTrue(text.contains("Notify: true"))
        assertTrue(text.contains("Auth: true"))
        assertTrue(text.contains("Name: Updated"))
    }

    // ========================================================================
    // Controller with Context Injection
    // ========================================================================

    @Test
    fun `Controller - inject Context`() {
        val app = Colleen()
        app.addController(ContextController())

        val response = TestClient(app)
            .get("/info")
            .header("X-Custom", "value")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("Method: GET"))
        assertTrue(response.text()!!.contains("Path: /info"))
        assertTrue(response.text()!!.contains("Custom: value"))
    }

    @Test
    fun `Controller - Context with other parameters`() {
        val app = Colleen()
        app.addController(ContextController())

        val response = TestClient(app)
            .get("/users/789")
            .query("fields", "name,email")
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("ID: 789"))
        assertTrue(text.contains("Fields: name,email"))
        assertTrue(text.contains("Path: /users/789"))
    }

    // ========================================================================
    // Controller with Prefix in addController
    // ========================================================================

    @Test
    fun `Controller - add with custom prefix`() {
        val app = Colleen()
        app.addController("/api", BasicController())

        val response = TestClient(app).get("/api/users").send()

        assertEquals(200, response.status)
        assertEquals("All users", response.text())
    }

    @Test
    fun `Controller - add with custom prefix and controller base path`() {
        val app = Colleen()
        app.addController("/api/v1", BasicController())

        val response = TestClient(app).get("/api/v1/users/123").send()

        assertEquals(200, response.status)
        assertEquals("User ID: 123", response.text())
    }

    // ========================================================================
    // Controller with Route Groups
    // ========================================================================

    @Test
    fun `Controller - inside route group`() {
        val app = Colleen()
        app.group("/api") {
            addController(BasicController())
        }

        val response = TestClient(app).get("/api/users").send()

        assertEquals(200, response.status)
        assertEquals("All users", response.text())
    }

    @Test
    fun `Controller - inside nested route groups`() {
        val app = Colleen()
        app.group("/api") {
            group("/v1") {
                addController(BasicController())
            }
        }

        val response = TestClient(app).get("/api/v1/users/123").send()

        assertEquals(200, response.status)
        assertEquals("User ID: 123", response.text())
    }

    @Test
    fun `Controller - inside route group with additional prefix`() {
        val app = Colleen()
        app.group("/api") {
            addController("/admin", BasicController())
        }

        val response = TestClient(app).get("/api/admin/users").send()

        assertEquals(200, response.status)
        assertEquals("All users", response.text())
    }

    @Test
    fun `Controller - multiple controllers in same group`() {
        val app = Colleen()
        app.group("/api") {
            addController(BasicController())
            addController(QueryController())
        }

        val response1 = TestClient(app).get("/api/users").send()
        assertEquals(200, response1.status)
        assertEquals("All users", response1.text())

        val response2 = TestClient(app)
            .get("/api/search")
            .query("q", "test")
            .send()
        assertEquals(200, response2.status)
        assertEquals("Search: test", response2.text())
    }

    @Test
    fun `Controller - deeply nested groups`() {
        val app = Colleen()
        app.group("/api") {
            group("/v1") {
                group("/admin") {
                    addController(BasicController())
                }
            }
        }

        val response = TestClient(app).get("/api/v1/admin/users/999").send()

        assertEquals(200, response.status)
        assertEquals("User ID: 999", response.text())
    }

    // ========================================================================
    // Controller with Mixed Controllers
    // ========================================================================

    @Test
    fun `Controller - without base path annotation`() {
        val app = Colleen()
        app.addController(NoPathController())

        val response = TestClient(app).get("/status").send()

        assertEquals(200, response.status)
        assertEquals("OK", response.text())
    }

    @Test
    fun `Controller - empty base path`() {
        val app = Colleen()
        app.addController(EmptyPathController())

        val response = TestClient(app).get("/health").send()

        assertEquals(200, response.status)
        assertEquals("Healthy", response.text())
    }

    @Test
    fun `Controller - root path`() {
        val app = Colleen()
        app.addController(RootController())

        val response = TestClient(app).get("/").send()

        assertEquals(200, response.status)
        assertEquals("Home", response.text())
    }

    // ========================================================================
    // Controller with Form Data
    // ========================================================================

    @Test
    fun `Controller - form data - simple fields`() {
        val app = Colleen()
        app.addController(FormController())

        val response = TestClient(app)
            .post("/login")
            .form(mapOf("username" to "alice", "password" to "secret"))
            .send()

        assertEquals(200, response.status)
        assertEquals("Login: alice", response.text())
    }

    @Test
    fun `Controller - form data - DTO`() {
        val app = Colleen()
        app.addController(FormController())

        val response = TestClient(app)
            .post("/register")
            .form(
                mapOf(
                    "username" to "bob",
                    "email" to "bob@example.com",
                    "age" to "25"
                )
            )
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("Registered: bob"))
        assertTrue(response.text()!!.contains("bob@example.com"))
        assertTrue(response.text()!!.contains("25"))
    }

    @Test
    fun `Controller - form data - with list fields`() {
        val app = Colleen()
        app.addController(FormController())

        val response = TestClient(app)
            .post("/interests")
            .field("userId", "123")
            .field("hobbies", "reading")
            .field("hobbies", "coding")
            .send()

        assertEquals(200, response.status)
        assertEquals("User 123: [reading, coding]", response.text())
    }

    // ========================================================================
    // Controller with File Upload
    // ========================================================================

    @Test
    fun `Controller - file upload - single file`() {
        val app = Colleen()
        app.addController(FileController())

        val fileContent = "Hello, World!".toByteArray()
        val response = TestClient(app)
            .post("/upload")
            .file("file", "test.txt", fileContent, "text/plain")
            .send()

        assertEquals(200, response.status)
        assertEquals("Uploaded: test.txt (13 bytes)", response.text())
    }

    @Test
    fun `Controller - file upload - with metadata`() {
        val app = Colleen()
        app.addController(FileController())

        val fileContent = "Document content".toByteArray()
        val response = TestClient(app)
            .post("/upload-with-meta")
            .file("file", "doc.pdf", fileContent, "application/pdf")
            .field("description", "Important document")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("doc.pdf"))
        assertTrue(response.text()!!.contains("Important document"))
    }

    // ========================================================================
    // Controller with Complex Scenarios
    // ========================================================================

    @Test
    fun `Controller - mixed parameter types in single handler`() {
        val app = Colleen()
        app.addController(ComplexController())

        val response = TestClient(app)
            .post("/items/456")
            .query("action", "update")
            .query("tags", "new")
            .query("tags", "featured")
            .header("Authorization", "Bearer token")
            .cookie("sessionId", "session123")
            .json(mapOf("name" to "Item Name", "price" to 99.99))
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("ID: 456"))
        assertTrue(text.contains("Action: update"))
        assertTrue(text.contains("Tags: [new, featured]"))
        assertTrue(text.contains("Auth: Bearer token"))
        assertTrue(text.contains("Session: session123"))
        assertTrue(text.contains("Name: Item Name"))
    }

    @Test
    fun `Controller - nested DTO in JSON body`() {
        val app = Colleen()
        app.addController(ComplexController())

        val response = TestClient(app)
            .post("/orders")
            .json(
                mapOf(
                    "orderId" to "ORD-123",
                    "customer" to mapOf("name" to "Alice", "email" to "alice@example.com"),
                    "items" to listOf(
                        mapOf("product" to "Laptop", "quantity" to 1),
                        mapOf("product" to "Mouse", "quantity" to 2)
                    )
                )
            )
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("Order: ORD-123"))
        assertTrue(text.contains("Customer: Alice"))
        assertTrue(text.contains("Items: 2"))
    }

    // ========================================================================
    // Controller Return Types
    // ========================================================================

    @Test
    fun `Controller - return String`() {
        val app = Colleen()
        app.addController(ReturnTypeController())

        val response = TestClient(app).get("/string").send()

        assertEquals(200, response.status)
        assertEquals("text/plain; charset=utf-8", response.header("Content-Type"))
        assertEquals("Simple text", response.text())
    }

    @Test
    fun `Controller - return data class (JSON)`() {
        val app = Colleen()
        app.addController(ReturnTypeController())

        val response = TestClient(app).get("/json").send()

        assertEquals(200, response.status)
        assertTrue(response.header("Content-Type")!!.contains("application/json"))
        assertTrue(response.text()!!.contains("\"name\""))
        assertTrue(response.text()!!.contains("\"Alice\""))
    }

    @Test
    fun `Controller - return List`() {
        val app = Colleen()
        app.addController(ReturnTypeController())

        val response = TestClient(app).get("/list").send()

        assertEquals(200, response.status)
        assertTrue(response.header("Content-Type")!!.contains("application/json"))
        assertTrue(response.text()!!.contains("["))
    }

    @Test
    fun `Controller - return Map`() {
        val app = Colleen()
        app.addController(ReturnTypeController())

        val response = TestClient(app).get("/map").send()

        assertEquals(200, response.status)
        assertTrue(response.header("Content-Type")!!.contains("application/json"))
        assertTrue(response.text()!!.contains("\"status\""))
    }

    @Test
    fun `Controller - return Unit (void)`() {
        val app = Colleen()
        app.addController(ReturnTypeController())

        val response = TestClient(app).delete("/void").send()

        assertEquals(204, response.status)
    }

    // ========================================================================
    // Error Handling in Controllers
    // ========================================================================

    @Test
    fun `Controller - throw HttpException`() {
        val app = Colleen()
        app.addController(ErrorController())

        val response = TestClient(app)
            .get("/not-found")
            .header("Accept", "application/json")
            .send()

        assertEquals(404, response.status)
        assertTrue(response.text()!!.contains("Resource not found"))
    }

    @Test
    fun `Controller - throw custom exception`() {
        val app = Colleen()
        app.addController(ErrorController())

        val response = TestClient(app)
            .get("/forbidden")
            .header("Accept", "application/json")
            .send()

        assertEquals(403, response.status)
    }

    @Test
    fun `Controller - validation error`() {
        val app = Colleen()
        app.addController(ErrorController())

        val response = TestClient(app)
            .post("/validate")
            .json(mapOf("age" to -5))
            .header("Accept", "application/json")
            .send()

        assertEquals(400, response.status)
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun `Controller - multiple HTTP methods on same path`() {
        val app = Colleen()
        app.addController(MultiMethodController())

        val getResponse = TestClient(app).get("/resource").send()
        assertEquals(200, getResponse.status)
        assertEquals("GET resource", getResponse.text())

        val postResponse = TestClient(app).post("/resource").send()
        assertEquals(200, postResponse.status)
        assertEquals("POST resource", postResponse.text())

        val deleteResponse = TestClient(app).delete("/resource").send()
        assertEquals(200, deleteResponse.status)
        assertEquals("DELETE resource", deleteResponse.text())
    }

    @Test
    fun `Controller - path with trailing slash`() {
        val app = Colleen()
        app.addController(TrailingSlashController())

        val response1 = TestClient(app).get("/items/").send()
        assertEquals(200, response1.status)

        val response2 = TestClient(app).get("/items").send()
        assertEquals(200, response2.status)
    }

    @Test
    fun `Controller - multiple path parameters`() {
        val app = Colleen()
        app.addController(MultiPathController())

        val response = TestClient(app)
            .get("/posts/123/comments/456")
            .send()

        assertEquals(200, response.status)
        assertEquals("Post: 123, Comment: 456", response.text())
    }

    @Test
    fun `Controller - wildcard path`() {
        val app = Colleen()
        app.addController(WildcardController())

        val response = TestClient(app).get("/static/css/main.css").send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("css/main.css"))
    }

    // ========================================================================
    // Controller Classes
    // ========================================================================

    @Controller("/users")
    class BasicController {
        @Get("/")
        fun getAll() = "All users"

        @Get("/{id}")
        fun getOne(id: Path<Int>) = "User ID: ${id.value}"

        @Post("/")
        fun create(user: Json<UserDto>) =
            "Created user: ${user.value.name}, age ${user.value.age}"

        @Put("/{id}")
        fun update(id: Path<Int>, user: Json<UserDto>) =
            "Updated user ${id.value}: ${user.value.name}, age ${user.value.age}"

        @Delete("/{id}")
        fun delete(id: Path<Int>) = "Deleted user: ${id.value}"

        @Patch("/{id}")
        fun patch(id: Path<Int>, data: Json<Map<String, String>>) =
            "Patched user ${id.value}: ${data.value["name"]}"
    }

    @Controller("/")
    class QueryController {
        @Get("/search")
        fun search(q: Query<String>) = "Search: ${q.value}"

        @Get("/filter")
        fun filter(filters: Query<Map<String, String>>) = "Filters: ${filters.value}"

        @Get("/optional")
        fun optional(filter: Query<String?>) = "Filter: ${filter.value}"

        @Get("/paginated")
        fun paginated(
            page: Query<Int> = Query(1),
            size: Query<Int> = Query(20)
        ) = "Page: ${page.value}, Size: ${size.value}"

        @Get("/tags")
        fun tags(tags: Query<List<String>>) = "Tags: ${tags.value}"

        @Get("/products")
        fun products(filter: Query<ProductFilter>) =
            "Price range: ${filter.value.minPrice}-${filter.value.maxPrice}, In stock: ${filter.value.inStock}"
    }

    @Controller("/")
    class HeaderController {
        @Get("/auth")
        fun auth(@Param("Authorization") auth: Header) = "Authorized with: ${auth.value}"

        @Get("/session")
        fun session(@Param("sessionId") session: Cookie) = "Session: ${session.value}"

        @Get("/verify")
        fun verify(
            @Param("Authorization") auth: Header,
            @Param("X-Request-ID") requestId: Header,
            @Param("sessionId") session: Cookie,
            @Param("csrfToken") csrf: Cookie
        ) = "Auth: ${auth.value}, RequestID: ${requestId.value}, Session: ${session.value}, CSRF: ${csrf.value}"
    }

    @Controller("/")
    class ServiceController {
        @Get("/users/{id}")
        fun getUser(id: Path<Int>, userService: UserService) =
            "User from service: ${userService.getUser(id.value)}"

        @Get("/secure/users/{id}")
        fun getSecureUser(
            id: Path<Int>,
            @Param("Authorization") auth: Header,
            userService: UserService,
            authService: AuthService
        ) = "Authenticated: ${authService.verify(auth.value)}, User: ${userService.getUser(id.value)}"

        @Post("/users/{id}")
        fun updateUser(
            id: Path<Int>,
            notify: Query<Boolean>,
            @Param("Authorization") auth: Header,
            data: Json<Map<String, String>>,
            userService: UserService,
            authService: AuthService
        ) =
            "ID: ${id.value}, Notify: ${notify.value}, Auth: ${authService.verify(auth.value)}, Name: ${data.value["name"]}"
    }

    @Controller("/")
    class ContextController {
        @Get("/info")
        fun info(ctx: Context) =
            "Method: ${ctx.method}, Path: ${ctx.path}, Custom: ${ctx.header("X-Custom")}"

        @Get("/users/{id}")
        fun userInfo(
            id: Path<Int>,
            fields: Query<String>,
            ctx: Context
        ) = "ID: ${id.value}, Fields: ${fields.value}, Path: ${ctx.path}"
    }

    @Controller("/")
    class NoPathController {
        @Get("/status")
        fun status() = "OK"
    }

    @Controller("")
    class EmptyPathController {
        @Get("/health")
        fun health() = "Healthy"
    }

    @Controller("/")
    class RootController {
        @Get("/")
        fun home() = "Home"
    }

    @Controller("/")
    class FormController {
        @Post("/login")
        fun login(username: Form<String>) = "Login: ${username.value}"

        @Post("/register")
        fun register(form: Form<RegistrationForm>) =
            "Registered: ${form.value.username}, ${form.value.email}, ${form.value.age}"

        @Post("/interests")
        fun interests(userId: Form<String>, hobbies: Form<List<String>>) =
            "User ${userId.value}: ${hobbies.value}"
    }

    @Controller("/")
    class FileController {
        @Post("/upload")
        fun upload(file: UploadedFile) =
            "Uploaded: ${file.value?.filename} (${file.value?.size} bytes)"

        @Post("/upload-with-meta")
        fun uploadWithMeta(
            file: UploadedFile,
            description: Form<String>
        ) = "File: ${file.value?.filename}, Description: ${description.value}"
    }

    @Controller("/")
    class ComplexController {
        @Post("/items/{id}")
        fun complexHandler(
            id: Path<Int>,
            action: Query<String>,
            tags: Query<List<String>>,
            @Param("Authorization") auth: Header,
            @Param("sessionId") session: Cookie,
            data: Json<Map<String, Any>>
        ) = "ID: ${id.value}, Action: ${action.value}, Tags: ${tags.value}, " +
                "Auth: ${auth.value}, Session: ${session.value}, Name: ${data.value["name"]}"

        @Post("/orders")
        fun createOrder(order: Json<OrderDto>) =
            "Order: ${order.value.orderId}, Customer: ${order.value.customer.name}, Items: ${order.value.items.size}"
    }

    @Controller("/")
    class ReturnTypeController {
        @Get("/string")
        fun returnString() = "Simple text"

        @Get("/json")
        fun returnJson() = UserDto("Alice", "alice@example.com", 30)

        @Get("/list")
        fun returnList() = listOf(1, 2, 3, 4, 5)

        @Get("/map")
        fun returnMap() = mapOf("status" to "ok", "count" to 42)

        @Delete("/void")
        fun returnVoid() {
        }
    }

    @Controller("/")
    class ErrorController {
        @Get("/not-found")
        fun notFound() {
            throw NotFound("Resource not found")
        }

        @Get("/forbidden")
        fun forbidden() {
            throw Forbidden("Access denied")
        }

        @Post("/validate")
        fun validate(data: Json<Map<String, Int>>) {
            val age = data.value["age"] ?: 0
            if (age < 0) {
                throw BadRequest("Age cannot be negative")
            }
        }
    }

    @Controller("/resource")
    class MultiMethodController {
        @Get("/")
        fun get() = "GET resource"

        @Post("/")
        fun post() = "POST resource"

        @Delete("/")
        fun delete() = "DELETE resource"
    }

    @Controller("/items")
    class TrailingSlashController {
        @Get("/")
        fun getItems() = "Items list"
    }

    @Controller("/posts")
    class MultiPathController {
        @Get("/{postId}/comments/{commentId}")
        fun getComment(postId: Path<Int>, commentId: Path<Int>) =
            "Post: ${postId.value}, Comment: ${commentId.value}"
    }

    @Controller("/static")
    class WildcardController {
        @Get("/{path...}")
        fun serve(path: Path<String>) = "Serving: ${path.value}"
    }

    // ========================================================================
    // Data Classes
    // ========================================================================

    data class UserDto(val name: String, val email: String?, val age: Int)

    data class ProductFilter(
        val minPrice: Int,
        val maxPrice: Int,
        val inStock: Boolean
    )

    data class RegistrationForm(
        val username: String,
        val email: String,
        val age: Int
    )

    data class CustomerDto(val name: String, val email: String)
    data class OrderItem(val product: String, val quantity: Int)
    data class OrderDto(
        val orderId: String,
        val customer: CustomerDto,
        val items: List<OrderItem>
    )

    // ========================================================================
    // Service Classes
    // ========================================================================

    class UserService {
        fun getUser(id: Int): String = id.toString()
    }

    class AuthService {
        fun verify(token: String?): Boolean = token?.startsWith("Bearer") == true
    }
}