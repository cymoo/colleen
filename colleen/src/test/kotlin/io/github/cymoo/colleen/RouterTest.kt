package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

class RouterTest {

    private lateinit var router: Router
    private lateinit var ctx: Context

    @BeforeEach
    fun setup() {
        router = Router()
        ctx = createTestContext()
    }

    private fun createTestContext(
        method: String = "GET",
        path: String = "/",
        headers: Map<String, String> = emptyMap()
    ): Context {
        val headersObj = Headers()
        headers.forEach { (k, v) -> headersObj[k] = v }

        val request = Request(
            method = method,
            path = path,
            headers = headersObj
        )
        return Context(request = request, app = Colleen())
    }

    @Nested
    inner class BasicRoutingTest {

        @Test
        fun `should match simple GET route`() {
            var executed = false
            router.addRoute("GET", "/users") { _ ->
                executed = true
                "users"
            }

            val ctx = createTestContext("GET", "/users")
            router.handleRequest(ctx)

            assertTrue(executed)
            assertEquals("users", (ctx.response.body as ResponseBody.Text).value)
        }

        @Test
        fun `should match POST route`() {
            var executed = false
            router.addRoute("POST", "/users") { _ ->
                executed = true
                mapOf("id" to 1)
            }

            val ctx = createTestContext("POST", "/users")
            router.handleRequest(ctx)

            assertTrue(executed)
            assertTrue(ctx.response.body is ResponseBody.Json)
        }

        @Test
        fun `should match wildcard method`() {
            var executed = false
            router.addRoute("*", "/health") { _ ->
                executed = true
                "ok"
            }

            val ctxGet = createTestContext("GET", "/health")
            router.handleRequest(ctxGet)
            assertTrue(executed)

            executed = false
            val ctxPost = createTestContext("POST", "/health")
            router.handleRequest(ctxPost)
            assertTrue(executed)
        }

        @Test
        fun `should not match wrong method`() {
            router.addRoute("GET", "/users") { _ -> "users" }

            val ctx = createTestContext("POST", "/users")

            assertThrows<MethodNotAllowed> {
                router.handleRequest(ctx)
            }
        }

        @Test
        fun `should not match wrong path`() {
            router.addRoute("GET", "/users") { _ -> "users" }

            val ctx = createTestContext("GET", "/posts")
            assertThrows<NotFound> {
                router.handleRequest(ctx)
            }
        }
    }

    @Nested
    inner class PathParametersTest {

        @Test
        fun `should extract single parameter`() {
            var capturedId: String? = null
            router.addRoute("GET", "/users/{id}") { ctx ->
                capturedId = ctx.pathParam("id")
                "user-$capturedId"
            }

            val ctx = createTestContext("GET", "/users/123")
            router.handleRequest(ctx)

            assertEquals("123", capturedId)
            assertEquals("123", ctx.pathParams["id"])
        }

        @Test
        fun `should extract multiple parameters`() {
            var capturedUserId: String? = null
            var capturedPostId: String? = null

            router.addRoute("GET", "/users/{userId}/posts/{postId}") { ctx ->
                capturedUserId = ctx.pathParam("userId")
                capturedPostId = ctx.pathParam("postId")
                "ok"
            }

            val ctx = createTestContext("GET", "/users/123/posts/456")
            router.handleRequest(ctx)

            assertEquals("123", capturedUserId)
            assertEquals("456", capturedPostId)
        }

        @Test
        fun `should extract wildcard parameter`() {
            var capturedPath: String? = null

            router.addRoute("GET", "/files/{filepath...}") { ctx ->
                capturedPath = ctx.pathParam("filepath")
                "ok"
            }

            val ctx = createTestContext("GET", "/files/docs/readme.md")
            router.handleRequest(ctx)

            assertEquals("docs/readme.md", capturedPath)
        }

        @Test
        fun `should handle empty wildcard`() {
            var capturedPath: String? = null

            router.addRoute("GET", "/files/{filepath...}") { ctx ->
                capturedPath = ctx.pathParam("filepath")
                "ok"
            }

            val ctx = createTestContext("GET", "/files")
            router.handleRequest(ctx)

            assertEquals("", capturedPath)
        }
    }

    @Nested
    inner class RoutePriorityTest {

        @Test
        fun `should prefer exact match over parameter`() {
            var executedRoute = ""

            router.addRoute("GET", "/users/{id}") { _ ->
                executedRoute = "param"
                "param"
            }

            router.addRoute("GET", "/users/admin") { _ ->
                executedRoute = "exact"
                "exact"
            }

            val ctx = createTestContext("GET", "/users/admin")
            router.handleRequest(ctx)

            assertEquals("exact", executedRoute)
        }

        @Test
        fun `should prefer parameter over wildcard`() {
            var executedRoute = ""

            router.addRoute("GET", "/users/{path...}") { _ ->
                executedRoute = "wildcard"
                "wildcard"
            }

            router.addRoute("GET", "/users/{id}") { _ ->
                executedRoute = "param"
                "param"
            }

            val ctx = createTestContext("GET", "/users/123")
            router.handleRequest(ctx)

            assertEquals("param", executedRoute)
        }

        @Test
        fun `should use first registered when same priority`() {
            var executedRoute = ""

            router.addRoute("GET", "/users/{id}") { _ ->
                executedRoute = "first"
                "first"
            }

            router.addRoute("GET", "/users/{userId}") { _ ->
                executedRoute = "second"
                "second"
            }

            val ctx = createTestContext("GET", "/users/123")
            router.handleRequest(ctx)

            assertEquals("first", executedRoute)
        }
    }

    @Nested
    inner class MiddlewareTest {

        @Test
        fun `should execute global middleware`() {
            val executionOrder = mutableListOf<String>()

            router.addGlobalMiddleware { _, next ->
                executionOrder.add("middleware-before")
                next()
                executionOrder.add("middleware-after")
            }

            router.addRoute("GET", "/test") { _ ->
                executionOrder.add("handler")
                "ok"
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertEquals(
                listOf("middleware-before", "handler", "middleware-after"),
                executionOrder
            )
        }

        @Test
        fun `should execute multiple middlewares in order`() {
            val executionOrder = mutableListOf<String>()

            router.addGlobalMiddleware { _, next ->
                executionOrder.add("m1-before")
                next()
                executionOrder.add("m1-after")
            }

            router.addGlobalMiddleware { _, next ->
                executionOrder.add("m2-before")
                next()
                executionOrder.add("m2-after")
            }

            router.addRoute("GET", "/test") { _ ->
                executionOrder.add("handler")
                "ok"
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertEquals(
                listOf(
                    "m1-before", "m2-before", "handler",
                    "m2-after", "m1-after"
                ),
                executionOrder
            )
        }

        @Test
        fun `should execute path prefix middleware only for matching paths`() {
            var adminExecuted = false
            var publicExecuted = false

            router.addPrefixMiddleware("/admin") { _, next ->
                adminExecuted = true
                next()
            }

            router.addPrefixMiddleware("/public") { _, next ->
                publicExecuted = true
                next()
            }

            router.addRoute("GET", "/admin/users") { _ -> "admin" }

            val ctx = createTestContext("GET", "/admin/users")
            router.handleRequest(ctx)

            assertTrue(adminExecuted)
            assertFalse(publicExecuted)
        }

        @Test
        fun `middleware can extract path parameters`() {
            var capturedId: String? = null

            router.addPrefixMiddleware("/users/{id}") { ctx, next ->
                capturedId = ctx.pathParam("id")
                next()
            }

            router.addRoute("GET", "/users/{id}/posts") { _ -> "posts" }

            val ctx = createTestContext("GET", "/users/123/posts")
            router.handleRequest(ctx)

            assertEquals("123", capturedId)
        }

        @Test
        fun `middleware can stop chain without calling next`() {
            val executionOrder = mutableListOf<String>()

            router.addGlobalMiddleware { ctx, _ ->
                executionOrder.add("auth-middleware")
                ctx.status(401)
                ctx.json(mapOf("error" to "Unauthorized"))
            }

            router.addRoute("GET", "/test") { _ ->
                executionOrder.add("handler")
                "ok"
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertEquals(listOf("auth-middleware"), executionOrder)
            assertEquals(401, ctx.response.status)
        }

        @Test
        fun `conditional middleware should only execute when predicate is true`() {
            var executed = false

            router.addConditionMiddleware(
                predicate = { ctx -> ctx.header("X-API-Key") != null }
            ) { _, next ->
                executed = true
                next()
            }

            router.addRoute("GET", "/test") { _ -> "ok" }

            // Without header
            val ctx1 = createTestContext("GET", "/test")
            router.handleRequest(ctx1)
            assertFalse(executed)

            // With header
            val ctx2 = createTestContext("GET", "/test", mapOf("X-API-Key" to "secret"))
            router.handleRequest(ctx2)
            assertTrue(executed)
        }

        @Test
        fun `middleware after parts should execute even when body is set`() {
            val executionOrder = mutableListOf<String>()

            router.addGlobalMiddleware { _, next ->
                executionOrder.add("m1-before")
                next()
                executionOrder.add("m1-after")
            }

            router.addGlobalMiddleware { ctx, next ->
                executionOrder.add("m2-before")
                ctx.text("early response")  // 设置 body
                next()
                executionOrder.add("m2-after")
            }

            router.addRoute("GET", "/test") { _ ->
                executionOrder.add("handler")
                "This should be ignored"
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertEquals(
                listOf("m1-before", "m2-before", "handler", "m2-after", "m1-after"),
                executionOrder
            )
        }
    }

    @Nested
    inner class ReturnValueHandlingTest {

        @Test
        fun `should handle String return value`() {
            router.addRoute("GET", "/test") { _ -> "Hello World" }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertTrue(ctx.response.body is ResponseBody.Text)
            assertEquals("Hello World", (ctx.response.body as ResponseBody.Text).value)
        }

        @Test
        fun `should handle Map return value as JSON`() {
            router.addRoute("GET", "/test") { _ ->
                mapOf("message" to "Hello", "status" to "ok")
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertTrue(ctx.response.body is ResponseBody.Json)
        }

        @Test
        fun `should handle List return value as JSON`() {
            router.addRoute("GET", "/test") { _ ->
                listOf("item1", "item2", "item3")
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertTrue(ctx.response.body is ResponseBody.Json)
        }

        @Test
        fun `should handle Number return value as JSON`() {
            router.addRoute("GET", "/test") { _ -> 205 }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertTrue(ctx.response.body is ResponseBody.Empty)
            assertEquals(205, ctx.response.status)
        }

        @Test
        fun `should handle ByteArray return value`() {
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            router.addRoute("GET", "/test") { _ -> bytes }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertTrue(ctx.response.body is ResponseBody.Bytes)
            assertArrayEquals(bytes, (ctx.response.body as ResponseBody.Bytes).value)
        }

        @Test
        fun `should handle null return value`() {
            router.addRoute("GET", "/test") { ctx ->
                ctx.text("Manual response")
                null
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            assertTrue(ctx.response.body is ResponseBody.Text)
            assertEquals("Manual response", (ctx.response.body as ResponseBody.Text).value)
        }

        @Test
        fun `should not override manually set response`() {
            router.addRoute("GET", "/test") { ctx ->
                ctx.json(mapOf("manual" to true))
                "This should be ignored"
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)

            val data = (ctx.response.body as ResponseBody.Json).value as Map<*, *>
            assertTrue(data["manual"] as Boolean)
        }
    }

    @Nested
    inner class HttpExceptionTest {

        @Test
        fun `should throw 405 for method not allowed`() {
            router.addRoute("GET", "/users") { _ -> "users" }
            router.addRoute("POST", "/users") { _ -> "create user" }

            val ctx = createTestContext("DELETE", "/users")

            val exception = assertThrows<MethodNotAllowed> {
                router.handleRequest(ctx)
            }

            assertEquals(405, exception.status)
            assertTrue(exception.allowedMethods.contains("GET"))
            assertTrue(exception.allowedMethods.contains("POST"))
            assertFalse(exception.allowedMethods.contains("DELETE"))
        }

        @Test
        fun `should execute middleware before throwing 404`() {
            val executionOrder = mutableListOf<String>()

            router.addGlobalMiddleware { _, next ->
                executionOrder.add("middleware-before")
                next()
                executionOrder.add("middleware-after")
            }

            router.addRoute("GET", "/") { _ -> "ok" }

            val ctx = createTestContext("GET", "/non-existence")

            assertThrows<NotFound> {
                router.handleRequest(ctx)
            }

            assertEquals(
                listOf("middleware-before", "middleware-after"),
                executionOrder
            )
        }

        @Test
        fun `should execute middleware before throwing 405`() {
            val executionOrder = mutableListOf<String>()

            router.addGlobalMiddleware { _, next ->
                executionOrder.add("middleware-before")
                next()
                executionOrder.add("middleware-after")
            }

            router.addRoute("GET", "/test") { _ -> "ok" }

            val ctx = createTestContext("POST", "/test")

            assertThrows<MethodNotAllowed> {
                router.handleRequest(ctx)
            }

            assertEquals(
                listOf("middleware-before", "middleware-after"),
                executionOrder
            )
        }

        @Test
        fun `should execute middleware before throwing 404 in sub app`() {
            val executionOrder = mutableListOf<String>()
            val app = Colleen()

            app.use { _, next ->
                executionOrder.add("parent-middleware-before")
                next()
                executionOrder.add("parent-middleware-after")
            }

            val subApp = Colleen()
            subApp.use { _, next ->
                executionOrder.add("child-middleware-before")
                next()
                executionOrder.add("child-middleware-after")
            }
            subApp.get("/test") { _ ->
                executionOrder.add("child-handler")
                "ok"
            }

            app.mount("/api", subApp)

            val ctx = createTestContext("GET", "/api/non-existence")

            app.handleRequest(ctx)

            assertEquals(404, ctx.response.status)

            assertEquals(
                listOf(
                    "parent-middleware-before",
                    "child-middleware-before",
                    "child-middleware-after",
                    "parent-middleware-after"
                ),
                executionOrder
            )
        }

        @Test
        fun `should execute middleware before throwing 405 in sub app`() {
            val executionOrder = mutableListOf<String>()

            val app = Colleen()

            app.use { _, next ->
                executionOrder.add("parent-middleware-before")
                next()
                executionOrder.add("parent-middleware-after")
            }

            val subApp = Colleen()
            subApp.use { _, next ->
                executionOrder.add("child-middleware-before")
                next()
                executionOrder.add("child-middleware-after")
            }
            subApp.get("/test") { _ ->
                executionOrder.add("child-handler")
                "ok"
            }

            app.mount("/api", subApp)

            val ctx = createTestContext("POST", "/api/test")

            app.handleRequest(ctx)

            assertEquals(405, ctx.response.status)

            assertEquals(
                listOf(
                    "parent-middleware-before",
                    "child-middleware-before",
                    "child-middleware-after",
                    "parent-middleware-after"
                ),
                executionOrder
            )
        }
    }

    @Nested
    inner class ErrorHandlingTest {
        @Test
        fun `middleware cannot catch exception thrown by handler`() {
            var err: Throwable? = null
            var err1: Throwable? = null
            router.addGlobalMiddleware { ctx, next ->
                try {
                    next()
                    err1 = ctx.error?.cause
                } catch (e: Exception) {
                    err = e
                }
            }
            router.addRoute("GET", "/test") { _ ->
                throw RuntimeException("error")
            }

            val ctx = createTestContext("GET", "/test")
            assertThrows<RuntimeException> {
                router.handleRequest(ctx)
            }
            assertNull(err)
            assertNotNull(err1)
        }

        @Test
        fun `middleware cannot catch exception thrown by other middleware`() {
            var err: Throwable? = null
            var err1: Throwable? = null
            router.addGlobalMiddleware { ctx, next ->
                try {
                    next()
                    err1 = ctx.error?.cause
                } catch (e: Exception) {
                    err = e
                }
            }
            router.addGlobalMiddleware { ctx, next ->
                next()
                throw RuntimeException("error in middleware")
            }
            router.addRoute("GET", "/test") { _ ->
                "OK"
            }

            val ctx = createTestContext("GET", "/test")
            assertThrows<RuntimeException> {
                router.handleRequest(ctx)
            }
            assertNull(err)
            assertNotNull(err1)
        }

        @Test
        fun `middleware can handle exception thrown by handler`() {
            var err: Throwable? = null
            router.addGlobalMiddleware { ctx, next ->
                next()
                err = ctx.error?.cause ?: return@addGlobalMiddleware
                ctx.error!!.handled = true
            }
            router.addRoute("GET", "/test") { _ ->
                throw RuntimeException("error")
            }

            val ctx = createTestContext("GET", "/test")
            router.handleRequest(ctx)
            assertNotNull(err)
        }
    }

    @Nested
    inner class MountTest {

        @Test
        fun `should mount sub-app`() {
            val subApp = Colleen()
            subApp.get("/users") { _ -> "sub app users" }

            router.addMount(MountNode.of("/api", subApp))

            val ctx = createTestContext("GET", "/api/users")
            router.handleRequest(ctx)

            assertTrue(ctx.response.body is ResponseBody.Text)
            assertEquals("sub app users", (ctx.response.body as ResponseBody.Text).value)
        }

        @Test
        fun `should execute parent middleware before sub-app`() {
            val executionOrder = mutableListOf<String>()

            router.addGlobalMiddleware { _, next ->
                executionOrder.add("parent-middleware-before")
                next()
                executionOrder.add("parent-middleware-after")
            }

            val subApp = Colleen()
            subApp.use { _, next ->
                executionOrder.add("child-middleware-before")
                next()
                executionOrder.add("child-middleware-after")
            }
            subApp.get("/test") { _ ->
                executionOrder.add("child-handler")
                "ok"
            }

            router.addMount(MountNode.of("/api", subApp))

            val ctx = createTestContext("GET", "/api/test")
            router.handleRequest(ctx)

            assertEquals(
                listOf(
                    "parent-middleware-before",
                    "child-middleware-before",
                    "child-handler",
                    "child-middleware-after",
                    "parent-middleware-after"
                ),
                executionOrder
            )
        }

        @Test
        fun `should reject mount path with parameters`() {
            val subApp = Colleen()

            assertThrows<IllegalArgumentException> {
                router.addMount(MountNode.of("/users/{id}", subApp))
            }
        }

        @Test
        fun `should calculate sub-path correctly`() {
            var capturedSubPath: String? = null

            val subApp = Colleen()
            subApp.get("/users/profile") { ctx ->
                capturedSubPath = ctx.path
                "profile"
            }

            router.addMount(MountNode.of("/api/v1", subApp))

            val ctx = createTestContext("GET", "/api/v1/users/profile")
            router.handleRequest(ctx)

            assertEquals("/users/profile", capturedSubPath)
        }

        @Test
        fun `should mount multiple sub-app at the same path`() {
            val app = Colleen()
            val subApp1 = Colleen()
            subApp1.get("/") { "sub1" }
            subApp1.get("/foo") { "foo" }

            val subApp2 = Colleen()
            subApp2.get("/") { "sub2" }
            subApp2.get("/bar") { "bar" }

            app.mount("/sub", subApp1)
            app.mount("/sub", subApp2)

            var ctx = createTestContext("GET", "/sub")
            app.handleRequest(ctx)
            assertEquals((ctx.response.body as ResponseBody.Text).value, "sub1")

            ctx = createTestContext("GET", "/sub/foo")
            app.handleRequest(ctx)
            assertEquals((ctx.response.body as ResponseBody.Text).value, "foo")

            ctx = createTestContext("GET", "/sub/bar")
            app.handleRequest(ctx)
            assertEquals((ctx.response.body as ResponseBody.Text).value, "bar")
        }
    }
}

class RouteBuilderTest {

    private lateinit var app: Colleen

    @BeforeEach
    fun setup() {
        app = Colleen()
    }

    private fun createTestContext(
        method: String = "GET",
        path: String = "/"
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers()
        )
        return Context(request = request, app = Colleen())
    }

    @Test
    fun `should build routes with prefix`() {
        val builder = RouteBuilder(app, "/api")
        builder.get("/users") { _ -> "users" }

        val ctx = createTestContext("GET", "/api/users")
        app.handleRequest(ctx)

        assertTrue(ctx.response.body is ResponseBody.Text)
    }

    @Test
    fun `should support nested groups`() {
        val builder = RouteBuilder(app, "/api")
        builder.group("/v1") {
            get("/users") { _ -> "v1 users" }
        }

        val ctx = createTestContext("GET", "/api/v1/users")
        app.handleRequest(ctx)

        assertTrue(ctx.response.body is ResponseBody.Text)
    }

    @Test
    fun `should add middleware with prefix`() {
        var executed = false

        val builder = RouteBuilder(app, "/api")
        builder.use { _, next ->
            executed = true
            next()
        }
        builder.get("/users") { _ -> "users" }

        val ctx = createTestContext("GET", "/api/users")
        app.handleRequest(ctx)

        assertTrue(executed)
    }

    @Test
    fun `should support all HTTP methods`() {
        val builder = RouteBuilder(app, "/api")

        builder.get("/get") { _ -> "GET" }
        builder.post("/post") { _ -> "POST" }
        builder.put("/put") { _ -> "PUT" }
        builder.delete("/delete") { _ -> "DELETE" }
        builder.patch("/patch") { _ -> "PATCH" }
        builder.head("/head") { _ -> "HEAD" }
        builder.options("/options") { _ -> "OPTIONS" }
        builder.all("/all") { _ -> "ALL" }

        listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").forEach { method ->
            val path = "/api/${method.lowercase()}"
            val ctx = createTestContext(method, path)
            app.handleRequest(ctx)
            assertTrue(ctx.response.isBodySet, "Failed for $method")
        }

        // Test ALL
        val ctxAll = createTestContext("GET", "/api/all")
        app.handleRequest(ctxAll)
        assertTrue(ctxAll.response.isBodySet)
    }
}

class IntegrationTest {

    @Test
    fun `should handle complex real-world scenario`() {
        val router = Router()
        val executionLog = mutableListOf<String>()

        router.addGlobalMiddleware { ctx, next ->
            executionLog.add("LOG: ${ctx.method} ${ctx.path}")
            next()
        }

        router.addPrefixMiddleware("/api") { ctx, next ->
            val token = ctx.header("Authorization")
            if (token == null) {
                executionLog.add("AUTH: Missing token")
                ctx.status(401)
                ctx.json(mapOf("error" to "Unauthorized"))
                return@addPrefixMiddleware
            }
            executionLog.add("AUTH: Validated")
            ctx.setState("user", mapOf("id" to "user123"))
            next()
        }

        router.addRoute("GET", "/health") { _ ->
            executionLog.add("HANDLER: health")
            mapOf("status" to "ok")
        }

        router.addRoute("GET", "/api/profile") { ctx ->
            executionLog.add("HANDLER: profile")
            val user = ctx.getState<Map<String, String>>("user")
            mapOf("userId" to user["id"])
        }

        router.addRoute("GET", "/api/users/{id}") { ctx ->
            executionLog.add("HANDLER: user detail")
            mapOf("id" to ctx.pathParam("id"))
        }

        router.addRoute("GET", "/static/{filepath...}") { ctx ->
            executionLog.add("HANDLER: static file")
            "File: ${ctx.pathParam("filepath")}"
        }

        val adminApp = Colleen()
        adminApp.use { _, next ->
            executionLog.add("ADMIN_MIDDLEWARE")
            next()
        }
        adminApp.get("/dashboard") { _ ->
            executionLog.add("HANDLER: admin dashboard")
            "Admin Dashboard"
        }
        router.addMount(MountNode.of("/admin", adminApp))

        // Test 1: Public route (no auth)
        executionLog.clear()
        val ctx1 = createTestContext("GET", "/health")
        router.handleRequest(ctx1)
        assertEquals(200, ctx1.response.status)
        assertTrue(executionLog.contains("HANDLER: health"))
        assertFalse(executionLog.any { it.startsWith("AUTH:") })

        // Test 2: Protected route without token (401)
        executionLog.clear()
        val ctx2 = createTestContext("GET", "/api/profile")
        router.handleRequest(ctx2)
        assertEquals(401, ctx2.response.status)
        assertTrue(executionLog.contains("AUTH: Missing token"))
        assertFalse(executionLog.contains("HANDLER: profile"))

        // Test 3: Protected route with token (success)
        executionLog.clear()
        val ctx3 = createTestContext(
            "GET",
            "/api/profile",
            mapOf("Authorization" to "Bearer token123")
        )
        router.handleRequest(ctx3)
        assertEquals(200, ctx3.response.status)
        assertTrue(executionLog.contains("AUTH: Validated"))
        assertTrue(executionLog.contains("HANDLER: profile"))

        // Test 4: Path parameter extraction
        executionLog.clear()
        val ctx4 = createTestContext(
            "GET",
            "/api/users/789",
            mapOf("Authorization" to "Bearer token123")
        )
        router.handleRequest(ctx4)
        assertEquals("789", ctx4.pathParams["id"])
        assertTrue(executionLog.contains("HANDLER: user detail"))

        // Test 5: Wildcard parameter
        executionLog.clear()
        val ctx5 = createTestContext("GET", "/static/images/logo.png")
        router.handleRequest(ctx5)
        assertEquals("images/logo.png", ctx5.pathParams["filepath"])
        assertTrue(executionLog.contains("HANDLER: static file"))

        // Test 6: Sub-app
        executionLog.clear()
        val ctx6 = createTestContext("GET", "/admin/dashboard")
        router.handleRequest(ctx6)
        assertTrue(executionLog.contains("ADMIN_MIDDLEWARE"))
        assertTrue(executionLog.contains("HANDLER: admin dashboard"))

        // Test 7: 404 for non-existent route
        executionLog.clear()
        val ctx7 = createTestContext("GET", "/nonexistent")
        assertThrows<NotFound> {
            router.handleRequest(ctx7)
        }

        // Test 8: 405 for method not allowed
        executionLog.clear()
        val ctx8 = createTestContext("POST", "/health")
        assertThrows<MethodNotAllowed> {
            router.handleRequest(ctx8)
        }
        assertTrue(executionLog.contains("LOG: POST /health"))
    }

    private fun createTestContext(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap()
    ): Context {
        val headersObj = Headers()
        headers.forEach { (k, v) -> headersObj[k] = v }

        val request = Request(
            method = method,
            path = path,
            headers = headersObj
        )
        return Context(request = request, app = Colleen())
    }
}

class EdgeCasesTest {

    private lateinit var router: Router

    @BeforeEach
    fun setup() {
        router = Router()
    }

    private fun createTestContext(
        method: String = "GET",
        path: String = "/"
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers()
        )
        return Context(request = request, app = Colleen())
    }

    @Test
    fun `should handle empty route path`() {
        router.addRoute("GET", "/") { _ -> "root" }

        val ctx = createTestContext("GET", "/")
        router.handleRequest(ctx)

        assertTrue(ctx.response.body is ResponseBody.Text)
    }

    @Test
    fun `should handle route with only parameters`() {
        router.addRoute("GET", "/{id}") { ctx ->
            "ID: ${ctx.pathParam("id")}"
        }

        val ctx = createTestContext("GET", "/123")
        router.handleRequest(ctx)

        assertEquals("123", ctx.pathParams["id"])
    }

    @Test
    fun `should handle multiple consecutive parameters`() {
        router.addRoute("GET", "/{a}/{b}/{c}") { ctx ->
            "${ctx.pathParam("a")}-${ctx.pathParam("b")}-${ctx.pathParam("c")}"
        }

        val ctx = createTestContext("GET", "/1/2/3")
        router.handleRequest(ctx)

        assertEquals("1", ctx.pathParams["a"])
        assertEquals("2", ctx.pathParams["b"])
        assertEquals("3", ctx.pathParams["c"])
    }

    @Test
    fun `should handle mixed static and parameter segments`() {
        router.addRoute("GET", "/users/{userId}/posts/{postId}/comments") { _ ->
            "ok"
        }

        val ctx = createTestContext("GET", "/users/123/posts/456/comments")
        router.handleRequest(ctx)

        assertEquals("123", ctx.pathParams["userId"])
        assertEquals("456", ctx.pathParams["postId"])
    }

    @Test
    fun `should handle middleware that throws exception`() {
        router.addGlobalMiddleware { _, _ ->
            throw RuntimeException("Middleware error")
        }

        router.addRoute("GET", "/test") { _ -> "ok" }

        val ctx = createTestContext("GET", "/test")

        assertThrows<RuntimeException> {
            router.handleRequest(ctx)
        }
    }

    @Test
    fun `should handle handler that throws exception`() {
        router.addRoute("GET", "/test") { _ ->
            throw RuntimeException("Handler error")
        }

        val ctx = createTestContext("GET", "/test")

        assertThrows<RuntimeException> {
            router.handleRequest(ctx)
        }
    }

    @Test
    fun `should handle very long path`() {
        val longPath = "/a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v/w/x/y/z"
        router.addRoute("GET", longPath) { _ -> "ok" }

        val ctx = createTestContext("GET", longPath)
        router.handleRequest(ctx)

        assertTrue(ctx.response.isBodySet)
    }

    @Test
    fun `should handle path with special characters in parameter`() {
        router.addRoute("GET", "/files/{filename}") { ctx ->
            ctx.pathParam("filename")
        }

        val ctx = createTestContext("GET", "/files/my-file_2024.txt")
        router.handleRequest(ctx)

        assertEquals("my-file_2024.txt", ctx.pathParams["filename"])
    }

    @Test
    fun `should handle empty wildcard parameter`() {
        router.addRoute("GET", "/files/{path...}") { ctx ->
            val path = ctx.pathParam("path")
            "Path: [$path]"
        }

        val ctx = createTestContext("GET", "/files")
        router.handleRequest(ctx)

        assertEquals("", ctx.pathParams["path"])
        assertTrue(ctx.response.body is ResponseBody.Text)
    }

    @Test
    fun `should handle no matching routes`() {
        router.addRoute("GET", "/users") { _ -> "users" }

        val ctx = createTestContext("GET", "/posts")
        assertThrows<NotFound> {
            router.handleRequest(ctx)
        }
    }

    @Test
    fun `should not call next() multiple times`() {
        router.addGlobalMiddleware { _, next ->
            next()
            next()
        }

        router.addRoute("GET", "/test") { _ -> "ok" }

        val ctx = createTestContext("GET", "/test")

        assertThrows<IllegalStateException> {
            router.handleRequest(ctx)
        }
    }
}

class PerformanceTest {

    @Test
    fun `should handle many routes efficiently`() {
        val router = Router()

        // Add 1000 routes
        repeat(1000) { i ->
            router.addRoute("GET", "/route/name$i/{id}") { _ -> "route$i" }
        }

        // Test matching performance
        val ctx = createTestContext("GET", "/route/name500/42")

        val startTime = System.nanoTime()
        router.handleRequest(ctx)
        val endTime = System.nanoTime()

        assertTrue(ctx.response.isBodySet)

        // Should be done in 50ms
        val durationMs = (endTime - startTime) / 1_000_000
        assertTrue(durationMs < 50, "Took ${durationMs}ms, expected < 20ms")
    }

    @Test
    fun `should handle many middlewares efficiently`() {
        val router = Router()

        // Add 100 middlewares
        repeat(100) { _ ->
            router.addGlobalMiddleware { _, next ->
                next()
            }
        }

        router.addRoute("GET", "/test") { _ -> "ok" }

        val ctx = createTestContext("GET", "/test")

        val startTime = System.nanoTime()
        router.handleRequest(ctx)
        val endTime = System.nanoTime()

        assertTrue(ctx.response.isBodySet)

        // Should be done in 10ms
        val durationMs = (endTime - startTime) / 1_000_000
        assertTrue(durationMs < 10, "Took ${durationMs}ms, expected < 5ms")
    }

    private fun createTestContext(
        method: String,
        path: String
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers()
        )
        return Context(request = request, app = Colleen())
    }
}

class RegressionTest {

    @Test
    fun `Response body should start as Unset not Empty`() {
        val response = Response()
        assertTrue(response.body is ResponseBody.Unset)
        assertFalse(response.isBodySet)
    }

    @Test
    fun `Handler return value should be processed when body is Unset`() {
        val router = Router()
        router.addRoute("GET", "/test") { _ -> "Hello" }

        val ctx = createTestContext("GET", "/test")
        router.handleRequest(ctx)

        assertTrue(ctx.response.body is ResponseBody.Text)
        assertEquals("Hello", (ctx.response.body as ResponseBody.Text).value)
    }

    private fun createTestContext(
        method: String,
        path: String
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers()
        )
        return Context(request = request, app = Colleen())
    }
}

/**
 * Documentation examples test - ensure code snippets in examples work
 */
class DocumentationExamplesTest {

    @Test
    fun `basic routing example from docs`() {
        val router = Router()

        router.addRoute("GET", "/") { _ -> "Welcome!" }
        router.addRoute("GET", "/users") { _ ->
            listOf(
                mapOf("id" to 1, "name" to "Alice"),
                mapOf("id" to 2, "name" to "Bob")
            )
        }
        router.addRoute("GET", "/users/{id}") { ctx ->
            mapOf("id" to ctx.pathParam("id"), "name" to "User ${ctx.pathParam("id")}")
        }

        val ctx1 = createTestContext("GET", "/")
        router.handleRequest(ctx1)
        assertEquals("Welcome!", (ctx1.response.body as ResponseBody.Text).value)

        val ctx2 = createTestContext("GET", "/users")
        router.handleRequest(ctx2)
        assertTrue(ctx2.response.body is ResponseBody.Json)

        val ctx3 = createTestContext("GET", "/users/123")
        router.handleRequest(ctx3)
        assertEquals("123", ctx3.pathParams["id"])
    }

    @Test
    fun `middleware example from docs`() {
        val router = Router()
        val log = mutableListOf<String>()

        router.addGlobalMiddleware { ctx, next ->
            log.add("→ ${ctx.method} ${ctx.path}")
            next()
            log.add("← ${ctx.response.status}")
        }

        router.addRoute("GET", "/test") { _ -> "ok" }

        val ctx = createTestContext("GET", "/test")
        router.handleRequest(ctx)

        assertEquals("→ GET /test", log[0])
        assertEquals("← 200", log[1])
    }

    @Test
    fun `path parameters example from docs`() {
        val router = Router()

        router.addRoute("GET", "/users/{userId}/posts/{postId}") { ctx ->
            mapOf(
                "userId" to ctx.pathParam("userId"),
                "postId" to ctx.pathParam("postId")
            )
        }

        val ctx = createTestContext("GET", "/users/123/posts/456")
        router.handleRequest(ctx)

        assertEquals("123", ctx.pathParams["userId"])
        assertEquals("456", ctx.pathParams["postId"])
    }

    @Test
    fun `wildcard parameter example from docs`() {
        val router = Router()

        router.addRoute("GET", "/files/{filepath...}") { ctx ->
            "File path: ${ctx.pathParam("filepath")}"
        }

        val ctx = createTestContext("GET", "/files/docs/2024/report.pdf")
        router.handleRequest(ctx)

        assertEquals("docs/2024/report.pdf", ctx.pathParams["filepath"])
    }

    private fun createTestContext(
        method: String,
        path: String
    ): Context {
        val request = Request(
            method = method,
            path = path,
            headers = Headers()
        )
        return Context(request = request, app = Colleen())
    }
}

private fun Router.addGlobalMiddleware(middleware: Middleware) {
    val node = MiddlewareNode.Global(middleware)
    addMiddleware(node)
}

private fun Router.addPrefixMiddleware(prefix: String, middleware: Middleware) {
    val node = MiddlewareNode.Prefix.of(prefix, middleware)
    addMiddleware(node)
}

private fun Router.addConditionMiddleware(predicate: (Context) -> Boolean, middleware: Middleware) {
    val node = MiddlewareNode.Conditional(predicate, middleware)
    addMiddleware(node)
}

private fun Router.addRoute(method: String, path: String, handler: Handler) {
    this.addRoute(RouteNode.of(method, path, RouteHandler.Lambda(handler)))
}