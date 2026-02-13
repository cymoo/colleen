package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.test.*

class ContextTest {

    private fun createTestApp(mountPath: String = ""): Colleen {
        return Colleen().apply { this.mountPath = mountPath }
    }

    private fun createTestRequest(
        method: String = "GET",
        path: String = "/test",
        queryString: String = "",
        headers: Headers = Headers(),
        body: ByteArray? = null
    ): Request {
        return Request(
            method = method,
            path = path,
            queryString = queryString,
            headers = headers,
            stream = body?.let { ByteArrayInputStream(it) }
        )
    }

    private fun createTestContext(
        request: Request = createTestRequest(),
        response: Response = Response(),
        app: Colleen = createTestApp(),
        parentContext: Context? = null
    ): Context {
        return Context(
            request = request,
            response = response,
            app = app,
            parentContext = parentContext
        )
    }

    // === Path Params Tests ===

    @Test
    fun `path param should return path parameter value`() {
        val ctx = createTestContext()
        ctx.setPathParam("id", "123")
        ctx.setPathParam("name", "test")

        assertEquals("123", ctx.pathParam("id"))
        assertEquals("test", ctx.pathParam("name"))
    }

    @Test
    fun `path param should return null for non-existent key`() {
        val ctx = createTestContext()
        assertNull(ctx.pathParam("nonexistent"))
    }

    @Test
    fun `path params should be mutable`() {
        val ctx = createTestContext()
        ctx.setPathParam("key", "value1")
        assertEquals("value1", ctx.pathParam("key"))

        ctx.setPathParam("key", "value2")
        assertEquals("value2", ctx.pathParam("key"))
    }

    // === Service Container Tests ===

    @Test
    fun `getService should return registered service`() {
        class TestService(val name: String)

        val app = createTestApp()
        app.serviceContainer.registerInstance(TestService("test"))

        val ctx = createTestContext(app = app)
        val service = ctx.getService<TestService>()

        assertNotNull(service)
        assertEquals("test", service.name)
    }

    @Test
    fun `getServiceOrNull should return null for unregistered service`() {
        class UnregisteredService

        val ctx = createTestContext()
        assertNull(ctx.getServiceOrNull<UnregisteredService>())
    }

    @Test
    fun `getService should throw for unregistered service`() {
        class UnregisteredService

        val ctx = createTestContext()
        assertThrows<IllegalStateException> {
            ctx.getService<UnregisteredService>()
        }
    }

    @Test
    fun `getService should inherit from parent context`() {
        class ParentService(val value: String)

        val parentApp = createTestApp()
        parentApp.serviceContainer.registerInstance(ParentService("parent"))

        val parentCtx = createTestContext(app = parentApp)
        val childCtx = createTestContext(parentContext = parentCtx)

        val service = childCtx.getService<ParentService>()
        assertNotNull(service)
        assertEquals("parent", service.value)
    }

    @Test
    fun `getService should prioritize child service over parent`() {
        class TestService(val source: String)

        val parentApp = createTestApp()
        parentApp.serviceContainer.registerInstance(TestService("parent"))

        val childApp = createTestApp()
        childApp.serviceContainer.registerInstance(TestService("child"))

        val parentCtx = createTestContext(app = parentApp)
        val childCtx = createTestContext(app = childApp, parentContext = parentCtx)

        val service = childCtx.getService<TestService>()
        assertNotNull(service)
        assertEquals("child", service.source)
    }

    @Test
    fun `getService with Java Class should work`() {
        class TestService(val name: String)

        val app = createTestApp()
        app.serviceContainer.registerInstance(TestService("test"))

        val ctx = createTestContext(app = app)
        val service = ctx.getService(TestService::class.java)

        assertNotNull(service)
        assertEquals("test", service.name)
    }

    // === Request Delegation Tests ===

    @Test
    fun `method should delegate to request`() {
        val request = createTestRequest(method = "POST")
        val ctx = createTestContext(request = request)

        assertEquals("POST", ctx.method)
    }

    @Test
    fun `path should delegate to request`() {
        val request = createTestRequest(path = "/users/123")
        val ctx = createTestContext(request = request)

        assertEquals("/users/123", ctx.path)
    }

    @Test
    fun `header should delegate to request`() {
        val headers = Headers()
        headers["Content-Type"] = "application/json"
        val request = createTestRequest(headers = headers)
        val ctx = createTestContext(request = request)

        assertEquals("application/json", ctx.header("Content-Type"))
    }

    @Test
    fun `query should delegate to request`() {
        val request = createTestRequest(queryString = "name=John&age=30")
        val ctx = createTestContext(request = request)

        assertEquals("John", ctx.query("name"))
        assertEquals("30", ctx.query("age"))
    }

    @Test
    fun `queries should delegate to request`() {
        val request = createTestRequest(queryString = "tag=java&tag=kotlin")
        val ctx = createTestContext(request = request)

        val queries = ctx.queries()
        assertEquals(listOf("java", "kotlin"), queries["tag"])
    }

    @Test
    fun `queries should delegate to request with ObjectMapper`() {
        data class QueryParams(val name: String, val age: Int)

        val request = createTestRequest(queryString = "name=John&age=30")
        val ctx = createTestContext(request = request)

        val params = ctx.queries<QueryParams>()
        assertNotNull(params)
        assertEquals("John", params.name)
        assertEquals(30, params.age)
    }

    @Test
    fun `queries with Class should work`() {
        data class QueryParams(val id: String)

        val request = createTestRequest(queryString = "id=123")
        val ctx = createTestContext(request = request)

        val params = ctx.queries(QueryParams::class.java)
        assertNotNull(params)
        assertEquals("123", params.id)
    }

    @Test
    fun `cookie should delegate to request`() {
        val headers = Headers()
        headers["Cookie"] = "session=abc123; user=John"
        val request = createTestRequest(headers = headers)
        val ctx = createTestContext(request = request)

        assertEquals("abc123", ctx.request.cookie("session"))
        assertEquals("John", ctx.request.cookie("user"))
    }

    @Test
    fun `text should delegate to request`() {
        val body = "Hello World".toByteArray()
        val request = createTestRequest(body = body)
        val ctx = createTestContext(request = request)

        assertEquals("Hello World", ctx.text())
    }

    @Test
    fun jsonShouldDelegateToRequest() {
        data class User(val name: String, val age: Int)

        val json = """{"name":"John","age":30}"""
        val request = createTestRequest(body = json.toByteArray())
        val ctx = createTestContext(request = request)

        val user = ctx.json<User>()
        assertNotNull(user)
        assertEquals("John", user.name)
        assertEquals(30, user.age)
    }

    @Test
    fun jsonWithClassShouldWork() {
        // NOTE:  NOTE: Using backticked function names together with local classes
        // causes javaClass.generic.Superclass to throw an exception
        data class User(val id: String)

        val json = """{"id":"123"}"""
        val request = createTestRequest(body = json.toByteArray())
        val ctx = createTestContext(request = request)

        val user = ctx.json<User>()
        assertNotNull(user)
        assertEquals("123", user.id)
    }

    @Test
    fun `forms should delegate to request`() {
        val headers = Headers()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        val body = "name=John&age=30".toByteArray()
        val request = createTestRequest(headers = headers, body = body)
        val ctx = createTestContext(request = request)

        val forms = ctx.forms()
        assertEquals(listOf("John"), forms["name"])
        assertEquals(listOf("30"), forms["age"])
    }

    @Test
    fun `form should delegate to request`() {
        val headers = Headers()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        val body = "username=admin".toByteArray()
        val request = createTestRequest(headers = headers, body = body)
        val ctx = createTestContext(request = request)

        assertEquals("admin", ctx.form("username"))
    }

    @Test
    fun `forms with generic should delegate to request`() {
        data class LoginForm(val username: String, val password: String)

        val headers = Headers()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        val body = "username=admin&password=secret".toByteArray()
        val request = createTestRequest(headers = headers, body = body)
        val ctx = createTestContext(request = request)

        val form = ctx.forms<LoginForm>()
        assertNotNull(form)
        assertEquals("admin", form.username)
        assertEquals("secret", form.password)
    }

    @Test
    fun `forms with Class should work`() {
        data class LoginForm(val username: String)

        val headers = Headers()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        val body = "username=admin".toByteArray()
        val request = createTestRequest(headers = headers, body = body)
        val ctx = createTestContext(request = request)

        val form = ctx.forms(LoginForm::class.java)
        assertNotNull(form)
        assertEquals("admin", form.username)
    }

    // === Response Delegation Tests ===

    @Test
    fun `status should delegate to response`() {
        val ctx = createTestContext()
        ctx.status(404)

        assertEquals(404, ctx.response.status)
    }

    @Test
    fun `text should delegate to response`() {
        val ctx = createTestContext()
        ctx.text("Hello World")

        assertTrue(ctx.response.body is ResponseBody.Text)
        assertEquals("Hello World", (ctx.response.body as ResponseBody.Text).value)
        assertEquals("text/plain; charset=utf-8", ctx.response.headers["Content-Type"])
    }

    @Test
    fun `html should delegate to response`() {
        val ctx = createTestContext()
        ctx.html("<h1>Hello</h1>")

        assertTrue(ctx.response.body is ResponseBody.Text)
        assertEquals("<h1>Hello</h1>", (ctx.response.body as ResponseBody.Text).value)
        assertEquals("text/html; charset=utf-8", ctx.response.headers["Content-Type"])
    }

    @Test
    fun `json should delegate to response`() {
        val ctx = createTestContext()
        val data = mapOf("name" to "John", "age" to 30)
        ctx.json(data)

        assertTrue(ctx.response.body is ResponseBody.Json)
        assertEquals("application/json; charset=utf-8", ctx.response.headers["Content-Type"])
    }

    @Test
    fun `bytes should delegate to response`() {
        val ctx = createTestContext()
        val data = byteArrayOf(1, 2, 3)
        ctx.bytes(data)

        assertTrue(ctx.response.body is ResponseBody.Bytes)
        assertContentEquals(data, (ctx.response.body as ResponseBody.Bytes).value)
        assertEquals("application/octet-stream", ctx.response.headers["Content-Type"])
    }

    @Test
    fun `bytes with custom content type should work`() {
        val ctx = createTestContext()
        val data = byteArrayOf(1, 2, 3)
        ctx.bytes(data, "image/png")

        assertEquals("image/png", ctx.response.headers["Content-Type"])
    }

    @Test
    fun `redirect should delegate to response`() {
        val ctx = createTestContext()
        ctx.redirect("/new-path")

        assertEquals(302, ctx.response.status)
        assertEquals("/new-path", ctx.response.headers["Location"])
        assertTrue(ctx.response.body is ResponseBody.Empty)
    }

    @Test
    fun `redirect with custom status code should work`() {
        val ctx = createTestContext()
        ctx.redirect("/new-path", 301)

        assertEquals(301, ctx.response.status)
        assertEquals("/new-path", ctx.response.headers["Location"])
    }

    @Test
    fun `stream should delegate to response`() {
        val ctx = createTestContext()
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        ctx.stream(stream)

        assertTrue(ctx.response.body is ResponseBody.Stream)
        assertEquals(stream, (ctx.response.body as ResponseBody.Stream).input)
        assertEquals("application/octet-stream", ctx.response.headers["Content-Type"])
    }

    @Test
    fun `stream with custom content type should work`() {
        val ctx = createTestContext()
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        ctx.stream(stream, "video/mp4")

        assertEquals("video/mp4", ctx.response.headers["Content-Type"])
    }

    @Test
    fun `sse should delegate to response`() {
        val ctx = createTestContext()
        ctx.sse { emitter ->
            emitter.send("test")
        }

        assertTrue(ctx.response.body is ResponseBody.Sse)
        assertEquals("text/event-stream; charset=utf-8", ctx.response.headers["Content-Type"])
        assertEquals("no-cache", ctx.response.headers["Cache-Control"])
        assertEquals("keep-alive", ctx.response.headers["Connection"])
    }

    // === Sub Context Tests ===
    @Test
    fun `subContext should has its own path`() {
        val app = Colleen()
        val subApp = Colleen()

        var fullPath1 = ""
        var path1 = ""

        var fullPath2 = ""
        var path2 = ""

        app.use { ctx, next ->
            fullPath1 = ctx.fullPath
            path1 = ctx.path
            next()
        }

        subApp.use { ctx, next ->
            fullPath2 = ctx.fullPath
            path2 = ctx.path
            next()
        }

        app.mount("/sub", subApp)

        app.handleRequest(Context(Request("GET", "/sub/hello"), app = app))

        assertEquals("/sub/hello", fullPath1)
        assertEquals("/sub/hello", fullPath2)

        assertEquals("/sub/hello", path1)
        assertEquals("/hello", path2)
    }

    @Test
    fun `context pattern should return null when no route matches`() {

        val app = Colleen()
        val subApp = Colleen()

        var fullPattern1: String? = null
        var pattern1: String? = null

        var fullPattern2: String? = null
        var pattern2: String? = null


        app.use { ctx, next ->
            next()
            fullPattern1 = ctx.fullPattern
            pattern1 = ctx.pattern
        }

        subApp.use { ctx, next ->
            next()
            fullPattern2 = ctx.fullPattern
            pattern2 = ctx.pattern
        }

        app.mount("/api", subApp)

        app.handleRequest(Context(Request("GET", "/api/users/1"), app = app))

        assertNull(fullPattern1)
        assertNull(fullPattern2)
        assertNull(pattern1)
        assertNull(pattern2)
    }

    @Test
    fun `subContext should has its own pattern`() {
        val app = Colleen()
        val subApp = Colleen()
        val subSubApp = Colleen()

        var fullPattern1 = ""
        var pattern1 = ""

        var fullPattern2 = ""
        var pattern2 = ""

        var fullPattern3 = ""
        var pattern3 = ""

        app.use { ctx, next ->
            next()
            fullPattern1 = ctx.fullPattern!!
            pattern1 = ctx.pattern!!
        }

        subApp.use { ctx, next ->
            next()
            fullPattern2 = ctx.fullPattern!!
            pattern2 = ctx.pattern!!
        }

        subSubApp.use { ctx, next ->
            next()
            fullPattern3 = ctx.fullPattern!!
            pattern3 = ctx.pattern!!
        }

        subSubApp.get("/{id}") {
            "hello"
        }

        app.mount("/api", subApp)
        subApp.mount("/users", subSubApp)

        app.handleRequest(Context(Request("GET", "/api/users/1"), app = app))

        assertEquals("/api/users/{id}", fullPattern1)
        assertEquals("/api/users/{id}", fullPattern2)
        assertEquals("/api/users/{id}", fullPattern3)

        assertEquals("/api/users/{id}", pattern1)
        assertEquals("/users/{id}", pattern2)
        assertEquals("/{id}", pattern3)
    }

    @Test
    fun `createSubContext should create child context with new path`() {
        val parentApp = createTestApp(mountPath = "/api")
        val parentCtx = createTestContext(app = parentApp)

        val childApp = createTestApp(mountPath = "/users")
        val childCtx = parentCtx.createSubContext("/api/users/123", childApp)

        assertEquals("/api/users/123", childCtx.path)
        assertEquals(childApp, childCtx.app)
        assertEquals(parentCtx, childCtx.parentContext)
    }

    @Test
    fun `createSubContext should share response object`() {
        val parentCtx = createTestContext()
        parentCtx.response.status = 200

        val childCtx = parentCtx.createSubContext("/new-path", createTestApp())

        assertEquals(parentCtx.response, childCtx.response)
        assertEquals(200, childCtx.response.status)
    }

    // === Full Path Tests ===

    @Test
    fun `fullPath should return original request path`() {
        val request = createTestRequest(path = "/api/users/123")
        val ctx = createTestContext(request = request)

        assertEquals("/api/users/123", ctx.fullPath)
    }

    // === Error State Tests ===

    @Test
    fun `error should be null when no exception thrown`() {
        val app = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
        }

        app.get("/test") { "hello" }

        app.handleRequest(createTestContext())

        assertNull(error)
    }

    @Test
    fun `error should return exception thrown in middleware`() {
        val app = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
            error?.handled = true
        }

        app.use { ctx, next ->
            throw RuntimeException("error")
        }

        app.handleRequest(createTestContext())

        assertNotNull(error)
        assertEquals(error?.handled, true)
    }

    @Test
    fun `error should return exception thrown in handler`() {
        val app = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
            error?.handled = true
        }

        app.get("/test") {
            throw RuntimeException("error in middleware")
        }

        app.handleRequest(createTestContext())

        assertNotNull(error)
        assertEquals(error?.handled, true)
    }

    @Test
    fun `error should return RouteNotFound when route not matched`() {
        val app = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
            error?.handled = true
        }

        app.use { ctx, next ->
            next()
            throw RuntimeException("error")
        }

        app.handleRequest(createTestContext())

        assertNotNull(error)
        assertTrue(error?.cause is RouteNotFound)
        assertEquals(error?.handled, true)
    }

    @Test
    fun `error should return downstream exception thrown in handler`() {
        val app = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
            error?.handled = true
        }

        app.use { ctx, next ->
            next()
            throw RuntimeException("error in middleware")
        }

        app.get("/test") {
            throw RuntimeException("error in handler")
        }

        app.handleRequest(createTestContext())

        assertNotNull(error)
        assertEquals(error?.handled, true)
        assertEquals(error?.cause?.message, "error in handler")
        assertEquals(error?.cause?.suppressed[0]?.message, "error in middleware")
    }

    @Test
    fun `error should return exception thrown in sub app's middleware`() {
        val app = Colleen()
        val subApp = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
            error?.handled = true
        }

        subApp.use { ctx, next ->
            next()
            throw RuntimeException("error in sub app's middleware")
        }

        subApp.get("/test") {
            "hello"
        }

        app.mount("/", subApp)

        app.handleRequest(createTestContext())

        assertNotNull(error)
        assertEquals(error?.handled, true)
        assertEquals(error?.cause?.message, "error in sub app's middleware")
    }

    @Test
    fun `error should return exception thrown in sub app's handler`() {
        val app = Colleen()
        val subApp = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
            error?.handled = true
        }

        subApp.use { ctx, next ->
            next()
        }

        subApp.get("/test") {
            throw RuntimeException("error in sub app's handler")
        }

        app.mount("/", subApp)

        app.handleRequest(createTestContext())

        assertNotNull(error)
        assertEquals(error?.handled, true)
        assertEquals(error?.cause?.message, "error in sub app's handler")
    }

    @Test
    fun `error should return RouteNotFound when route not matched in sub app`() {
        val app = Colleen()
        val subApp = Colleen()
        var error: ErrorState? = null

        app.use { ctx, next ->
            next()
            error = ctx.error
            error?.handled = true
        }

        subApp.get("/test1") { "hello" }

        app.mount("/sub", subApp)

        app.handleRequest(createTestContext(Request("GET", "/sub/test")))

        assertNotNull(error)
        assertTrue(error?.cause is RouteNotFound)
        assertEquals(error?.handled, true)
    }

    // === Integration Tests ===

    @Test
    fun contextShouldWorkWithCompleteRequestResponseCycle() {
        data class User(val name: String, val age: Int)

        val json = """{"name":"John","age":30}"""
        val headers = Headers()
        headers["Content-Type"] = "application/json"

        val request = createTestRequest(
            method = "POST",
            path = "/users",
            queryString = "debug=true",
            headers = headers,
            body = json.toByteArray()
        )

        val ctx = createTestContext(request = request)
        ctx.setPathParam("id", "123")
        ctx.setState("userId", "user-123")

        // Request operations
        assertEquals("POST", ctx.method)
        assertEquals("/users", ctx.path)
        assertEquals("true", ctx.query("debug"))
        assertEquals("123", ctx.pathParam("id"))
        assertEquals("user-123", ctx.getState("userId"))

        val user = ctx.json<User>()
        assertNotNull(user)
        assertEquals("John", user.name)

        // Response operations
        ctx.status(201)
        ctx.json(mapOf("id" to "123", "name" to user.name))

        assertEquals(201, ctx.response.status)
        assertTrue(ctx.response.body is ResponseBody.Json)
    }

    // === State Management Tests ===

    @Test
    fun `setState and getState should work correctly`() {
        val ctx = createTestContext()
        ctx.setState("user", "John")
        ctx.setState("count", 42)

        assertEquals("John", ctx.getState("user"))
        assertEquals(42, ctx.getState("count"))
    }

    @Test
    fun `getState should throw for non-existent key`() {
        val ctx = createTestContext()
        assertThrows(NoSuchElementException::class.java) {
            ctx.getState<String>("nonexistent")
        }
    }

    @Test
    fun `getState should inherit from parent context`() {
        val parentCtx = createTestContext()
        parentCtx.setState("parentKey", "parentValue")

        val childCtx = createTestContext(parentContext = parentCtx)
        childCtx.setState("childKey", "childValue")

        assertEquals("childValue", childCtx.getState("childKey"))
        assertEquals("parentValue", childCtx.getState("parentKey"))
    }

    @Test
    fun `child state should override parent state with same key`() {
        val parentCtx = createTestContext()
        parentCtx.setState("key", "parentValue")

        val childCtx = createTestContext(parentContext = parentCtx)
        childCtx.setState("key", "childValue")

        assertEquals("childValue", childCtx.getState("key"))
    }

    @Test
    fun `getState should support type casting`() {
        val ctx = createTestContext()
        ctx.setState("list", listOf(1, 2, 3))
        ctx.setState("map", mapOf("key" to "value"))

        val list: List<Int> = ctx.getState("list")
        val map: Map<String, String> = ctx.getState("map")

        assertEquals(listOf(1, 2, 3), list)
        assertEquals(mapOf("key" to "value"), map)
    }

    @Test
    fun `getState should throw when type casting failed`() {
        val ctx = createTestContext()
        ctx.setState("key", "abc")
        assertThrows(ClassCastException::class.java) {
            val value = ctx.getState<Int>("key")
        }
    }

    @Test
    fun `getState should throw NullPointerException when value is null`() {
        val ctx = createTestContext()
        ctx.setState("nullKey", null)

        assertThrows(NullPointerException::class.java) {
            ctx.getState<String>("nullKey")
        }
    }

    @Test
    fun `getStateOrNull should throw ClassCastException when type casting failed`() {
        val ctx = createTestContext()
        ctx.setState("key", "stringValue")

        assertThrows(ClassCastException::class.java) {
            val rv = ctx.getStateOrNull<Int>("key")
        }
    }

    @Test
    fun `setState should allow null values`() {
        val ctx = createTestContext()
        ctx.setState("nullKey", null)

        assertTrue(ctx.hasState("nullKey"))
        assertNull(ctx.getStateOrNull("nullKey"))
    }

    @Test
    fun `getStateOrNull should return null for non-existent key`() {
        val ctx = createTestContext()
        assertNull(ctx.getStateOrNull<String>("nonexistent"))
    }

    @Test
    fun `getStateOrNull should return value for existing key`() {
        val ctx = createTestContext()
        ctx.setState("key", "value")

        assertEquals("value", ctx.getStateOrNull<String>("key"))
    }

    @Test
    fun `getStateOrNull should return null for null state value`() {
        val ctx = createTestContext()
        ctx.setState("nullKey", null)

        assertNull(ctx.getStateOrNull<String>("nullKey"))
    }

    @Test
    fun `getStateOrNull should inherit from parent context`() {
        val parentCtx = createTestContext()
        parentCtx.setState("parentKey", "parentValue")

        val childCtx = createTestContext(parentContext = parentCtx)

        assertEquals("parentValue", childCtx.getStateOrNull<String>("parentKey"))
    }

    @Test
    fun `hasState should return true for existing key`() {
        val ctx = createTestContext()
        ctx.setState("key", "value")

        assertTrue(ctx.hasState("key"))
    }

    @Test
    fun `hasState should return false for non-existent key`() {
        val ctx = createTestContext()

        assertFalse(ctx.hasState("nonexistent"))
    }

    @Test
    fun `hasState should return true for null value`() {
        val ctx = createTestContext()
        ctx.setState("nullKey", null)

        assertTrue(ctx.hasState("nullKey"))
    }

    @Test
    fun `hasState should check parent context`() {
        val parentCtx = createTestContext()
        parentCtx.setState("parentKey", "value")

        val childCtx = createTestContext(parentContext = parentCtx)

        assertTrue(childCtx.hasState("parentKey"))
    }

    @Test
    fun `hasState should return false when key not in child or parent`() {
        val parentCtx = createTestContext()
        val childCtx = createTestContext(parentContext = parentCtx)

        assertFalse(childCtx.hasState("nonexistent"))
    }

    @Test
    fun `forEachState should iterate over all states`() {
        val ctx = createTestContext()
        ctx.setState("key1", "value1")
        ctx.setState("key2", "value2")
        ctx.setState("key3", null)

        val collected = mutableMapOf<String, Any?>()
        ctx.forEachState(includeParents = false) { k, v ->
            collected[k] = v
        }

        assertEquals(3, collected.size)
        assertEquals("value1", collected["key1"])
        assertEquals("value2", collected["key2"])
        assertNull(collected["key3"])
        assertTrue(collected.containsKey("key3"))
    }

    @Test
    fun `forEachState should iterate children states first when includeParents is true`() {
        val parentCtx = createTestContext()
        parentCtx.setState("parent1", "pValue1")
        parentCtx.setState("parent2", "pValue2")

        val childCtx = createTestContext(parentContext = parentCtx)
        childCtx.setState("child1", "cValue1")
        childCtx.setState("child2", "cValue2")

        val order = mutableListOf<String>()
        childCtx.forEachState(includeParents = true) { k, _ ->
            order.add(k)
        }

        assertEquals(4, order.size)
        assertTrue(order.indexOf("parent1") > order.indexOf("child1"))
        assertTrue(order.indexOf("parent2") > order.indexOf("child2"))
    }

    @Test
    fun `forEachState should not iterate parent states when includeParents is false`() {
        val parentCtx = createTestContext()
        parentCtx.setState("parentKey", "parentValue")

        val childCtx = createTestContext(parentContext = parentCtx)
        childCtx.setState("childKey", "childValue")

        val collected = mutableMapOf<String, Any?>()
        childCtx.forEachState(includeParents = false) { k, v ->
            collected[k] = v
        }

        assertEquals(1, collected.size)
        assertEquals("childValue", collected["childKey"])
        assertFalse(collected.containsKey("parentKey"))
    }

    @Test
    fun `forEachState should handle multiple levels of parent contexts`() {
        val grandparentCtx = createTestContext()
        grandparentCtx.setState("grandparent", "gValue")

        val parentCtx = createTestContext(parentContext = grandparentCtx)
        parentCtx.setState("parent", "pValue")

        val childCtx = createTestContext(parentContext = parentCtx)
        childCtx.setState("child", "cValue")

        val order = mutableListOf<String>()
        childCtx.forEachState(includeParents = true) { k, _ ->
            order.add(k)
        }

        assertEquals(listOf("child", "parent", "grandparent"), order)
    }

    @Test
    fun `forEachState should handle empty states`() {
        val ctx = createTestContext()

        val collected = mutableMapOf<String, Any?>()
        ctx.forEachState(includeParents = false) { k, v ->
            collected[k] = v
        }

        assertTrue(collected.isEmpty())
    }

    // === createSubContext Tests ===

    @Test
    fun `createSubContext should create context with rewritten path`() {
        val parentApp = createTestApp()
        val parentRequest = createTestRequest(path = "/api/users")
        val parentCtx = createTestContext(request = parentRequest, app = parentApp)

        val subApp = createTestApp(mountPath = "/api")
        val subCtx = parentCtx.createSubContext("/users", subApp)

        assertEquals("/users", subCtx.request.path)
        assertEquals(subApp, subCtx.app)
        assertEquals(parentCtx, subCtx.parentContext)
    }

    @Test
    fun `createSubContext should copy response`() {
        val parentCtx = createTestContext()
        val subApp = createTestApp()
        val subCtx = parentCtx.createSubContext("/sub", subApp)

        // Response should be a copy, not the same instance
        assertNotSame(parentCtx.response, subCtx.response)
    }

    @Test
    fun `createSubContext should preserve request properties except path`() {
        val headers = Headers().apply {
            add("Content-Type", "application/json")
        }
        val parentRequest = createTestRequest(
            method = "POST",
            path = "/api/resource",
            queryString = "param=value",
            headers = headers
        )
        val parentCtx = createTestContext(request = parentRequest)

        val subApp = createTestApp()
        val subCtx = parentCtx.createSubContext("/resource", subApp)

        assertEquals("POST", subCtx.request.method)
        assertEquals("param=value", subCtx.request.queryString)
        assertEquals(headers, subCtx.request.headers)
        assertEquals("/resource", subCtx.request.path)
    }

    @Test
    fun `createSubContext should allow state inheritance`() {
        val parentCtx = createTestContext()
        parentCtx.setState("parentState", "parentValue")

        val subApp = createTestApp()
        val subCtx = parentCtx.createSubContext("/sub", subApp)
        subCtx.setState("childState", "childValue")

        assertEquals("childValue", subCtx.getState("childState"))
        assertEquals("parentValue", subCtx.getState("parentState"))
    }

    @Test
    fun `createSubContext should support nested sub-contexts`() {
        val rootApp = createTestApp()
        val rootCtx = createTestContext(app = rootApp)
        rootCtx.setState("rootState", "root")

        val subApp1 = createTestApp(mountPath = "/api")
        val subCtx1 = rootCtx.createSubContext("/v1/users", subApp1)
        subCtx1.setState("subState1", "sub1")

        val subApp2 = createTestApp(mountPath = "/api/v1")
        val subCtx2 = subCtx1.createSubContext("/users", subApp2)
        subCtx2.setState("subState2", "sub2")

        assertEquals("root", subCtx2.getState("rootState"))
        assertEquals("sub1", subCtx2.getState("subState1"))
        assertEquals("sub2", subCtx2.getState("subState2"))
    }

    // === Edge Cases and Integration Tests ===

    @Test
    fun `setState should update existing value`() {
        val ctx = createTestContext()
        ctx.setState("key", "value1")
        ctx.setState("key", "value2")

        assertEquals("value2", ctx.getState("key"))
    }

    @Test
    fun `setState should allow switching from non-null to null`() {
        val ctx = createTestContext()
        ctx.setState("key", "value")
        ctx.setState("key", null)

        assertTrue(ctx.hasState("key"))
        assertNull(ctx.getStateOrNull("key"))
    }

    @Test
    fun `state should be independent between sibling contexts`() {
        val parentCtx = createTestContext()
        parentCtx.setState("shared", "parent")

        val child1 = createTestContext(parentContext = parentCtx)
        child1.setState("child1Key", "child1Value")

        val child2 = createTestContext(parentContext = parentCtx)
        child2.setState("child2Key", "child2Value")

        assertFalse(child1.hasState("child2Key"))
        assertFalse(child2.hasState("child1Key"))
        assertEquals("parent", child1.getState("shared"))
        assertEquals("parent", child2.getState("shared"))
    }

    @Test
    fun `context should handle complex nested object types`() {
        data class User(val id: Int, val name: String)
        data class Session(val user: User, val token: String)

        val ctx = createTestContext()
        val session = Session(User(1, "John"), "token123")
        ctx.setState("session", session)

        val retrieved: Session = ctx.getState("session")
        assertEquals(session, retrieved)
        assertEquals(1, retrieved.user.id)
        assertEquals("John", retrieved.user.name)
    }
}