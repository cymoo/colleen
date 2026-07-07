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
 * Per-request processing timeout (ServerConfig.requestTimeout)
 * — review finding 3.10.
 */
class RequestTimeoutTest {

    private fun appWithTimeout(timeoutMillis: Long): Colleen {
        val app = Colleen()
        app.config { server { requestTimeout = timeoutMillis } }
        return app
    }

    @Nested
    inner class Basics {

        @Test
        fun `a handler exceeding the deadline answers 503 REQUEST_TIMEOUT`() {
            val app = appWithTimeout(100)
            app.get("/slow") {
                Thread.sleep(5_000)
                "never reached"
            }

            val start = System.nanoTime()
            val response = TestClient(app).get("/slow")
                .header("Accept", "application/json")
                .send()
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000

            assertEquals(503, response.status)
            assertEquals("REQUEST_TIMEOUT", response.json<Map<String, Any?>>()!!["code"])
            assertTrue(elapsedMillis < 3_000, "the response must not wait for the full sleep (took ${elapsedMillis}ms)")
        }

        @Test
        fun `fast handlers are unaffected`() {
            val app = appWithTimeout(1_000)
            app.get("/fast") { "ok" }

            val response = TestClient(app).get("/fast").send()

            assertEquals(200, response.status)
            assertEquals("ok", response.text())
        }

        @Test
        fun `disabled by default - slow handlers complete normally`() {
            val app = Colleen() // requestTimeout = 0
            app.get("/slowish") {
                Thread.sleep(150)
                "done"
            }

            val response = TestClient(app).get("/slowish").send()

            assertEquals(200, response.status)
            assertEquals("done", response.text())
        }

        @Test
        fun `requests after a timeout are served normally`() {
            val app = appWithTimeout(100)
            app.get("/slow") { Thread.sleep(5_000); "never" }
            app.get("/fast") { "ok" }

            val client = TestClient(app)
            assertEquals(503, client.get("/slow").send().status)
            // The watchdog must not leak interrupts or poison later requests
            assertEquals(200, client.get("/fast").send().status)
            assertEquals(503, client.get("/slow").send().status)
        }
    }

    @Nested
    inner class Semantics {

        @Test
        fun `onError can customize the timeout response`() {
            val app = appWithTimeout(100)
            app.onError<RequestTimeout> { e, ctx ->
                ctx.status(503).text("deadline was ${e.timeoutMillis}ms")
            }
            app.get("/slow") { Thread.sleep(5_000); "never" }

            val response = TestClient(app).get("/slow").send()

            assertEquals(503, response.status)
            assertEquals("deadline was 100ms", response.text())
        }

        @Test
        fun `the root deadline covers mounted sub-apps`() {
            val parent = appWithTimeout(100)
            val child = Colleen()
            child.get("/slow") { Thread.sleep(5_000); "never" }
            parent.mount("/api", child)

            val response = TestClient(parent).get("/api/slow")
                .header("Accept", "application/json")
                .send()

            assertEquals(503, response.status)
            assertEquals("REQUEST_TIMEOUT", response.json<Map<String, Any?>>()!!["code"])
        }

        @Test
        fun `middleware after-phase still runs on timeout`() {
            val app = appWithTimeout(100)
            var afterRan = false
            var sawError = false
            app.use { ctx, next ->
                next()
                afterRan = true
                // Inside the chain the RAW interruption is visible; translation
                // to RequestTimeout happens outside the chain, so error handlers
                // (onError / the default 503) see RequestTimeout instead.
                sawError = ctx.error != null
            }
            app.get("/slow") { Thread.sleep(5_000); "never" }

            TestClient(app).get("/slow").send()

            assertTrue(afterRan, "the middleware after-phase must run (onion symmetry)")
            assertTrue(sawError, "ctx.error must be populated on timeout")
        }

        @Test
        fun `cooperative semantics - a handler that swallows the interrupt completes normally`() {
            val app = appWithTimeout(100)
            app.get("/stubborn") {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    // handler chooses to ignore the deadline
                }
                "finished anyway"
            }

            val response = TestClient(app).get("/stubborn").send()

            // Documented behavior: the timeout is cooperative. A handler that
            // catches the interrupt and completes produces a normal response.
            assertEquals(200, response.status)
            assertEquals("finished anyway", response.text())
        }
    }

    // ========================================================================
    // Wire-level behavior (real server)
    // ========================================================================

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class WireLevel {

        private val port = 18926
        private val baseUrl = "http://127.0.0.1:$port"
        private lateinit var app: Colleen
        private lateinit var client: HttpClient

        @BeforeAll
        fun setup() {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

            app = Colleen()
            app.config { server { requestTimeout = 200 } }
            app.get("/slow") { Thread.sleep(10_000); "never" }
            app.get("/fast") { "ok" }

            app.listen(port = port, host = "127.0.0.1")
        }

        @AfterAll
        fun teardown() {
            app.stop()
        }

        @Test
        fun `timeout produces a prompt 503 over the wire`() {
            val start = System.nanoTime()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/slow"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000

            assertEquals(503, response.statusCode())
            assertTrue(elapsedMillis < 3_000, "503 must arrive promptly (took ${elapsedMillis}ms)")

            // And the server keeps serving
            val fast = client.send(
                HttpRequest.newBuilder().uri(URI.create("$baseUrl/fast")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, fast.statusCode())
        }
    }
}
