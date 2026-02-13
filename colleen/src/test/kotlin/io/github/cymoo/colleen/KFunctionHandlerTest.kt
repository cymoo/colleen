package io.github.cymoo.colleen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KFunctionHandlerTest {

    // ========================================================================
    // Path Parameter Tests
    // ========================================================================

    @Test
    fun `Path - extract Int path parameter`() {
        val app = Colleen()
        app.get("/users/{id}", ::pathIntHandler)

        val response = TestClient(app).get("/users/123").send()

        assertEquals(200, response.status)
        assertEquals("User ID: 123", response.text())
    }

    @Test
    fun `Path - extract String path parameter`() {
        val app = Colleen()
        app.get("/users/{username}", ::pathStringHandler)

        val response = TestClient(app).get("/users/alice").send()

        assertEquals(200, response.status)
        assertEquals("Username: alice", response.text())
    }

    @Test
    fun `Path - extract Long path parameter`() {
        val app = Colleen()
        app.get("/orders/{orderId}", ::pathLongHandler)

        val response = TestClient(app).get("/orders/9876543210").send()

        assertEquals(200, response.status)
        assertEquals("Order ID: 9876543210", response.text())
    }

    @Test
    fun `Path - extract Double path parameter`() {
        val app = Colleen()
        app.get("/prices/{amount}", ::pathDoubleHandler)

        val response = TestClient(app).get("/prices/99.99").send()

        assertEquals(200, response.status)
        assertEquals("Price: 99.99", response.text())
    }

    @Test
    fun `Path - extract Boolean path parameter`() {
        val app = Colleen()
        app.get("/flags/{enabled}", ::pathBooleanHandler)

        val response = TestClient(app).get("/flags/true").send()

        assertEquals(200, response.status)
        assertEquals("Enabled: true", response.text())
    }

    @Test
    fun `Path - missing path parameter throws exception`() {
        val app = Colleen()
        app.get("/users/{id}", ::pathIntHandler)

        val response = TestClient(app).get("/users/").send()

        assertEquals(404, response.status)
    }

    @Test
    fun `Path - invalid type conversion throws exception`() {
        val app = Colleen()
        app.get("/users/{id}", ::pathIntHandler)

        val response = TestClient(app).get("/users/abc").header("accept", "application/json").send()

        assertContains(response.text()!!, "Cannot convert 'abc' to Int")
        assertEquals(400, response.status)
    }

    @Test
    fun `Path - nullable path parameter with value`() {
        val app = Colleen()
        app.get("/items/{code}", ::pathNullableHandler)

        val response = TestClient(app).get("/items/ABC123").send()

        assertEquals(200, response.status)
        assertEquals("Code: ABC123", response.text())
    }

    // ========================================================================
    // Query Parameter Tests - Simple Types
    // ========================================================================

    @Test
    fun `Query - extract String query parameter`() {
        val app = Colleen()
        app.get("/search", ::queryStringHandler)

        val response = TestClient(app).get("/search").query("q", "kotlin").send()

        assertEquals(200, response.status)
        assertEquals("Query: kotlin", response.text())
    }

    @Test
    fun `Query - extract Int query parameter`() {
        val app = Colleen()
        app.get("/page", ::queryIntHandler)

        val response = TestClient(app).get("/page").query("page", "5").send()

        assertEquals(200, response.status)
        assertEquals("Page: 5", response.text())
    }

    @Test
    fun `Query - extract Long query parameter`() {
        val app = Colleen()
        app.get("/timestamp", ::queryLongHandler)

        val response = TestClient(app).get("/timestamp").query("ts", "1234567890").send()

        assertEquals(200, response.status)
        assertEquals("Timestamp: 1234567890", response.text())
    }

    @Test
    fun `Query - extract Double query parameter`() {
        val app = Colleen()
        app.get("/price", ::queryDoubleHandler)

        val response = TestClient(app).get("/price").query("amount", "49.99").send()

        assertEquals(200, response.status)
        assertEquals("Amount: 49.99", response.text())
    }

    @Test
    fun `Query - extract Boolean query parameter with true`() {
        val app = Colleen()
        app.get("/filter", ::queryBooleanHandler)

        val response = TestClient(app).get("/filter").query("active", "true").send()

        assertEquals(200, response.status)
        assertEquals("Active: true", response.text())
    }

    @Test
    fun `Query - extract Boolean query parameter with 1`() {
        val app = Colleen()
        app.get("/filter", ::queryBooleanHandler)

        val response = TestClient(app).get("/filter").query("active", "1").send()

        assertEquals(200, response.status)
        assertEquals("Active: true", response.text())
    }

    @Test
    fun `Query - extract Boolean query parameter with yes`() {
        val app = Colleen()
        app.get("/filter", ::queryBooleanHandler)

        val response = TestClient(app).get("/filter").query("active", "yes").send()

        assertEquals(200, response.status)
        assertEquals("Active: true", response.text())
    }

    @Test
    fun `Query - extract Boolean query parameter with false`() {
        val app = Colleen()
        app.get("/filter", ::queryBooleanHandler)

        val response = TestClient(app).get("/filter").query("active", "false").send()

        assertEquals(200, response.status)
        assertEquals("Active: false", response.text())
    }

    @Test
    fun `Query - missing required query parameter throws exception`() {
        val app = Colleen()
        app.get("/search", ::queryStringHandler)

        val response = TestClient(app).get("/search").send()

        assertContains(response.text()!!, "Missing required query parameter: q")

        assertEquals(400, response.status)
    }

    @Test
    fun `Query - nullable query parameter with value`() {
        val app = Colleen()
        app.get("/search", ::queryNullableHandler)

        val response = TestClient(app).get("/search").query("q", "test").send()

        assertEquals(200, response.status)
        assertEquals("Query: test", response.text())
    }

    @Test
    fun `Query - nullable query parameter without value`() {
        val app = Colleen()
        app.get("/search", ::queryNullableHandler)

        val response = TestClient(app).get("/search").send()

        assertEquals(200, response.status)
        assertEquals("Query: null", response.text())
    }

    @Test
    fun `Query - optional query parameter with value`() {
        val app = Colleen()
        app.get("/search", ::queryOptionalHandler)

        val response = TestClient(app).get("/search").query("q", "test").send()

        assertEquals(200, response.status)
        assertEquals("Query: test", response.text())
    }

    @Test
    fun `Query - optional query parameter without value uses default`() {
        val app = Colleen()
        app.get("/search", ::queryOptionalHandler)

        val response = TestClient(app).get("/search").send()

        assertEquals(200, response.status)
        assertEquals("Query: default", response.text())
    }

    @Test
    fun `Query - invalid type conversion throws exception`() {
        val app = Colleen()
        app.get("/page", ::queryIntHandler)

        val response = TestClient(app).get("/page").header("Accept", "application/json").query("page", "abc").send()

        assertContains(response.text()!!, "Cannot convert 'abc' to Int")
        assertEquals(400, response.status)
    }

    // ========================================================================
    // Query Parameter Tests - List Types
    // ========================================================================

    @Test
    fun `Query - extract List of Strings`() {
        val app = Colleen()
        app.get("/tags", ::queryListStringHandler)

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
    fun `Query - extract List of Ints`() {
        val app = Colleen()
        app.get("/ids", ::queryListIntHandler)

        val response = TestClient(app)
            .get("/ids")
            .query("ids", "1")
            .query("ids", "2")
            .query("ids", "3")
            .send()

        assertEquals(200, response.status)
        assertEquals("IDs: [1, 2, 3]", response.text())
    }

    @Test
    fun `Query - extract empty List`() {
        val app = Colleen()
        app.get("/tags", ::queryListStringHandler)

        val response = TestClient(app).get("/tags").send()

        assertEquals("Tags: []", response.text())
        assertEquals(200, response.status)
    }

    @Test
    fun `Query - extract nullable List without value`() {
        val app = Colleen()
        app.get("/tags", ::queryNullableListHandler)

        val response = TestClient(app).get("/tags").send()

        assertEquals(200, response.status)
        assertEquals("Tags: []", response.text())
    }

    @Test
    fun `Query - extract optional List with default`() {
        val app = Colleen()
        app.get("/tags", ::queryOptionalListHandler)

        val response = TestClient(app).get("/tags").send()

        assertEquals(200, response.status)
        assertEquals("Tags: []", response.text())
    }

    // ========================================================================
    // Query Parameter Tests - Map Types
    // ========================================================================

    @Test
    fun `Query - extract Map of String to String`() {
        val app = Colleen()
        app.get("/filters", ::queryMapStringHandler)

        val response = TestClient(app)
            .get("/filters")
            .query("color", "red")
            .query("size", "large")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("color=red"))
        assertTrue(response.text()!!.contains("size=large"))
    }

    @Test
    fun `Query - extract Map of String to List of String`() {
        val app = Colleen()
        app.get("/filters", ::queryMapListHandler)

        val response = TestClient(app)
            .get("/filters")
            .query("tags", "kotlin")
            .query("tags", "java")
            .query("category", "backend")
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("tags=[kotlin, java]"))
        assertTrue(text.contains("category=[backend]"))
    }

    @Test
    fun `Query - extract nullable Map without value`() {
        val app = Colleen()
        app.get("/filters", ::queryNullableMapHandler)

        val response = TestClient(app).get("/filters").send()

        assertEquals(200, response.status)
        assertEquals("Filters: {}", response.text())
    }

    @Test
    fun `Query - extract required Map without value`() {
        val app = Colleen()
        app.get("/search", ::queryMapStringHandler)

        val response = TestClient(app).get("/search").send()

        assertEquals("Filters: {}", response.text())

        assertEquals(200, response.status)
    }

    @Test
    fun `Query - extract optional Map with default`() {
        val app = Colleen()
        app.get("/filters", ::queryOptionalMapHandler)

        val response = TestClient(app).get("/filters").send()

        assertEquals(200, response.status)
        assertEquals("Filters: {}", response.text())
    }

    // ========================================================================
    // Query Parameter Tests - Custom DTO
    // ========================================================================

    @Test
    fun `Query - extract custom DTO with simple fields`() {
        val app = Colleen()
        app.get("/users", ::queryDtoSimpleHandler)

        val response = TestClient(app)
            .get("/users")
            .query("name", "Alice")
            .query("age", "30")
            .send()

        assertEquals(200, response.status)
        assertEquals("User: Alice, Age: 30", response.text())
    }

    @Test
    fun `Query - extract custom DTO with List field`() {
        val app = Colleen()
        app.get("/users", ::queryDtoListHandler)

        val response = TestClient(app)
            .get("/users")
            .query("name", "Bob")
            .query("addr", "Tokyo")
            .query("addr", "Osaka")
            .send()

        assertEquals(200, response.status)
        assertEquals("User: Bob, Addresses: [Tokyo, Osaka]", response.text())
    }

    @Test
    fun `Query - extract custom DTO with mixed types`() {
        val app = Colleen()
        app.get("/search", ::queryDtoMixedHandler)

        val response = TestClient(app)
            .get("/search")
            .query("keyword", "kotlin")
            .query("minPrice", "100")
            .query("maxPrice", "500")
            .query("inStock", "true")
            .query("tags", "programming")
            .query("tags", "jvm")
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("Keyword: kotlin"))
        assertTrue(text.contains("Price: 100-500"))
        assertTrue(text.contains("InStock: true"))
        assertTrue(text.contains("Tags: [programming, jvm]"))
    }

    @Test
    fun `Query - extract nullable custom DTO without value`() {
        val app = Colleen()
        app.get("/users", ::queryNullableDtoHandler)

        val response = TestClient(app).get("/users").send()

        assertEquals(200, response.status)
        assertEquals("User: null", response.text())
    }

    @Test
    fun `Query - extract optional custom DTO with default`() {
        val app = Colleen()
        app.get("/users", ::queryOptionalDtoHandler)

        val response = TestClient(app).get("/users").send()

        assertEquals(200, response.status)
        assertEquals("User: Guest, Age: 0", response.text())
    }

    @Test
    fun `Query - extract custom DTO with partial fields`() {
        val app = Colleen()
        app.get("/users", ::queryDtoSimpleHandler)

        val response = TestClient(app)
            .get("/users")
            .query("name", "Charlie")
            // missing age field
            .send()

        // Behavior depends on DTO field nullability/defaults
        // This tests the framework's handling of incomplete data
        assertEquals(400, response.status) // or appropriate error handling
    }

    // ========================================================================
    // Header Parameter Tests
    // ========================================================================

    @Test
    fun `Header - extract header value`() {
        val app = Colleen()
        app.get("/api", ::headerHandler)

        val response = TestClient(app)
            .get("/api")
            .header("Authorization", "Bearer token123")
            .send()

        assertEquals(200, response.status)
        assertEquals("Auth: Bearer token123", response.text())
    }

    @Test
    fun `Header - extract missing header returns null`() {
        val app = Colleen()
        app.get("/api", ::headerHandler)

        val response = TestClient(app).get("/api").send()

        assertEquals(200, response.status)
        assertEquals("Auth: null", response.text())
    }

    @Test
    fun `Header - extract multiple headers`() {
        val app = Colleen()
        app.get("/api", ::multipleHeadersHandler)

        val response = TestClient(app)
            .get("/api")
            .header("X-Request-ID", "req-123")
            .header("X-Trace-ID", "trace-456")
            .send()

        assertEquals(200, response.status)
        assertEquals("RequestID: req-123, TraceID: trace-456", response.text())
    }

    // ========================================================================
    // Cookie Parameter Tests
    // ========================================================================

    @Test
    fun `Cookie - extract cookie value`() {
        val app = Colleen()
        app.get("/session", ::cookieHandler)

        val response = TestClient(app)
            .get("/session")
            .cookie("sessionId", "abc123")
            .send()

        assertEquals(200, response.status)
        assertEquals("Session: abc123", response.text())
    }

    @Test
    fun `Cookie - extract missing cookie returns null`() {
        val app = Colleen()
        app.get("/session", ::cookieHandler)

        val response = TestClient(app).get("/session").send()

        assertEquals(200, response.status)
        assertEquals("Session: null", response.text())
    }

    @Test
    fun `Cookie - extract multiple cookies`() {
        val app = Colleen()
        app.get("/auth", ::multipleCookiesHandler)

        val response = TestClient(app)
            .get("/auth")
            .cookie("sessionId", "session123")
            .cookie("csrfToken", "csrf456")
            .send()

        assertEquals(200, response.status)
        assertEquals("Session: session123, CSRF: csrf456", response.text())
    }

    // ========================================================================
    // Text Body Parameter Tests
    // ========================================================================

    @Test
    fun `Text - extract text body`() {
        val app = Colleen()
        app.post("/echo", ::textHandler)

        val response = TestClient(app)
            .post("/echo")
            .body("Hello World")
            .send()

        assertEquals(200, response.status)
        assertEquals("Body: Hello World", response.text())
    }

    @Test
    fun `Text - extract empty body returns null`() {
        val app = Colleen()
        app.post("/echo", ::textHandler)

        val response = TestClient(app).post("/echo").send()

        assertEquals(200, response.status)
        assertEquals("Body: null", response.text())
    }

    // ========================================================================
    // JSON Body Parameter Tests
    // ========================================================================

    @Test
    fun `Json - extract JSON object`() {
        val app = Colleen()
        app.post("/users", ::jsonHandler)

        val response = TestClient(app)
            .post("/users")
            .json(mapOf("name" to "Alice", "age" to 30))
            .send()

        assertEquals(200, response.status)
        assertEquals("User: Alice, Age: 30", response.text())
    }

    @Test
    fun `Json - extract nullable JSON object`() {
        val app = Colleen()
        app.post("/users", ::nullableJsonHandler)

        val response = TestClient(app)
            .post("/users")
            .json(mapOf("name" to "Alice", "age" to 30))
            .send()

        assertEquals(200, response.status)
        assertEquals("User: Alice, Age: 30", response.text())
    }

    @Test
    fun `Json - missing required json throws exception`() {
        val app = Colleen()
        app.post("/users", ::jsonHandler)

        val response = TestClient(app)
            .post("/users")
            .header("accept", "application/json")
            .send()

        assertContains(response.text()!!, "Missing required JSON body")
        assertEquals(400, response.status)
    }

    @Test
    fun `Json - missing nullable json throws no exception`() {
        val app = Colleen()
        app.post("/users", ::nullableJsonHandler)

        val response = TestClient(app)
            .post("/users")
            .header("accept", "application/json")
            .send()

        assertEquals(response.text(), "User: null")
        assertEquals(200, response.status)
    }

    @Test
    fun `Json - extract JSON array`() {
        val app = Colleen()
        app.post("/batch", ::jsonListHandler)

        val response = TestClient(app)
            .post("/batch")
            .json(listOf(1, 2, 3))
            .send()

        assertEquals(200, response.status)
        assertEquals("Items: [1, 2, 3]", response.text())
    }

    // ========================================================================
    // JSON Body Parameter Tests - Custom DTO
    // ========================================================================

    @Test
    fun `Json - extract simple DTO`() {
        val app = Colleen()
        app.post("/users", ::jsonDtoSimpleHandler)

        val response = TestClient(app)
            .post("/users")
            .json(mapOf("name" to "Alice", "age" to 30))
            .send()

        assertEquals(200, response.status)
        assertEquals("Created user: Alice, age 30", response.text())
    }

    @Test
    fun `Json - extract DTO with List field`() {
        val app = Colleen()
        app.post("/users", ::jsonDtoListHandler)

        val response = TestClient(app)
            .post("/users")
            .json(
                mapOf(
                    "name" to "Bob",
                    "emails" to listOf("bob@example.com", "bob@work.com")
                )
            )
            .send()

        assertEquals(200, response.status)
        assertEquals("User Bob has 2 emails", response.text())
    }

    @Test
    fun `Json - extract nested DTO`() {
        val app = Colleen()
        app.post("/orders", ::jsonNestedDtoHandler)

        val response = TestClient(app)
            .post("/orders")
            .json(
                mapOf(
                    "orderId" to "ORD-123",
                    "customer" to mapOf(
                        "name" to "Charlie",
                        "age" to 35
                    ),
                    "total" to 299.99
                )
            )
            .send()

        assertEquals(200, response.status)
        assertEquals("Order ORD-123 for Charlie, total: 299.99", response.text())
    }

    @Test
    fun `Json - extract deeply nested DTO`() {
        val app = Colleen()
        app.post("/organizations", ::jsonDeeplyNestedHandler)

        val response = TestClient(app)
            .post("/organizations")
            .json(
                mapOf(
                    "orgName" to "TechCorp",
                    "department" to mapOf(
                        "deptName" to "Engineering",
                        "manager" to mapOf(
                            "name" to "David",
                            "age" to 40
                        ),
                        "members" to listOf(
                            mapOf("name" to "Eve", "age" to 28),
                            mapOf("name" to "Frank", "age" to 32)
                        )
                    )
                )
            )
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("TechCorp"))
        assertTrue(text.contains("Engineering"))
        assertTrue(text.contains("David"))
        assertTrue(text.contains("2 members"))
    }

    @Test
    fun `Json - extract DTO with nullable fields`() {
        val app = Colleen()
        app.post("/users", ::jsonDtoNullableFieldsHandler)

        val response = TestClient(app)
            .post("/users")
            .json(
                mapOf(
                    "name" to "Grace",
                    "age" to 25
                    // middleName is null
                )
            )
            .send()

        assertEquals(200, response.status)
        assertEquals("User: Grace, middle: null", response.text())
    }

    @Test
    fun `Json - extract nullable DTO without body`() {
        val app = Colleen()
        app.post("/data", ::jsonNullableDtoHandler)

        val response = TestClient(app).post("/data").send()

        assertEquals(200, response.status)
        assertEquals("Data: null", response.text())
    }

    @Test
    fun `Json - extract DTO with Map field`() {
        val app = Colleen()
        app.post("/config", ::jsonDtoMapHandler)

        val response = TestClient(app)
            .post("/config")
            .json(
                mapOf(
                    "appName" to "MyApp",
                    "settings" to mapOf(
                        "theme" to "dark",
                        "language" to "en",
                        "notifications" to "enabled"
                    )
                )
            )
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("MyApp"))
        assertTrue(response.text()!!.contains("3 settings"))
    }

    @Test
    fun `Json - extract DTO with complex nested collections`() {
        val app = Colleen()
        app.post("/batch", ::jsonComplexCollectionHandler)

        val response = TestClient(app)
            .post("/batch")
            .json(
                mapOf(
                    "batchId" to "BATCH-001",
                    "items" to listOf(
                        mapOf(
                            "productId" to "P1",
                            "tags" to listOf("electronics", "sale")
                        ),
                        mapOf(
                            "productId" to "P2",
                            "tags" to listOf("books", "new")
                        )
                    )
                )
            )
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("BATCH-001"))
        assertTrue(response.text()!!.contains("2 items"))
    }

    // ========================================================================
    // Service Injection Tests
    // ========================================================================

    @Test
    fun `Service - inject service dependency`() {
        val app = Colleen()
        val userService = UserService()
        app.provide<UserService>(userService)

        app.get("/users/{id}", ::serviceHandler)

        val response = TestClient(app).get("/users/123").send()

        assertEquals(200, response.status)
        assertEquals("User from service: 123", response.text())
    }

    @Test
    fun `Service - inject multiple services`() {
        val app = Colleen()
        val userService = UserService()
        val authService = AuthService()
        app.provide<UserService>(userService)
        app.provide<AuthService>(authService)

        app.get("/auth/user/{id}", ::multipleServicesHandler)

        val response = TestClient(app)
            .get("/auth/user/456")
            .header("Authorization", "Bearer token")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("Authenticated"))
        assertTrue(response.text()!!.contains("User: 456"))
    }

    @Test
    fun `Service - nullable service without registration`() {
        val app = Colleen()
        // Service not registered

        app.get("/optional", ::nullableServiceHandler)

        val response = TestClient(app).get("/optional").send()

        assertEquals(200, response.status)
        assertEquals("Service: null", response.text())
    }

    @Test
    fun `Service - optional service with default when not registered`() {
        val app = Colleen()
        // Service not registered

        app.get("/default", ::optionalServiceHandler)

        val response = TestClient(app).get("/default").send()

        assertEquals(200, response.status)
        assertEquals("Using default service", response.text())
    }

    @Test
    fun `Service - optional service with registered value`() {
        val app = Colleen()
        val userService = UserService()
        app.provide<UserService>(userService)

        app.get("/default", ::optionalServiceHandler)

        val response = TestClient(app).get("/default").send()

        assertEquals(200, response.status)
        assertEquals("Using injected service", response.text())
    }

    @Test
    fun `Service - required service throws when not registered`() {
        val app = Colleen()
        // Service not registered

        app.get("/required", ::requiredServiceHandler)

        val response = TestClient(app).get("/required").send()

        assertEquals(500, response.status)
    }

    @Test
    fun `Service - mixed with path and query parameters`() {
        val app = Colleen()
        val userService = UserService()
        app.provide<UserService>(userService)

        app.get("/users/{id}", ::serviceMixedParamsHandler)

        val response = TestClient(app)
            .get("/users/789")
            .query("detail", "full")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("User 789"))
        assertTrue(response.text()!!.contains("detail: full"))
    }

    @Test
    fun `Service - mixed with all parameter types`() {
        val app = Colleen()
        val userService = UserService()
        val authService = AuthService()
        app.provide<UserService>(userService)
        app.provide<AuthService>(authService)

        app.post("/api/users/{id}", ::serviceAllParamsHandler)

        val response = TestClient(app)
            .post("/api/users/999")
            .query("notify", "true")
            .header("Authorization", "Bearer token")
            .json(mapOf("name" to "Updated Name"))
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("ID: 999"))
        assertTrue(text.contains("Notify: true"))
        assertTrue(text.contains("Authenticated"))
        assertTrue(text.contains("Updated Name"))
    }

    @Test
    fun `Service - with Context injection`() {
        val app = Colleen()
        val userService = UserService()
        app.provide<UserService>(userService)

        app.get("/context-service", ::serviceWithContextHandler)

        val response = TestClient(app)
            .get("/context-service")
            .header("X-Request-ID", "req-123")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("RequestID: req-123"))
        assertTrue(response.text()!!.contains("Service available"))
    }

    @Test
    fun `Service - inject generic service dependency`() {
        val app = Colleen()
        val boxService = BoxService<String>()
        boxService.add("foo")
        boxService.add("bar")
        app.provide<BoxService<String>>(boxService)

        app.get("/boxes/{id}", ::genericServiceHandler)

        val response = TestClient(app).get("/boxes/1").send()

        assertEquals(200, response.status)
        assertEquals("Box from service: bar", response.text())
    }

    // ========================================================================
    // Form Parameter Tests
    // ========================================================================

    @Test
    fun `Form - extract single form field`() {
        val app = Colleen()
        app.post("/login", ::formStringHandler)

        val response = TestClient(app)
            .post("/login")
            .form(mapOf("username" to "alice"))
            .send()

        assertEquals(200, response.status)
        assertEquals("Username: alice", response.text())
    }

    @Test
    fun `Form - extract nullable form field without value`() {
        val app = Colleen()
        app.post("/login", ::formNullableHandler)

        val response = TestClient(app)
            .post("/login")
            .form(mapOf())
            .send()

        assertEquals(200, response.status)
        assertEquals("Username: null", response.text())
    }

    @Test
    fun `Form - extract optional form field with default`() {
        val app = Colleen()
        app.post("/login", ::formOptionalHandler)

        val response = TestClient(app)
            .post("/login")
            .form(mapOf())
            .send()

        assertEquals(200, response.status)
        assertEquals("Username: guest", response.text())
    }

    @Test
    fun `Form - extract List of form values`() {
        val app = Colleen()
        app.post("/tags", ::formListHandler)

        val response = TestClient(app)
            .post("/tags")
            .field("tags", "kotlin")
            .field("tags", "java")
            .send()

        assertEquals(200, response.status)
        assertEquals("Tags: [kotlin, java]", response.text())
    }

    @Test
    fun `Form - extract Map of form fields`() {
        val app = Colleen()
        app.post("/profile", ::formMapHandler)

        val response = TestClient(app)
            .post("/profile")
            .form(mapOf("name" to "Alice", "city" to "Tokyo"))
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("name=Alice"))
        assertTrue(text.contains("city=Tokyo"))
    }

    // ========================================================================
    // Form Parameter Tests - Custom DTO
    // ========================================================================

    @Test
    fun `Form - extract custom DTO with simple fields`() {
        val app = Colleen()
        app.post("/register", ::formDtoSimpleHandler)

        val response = TestClient(app)
            .post("/register")
            .form(mapOf("username" to "alice", "email" to "alice@example.com"))
            .send()

        assertEquals(200, response.status)
        assertEquals("Registered: alice (alice@example.com)", response.text())
    }

    @Test
    fun `Form - extract custom DTO with List field`() {
        val app = Colleen()
        app.post("/interests", ::formDtoListHandler)

        val response = TestClient(app)
            .post("/interests")
            .field("userId", "123")
            .field("hobbies", "reading")
            .field("hobbies", "coding")
            .field("hobbies", "gaming")
            .send()

        assertEquals(200, response.status)
        assertEquals("User 123 hobbies: [reading, coding, gaming]", response.text())
    }

    @Test
    fun `Form - extract custom DTO with mixed types`() {
        val app = Colleen()
        app.post("/profile", ::formDtoMixedHandler)

        val response = TestClient(app)
            .post("/profile")
            .field("name", "David")
            .field("age", "25")
            .field("active", "true")
            .field("roles", "admin")
            .field("roles", "user")
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("Name: David"))
        assertTrue(text.contains("Age: 25"))
        assertTrue(text.contains("Active: true"))
        assertTrue(text.contains("Roles: [admin, user]"))
    }

    @Test
    fun `Form - extract nullable custom DTO without value`() {
        val app = Colleen()
        app.post("/optional", ::formNullableDtoHandler)

        val response = TestClient(app)
            .post("/optional")
            .form(mapOf())
            .send()

        assertEquals(200, response.status)
        assertEquals("Data: null", response.text())
    }

    // ========================================================================
    // File Upload Parameter Tests
    // ========================================================================

    @Test
    fun `UploadedFile - extract uploaded file`() {
        val app = Colleen()
        app.post("/upload", ::fileUploadHandler)

        val fileContent = "Hello, World!".toByteArray()
        val response = TestClient(app)
            .post("/upload")
            .file("file", "test.txt", fileContent, "text/plain")
            .send()

        assertEquals(200, response.status)
        assertEquals("File: test.txt, Size: 13", response.text())
    }

    @Test
    fun `UploadedFile - missing file returns null`() {
        val app = Colleen()
        app.post("/upload", ::fileUploadHandler)

        val response = TestClient(app).post("/upload").send()

        assertEquals(200, response.status)
        assertEquals("File: null, Size: null", response.text())
    }

    @Test
    fun `UploadedFile - custom parameter name`() {
        val app = Colleen()
        app.post("/upload", ::fileCustomNameHandler)

        val fileContent = "Test".toByteArray()
        val response = TestClient(app)
            .post("/upload")
            .file("document", "doc.pdf", fileContent, "application/pdf")
            .send()

        assertEquals(200, response.status)
        assertEquals("Document: doc.pdf", response.text())
    }

    // ========================================================================
    // Mixed Parameter Tests
    // ========================================================================

    @Test
    fun `Mixed - path, query, and header parameters`() {
        val app = Colleen()
        app.get("/users/{id}", ::mixedHandler)

        val response = TestClient(app)
            .get("/users/123")
            .query("format", "json")
            .header("Accept-Language", "en-US")
            .send()

        assertEquals(200, response.status)
        assertEquals("ID: 123, Format: json, Lang: en-US", response.text())
    }

    @Test
    fun `Mixed - all parameter types`() {
        val app = Colleen()
        app.post("/api/{resource}", ::allParamsHandler)

        val response = TestClient(app)
            .post("/api/users")
            .query("page", "1")
            .header("Authorization", "Bearer token")
            .cookie("session", "abc")
            .json(mapOf("action" to "create"))
            .send()

        assertEquals(200, response.status)
        val text = response.text()!!
        assertTrue(text.contains("Resource: users"))
        assertTrue(text.contains("Page: 1"))
        assertTrue(text.contains("Auth: Bearer token"))
        assertTrue(text.contains("Session: abc"))
        assertTrue(text.contains("Action: create"))
    }

    // ========================================================================
    // Context Parameter Tests
    // ========================================================================

    @Test
    fun `Context - inject Context parameter`() {
        val app = Colleen()
        app.get("/context", ::contextHandler)

        val response = TestClient(app)
            .get("/context")
            .header("X-Custom", "value")
            .send()

        assertEquals(200, response.status)
        assertEquals("Method: GET, Custom: value", response.text())
    }

    @Test
    fun `Context - mixed with other parameters`() {
        val app = Colleen()
        app.get("/users/{id}", ::contextMixedHandler)

        val response = TestClient(app)
            .get("/users/456")
            .query("fields", "name,email")
            .send()

        assertEquals(200, response.status)
        assertTrue(response.text()!!.contains("ID: 456"))
        assertTrue(response.text()!!.contains("Fields: name,email"))
        assertTrue(response.text()!!.contains("Path: /users/456"))
    }

    // ========================================================================
    // Edge Cases and Error Handling
    // ========================================================================

    @Test
    fun `Edge - empty string path parameter`() {
        val app = Colleen()
        app.get("/search/{query}", ::pathStringHandler)

        val response = TestClient(app).get("/search/").send()

        assertEquals(404, response.status)
    }

    @Test
    fun `Edge - special characters in query parameter`() {
        val app = Colleen()
        app.get("/search", ::queryStringHandler)

        val response = TestClient(app)
            .get("/search")
            .query("q", "hello world & special chars!")
            .send()

        assertEquals(200, response.status)
        assertEquals("Query: hello world & special chars!", response.text())
    }

    @Test
    fun `Edge - multiple query parameters with same name`() {
        val app = Colleen()
        app.get("/filter", ::queryListStringHandler)

        val response = TestClient(app)
            .get("/filter")
            .query("tags", "a")
            .query("tags", "b")
            .query("tags", "c")
            .send()

        assertEquals(200, response.status)
        assertEquals("Tags: [a, b, c]", response.text())
    }

    @Test
    fun `Edge - Float type conversion`() {
        val app = Colleen()
        app.get("/price", ::queryFloatHandler)

        val response = TestClient(app)
            .get("/price")
            .query("amount", "12.5")
            .send()

        assertEquals(200, response.status)
        assertEquals("Amount: 12.5", response.text())
    }

    @Test
    fun `Edge - zero value for numeric types`() {
        val app = Colleen()
        app.get("/count", ::queryIntHandler)

        val response = TestClient(app)
            .get("/count")
            .query("page", "0")
            .send()

        assertEquals(200, response.status)
        assertEquals("Page: 0", response.text())
    }

    @Test
    fun `Edge - negative value for numeric types`() {
        val app = Colleen()
        app.get("/offset", ::queryIntHandler)

        val response = TestClient(app)
            .get("/offset")
            .query("page", "-5")
            .send()

        assertEquals(200, response.status)
        assertEquals("Page: -5", response.text())
    }

    @Test
    fun `Edge - very large number for Long`() {
        val app = Colleen()
        app.get("/id", ::queryLongHandler)

        val response = TestClient(app)
            .get("/id")
            .query("ts", "9223372036854775807")
            .send()

        assertEquals(200, response.status)
        assertEquals("Timestamp: 9223372036854775807", response.text())
    }

    @Test
    fun `Edge - scientific notation for Double`() {
        val app = Colleen()
        app.get("/value", ::queryDoubleHandler)

        val response = TestClient(app)
            .get("/value")
            .query("amount", "1.23e5")
            .send()

        assertEquals(200, response.status)
        assertEquals("Amount: 123000.0", response.text())
    }

    @Test
    fun `Edge - Parameter extractor types must NOT be nullable`() {
        val app = Colleen()
        assertThrows<IllegalArgumentException> {
            app.get("/search", ::queryNullableWrapperHandler)
        }
    }

    // ========================================================================
    // Handler Functions
    // ========================================================================

    // Path handlers
    fun pathIntHandler(id: Path<Int>) = "User ID: ${id.value}"
    fun pathStringHandler(username: Path<String>) = "Username: ${username.value}"
    fun pathLongHandler(orderId: Path<Long>) = "Order ID: ${orderId.value}"
    fun pathDoubleHandler(amount: Path<Double>) = "Price: ${amount.value}"
    fun pathBooleanHandler(enabled: Path<Boolean>) = "Enabled: ${enabled.value}"
    fun pathNullableHandler(code: Path<String>) = "Code: ${code.value}"

    // Query handlers - simple types
    fun queryStringHandler(q: Query<String>) = "Query: ${q.value}"
    fun queryIntHandler(page: Query<Int>) = "Page: ${page.value}"
    fun queryLongHandler(ts: Query<Long>) = "Timestamp: ${ts.value}"
    fun queryDoubleHandler(amount: Query<Double>) = "Amount: ${amount.value}"
    fun queryFloatHandler(amount: Query<Float>) = "Amount: ${amount.value}"
    fun queryBooleanHandler(active: Query<Boolean>) = "Active: ${active.value}"
    fun queryNullableHandler(q: Query<String?>) = "Query: ${q.value}"
    fun queryOptionalHandler(q: Query<String> = Query("default")) = "Query: ${q.value}"
    fun queryNullableWrapperHandler(q: Query<String>?) = "Query: ${q?.value}"

    // Query handlers - List types
    fun queryListStringHandler(tags: Query<List<String>>) = "Tags: ${tags.value}"
    fun queryListIntHandler(ids: Query<List<Int>>) = "IDs: ${ids.value}"
    fun queryNullableListHandler(tags: Query<List<String>?>) = "Tags: ${tags.value}"
    fun queryOptionalListHandler(tags: Query<List<String>> = Query(emptyList())) = "Tags: ${tags.value}"

    // Query handlers - Map types
    fun queryMapStringHandler(filters: Query<Map<String, String>>) = "Filters: ${filters.value}"
    fun queryMapListHandler(filters: Query<Map<String, List<String>>>) = "Filters: ${filters.value}"
    fun queryNullableMapHandler(filters: Query<Map<String, String>?>) = "Filters: ${filters.value}"
    fun queryOptionalMapHandler(filters: Query<Map<String, String>> = Query(emptyMap())) =
        "Filters: ${filters.value}"

    // Header handlers
    fun headerHandler(@Param("Authorization") auth: Header) = "Auth: ${auth.value}"
    fun multipleHeadersHandler(
        @Param("X-Request-ID") requestId: Header,
        @Param("X-Trace-ID") traceId: Header
    ) = "RequestID: ${requestId.value}, TraceID: ${traceId.value}"

    // Cookie handlers
    fun cookieHandler(@Param("sessionId") session: Cookie) = "Session: ${session.value}"
    fun multipleCookiesHandler(
        @Param("sessionId") session: Cookie,
        @Param("csrfToken") csrf: Cookie
    ) = "Session: ${session.value}, CSRF: ${csrf.value}"

    // Text body handlers
    fun textHandler(body: Text) = "Body: ${body.value}"

    // JSON body handlers
    fun jsonHandler(user: Json<Map<String, Any>>) = "User: ${user.value["name"]}, Age: ${user.value["age"]}"
    fun nullableJsonHandler(user: Json<Map<String, Any>?>) =
        user.value?.let { "User: ${user.value["name"]}, Age: ${user.value["age"]}" } ?: "User: null"

    fun jsonListHandler(items: Json<List<Int>>) = "Items: ${items.value}"

    // ========================================================================
    // Data classes for JSON DTO Tests
    // ========================================================================

    data class UserWithEmails(val name: String, val emails: List<String>)
    data class OrderDto(val orderId: String, val customer: UserDto, val total: Double)
    data class DepartmentDto(val deptName: String, val manager: UserDto, val members: List<UserDto>)
    data class OrganizationDto(val orgName: String, val department: DepartmentDto)
    data class UserWithNullableFields(val name: String, val age: Int, val middleName: String? = null)
    data class ConfigDto(val appName: String, val settings: Map<String, String>)
    data class ProductItem(val productId: String, val tags: List<String>)
    data class BatchDto(val batchId: String, val items: List<ProductItem>)


    // Form handlers
    fun formStringHandler(username: Form<String>) = "Username: ${username.value}"
    fun formNullableHandler(username: Form<String?>) = "Username: ${username.value}"
    fun formOptionalHandler(username: Form<String> = Form("guest")) = "Username: ${username.value}"
    fun formListHandler(tags: Form<List<String>>) = "Tags: ${tags.value}"
    fun formMapHandler(profile: Form<Map<String, String>>) = "Profile: ${profile.value}"

    // File upload handlers
    fun fileUploadHandler(file: UploadedFile) = "File: ${file.value?.filename}, Size: ${file.value?.size}"
    fun fileCustomNameHandler(@Param("document") doc: UploadedFile) = "Document: ${doc.value?.filename}"

    // Mixed parameter handlers
    fun mixedHandler(
        id: Path<Int>,
        format: Query<String>,
        @Param("Accept-Language") lang: Header
    ) = "ID: ${id.value}, Format: ${format.value}, Lang: ${lang.value}"

    fun allParamsHandler(
        resource: Path<String>,
        page: Query<Int>,
        @Param("Authorization") auth: Header,
        session: Cookie,
        data: Json<Map<String, String>>
    ) =
        "Resource: ${resource.value}, Page: ${page.value}, Auth: ${auth.value}, Session: ${session.value}, Action: ${data.value["action"]}"

    // Context handlers
    fun contextHandler(ctx: Context) = "Method: ${ctx.method}, Custom: ${ctx.header("X-Custom")}"
    fun contextMixedHandler(
        id: Path<Int>,
        fields: Query<String>,
        ctx: Context
    ) = "ID: ${id.value}, Fields: ${fields.value}, Path: ${ctx.path}"

    // Data classes for JSON tests
    data class UserRequest(val name: String, val age: Int)

    // ========================================================================
    // Handler Functions for Custom DTO Tests
    // ========================================================================

    // Query DTO handlers
    fun queryDtoSimpleHandler(user: Query<UserDto>) = "User: ${user.value.name}, Age: ${user.value.age}"
    fun queryDtoListHandler(user: Query<UserWithAddresses>) = "User: ${user.value.name}, Addresses: ${user.value.addr}"
    fun queryDtoMixedHandler(filter: Query<SearchFilter>) =
        "Keyword: ${filter.value.keyword}, Price: ${filter.value.minPrice}-${filter.value.maxPrice}, InStock: ${filter.value.inStock}, Tags: ${filter.value.tags}"

    fun queryNullableDtoHandler(user: Query<UserDto?>) = "User: ${user.value?.name}"
    fun queryOptionalDtoHandler(user: Query<UserDto> = Query(UserDto("Guest", 0))) =
        "User: ${user.value.name}, Age: ${user.value.age}"

    // Form DTO handlers
    fun formDtoSimpleHandler(form: Form<RegistrationForm>) = "Registered: ${form.value.username} (${form.value.email})"
    fun formDtoListHandler(form: Form<UserInterests>) = "User ${form.value.userId} hobbies: ${form.value.hobbies}"
    fun formDtoMixedHandler(profile: Form<ProfileForm>) =
        "Name: ${profile.value.name}, Age: ${profile.value.age}, Active: ${profile.value.active}, Roles: ${profile.value.roles}"

    fun formNullableDtoHandler(data: Form<UserDto?>) = "Data: ${data.value}"

    // Data classes
    data class UserDto(val name: String, val age: Int)
    data class UserWithAddresses(val name: String, val addr: List<String>)
    data class SearchFilter(
        val keyword: String,
        val minPrice: Int,
        val maxPrice: Int,
        val inStock: Boolean,
        val tags: List<String>
    )

    data class RegistrationForm(val username: String, val email: String)
    data class UserInterests(val userId: String, val hobbies: List<String>)
    data class ProfileForm(
        val name: String,
        val age: Int,
        val active: Boolean,
        val roles: List<String>
    )

    // ========================================================================
    // Handler Functions for JSON DTO Tests
    // ========================================================================

    fun jsonDtoSimpleHandler(user: Json<UserDto>) = "Created user: ${user.value.name}, age ${user.value.age}"
    fun jsonDtoListHandler(user: Json<UserWithEmails>) =
        "User ${user.value.name} has ${user.value.emails.size} emails"

    fun jsonNestedDtoHandler(order: Json<OrderDto>) =
        "Order ${order.value.orderId} for ${order.value.customer.name}, total: ${order.value.total}"

    fun jsonDeeplyNestedHandler(org: Json<OrganizationDto>) =
        "${org.value.orgName} - ${org.value.department.deptName} managed by ${org.value.department.manager.name}, ${org.value.department.members.size} members"

    fun jsonDtoNullableFieldsHandler(user: Json<UserWithNullableFields>) =
        "User: ${user.value.name}, middle: ${user.value.middleName}"

    fun jsonNullableDtoHandler(data: Json<UserDto?>) = "Data: ${data.value?.name}"
    fun jsonDtoMapHandler(config: Json<ConfigDto>) =
        "${config.value.appName} with ${config.value.settings.size} settings"

    fun jsonComplexCollectionHandler(batch: Json<BatchDto>) =
        "${batch.value.batchId}: ${batch.value.items.size} items"

    // ========================================================================
    // Handler Functions for Service Tests
    // ========================================================================

    fun serviceHandler(id: Path<Int>, userService: UserService) =
        "User from service: ${userService.getUser(id.value)}"

    fun genericServiceHandler(id: Path<Int>, boxService: BoxService<String>) =
        "Box from service: ${boxService.get(id.value)}"

    fun multipleServicesHandler(
        id: Path<Int>,
        auth: Header,
        userService: UserService,
        authService: AuthService
    ) = "Authenticated: ${authService.verify(auth.value)}, User: ${userService.getUser(id.value)}"

    fun nullableServiceHandler(userService: UserService?) =
        "Service: ${userService?.toString()}"

    fun optionalServiceHandler(userService: UserService? = null) =
        if (userService != null) "Using injected service" else "Using default service"

    fun requiredServiceHandler(userService: UserService) =
        "Service: ${userService.getUser(1)}"

    fun serviceMixedParamsHandler(
        id: Path<Int>,
        detail: Query<String>,
        userService: UserService
    ) = "User ${userService.getUser(id.value)}, detail: ${detail.value}"

    fun serviceAllParamsHandler(
        id: Path<Int>,
        notify: Query<Boolean>,
        auth: Header,
        data: Json<Map<String, String>>,
        userService: UserService,
        authService: AuthService
    ) =
        "ID: ${id.value}, Notify: ${notify.value}, Authenticated: ${authService.verify(auth.value)}, ${data.value["name"]}"

    fun serviceWithContextHandler(
        ctx: Context,
        userService: UserService
    ) = "RequestID: ${ctx.header("X-Request-ID")}, Service available: ${true}"

    // ========================================================================
    // Service classes for Service Tests
    // ========================================================================

    class UserService {
        fun getUser(id: Int): String = id.toString()
    }

    class AuthService {
        fun verify(token: String?): Boolean = token?.startsWith("Bearer") == true
    }

    class BoxService<T> {
        val items: MutableList<T> = mutableListOf()
        fun add(item: T) = items.add(item)
        fun get(idx: Int) = items[idx]
    }
}