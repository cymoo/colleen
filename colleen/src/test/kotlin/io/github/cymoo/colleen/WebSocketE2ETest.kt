package io.github.cymoo.colleen

import io.github.cymoo.colleen.ws.*
import org.junit.jupiter.api.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * E2E test suite for WebSocket support.
 *
 * Tests real WebSocket server behavior with actual network connections
 * using Java's java.net.http.WebSocket client.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketE2ETest {

    private lateinit var app: Colleen
    private lateinit var client: HttpClient
    private val baseUrl = "ws://127.0.0.1:8890"

    @BeforeAll
    fun setup() {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        app = Colleen()
        setupRoutes()

        app.listen(port = 8890, host = "127.0.0.1")
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() {
        app.stop()
    }

    private fun setupRoutes() {
        // Echo text messages
        app.ws("/echo") { conn ->
            conn.onMessage { msg ->
                when (msg) {
                    is WsMessage.Text -> conn.send(msg.data)
                    is WsMessage.Binary -> conn.send(msg.data)
                }
            }
        }

        // Echo with path params
        app.ws("/chat/{room}") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val room = conn.pathParam("room")
                    conn.send("[$room] ${msg.data}")
                }
            }
        }

        // Endpoint that closes with custom reason
        app.ws("/close-me") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text && msg.data == "close") {
                    conn.close(WsCloseReason.Normal)
                }
            }
        }

        // WS middleware test: reject connections without auth token
        app.wsUse { ctx, next ->
            if (ctx.path.startsWith("/auth-ws")) {
                val token = ctx.header("X-Auth-Token")
                if (token == "valid-token") {
                    next()
                } else {
                    ctx.status(403).text("Forbidden")
                }
            } else {
                next()
            }
        }

        app.ws("/auth-ws") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    conn.send("Authenticated: ${msg.data}")
                }
            }
        }

        // Controller-style WS route
        app.addController(WsChatController())
    }

    @Controller("/api")
    class WsChatController {
        @Ws("/ws-chat")
        fun chat(conn: WsConnection) {
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    conn.send("controller: ${msg.data}")
                }
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private class TestListener : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val binaryMessages = CopyOnWriteArrayList<ByteArray>()
        val closeCode = AtomicReference<Int>()
        val closeReason = AtomicReference<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            openLatch.countDown()
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            textBuffer.append(data)
            if (last) {
                messages.add(textBuffer.toString())
                textBuffer.setLength(0)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*> {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            binaryMessages.add(bytes)
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeCode.set(statusCode)
            closeReason.set(reason)
            closeLatch.countDown()
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
            closeLatch.countDown()
        }
    }

    private fun connectWs(path: String, headers: Map<String, String> = emptyMap()): Pair<WebSocket, TestListener> {
        val listener = TestListener()
        val builder = client.newWebSocketBuilder()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val ws = builder.buildAsync(URI.create("$baseUrl$path"), listener).get(5, TimeUnit.SECONDS)
        assertTrue(listener.openLatch.await(5, TimeUnit.SECONDS), "WebSocket should open")
        return ws to listener
    }

    // ========================================================================
    // Tests
    // ========================================================================

    @Test
    fun `should echo text message`() {
        val (ws, listener) = connectWs("/echo")
        try {
            ws.sendText("Hello WebSocket!", true).get(5, TimeUnit.SECONDS)

            // Wait for response
            awaitCondition { listener.messages.size >= 1 }
            assertEquals("Hello WebSocket!", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `should echo binary message`() {
        val (ws, listener) = connectWs("/echo")
        try {
            val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
            ws.sendBinary(ByteBuffer.wrap(data), true).get(5, TimeUnit.SECONDS)

            awaitCondition { listener.binaryMessages.size >= 1 }
            assertTrue(data.contentEquals(listener.binaryMessages[0]))
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `should echo multiple messages`() {
        val (ws, listener) = connectWs("/echo")
        try {
            ws.sendText("msg1", true).get(5, TimeUnit.SECONDS)
            ws.sendText("msg2", true).get(5, TimeUnit.SECONDS)
            ws.sendText("msg3", true).get(5, TimeUnit.SECONDS)

            awaitCondition { listener.messages.size >= 3 }
            assertEquals(listOf("msg1", "msg2", "msg3"), listener.messages.toList())
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `should include path params in echo`() {
        val (ws, listener) = connectWs("/chat/lobby")
        try {
            ws.sendText("hello!", true).get(5, TimeUnit.SECONDS)

            awaitCondition { listener.messages.size >= 1 }
            assertEquals("[lobby] hello!", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `should handle server-initiated close`() {
        val (ws, listener) = connectWs("/close-me")

        ws.sendText("close", true).get(5, TimeUnit.SECONDS)

        assertTrue(listener.closeLatch.await(5, TimeUnit.SECONDS), "Should receive close frame")
        assertEquals(1000, listener.closeCode.get())
    }

    @Test
    fun `ws middleware should allow authorized connections`() {
        val (ws, listener) = connectWs("/auth-ws", mapOf("X-Auth-Token" to "valid-token"))
        try {
            ws.sendText("secret data", true).get(5, TimeUnit.SECONDS)

            awaitCondition { listener.messages.size >= 1 }
            assertEquals("Authenticated: secret data", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `ws middleware should reject unauthorized connections`() {
        // Without the auth token, the WS middleware returns 403
        // The WS handshake should fail
        val listener = TestListener()
        try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create("$baseUrl/auth-ws"), listener)
                .get(5, TimeUnit.SECONDS)
            // If we get here, the handshake succeeded but should have failed
            fail("WebSocket handshake should have been rejected")
        } catch (e: Exception) {
            // Expected: handshake failure (403)
            assertTrue(true)
        }
    }

    @Test
    fun `controller @Ws annotation should work`() {
        val (ws, listener) = connectWs("/api/ws-chat")
        try {
            ws.sendText("hello from controller", true).get(5, TimeUnit.SECONDS)

            awaitCondition { listener.messages.size >= 1 }
            assertEquals("controller: hello from controller", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `should return 404 for non-existent ws path`() {
        val listener = TestListener()
        try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create("$baseUrl/nonexistent-ws"), listener)
                .get(5, TimeUnit.SECONDS)
            fail("WebSocket handshake should have failed for non-existent path")
        } catch (e: Exception) {
            // Expected: 404 which causes handshake failure
            assertTrue(true)
        }
    }

    private fun awaitCondition(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(condition(), "Condition not met within ${timeoutMs}ms")
    }
}
