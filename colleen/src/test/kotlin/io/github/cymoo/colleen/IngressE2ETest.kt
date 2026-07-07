package io.github.cymoo.colleen

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
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
 * E2E tests for request-ingress behavior that CANNOT be covered by TestClient
 * (which bypasses the Undertow layer): path decoding, header-name parsing,
 * proxy-header handling and response framing.
 *
 * Covers review findings 1.1, 5.1, 5.4 and 3.6.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngressE2ETest {

    private val port = 18923
    private val baseUrl = "http://127.0.0.1:$port"
    private lateinit var app: Colleen
    private lateinit var client: HttpClient

    @BeforeAll
    fun setup() {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        app = Colleen()
        app.config { server { trustedProxyCount = 1 } }

        app.get("/echo/{value}") { ctx -> ctx.pathParam("value") ?: "" }
        app.get("/header-echo") { ctx -> ctx.header("X-My.Header") ?: "missing" }
        app.get("/secure") { ctx -> ctx.request.serverInfo.isSecure.toString() }
        app.get("/big") { ctx -> ctx.bytes(ByteArray(64 * 1024) { 'a'.code.toByte() }, "text/plain") }
        app.get("/only-get") { "ok" }

        // listen() starts Undertow synchronously — the port is bound on return
        app.listen(port = port, host = "127.0.0.1")
    }

    @AfterAll
    fun teardown() {
        app.stop()
    }

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(5))
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    // ========================================================================
    // 1.1 — the request path must be decoded exactly once
    // ========================================================================

    @Test
    fun `1_1 - double-encoded value is decoded exactly once`() {
        // Wire: %2541 → one decode → %41. A second decode would yield "A".
        val response = get("/echo/100%2541")
        assertEquals(200, response.statusCode())
        assertEquals("100%41", response.body())
    }

    @Test
    fun `1_1 - literal plus stays a plus in paths`() {
        val response = get("/echo/a+b")
        assertEquals(200, response.statusCode())
        assertEquals("a+b", response.body())
    }

    @Test
    fun `1_1 - literal percent value does not crash the request`() {
        // %25 → "%" — before the fix the second decode threw and produced a 500
        val response = get("/echo/50%25")
        assertEquals(200, response.statusCode())
        assertEquals("50%", response.body())
    }

    @Test
    fun `1_1 - encoded slash cannot change the path structure`() {
        // a%2Fb must remain ONE segment; decoding it to a/b would let it
        // impersonate a two-segment path (path-confusion / smuggling)
        val response = get("/echo/a%2Fb")
        assertEquals(200, response.statusCode())
        assertEquals("a%2Fb", response.body())
    }

    @Test
    fun `1_1 - encoded dot segments are rejected with 400, not 500`() {
        // %2e%2e decodes (once, by the server) to ".." which normalize rejects
        val response = get("/echo/%2e%2e/x")
        assertEquals(400, response.statusCode())
    }

    // ========================================================================
    // 5.1 — RFC 9110 token header names must be accepted
    // ========================================================================

    @Test
    fun `5_1 - header names containing a dot are accepted`() {
        // Before the fix any request carrying such a header answered 500
        val response = get("/header-echo", "X-My.Header" to "v1")
        assertEquals(200, response.statusCode())
        assertEquals("v1", response.body())
    }

    // ========================================================================
    // 5.4 — X-Forwarded-Proto honored only behind a trusted proxy
    // ========================================================================

    @Test
    fun `5_4 - forwarded proto marks the request secure when proxies are trusted`() {
        val response = get("/secure", "X-Forwarded-Proto" to "https")
        assertEquals("true", response.body())
    }

    @Test
    fun `5_4 - without forwarded proto the request stays insecure`() {
        val response = get("/secure")
        assertEquals("false", response.body())
    }

    // ========================================================================
    // 3.6 — fully materialized byte responses advertise Content-Length
    // ========================================================================

    @Test
    fun `3_6 - large byte responses carry Content-Length instead of chunked encoding`() {
        val response = get("/big")
        assertEquals(200, response.statusCode())
        assertEquals((64 * 1024).toString(), response.headers().firstValue("Content-Length").orElse(null))
        assertTrue(response.headers().firstValue("Transfer-Encoding").isEmpty)
    }

    // ========================================================================
    // 1.3 — 405 over the wire carries Allow
    // ========================================================================

    @Test
    fun `1_3 - 405 responses carry the Allow header end to end`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/only-get"))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(405, response.statusCode())
        // HEAD/OPTIONS are served automatically, so they appear in Allow too
        assertEquals("GET, HEAD, OPTIONS", response.headers().firstValue("Allow").orElse(null))
    }
}
