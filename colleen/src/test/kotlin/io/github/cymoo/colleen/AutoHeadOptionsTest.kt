package io.github.cymoo.colleen

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Automatic HEAD (served from GET routes) and automatic OPTIONS (Allow header)
 * — review finding 1.21.
 */
class AutoHeadOptionsTest {

    // ========================================================================
    // Automatic HEAD
    // ========================================================================

    @Nested
    inner class AutoHead {

        @Test
        fun `HEAD is served by the GET route when no HEAD route exists`() {
            val app = Colleen()
            app.get("/doc") { ctx ->
                ctx.header("X-From-Get", "yes")
                "document body"
            }

            val response = TestClient(app).head("/doc").send()

            assertEquals(200, response.status)
            // The GET handler ran (headers are produced); the body is suppressed
            // at the server write layer, not here (TestClient sees the pre-write view)
            assertEquals("yes", response.header("X-From-Get"))
        }

        @Test
        fun `explicit HEAD route wins over the GET fallback`() {
            val app = Colleen()
            app.get("/doc") { ctx ->
                ctx.header("X-Handler", "get")
                "body"
            }
            app.head("/doc") { ctx ->
                ctx.header("X-Handler", "head")
                ctx.status(204)
            }

            val response = TestClient(app).head("/doc").send()

            assertEquals(204, response.status)
            assertEquals("head", response.header("X-Handler"))
        }

        @Test
        fun `HEAD on a path without GET stays 405 with Allow`() {
            val app = Colleen()
            app.post("/submit") { "ok" }

            val response = TestClient(app).head("/submit").send()

            assertEquals(405, response.status)
            assertEquals("OPTIONS, POST", response.header("Allow"))
        }

        @Test
        fun `HEAD on an unknown path stays 404`() {
            val app = Colleen()
            app.get("/known") { "ok" }

            assertEquals(404, TestClient(app).head("/unknown").send().status)
        }

        @Test
        fun `HEAD falls back to GET inside mounted sub-apps`() {
            val parent = Colleen()
            val child = Colleen()
            child.get("/page") { "child body" }
            parent.mount("/site", child)

            assertEquals(200, TestClient(parent).head("/site/page").send().status)
        }
    }

    // ========================================================================
    // Automatic OPTIONS
    // ========================================================================

    @Nested
    inner class AutoOptions {

        @Test
        fun `OPTIONS answers 204 with Allow for a routed path`() {
            val app = Colleen()
            app.get("/resource") { "ok" }
            app.put("/resource") { "ok" }

            val response = TestClient(app).options("/resource").send()

            assertEquals(204, response.status)
            // HEAD is implied by GET; OPTIONS is implied by any route
            assertEquals("GET, HEAD, OPTIONS, PUT", response.header("Allow"))
        }

        @Test
        fun `explicit OPTIONS route wins over the automatic response`() {
            val app = Colleen()
            app.get("/resource") { "ok" }
            app.options("/resource") { ctx ->
                ctx.header("X-Custom-Options", "yes")
                ctx.status(200).text("custom")
            }

            val response = TestClient(app).options("/resource").send()

            assertEquals(200, response.status)
            assertEquals("custom", response.text())
            assertEquals("yes", response.header("X-Custom-Options"))
        }

        @Test
        fun `OPTIONS on an unknown path stays 404`() {
            val app = Colleen()
            app.get("/known") { "ok" }

            assertEquals(404, TestClient(app).options("/unknown").send().status)
        }

        @Test
        fun `OPTIONS works for paths served only by a mounted sub-app`() {
            val parent = Colleen()
            val child = Colleen()
            child.post("/things") { "created" }
            parent.mount("/api", child)

            val response = TestClient(parent).options("/api/things").send()

            assertEquals(204, response.status)
            assertEquals("OPTIONS, POST", response.header("Allow"))
        }

        @Test
        fun `405 Allow now includes automatic HEAD and OPTIONS`() {
            val app = Colleen()
            app.get("/resource") { "ok" }

            val response = TestClient(app).post("/resource").send()

            assertEquals(405, response.status)
            assertEquals("GET, HEAD, OPTIONS", response.header("Allow"))
        }

        @Test
        fun `CORS preflight still short-circuits before the automatic OPTIONS`() {
            val app = Colleen()
            app.use(io.github.cymoo.colleen.middleware.Cors(allowOrigins = setOf("https://example.com")))
            app.get("/resource") { "ok" }

            val response = TestClient(app).options("/resource")
                .header("Origin", "https://example.com")
                .send()

            assertEquals(204, response.status)
            // CORS middleware answered (preflight headers present, no Allow header)
            assertEquals(
                "https://example.com",
                response.header("Access-Control-Allow-Origin")
            )
        }
    }

    // ========================================================================
    // Wire-level behavior (real server)
    // ========================================================================

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class WireLevel {

        private val port = 18924
        private val baseUrl = "http://127.0.0.1:$port"
        private lateinit var app: Colleen
        private lateinit var client: HttpClient

        @BeforeAll
        fun setup() {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

            app = Colleen()
            app.get("/doc") { "hello head" }
            app.put("/doc") { "updated" }

            app.listen(port = port, host = "127.0.0.1")
        }

        @AfterAll
        fun teardown() {
            app.stop()
        }

        @Test
        fun `HEAD response has GET's Content-Length but an empty body`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/doc"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals(
                "hello head".length.toString(),
                response.headers().firstValue("Content-Length").orElse(null)
            )
            assertTrue(response.body().isEmpty(), "HEAD response must not carry a body")
        }

        @Test
        fun `OPTIONS response carries Allow over the wire`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/doc"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(204, response.statusCode())
            assertEquals(
                "GET, HEAD, OPTIONS, PUT",
                response.headers().firstValue("Allow").orElse(null)
            )
        }
    }
}
