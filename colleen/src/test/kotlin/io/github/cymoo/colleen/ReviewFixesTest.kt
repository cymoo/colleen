package io.github.cymoo.colleen

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the fixes from the 2026-07 code review (see REVIEW.md /
 * FIXES.md; test names reference the finding numbers).
 */
class ReviewFixesTest {

    // ========================================================================
    // Routing
    // ========================================================================

    @Nested
    inner class Routing {

        @Test
        fun `1_2 - exact route must beat a longer wildcard route matching zero segments`() {
            val app = Colleen()
            app.get("/users/{path...}") { "wildcard" }
            app.get("/users") { "exact" }

            val client = TestClient(app)
            assertEquals("exact", client.get("/users").send().text())
            assertEquals("wildcard", client.get("/users/1/posts").send().text())
        }

        @Test
        fun `1_3 - 405 response carries the Allow header`() {
            val app = Colleen()
            app.get("/resource") { "ok" }
            app.put("/resource") { "ok" }

            val response = TestClient(app).post("/resource").send()

            assertEquals(405, response.status)
            // HEAD/OPTIONS are served automatically, so they appear in Allow too
            assertEquals("GET, HEAD, OPTIONS, PUT", response.header("Allow"))
        }

        @Test
        fun `1_3 - TooManyRequests retryAfter becomes a Retry-After header`() {
            val app = Colleen()
            app.get("/limited") { throw TooManyRequests(retryAfter = 42) }

            val response = TestClient(app).get("/limited").send()

            assertEquals(429, response.status)
            assertEquals("42", response.header("Retry-After"))
        }

        @Test
        fun `1_6 - conditional middleware registered inside a group stays scoped to the group`() {
            val app = Colleen()
            var invocations = 0

            app.group("/api") {
                use({ true }) { _, next ->
                    invocations++
                    next()
                }
                get("/inside") { "in" }
            }
            app.get("/outside") { "out" }

            val client = TestClient(app)
            // /inside does not exist (the grouped route is /api/inside) — this 404
            // request verifies the middleware does NOT run outside the group prefix
            client.get("/inside").send()
            client.get("/api/inside").send()
            client.get("/outside").send()

            assertEquals(1, invocations, "middleware must only run for /api/*")
        }

        @Test
        fun `1_7 - failed mount leaves no half-mounted state`() {
            val parent = Colleen()
            val child = Colleen()

            // Parameterized mount prefix is invalid
            assertThrows<IllegalArgumentException> { parent.mount("/bad/{id}", child) }

            // A valid prefix must still work afterwards
            parent.mount("/good", child)
            assertEquals("/good", child.mountPath)
        }

        @Test
        fun `1_8 - mounting an ancestor is rejected instead of creating a cycle`() {
            val a = Colleen()
            val b = Colleen()
            a.mount("/x", b)

            val ex = assertThrows<IllegalStateException> { b.mount("/y", a) }
            assertTrue(ex.message!!.contains("cycle"))

            // Self-mount is also a cycle
            val c = Colleen()
            assertThrows<IllegalStateException> { c.mount("/self", c) }
        }
    }

    // ========================================================================
    // Error handling
    // ========================================================================

    @Nested
    inner class ErrorHandling {

        @Test
        fun `1_4 - onError registered for Throwable is invoked`() {
            val app = Colleen()
            var fired = false
            app.onError<Throwable> { _, ctx ->
                fired = true
                ctx.status(500).text("caught")
            }
            app.get("/boom") { throw RuntimeException("x") }

            val response = TestClient(app).get("/boom").send()

            assertTrue(fired, "onError<Throwable> must catch subclasses")
            assertEquals("caught", response.text())
        }

        @Test
        fun `1_5 - direct Throwable subclasses are rethrown, not silently swallowed`() {
            class CustomThrowable : Throwable("custom")

            val app = Colleen()
            app.get("/boom") { throw CustomThrowable() }

            // With TestClient the throwable surfaces to the caller (the real
            // server logs it and answers 500); before the fix it vanished and
            // the response stayed unmaterialized with no trace of the error.
            assertThrows<CustomThrowable> { TestClient(app).get("/boom").send() }
        }

        @Test
        fun `2_12 - validation errors appear structured in the default JSON error body`() {
            val app = Colleen()
            app.get("/validate") {
                throw ValidationException(mapOf("name" to listOf("must not be blank")))
            }

            val response = TestClient(app).get("/validate")
                .header("Accept", "application/json")
                .send()

            assertEquals(422, response.status)
            val body = response.json<Map<String, Any?>>()!!
            @Suppress("UNCHECKED_CAST")
            val errors = body["errors"] as Map<String, List<String>>
            assertEquals(listOf("must not be blank"), errors["name"])
        }
    }

    // ========================================================================
    // Response mapping
    // ========================================================================

    @Nested
    inner class ResponseMapping {

        @Test
        fun `2_1 - handler returning a Long status does not crash`() {
            val app = Colleen()
            app.get("/long") { 201L }

            assertEquals(201, TestClient(app).get("/long").send().status)
        }

        @Test
        fun `2_6 - numeric Result body is payload, not a status code`() {
            val app = Colleen()
            app.get("/count") { Result.ok(42) }
            app.get("/status-like") { Result.ok(204) }

            val client = TestClient(app)

            val count = client.get("/count").send()
            assertEquals(200, count.status)
            assertEquals("42", count.text())

            // 204 must NOT be re-interpreted as a status code
            val statusLike = client.get("/status-like").send()
            assertEquals(200, statusLike.status)
            assertEquals("204", statusLike.text())
        }

        @Test
        fun `2_8 - a newly constructed Response returned from a handler is applied`() {
            val app = Colleen()
            app.get("/fresh") {
                Response(status = 418).apply {
                    header("X-Custom", "yes")
                    text("teapot")
                }
            }

            val response = TestClient(app).get("/fresh").send()

            assertEquals(418, response.status)
            assertEquals("yes", response.header("X-Custom"))
            assertEquals("teapot", response.text())
        }

        @Test
        fun `4_3 - json with a plain string produces valid quoted JSON`() {
            val app = Colleen()
            app.get("/str") { ctx -> ctx.json("hello") }
            app.get("/raw") { ctx -> ctx.json(io.github.cymoo.colleen.json.RawJson("""{"pre":"rendered"}""")) }
            app.get("/nil") { ctx -> ctx.json(null) }

            val client = TestClient(app)
            assertEquals("\"hello\"", client.get("/str").send().text())
            assertEquals("""{"pre":"rendered"}""", client.get("/raw").send().text())
            assertEquals("null", client.get("/nil").send().text())
        }
    }

    // ========================================================================
    // Mounted sub-apps
    // ========================================================================

    @Nested
    inner class Mounting {

        @Test
        fun `2_3 - parent and mounted child see the same request body`() {
            val parent = Colleen()
            val child = Colleen()

            var bodyInParentMiddleware: String? = null
            parent.use { ctx, next ->
                bodyInParentMiddleware = ctx.text() // consumes the stream first
                next()
            }
            child.post("/read") { ctx -> ctx.text() ?: "EMPTY" }
            parent.mount("/child", child)

            val response = TestClient(parent).post("/child/read")
                .body("shared-body")
                .send()

            assertEquals("shared-body", bodyInParentMiddleware)
            assertEquals("shared-body", response.text(), "child must see the cached body, not a drained stream")
        }

        @Test
        fun `2_5 - headers written by a failing sub-app do not leak into the final response`() {
            val parent = Colleen()

            val failing = Colleen()
            failing.use { ctx, next ->
                ctx.response.header("X-Leaky", "should-not-appear")
                next()
            }
            // no matching route in `failing` -> internal 404 -> next mount is tried
            failing.config { propagateExceptions = true }

            val succeeding = Colleen()
            succeeding.get("/page") { "served" }

            parent.mount("/site", failing)
            parent.mount("/site", succeeding)

            val response = TestClient(parent).get("/site/page").send()

            assertEquals(200, response.status)
            assertEquals("served", response.text())
            assertNull(response.header("X-Leaky"), "fallthrough sub-app headers must not leak")
        }
    }

    // ========================================================================
    // Parameter binding
    // ========================================================================

    @Nested
    inner class ParameterBinding {

        // Handlers as member functions: kotlin-reflect does not fully support
        // introspecting local function parameters.
        fun byId(id: Path<UUID>): String = id.value.toString()
        fun byDate(date: Query<LocalDate>): String = date.value.toString()
        fun byPrice(price: Query<BigDecimal>): String = price.value.toPlainString()
        fun byColor(color: Query<Color>): String = color.value.name

        @Suppress("unused")
        fun badTarget(id: Path<ReviewFixesTest>): String = "never"

        @Test
        fun `4_1 - UUID, LocalDate, BigDecimal and enums bind from path and query`() {
            val app = Colleen()

            app.get("/u/{id}", ::byId)
            app.get("/d", ::byDate)
            app.get("/p", ::byPrice)
            app.get("/c", ::byColor)

            val client = TestClient(app)
            val uuid = "123e4567-e89b-12d3-a456-426614174000"

            assertEquals(uuid, client.get("/u/$uuid").send().text())
            assertEquals("2026-07-06", client.get("/d").query("date", "2026-07-06").send().text())
            assertEquals("19.99", client.get("/p").query("price", "19.99").send().text())
            assertEquals("GREEN", client.get("/c").query("color", "green").send().text())
        }

        @Test
        fun `4_1 - invalid values for extended scalar types produce 400, not 500`() {
            val app = Colleen()
            app.get("/u/{id}", ::byId)

            val response = TestClient(app).get("/u/not-a-uuid").send()
            assertEquals(400, response.status)
        }

        @Test
        fun `4_1 - unsupported Path target type fails at registration, not per request`() {
            val app = Colleen()

            assertThrows<IllegalArgumentException> { app.get("/u/{id}", ::badTarget) }
        }

        @Test
        fun `2_7 - FilePart save works for a bare filename without parent directory`() {
            val name = "review-fix-2-7-${UUID.randomUUID()}.bin"
            val part = FilePart(
                name = "file",
                filename = name,
                contentType = "application/octet-stream",
                size = 3,
                inputStream = byteArrayOf(1, 2, 3).inputStream()
            )

            try {
                part.save(name) // relative to the working directory; parent == null
                assertTrue(Files.exists(Paths.get(name)))
            } finally {
                Files.deleteIfExists(Paths.get(name))
            }
        }
    }

    // ========================================================================
    // Controller / DTO inheritance (4.11)
    // ========================================================================

    @Nested
    inner class ControllerInheritance {

        fun createDog(@Suppress("unused") dog: Json<DogDto>): String = "ok"

        @Test
        fun `4_11 - annotated methods inherited from a base controller are registered`() {
            val app = Colleen()
            app.addController(SubController())

            val client = TestClient(app)
            assertEquals("own", client.get("/sub/own").send().text())
            assertEquals("from-base", client.get("/sub/inherited").send().text())
        }

        @Test
        fun `4_11 - un-annotated override keeps the route and dispatches to the override`() {
            val app = Colleen()
            app.addController(SubController())

            // @Get lives on the base declaration; the response must come from
            // the subclass implementation (virtual dispatch)
            assertEquals("sub-impl", TestClient(app).get("/sub/overridden").send().text())
        }

        @Test
        fun `4_11 - re-annotated override replaces the base registration`() {
            val app = Colleen()
            app.addController(SubController())

            assertEquals("sub-impl", TestClient(app).get("/sub/re-annotated").send().text())
        }

        @Test
        fun `4_11 - inherited DTO fields appear in the OpenAPI schema`() {
            val app = Colleen()
            app.openApi()
            app.post("/dogs", ::createDog)

            val spec = TestClient(app).get("/openapi.json").send().json<Map<String, Any?>>()!!

            @Suppress("UNCHECKED_CAST")
            val components = spec["components"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val schemas = components["schemas"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val dog = schemas["DogDto"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val properties = dog["properties"] as Map<String, Any?>

            assertTrue("barks" in properties, "own field must be present")
            assertTrue("kind" in properties, "inherited field must be present")
        }
    }

    // ========================================================================
    // Dependency injection
    // ========================================================================

    @Nested
    inner class DependencyInjection {

        @Test
        fun `4_4 - circular singleton dependencies produce a clear error with the chain`() {
            val container = ServiceContainer()
            container.registerSingleton<ServiceA> { ServiceA(container.get<ServiceB>()) }
            container.registerSingleton<ServiceB> { ServiceB(container.get<ServiceA>()) }

            val ex = assertThrows<IllegalStateException> { container.get<ServiceA>() }
            assertTrue(
                ex.message!!.contains("Circular"),
                "expected circular-dependency message, got: ${ex.message}"
            )
            assertTrue(ex.message!!.contains("ServiceA"))
            assertTrue(ex.message!!.contains("ServiceB"))
        }
    }

    enum class Color { RED, GREEN, BLUE }
    class ServiceA(@Suppress("unused") val b: ServiceB)
    class ServiceB(@Suppress("unused") val a: ServiceA)

    open class BaseController {
        @Get("/inherited")
        fun inherited(): String = "from-base"

        @Get("/overridden")
        open fun overridden(): String = "base-impl"

        @Get("/re-annotated")
        open fun reAnnotated(): String = "base-impl"
    }

    @Controller("/sub")
    class SubController : BaseController() {
        @Get("/own")
        fun own(): String = "own"

        // No annotation here: the route defined on the base method must survive
        override fun overridden(): String = "sub-impl"

        @Get("/re-annotated")
        override fun reAnnotated(): String = "sub-impl"
    }

    open class BaseDto {
        @Suppress("unused")
        val kind: String = "animal"
    }

    class DogDto : BaseDto() {
        @Suppress("unused")
        val barks: Boolean = true
    }
}
