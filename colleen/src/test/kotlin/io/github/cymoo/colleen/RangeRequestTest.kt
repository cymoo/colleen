package io.github.cymoo.colleen

import io.github.cymoo.colleen.middleware.ServeStatic
import io.github.cymoo.colleen.util.http.BoundedInputStream
import io.github.cymoo.colleen.util.http.RangeOutcome
import io.github.cymoo.colleen.util.http.RangeSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Range / 206 Partial Content support for sendFile and ServeStatic
 * — review findings 2.13 / 5.8.
 */
class RangeRequestTest {

    // ========================================================================
    // RangeSupport.evaluate — parsing and semantics
    // ========================================================================

    @Nested
    inner class Evaluate {

        private fun eval(
            range: String?,
            total: Long = 100,
            method: String = "GET",
            ifRange: String? = null,
            lastModified: String? = "Mon, 06 Jul 2026 00:00:00 GMT",
        ) = RangeSupport.evaluate(method, range, ifRange, lastModified, total)

        @Test
        fun `no header - full`() {
            assertEquals(RangeOutcome.Full, eval(null))
        }

        @Test
        fun `explicit range`() {
            assertEquals(RangeOutcome.Partial(0, 49, 100), eval("bytes=0-49"))
            assertEquals(RangeOutcome.Partial(10, 20, 100), eval("bytes=10-20"))
        }

        @Test
        fun `open-ended range reaches the last byte`() {
            assertEquals(RangeOutcome.Partial(40, 99, 100), eval("bytes=40-"))
        }

        @Test
        fun `suffix range takes the last N bytes`() {
            assertEquals(RangeOutcome.Partial(90, 99, 100), eval("bytes=-10"))
            // Suffix longer than the resource clamps to the whole resource
            assertEquals(RangeOutcome.Partial(0, 99, 100), eval("bytes=-500"))
        }

        @Test
        fun `end beyond the resource is clamped`() {
            assertEquals(RangeOutcome.Partial(50, 99, 100), eval("bytes=50-1000"))
        }

        @Test
        fun `start past the end is unsatisfiable`() {
            assertEquals(RangeOutcome.Unsatisfiable(100), eval("bytes=100-"))
            assertEquals(RangeOutcome.Unsatisfiable(100), eval("bytes=500-600"))
        }

        @Test
        fun `zero-length suffix is unsatisfiable`() {
            assertEquals(RangeOutcome.Unsatisfiable(100), eval("bytes=-0"))
        }

        @Test
        fun `malformed and unusual specs fall back to full`() {
            // RFC 9110 §14.2: a server MAY ignore the Range header
            assertEquals(RangeOutcome.Full, eval("bytes=abc-def"))
            assertEquals(RangeOutcome.Full, eval("bytes=20-10"))      // inverted
            assertEquals(RangeOutcome.Full, eval("bytes=0-1,5-9"))    // multi-range
            assertEquals(RangeOutcome.Full, eval("items=0-10"))       // unknown unit
            assertEquals(RangeOutcome.Full, eval("bytes=-"))
            assertEquals(RangeOutcome.Full, eval("bytes=5"))          // no dash
        }

        @Test
        fun `non-GET methods ignore Range`() {
            assertEquals(RangeOutcome.Full, eval("bytes=0-9", method = "POST"))
            assertEquals(RangeOutcome.Full, eval("bytes=0-9", method = "HEAD"))
        }

        @Test
        fun `unknown length ignores Range`() {
            assertEquals(RangeOutcome.Full, eval("bytes=0-9", total = -1))
        }

        @Test
        fun `If-Range matching the validator allows the partial response`() {
            val lm = "Mon, 06 Jul 2026 00:00:00 GMT"
            assertEquals(
                RangeOutcome.Partial(0, 9, 100),
                eval("bytes=0-9", ifRange = lm, lastModified = lm)
            )
        }

        @Test
        fun `If-Range mismatch forces the full response`() {
            assertEquals(
                RangeOutcome.Full,
                eval("bytes=0-9", ifRange = "Tue, 07 Jul 2026 00:00:00 GMT")
            )
            // No Last-Modified available (e.g. JAR entry) — never satisfy If-Range
            assertEquals(
                RangeOutcome.Full,
                eval("bytes=0-9", ifRange = "\"some-etag\"", lastModified = null)
            )
        }
    }

    // ========================================================================
    // BoundedInputStream / openSlice
    // ========================================================================

    @Nested
    inner class Slicing {

        @Test
        fun `openSlice returns exactly the requested window`() {
            val data = ByteArray(100) { it.toByte() }

            val slice = RangeSupport.openSlice({ data.inputStream() }, 10, 5)
            assertEquals(listOf<Byte>(10, 11, 12, 13, 14), slice.readBytes().toList())
        }

        @Test
        fun `bounded stream stops at the limit even with a longer source`() {
            val bounded = BoundedInputStream("abcdef".byteInputStream(), 3)
            assertEquals("abc", bounded.readBytes().toString(Charsets.UTF_8))
            assertEquals(-1, bounded.read())
        }
    }

    // ========================================================================
    // sendFile integration (TestClient)
    // ========================================================================

    @Nested
    inner class SendFile {

        private fun appServing(file: File): Colleen {
            val app = Colleen()
            app.get("/download") { ctx -> ctx.sendFile(file.absolutePath) }
            return app
        }

        @Test
        fun `full responses advertise Accept-Ranges`(@TempDir dir: Path) {
            val file = dir.resolve("data.bin").toFile().apply { writeText("0123456789") }

            val response = TestClient(appServing(file)).get("/download").send()

            assertEquals(200, response.status)
            assertEquals("bytes", response.header("Accept-Ranges"))
            assertEquals("0123456789", response.text())
        }

        @Test
        fun `range requests answer 206 with the requested slice`(@TempDir dir: Path) {
            val file = dir.resolve("data.bin").toFile().apply { writeText("0123456789") }

            val response = TestClient(appServing(file)).get("/download")
                .header("Range", "bytes=2-5")
                .send()

            assertEquals(206, response.status)
            assertEquals("bytes 2-5/10", response.header("Content-Range"))
            assertEquals("4", response.header("Content-Length"))
            assertEquals("2345", response.text())
        }

        @Test
        fun `suffix range serves the file tail`(@TempDir dir: Path) {
            val file = dir.resolve("data.bin").toFile().apply { writeText("0123456789") }

            val response = TestClient(appServing(file)).get("/download")
                .header("Range", "bytes=-3")
                .send()

            assertEquals(206, response.status)
            assertEquals("bytes 7-9/10", response.header("Content-Range"))
            assertEquals("789", response.text())
        }

        @Test
        fun `unsatisfiable range answers 416`(@TempDir dir: Path) {
            val file = dir.resolve("data.bin").toFile().apply { writeText("0123456789") }

            val response = TestClient(appServing(file)).get("/download")
                .header("Range", "bytes=100-")
                .send()

            assertEquals(416, response.status)
            assertEquals("bytes */10", response.header("Content-Range"))
        }

        @Test
        fun `multi-range requests are served in full`(@TempDir dir: Path) {
            val file = dir.resolve("data.bin").toFile().apply { writeText("0123456789") }

            val response = TestClient(appServing(file)).get("/download")
                .header("Range", "bytes=0-1,4-5")
                .send()

            assertEquals(200, response.status)
            assertEquals("0123456789", response.text())
        }

        @Test
        fun `If-Range with a stale validator serves the full body`(@TempDir dir: Path) {
            val file = dir.resolve("data.bin").toFile().apply { writeText("0123456789") }

            val response = TestClient(appServing(file)).get("/download")
                .header("Range", "bytes=0-3")
                .header("If-Range", "Mon, 01 Jan 1990 00:00:00 GMT")
                .send()

            assertEquals(200, response.status)
            assertEquals("0123456789", response.text())
        }

        @Test
        fun `If-Modified-Since keeps precedence over Range`(@TempDir dir: Path) {
            val file = dir.resolve("data.bin").toFile().apply { writeText("0123456789") }
            val app = appServing(file)

            // First fetch to learn the validator
            val lastModified = TestClient(app).get("/download").send().header("Last-Modified")!!

            val response = TestClient(app).get("/download")
                .header("If-Modified-Since", lastModified)
                .header("Range", "bytes=0-3")
                .send()

            assertEquals(304, response.status)
        }
    }

    // ========================================================================
    // ServeStatic integration (TestClient)
    // ========================================================================

    @Nested
    inner class ServeStaticRange {

        private fun appServing(dir: Path): Colleen {
            val app = Colleen()
            app.use(ServeStatic(dir.toString(), baseUrl = "/static", fallthrough = false))
            return app
        }

        @Test
        fun `range requests answer 206`(@TempDir dir: Path) {
            dir.resolve("file.txt").toFile().writeText("abcdefghij")

            val response = TestClient(appServing(dir)).get("/static/file.txt")
                .header("Range", "bytes=3-6")
                .send()

            assertEquals(206, response.status)
            assertEquals("bytes 3-6/10", response.header("Content-Range"))
            assertEquals("defg", response.text())
        }

        @Test
        fun `full responses advertise Accept-Ranges`(@TempDir dir: Path) {
            dir.resolve("file.txt").toFile().writeText("abcdefghij")

            val response = TestClient(appServing(dir)).get("/static/file.txt").send()

            assertEquals(200, response.status)
            assertEquals("bytes", response.header("Accept-Ranges"))
        }

        @Test
        fun `unsatisfiable range answers 416`(@TempDir dir: Path) {
            dir.resolve("file.txt").toFile().writeText("abcdefghij")

            val response = TestClient(appServing(dir)).get("/static/file.txt")
                .header("Range", "bytes=50-")
                .send()

            assertEquals(416, response.status)
            assertEquals("bytes */10", response.header("Content-Range"))
        }

        @Test
        fun `HEAD ignores Range and keeps full Content-Length`(@TempDir dir: Path) {
            dir.resolve("file.txt").toFile().writeText("abcdefghij")

            val response = TestClient(appServing(dir)).head("/static/file.txt")
                .header("Range", "bytes=0-3")
                .send()

            assertEquals(200, response.status)
            assertEquals("10", response.header("Content-Length"))
        }
    }

    // ========================================================================
    // Wire-level behavior (real server)
    // ========================================================================

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class WireLevel {

        private val port = 18925
        private val baseUrl = "http://127.0.0.1:$port"
        private lateinit var app: Colleen
        private lateinit var client: HttpClient
        private lateinit var file: File

        @BeforeAll
        fun setup() {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

            file = File.createTempFile("range-e2e", ".bin").apply {
                writeText("The quick brown fox jumps over the lazy dog")
                deleteOnExit()
            }

            app = Colleen()
            app.get("/video") { ctx -> ctx.sendFile(file.absolutePath) }
            app.listen(port = port, host = "127.0.0.1")
        }

        @AfterAll
        fun teardown() {
            app.stop()
            file.delete()
        }

        private fun get(vararg headers: Pair<String, String>): HttpResponse<String> {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/video"))
                .timeout(Duration.ofSeconds(5))
                .GET()
            headers.forEach { (k, v) -> builder.header(k, v) }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }

        @Test
        fun `206 slice arrives with correct framing over the wire`() {
            val total = file.length()
            val response = get("Range" to "bytes=4-8")

            assertEquals(206, response.statusCode())
            assertEquals("quick", response.body())
            assertEquals("bytes 4-8/$total", response.headers().firstValue("Content-Range").orElse(null))
            assertEquals("5", response.headers().firstValue("Content-Length").orElse(null))
        }

        @Test
        fun `resumed download - open-ended range returns the remainder`() {
            val full = get().body()
            val tail = get("Range" to "bytes=10-")

            assertEquals(206, tail.statusCode())
            assertEquals(full.substring(10), tail.body())
        }

        @Test
        fun `416 carries the unsatisfied Content-Range and no body`() {
            val total = file.length()
            val response = get("Range" to "bytes=9999-")

            assertEquals(416, response.statusCode())
            assertEquals("bytes */$total", response.headers().firstValue("Content-Range").orElse(null))
            assertTrue(response.body().isEmpty())
        }

        @Test
        fun `Accept-Ranges is advertised on the full response`() {
            val response = get()
            assertEquals("bytes", response.headers().firstValue("Accept-Ranges").orElse(null))
            assertNull(response.headers().firstValue("Content-Range").orElse(null))
        }
    }
}
