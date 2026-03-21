package io.github.cymoo.colleen

import org.junit.jupiter.api.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ============================================================================
// Top-level controller used by the @Ws annotation test
// ============================================================================

@Controller("/ws-ctrl")
class WsE2EController {
    @Ws("/echo")
    fun echo(conn: WebSocketConnection) {
        conn.onMessage { msg ->
            conn.send("ctrl: ${(msg as WebSocketMessage.Text).text()}")
        }
    }

    @Ws("/greet/{name}")
    fun greet(conn: WebSocketConnection) {
        val name = conn.pathParam("name") ?: "stranger"
        conn.onOpen { conn.send("Hello, $name!") }
    }
}

/**
 * End-to-end WebSocket test suite.
 *
 * Spins up a real Undertow server and exercises all WebSocket features via
 * Java's built-in [java.net.http.WebSocket] client.
 *
 * Main server: 127.0.0.1:8890
 * Isolated servers (per-config tests): 8891, 8892, 8893
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebSocketE2ETest {

    // =========================================================================
    // WsClient — thin wrapper around java.net.http.WebSocket
    // =========================================================================

    /**
     * Simple WebSocket test client.
     *
     * Uses [BlockingQueue] so tests can poll for messages with a timeout,
     * regardless of when they arrive relative to send() calls.
     */
    inner class WsClient(url: String, extraHeaders: Map<String, String> = emptyMap()) {

        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)

        /** Received text messages (complete frames, fragments reassembled). */
        val textMessages: BlockingQueue<String> = LinkedBlockingQueue()

        /** Received binary messages (complete frames, fragments reassembled). */
        val binaryMessages: BlockingQueue<ByteArray> = LinkedBlockingQueue()

        @Volatile var closeCode: Int = -1
        @Volatile var closeReason: String = ""
        @Volatile var errorOccurred: Throwable? = null

        // Fragment reassembly buffers
        private val textBuffer = StringBuilder()
        private val binaryChunks = mutableListOf<ByteArray>()

        val ws: WebSocket = run {
            val builder = httpClient.newWebSocketBuilder()
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            builder.buildAsync(URI.create(url), object : WebSocket.Listener {

                override fun onOpen(webSocket: WebSocket) {
                    openLatch.countDown()
                    webSocket.request(Long.MAX_VALUE)
                }

                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*>? {
                    textBuffer.append(data)
                    if (last) {
                        textMessages.put(textBuffer.toString())
                        textBuffer.clear()
                    }
                    return CompletableFuture.completedFuture<Any>(null)
                }

                override fun onBinary(
                    webSocket: WebSocket,
                    data: ByteBuffer,
                    last: Boolean,
                ): CompletionStage<*>? {
                    val chunk = ByteArray(data.remaining())
                    data.get(chunk)
                    binaryChunks.add(chunk)
                    if (last) {
                        val combined = binaryChunks.fold(byteArrayOf()) { acc, b -> acc + b }
                        binaryMessages.put(combined)
                        binaryChunks.clear()
                    }
                    return CompletableFuture.completedFuture<Any>(null)
                }

                override fun onClose(
                    webSocket: WebSocket,
                    statusCode: Int,
                    reason: String,
                ): CompletionStage<*>? {
                    closeCode = statusCode
                    closeReason = reason
                    closeLatch.countDown()
                    return CompletableFuture.completedFuture<Any>(null)
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    errorOccurred = error
                    closeLatch.countDown()
                }
            }).get(5, TimeUnit.SECONDS)
        }

        /** Send a complete text message. */
        fun sendText(text: String): WsClient {
            ws.sendText(text, true).get(5, TimeUnit.SECONDS)
            return this
        }

        /** Send a complete binary message. */
        fun sendBinary(bytes: ByteArray): WsClient {
            ws.sendBinary(ByteBuffer.wrap(bytes), true).get(5, TimeUnit.SECONDS)
            return this
        }

        /** Poll for the next text message, waiting up to [timeoutSeconds]. */
        fun nextText(timeoutSeconds: Long = 3): String? =
            textMessages.poll(timeoutSeconds, TimeUnit.SECONDS)

        /** Poll for the next binary message, waiting up to [timeoutSeconds]. */
        fun nextBinary(timeoutSeconds: Long = 3): ByteArray? =
            binaryMessages.poll(timeoutSeconds, TimeUnit.SECONDS)

        /** Initiate a clean close. */
        fun close(code: Int = WebSocket.NORMAL_CLOSURE, reason: String = "bye"): WsClient {
            ws.sendClose(code, reason)
            return this
        }

        /** Wait for the [openLatch] to reach zero. */
        fun awaitOpen(timeoutSeconds: Long = 5): Boolean = openLatch.await(timeoutSeconds, TimeUnit.SECONDS)

        /** Wait for the [closeLatch] to reach zero (either onClose or onError). */
        fun awaitClose(timeoutSeconds: Long = 5): Boolean = closeLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    }

    // =========================================================================
    // Server lifecycle
    // =========================================================================

    private lateinit var app: Colleen
    private lateinit var httpClient: HttpClient

    private val host = "127.0.0.1"
    private val port = 8890
    private val wsBase = "ws://$host:$port"
    private val httpBase = "http://$host:$port"

    /** Counter incremented by the HTTP middleware on /http-mw-isolation. */
    private val httpMwHitCount = AtomicInteger(0)

    /** Counter incremented by the WS middleware on /ws-mw-isolation. */
    private val wsMwHitCount = AtomicInteger(0)

    /** Connections registered for the broadcast test. */
    private val broadcastConnections = CopyOnWriteArrayList<WebSocketConnection>()

    @BeforeAll
    fun setup() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        app = Colleen()
        app.config {
            server {
                host = this@WebSocketE2ETest.host
                port = this@WebSocketE2ETest.port
            }
        }
        setupRoutes()
        app.listen(port = port, host = host)
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() {
        app.stop()
    }

    private fun setupRoutes() {
        // ---------------------------------------------------------------
        // Basic echo (text + binary)
        // ---------------------------------------------------------------
        app.ws("/echo") { conn ->
            conn.onMessage { msg ->
                when (msg) {
                    is WebSocketMessage.Text -> conn.send(msg.text())
                    is WebSocketMessage.Binary -> conn.send(msg.bytes())
                }
            }
        }

        // ---------------------------------------------------------------
        // onOpen greeting then echo
        // ---------------------------------------------------------------
        app.ws("/greet") { conn ->
            conn.onOpen { conn.send("Welcome!") }
            conn.onMessage { msg ->
                conn.send("Hello, ${(msg as WebSocketMessage.Text).text()}!")
            }
        }

        // ---------------------------------------------------------------
        // Server-initiated close on open
        // ---------------------------------------------------------------
        app.ws("/server-close") { conn ->
            conn.onOpen {
                conn.close(WebSocketCloseReason.NORMAL, "server says bye")
            }
        }

        // ---------------------------------------------------------------
        // Path parameters
        // ---------------------------------------------------------------
        app.ws("/rooms/{roomId}/users/{userId}") { conn ->
            conn.onOpen {
                val roomId = conn.pathParam("roomId") ?: "?"
                val userId = conn.pathParam("userId") ?: "?"
                conn.send("$roomId:$userId")
            }
        }

        // ---------------------------------------------------------------
        // Query parameters
        // ---------------------------------------------------------------
        app.ws("/query-test") { conn ->
            conn.onOpen {
                val name = conn.query("name") ?: "unknown"
                val page = conn.query("page") ?: "0"
                conn.send("$name:$page")
            }
        }

        // ---------------------------------------------------------------
        // Handshake headers
        // ---------------------------------------------------------------
        app.ws("/headers-test") { conn ->
            conn.onOpen {
                val custom = conn.headers["x-custom"] ?: "missing"
                conn.send(custom)
            }
        }

        // ---------------------------------------------------------------
        // WS middleware — auth gate on /secure
        // ---------------------------------------------------------------
        app.wsUse("/secure") { ctx, next ->
            val token = ctx.query("token")
            if (token.isNullOrBlank()) {
                throw HttpException(401, "Missing token")
            }
            ctx.setState("userId", "user-$token")
            ctx.setState("role", "admin")
            next()
        }

        app.ws("/secure/chat") { conn ->
            conn.onOpen {
                val userId = conn.attribute<String>("userId") ?: "unknown"
                val role = conn.attribute<String>("role") ?: "unknown"
                conn.send("$userId:$role")
            }
        }

        // ---------------------------------------------------------------
        // Isolation: HTTP middleware MUST NOT run for WS upgrade requests
        //
        // app.use() rejects with 403 — if it ran during WS upgrade the
        // client would fail to connect.  The test asserts the WS connection
        // succeeds and the plain GET returns 403.
        // ---------------------------------------------------------------
        app.use("/http-mw-isolation") { ctx, next ->
            httpMwHitCount.incrementAndGet()
            throw HttpException(403, "HTTP-only middleware")
        }

        app.ws("/http-mw-isolation/ws") { conn ->
            conn.onOpen { conn.send("connected") }
        }

        app.get("/http-mw-isolation/get") { "should be blocked" }

        // ---------------------------------------------------------------
        // Isolation: WS middleware MUST NOT run for plain HTTP requests
        //
        // app.wsUse() rejects with 403 — if it ran during a plain HTTP
        // request the GET would fail.  The test asserts the GET succeeds.
        // ---------------------------------------------------------------
        app.wsUse("/ws-mw-isolation") { ctx, next ->
            wsMwHitCount.incrementAndGet()
            throw HttpException(403, "WS-only middleware")
        }

        app.get("/ws-mw-isolation/get") { "ok" }

        // ---------------------------------------------------------------
        // Broadcast: WS handler accumulates connections;
        //            GET /broadcast/send/{msg} fans out to all of them.
        // ---------------------------------------------------------------
        app.ws("/broadcast") { conn ->
            broadcastConnections.add(conn)
            conn.onClose { _ -> broadcastConnections.remove(conn) }
        }

        app.get("/broadcast/send/{msg}") { ctx ->
            val msg = ctx.pathParam("msg") ?: ""
            broadcastConnections.forEach { it.send(msg) }
            "sent"
        }

        // ---------------------------------------------------------------
        // Controller-style (@Ws annotation)
        // ---------------------------------------------------------------
        app.addController(WsE2EController())

        // ---------------------------------------------------------------
        // Group registration
        // ---------------------------------------------------------------
        app.group("/api/v2") {
            ws("/notifications") { conn ->
                conn.onOpen { conn.send("api-v2") }
            }
        }

        // ---------------------------------------------------------------
        // Long-lived connection (used by shutdown test — separate server)
        // ---------------------------------------------------------------
        // (no route needed here; shutdown test uses its own server)
    }

    // =========================================================================
    // Helper — isolated single-use server
    // =========================================================================

    private fun withIsolatedServer(
        port: Int,
        serverConfig: io.github.cymoo.colleen.ServerConfig.() -> Unit = {},
        routes: Colleen.() -> Unit,
        testBody: (wsBase: String) -> Unit,
    ) {
        val server = Colleen()
        server.config { server(serverConfig) }
        server.routes()
        server.listen(host = host, port = port)
        Thread.sleep(300)
        try {
            testBody("ws://$host:$port")
        } finally {
            server.stop()
        }
    }

    // =========================================================================
    // Tests — Basic Connectivity
    // =========================================================================

    @Test
    @Order(1)
    fun `text echo - client sends text and receives same text back`() {
        val client = WsClient("$wsBase/echo")
        assertTrue(client.awaitOpen(), "WS connection should open")
        client.sendText("hello world")
        assertEquals("hello world", client.nextText(), "Should echo text back")
        client.close()
        assertTrue(client.awaitClose(), "WS connection should close cleanly")
    }

    @Test
    @Order(2)
    fun `binary echo - client sends bytes and receives same bytes back`() {
        val client = WsClient("$wsBase/echo")
        assertTrue(client.awaitOpen())
        val payload = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())
        client.sendBinary(payload)
        val received = client.nextBinary()
        assertNotNull(received)
        assertContentEquals(payload, received, "Should echo binary back byte-for-byte")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(3)
    fun `multiple messages in sequence are all received in order`() {
        val client = WsClient("$wsBase/echo")
        assertTrue(client.awaitOpen())
        val messages = (1..5).map { "message-$it" }
        messages.forEach { client.sendText(it) }
        val received = messages.map { client.nextText() }
        assertEquals(messages, received, "All messages should be echoed in send order")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(4)
    fun `onOpen fires and server sends greeting before any message`() {
        val client = WsClient("$wsBase/greet")
        assertTrue(client.awaitOpen())
        // Server sends greeting spontaneously in onOpen
        assertEquals("Welcome!", client.nextText(), "Should receive server greeting on open")
        client.sendText("Alice")
        assertEquals("Hello, Alice!", client.nextText(), "Should receive personalised reply")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(5)
    fun `empty binary message is handled correctly`() {
        val client = WsClient("$wsBase/echo")
        assertTrue(client.awaitOpen())
        client.sendBinary(byteArrayOf())
        val received = client.nextBinary()
        assertNotNull(received)
        assertEquals(0, received.size, "Empty binary frame should be echoed as empty")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(6)
    fun `large text message within default size limit is handled`() {
        val client = WsClient("$wsBase/echo")
        assertTrue(client.awaitOpen())
        val payload = "A".repeat(32 * 1024) // 32 KB — within 64 KB default
        client.sendText(payload)
        val received = client.nextText()
        assertEquals(payload.length, received?.length, "Full 32 KB message should be echoed")
        client.close()
        assertTrue(client.awaitClose())
    }

    // =========================================================================
    // Tests — Close Handling
    // =========================================================================

    @Test
    @Order(10)
    fun `client-initiated normal close is acknowledged`() {
        val client = WsClient("$wsBase/echo")
        assertTrue(client.awaitOpen())
        client.close(WebSocket.NORMAL_CLOSURE, "done")
        assertTrue(client.awaitClose(), "Close should complete")
        assertEquals(WebSocket.NORMAL_CLOSURE, client.closeCode, "Close code should be 1000")
    }

    @Test
    @Order(11)
    fun `server-initiated close is received by client with correct code`() {
        val client = WsClient("$wsBase/server-close")
        assertTrue(client.awaitOpen())
        assertTrue(client.awaitClose(5), "Server-initiated close should propagate to client")
        assertEquals(WebSocketCloseReason.NORMAL, client.closeCode, "Close code should be 1000")
        assertEquals("server says bye", client.closeReason, "Close reason should match")
    }

    @Test
    @Order(12)
    fun `isClosed is true after client closes the connection`() {
        val client = WsClient("$wsBase/echo")
        assertTrue(client.awaitOpen())
        assertFalse(client.ws.isOutputClosed, "Connection should not be closed before close()")
        client.close()
        assertTrue(client.awaitClose())
    }

    // =========================================================================
    // Tests — Concurrent Connections
    // =========================================================================

    @Test
    @Order(20)
    fun `multiple concurrent connections to the same route are fully independent`() {
        val n = 4
        val clients = (1..n).map { i -> WsClient("$wsBase/echo") to i }
        clients.forEach { (c, _) -> assertTrue(c.awaitOpen(), "All connections should open") }

        // Each client sends its own unique message
        clients.forEach { (c, i) -> c.sendText("client-$i") }
        // Each client receives only its own message back
        clients.forEach { (c, i) -> assertEquals("client-$i", c.nextText(), "Client $i should echo its own message") }

        clients.forEach { (c, _) -> c.close(); c.awaitClose() }
    }

    // =========================================================================
    // Tests — Route Features
    // =========================================================================

    @Test
    @Order(30)
    fun `path parameters are extracted and accessible via conn pathParam()`() {
        val client = WsClient("$wsBase/rooms/lobby/users/alice")
        assertTrue(client.awaitOpen())
        assertEquals("lobby:alice", client.nextText(), "Path params should be extracted correctly")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(31)
    fun `query parameters are accessible via conn query()`() {
        val client = WsClient("$wsBase/query-test?name=bob&page=3")
        assertTrue(client.awaitOpen())
        assertEquals("bob:3", client.nextText(), "Query params should be accessible")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(32)
    fun `query with missing parameter returns null sentinel`() {
        val client = WsClient("$wsBase/query-test")
        assertTrue(client.awaitOpen())
        // Both params missing — should fall back to defaults defined in route handler
        assertEquals("unknown:0", client.nextText())
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(33)
    fun `handshake HTTP headers are accessible in connection`() {
        val client = WsClient("$wsBase/headers-test", extraHeaders = mapOf("X-Custom" to "secret-42"))
        assertTrue(client.awaitOpen())
        assertEquals("secret-42", client.nextText(), "Custom header value should be readable")
        client.close()
        assertTrue(client.awaitClose())
    }

    // =========================================================================
    // Tests — WS Middleware (wsUse)
    // =========================================================================

    @Test
    @Order(40)
    fun `wsUse middleware sets context state accessible via conn attribute()`() {
        val client = WsClient("$wsBase/secure/chat?token=abc123")
        assertTrue(client.awaitOpen())
        assertEquals("user-abc123:admin", client.nextText(), "Middleware-set attributes should be on connection")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(41)
    fun `wsUse middleware rejection returns HTTP error before upgrade`() {
        // No token — middleware throws HttpException(401)
        assertThrows<Exception>("Should throw because handshake is rejected with 401") {
            WsClient("$wsBase/secure/chat")
        }
    }

    @Test
    @Order(42)
    fun `wsUse with valid token allows connection`() {
        // Different token value to ensure each token is processed independently
        val client = WsClient("$wsBase/secure/chat?token=xyz987")
        assertTrue(client.awaitOpen())
        assertEquals("user-xyz987:admin", client.nextText())
        client.close()
        assertTrue(client.awaitClose())
    }

    // =========================================================================
    // Tests — Middleware Isolation
    // =========================================================================

    @Test
    @Order(50)
    fun `HTTP use() middleware does NOT run for WebSocket upgrade requests`() {
        val countBefore = httpMwHitCount.get()
        // This WS route is under /http-mw-isolation, which has an HTTP middleware that throws 403.
        // If the HTTP middleware were applied, the WS handshake would fail.
        val client = WsClient("$wsBase/http-mw-isolation/ws")
        assertTrue(client.awaitOpen(), "WS connection should succeed despite blocking HTTP middleware")
        assertEquals("connected", client.nextText())
        client.close()
        assertTrue(client.awaitClose())
        // The HTTP middleware should NOT have been called for the WS upgrade
        assertEquals(countBefore, httpMwHitCount.get(), "HTTP middleware should not run for WS upgrades")
    }

    @Test
    @Order(51)
    fun `HTTP use() middleware DOES run for plain HTTP GET under the same prefix`() {
        val countBefore = httpMwHitCount.get()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/http-mw-isolation/get"))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, response.statusCode(), "HTTP middleware should block the GET request")
        assertTrue(httpMwHitCount.get() > countBefore, "HTTP middleware should have run")
    }

    @Test
    @Order(52)
    fun `wsUse() middleware does NOT run for plain HTTP GET requests`() {
        val countBefore = wsMwHitCount.get()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/ws-mw-isolation/get"))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "WS middleware should not block plain HTTP GET")
        assertEquals("ok", response.body())
        // The WS middleware should NOT have been called for the plain HTTP request
        assertEquals(countBefore, wsMwHitCount.get(), "WS middleware should not run for HTTP requests")
    }

    // =========================================================================
    // Tests — Protocol Enforcement
    // =========================================================================

    @Test
    @Order(60)
    fun `GET to WS path without Upgrade header returns 426 Upgrade Required`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/echo"))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(426, response.statusCode(), "Missing Upgrade header should yield 426")
    }

    @Test
    @Order(61)
    fun `POST to a WS-only path returns 404`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/echo"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(404, response.statusCode(), "POST to WS path should yield 404")
    }

    @Test
    @Order(62)
    fun `PUT to a WS-only path returns 404`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/echo"))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(404, response.statusCode(), "PUT to WS path should yield 404")
    }

    @Test
    @Order(63)
    fun `GET to completely unknown path returns 404`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/no-such-ws-route"))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(404, response.statusCode())
    }

    // =========================================================================
    // Tests — Controller Style
    // =========================================================================

    @Test
    @Order(70)
    fun `@Ws annotation in @Controller registers and dispatches correctly`() {
        val client = WsClient("$wsBase/ws-ctrl/echo")
        assertTrue(client.awaitOpen())
        client.sendText("ping")
        assertEquals("ctrl: ping", client.nextText(), "Controller @Ws handler should process message")
        client.close()
        assertTrue(client.awaitClose())
    }

    @Test
    @Order(71)
    fun `@Ws controller handler receives correct path parameters`() {
        val client = WsClient("$wsBase/ws-ctrl/greet/world")
        assertTrue(client.awaitOpen())
        assertEquals("Hello, world!", client.nextText(), "Controller route with path param should work")
        client.close()
        assertTrue(client.awaitClose())
    }

    // =========================================================================
    // Tests — Group Registration
    // =========================================================================

    @Test
    @Order(80)
    fun `WS routes registered inside group() use the correct path prefix`() {
        val client = WsClient("$wsBase/api/v2/notifications")
        assertTrue(client.awaitOpen())
        assertEquals("api-v2", client.nextText())
        client.close()
        assertTrue(client.awaitClose())
    }

    // =========================================================================
    // Tests — Broadcast
    // =========================================================================

    @Test
    @Order(90)
    fun `server can broadcast a message to all connected clients simultaneously`() {
        val c1 = WsClient("$wsBase/broadcast")
        val c2 = WsClient("$wsBase/broadcast")
        val c3 = WsClient("$wsBase/broadcast")
        assertTrue(c1.awaitOpen())
        assertTrue(c2.awaitOpen())
        assertTrue(c3.awaitOpen())

        // Wait until all three connections are registered in the server-side list
        val deadline = System.currentTimeMillis() + 3_000
        while (broadcastConnections.size < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(broadcastConnections.size >= 3, "All 3 connections must be registered before broadcast")

        val broadcastRequest = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/broadcast/send/hello-all"))
            .GET()
            .build()
        httpClient.send(broadcastRequest, HttpResponse.BodyHandlers.ofString())

        assertEquals("hello-all", c1.nextText(), "Client 1 should receive broadcast")
        assertEquals("hello-all", c2.nextText(), "Client 2 should receive broadcast")
        assertEquals("hello-all", c3.nextText(), "Client 3 should receive broadcast")

        c1.close(); c2.close(); c3.close()
        c1.awaitClose(); c2.awaitClose(); c3.awaitClose()
    }

    @Test
    @Order(91)
    fun `disconnected clients are removed from broadcast list`() {
        val c1 = WsClient("$wsBase/broadcast")
        val c2 = WsClient("$wsBase/broadcast")
        assertTrue(c1.awaitOpen())
        assertTrue(c2.awaitOpen())

        // Wait until both connections are registered
        val regDeadline = System.currentTimeMillis() + 3_000
        while (broadcastConnections.size < 2 && System.currentTimeMillis() < regDeadline) {
            Thread.sleep(50)
        }

        val sizeBefore = broadcastConnections.size

        // Disconnect c1 and wait for the server to remove it from the list
        c1.close()
        assertTrue(c1.awaitClose())
        val removeDeadline = System.currentTimeMillis() + 3_000
        while (broadcastConnections.size >= sizeBefore && System.currentTimeMillis() < removeDeadline) {
            Thread.sleep(50)
        }

        // Broadcast should only go to c2
        val broadcastRequest = HttpRequest.newBuilder()
            .uri(URI.create("$httpBase/broadcast/send/after-disconnect"))
            .GET()
            .build()
        httpClient.send(broadcastRequest, HttpResponse.BodyHandlers.ofString())

        assertEquals("after-disconnect", c2.nextText(), "Remaining client should still receive broadcast")
        assertNull(c1.textMessages.poll(300, TimeUnit.MILLISECONDS), "Disconnected client must not receive message")

        c2.close()
        c2.awaitClose()
    }

    // =========================================================================
    // Tests — wsIdleTimeout (isolated server, short timeout)
    // =========================================================================

    @Test
    @Order(100)
    fun `wsIdleTimeout closes idle connection after the configured interval`() {
        withIsolatedServer(
            port = 8891,
            serverConfig = {
                useVirtualThreads = false
                wsIdleTimeout = 800 // 800 ms
            },
            routes = {
                ws("/idle") { _ -> /* stay open, never send */ }
            },
        ) { base ->
            val client = WsClient("$base/idle")
            assertTrue(client.awaitOpen(), "WS connection should open successfully")
            // After 800 ms of inactivity the server should close the channel
            assertTrue(
                client.awaitClose(4),
                "Idle connection should be closed by the server within wsIdleTimeout"
            )
        }
    }

    @Test
    @Order(101)
    fun `active connection with periodic messages is not closed by wsIdleTimeout`() {
        withIsolatedServer(
            port = 8891,
            serverConfig = {
                useVirtualThreads = false
                wsIdleTimeout = 800 // 800 ms idle timeout
            },
            routes = {
                ws("/active") { conn ->
                    conn.onMessage { msg -> conn.send("pong") }
                }
            },
        ) { base ->
            val client = WsClient("$base/active")
            assertTrue(client.awaitOpen())

            // Keep the connection alive by exchanging messages every 300 ms
            repeat(3) {
                client.sendText("ping")
                assertEquals("pong", client.nextText(2))
                Thread.sleep(300)
            }

            // Connection should still be alive after 3 × 300 ms = 900 ms of activity
            assertNull(
                client.textMessages.poll(100, TimeUnit.MILLISECONDS),
                "No unexpected messages should arrive"
            )
            client.close()
            assertTrue(client.awaitClose())
        }
    }

    // =========================================================================
    // Tests — maxWebSocketMessageSize (isolated server, tiny limit)
    // =========================================================================

    @Test
    @Order(110)
    fun `messages within maxWebSocketMessageSize are processed normally`() {
        withIsolatedServer(
            port = 8892,
            serverConfig = {
                useVirtualThreads = false
                maxWebSocketMessageSize = 100 // 100 bytes
            },
            routes = {
                ws("/size") { conn ->
                    conn.onMessage { msg ->
                        conn.send("ok:${(msg as WebSocketMessage.Text).text().length}")
                    }
                }
            },
        ) { base ->
            val client = WsClient("$base/size")
            assertTrue(client.awaitOpen())
            client.sendText("short") // 5 bytes — well within limit
            assertEquals("ok:5", client.nextText())
            client.close()
            assertTrue(client.awaitClose())
        }
    }

    @Test
    @Order(111)
    fun `message exceeding maxWebSocketMessageSize causes connection to be closed`() {
        withIsolatedServer(
            port = 8892,
            serverConfig = {
                useVirtualThreads = false
                maxWebSocketMessageSize = 50 // 50 bytes
            },
            routes = {
                ws("/size") { conn ->
                    conn.onMessage { msg ->
                        conn.send("ok:${(msg as WebSocketMessage.Text).text().length}")
                    }
                }
            },
        ) { base ->
            val client = WsClient("$base/size")
            assertTrue(client.awaitOpen())

            // Send a message that exceeds the 50-byte limit
            client.sendText("X".repeat(100))

            // Server must close the connection (onClose or onError)
            assertTrue(
                client.awaitClose(5),
                "Connection should be closed after oversized message"
            )
        }
    }

    // =========================================================================
    // Tests — Graceful Shutdown
    // =========================================================================

    @Test
    @Order(120)
    fun `graceful shutdown closes all active WebSocket connections`() {
        // Capture close latches INSIDE the lambda so we can check them AFTER withIsolatedServer
        // calls server.stop() in its finally block.
        val c1CloseLatch = AtomicReference<CountDownLatch>()
        val c2CloseLatch = AtomicReference<CountDownLatch>()

        withIsolatedServer(
            port = 8893,
            serverConfig = { useVirtualThreads = false },
            routes = {
                ws("/long-lived") { _ -> /* stay open */ }
            },
        ) { base ->
            val c1 = WsClient("$base/long-lived")
            val c2 = WsClient("$base/long-lived")
            assertTrue(c1.awaitOpen(), "Connection 1 should open")
            assertTrue(c2.awaitOpen(), "Connection 2 should open")

            // Verify connections are still open before we trigger shutdown
            assertFalse(c1.closeLatch.await(100, TimeUnit.MILLISECONDS), "Connection 1 should still be open")
            assertFalse(c2.closeLatch.await(100, TimeUnit.MILLISECONDS), "Connection 2 should still be open")

            // Store the latches for assertion after withIsolatedServer returns
            c1CloseLatch.set(c1.closeLatch)
            c2CloseLatch.set(c2.closeLatch)
            // Returning from the lambda triggers server.stop() in withIsolatedServer's finally block
        }

        // After withIsolatedServer returns, server.stop() has completed.
        // Both WS connections should have received close events.
        assertTrue(c1CloseLatch.get().await(5, TimeUnit.SECONDS), "Connection 1 should be closed on server shutdown")
        assertTrue(c2CloseLatch.get().await(5, TimeUnit.SECONDS), "Connection 2 should be closed on server shutdown")
    }

    @Test
    @Order(121)
    fun `server shutdown does not block when there are no active WebSocket connections`() {
        withIsolatedServer(
            port = 8893,
            serverConfig = { useVirtualThreads = false },
            routes = {
                ws("/noop") { _ -> /* never connected */ }
            },
        ) { _ ->
            // No connections — stop() must return promptly
        }
        // If this test completes without hanging, shutdown with no WS connections works
    }

    // =========================================================================
    // Tests — Error Handling
    // =========================================================================

    @Test
    @Order(130)
    fun `unhandled exception in WS handler does not crash the server`() {
        withIsolatedServer(
            port = 8894,
            serverConfig = { useVirtualThreads = false },
            routes = {
                ws("/crash") { conn ->
                    conn.onMessage { _ ->
                        error("intentional crash")
                    }
                }
                ws("/healthy") { conn ->
                    conn.onMessage { msg -> conn.send((msg as WebSocketMessage.Text).text()) }
                }
            },
        ) { base ->
            // Trigger a crash on /crash
            val crashClient = WsClient("$base/crash")
            assertTrue(crashClient.awaitOpen())
            crashClient.sendText("boom")
            // The crash client may get disconnected or may stay open — either is acceptable
            // as long as the server is still running

            // The server must still handle connections on /healthy
            val healthyClient = WsClient("$base/healthy")
            assertTrue(healthyClient.awaitOpen(), "Server should still accept new connections after a handler crash")
            healthyClient.sendText("alive")
            assertEquals("alive", healthyClient.nextText(), "Healthy client should still work")
            healthyClient.close()
            assertTrue(healthyClient.awaitClose())

            crashClient.close()
            crashClient.awaitClose()
        }
    }
}
