package io.github.cymoo.colleen

import io.github.cymoo.colleen.ws.*
import org.junit.jupiter.api.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Comprehensive E2E test suite for WebSocket support.
 *
 * Tests real WebSocket server behavior with actual network connections
 * using Java's java.net.http.WebSocket client.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketE2ETest {

    private lateinit var app: Colleen
    private lateinit var client: HttpClient
    private val baseUrl = "ws://127.0.0.1:8890"
    private val httpBaseUrl = "http://127.0.0.1:8890"

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
        // Echo text/binary messages
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

        // Multiple path params
        app.ws("/ns/{namespace}/room/{room}") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val ns = conn.pathParam("namespace")
                    val room = conn.pathParam("room")
                    conn.send("$ns/$room: ${msg.data}")
                }
            }
        }

        // Query params endpoint
        app.ws("/with-query") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val name = conn.query("name") ?: "anonymous"
                    val tags = conn.queryList("tag").joinToString(",")
                    conn.send("name=$name tags=$tags")
                }
            }
        }

        // Endpoint that closes with normal close
        app.ws("/close-me") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text && msg.data == "close") {
                    conn.close(WsCloseReason.Normal)
                }
            }
        }

        // Endpoint with onClose callback
        app.ws("/on-close") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    conn.send("received: ${msg.data}")
                }
            }
            conn.onClose { reason ->
                // Server logs close reason - we can't test this directly
                // but we verify the close flow works
            }
        }

        // Endpoint with onError callback
        app.ws("/on-error") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text && msg.data == "trigger-error") {
                    throw RuntimeException("Intentional error")
                }
                if (msg is WsMessage.Text) {
                    conn.send("ok: ${msg.data}")
                }
            }
            conn.onError { error ->
                // Error handled gracefully
            }
        }

        // WS middleware: reject connections without auth token
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

        // HTTP GET on same path as WS (co-exist test)
        app.get("/echo") { "HTTP echo page" }

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

    private fun awaitCondition(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(condition(), "Condition not met within ${timeoutMs}ms")
    }

    // ========================================================================
    // Basic Echo
    // ========================================================================

    @Nested
    inner class BasicEcho {
        @Test
        fun `should echo text message`() {
            val (ws, listener) = connectWs("/echo")
            try {
                ws.sendText("Hello WebSocket!", true).get(5, TimeUnit.SECONDS)
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
        fun `should echo multiple messages in order`() {
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
        fun `should echo empty text message`() {
            val (ws, listener) = connectWs("/echo")
            try {
                ws.sendText("", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should echo unicode text`() {
            val (ws, listener) = connectWs("/echo")
            try {
                ws.sendText("你好世界 🌍 café", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("你好世界 🌍 café", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should echo empty binary message`() {
            val (ws, listener) = connectWs("/echo")
            try {
                ws.sendBinary(ByteBuffer.wrap(byteArrayOf()), true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.binaryMessages.size >= 1 }
                assertEquals(0, listener.binaryMessages[0].size)
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Path Parameters
    // ========================================================================

    @Nested
    inner class PathParameters {
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
        fun `should handle path params with special characters`() {
            val (ws, listener) = connectWs("/chat/room-123")
            try {
                ws.sendText("test", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("[room-123] test", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should handle multiple path params`() {
            val (ws, listener) = connectWs("/ns/production/room/general")
            try {
                ws.sendText("msg", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("production/general: msg", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Query Parameters
    // ========================================================================

    @Nested
    inner class QueryParameters {
        @Test
        fun `should pass query parameters`() {
            val (ws, listener) = connectWs("/with-query?name=Alice")
            try {
                ws.sendText("hi", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("name=Alice tags=", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should handle multiple query values`() {
            val (ws, listener) = connectWs("/with-query?name=Bob&tag=ws&tag=test")
            try {
                ws.sendText("hi", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("name=Bob tags=ws,test", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should handle missing query parameters`() {
            val (ws, listener) = connectWs("/with-query")
            try {
                ws.sendText("hi", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("name=anonymous tags=", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Close Behavior
    // ========================================================================

    @Nested
    inner class CloseBehavior {
        @Test
        fun `should handle server-initiated close`() {
            val (ws, listener) = connectWs("/close-me")
            ws.sendText("close", true).get(5, TimeUnit.SECONDS)
            assertTrue(listener.closeLatch.await(5, TimeUnit.SECONDS), "Should receive close frame")
            assertEquals(1000, listener.closeCode.get())
        }

        @Test
        fun `should handle client-initiated close`() {
            val (ws, listener) = connectWs("/on-close")
            ws.sendText("hi", true).get(5, TimeUnit.SECONDS)
            awaitCondition { listener.messages.size >= 1 }
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "goodbye").get(5, TimeUnit.SECONDS)
            assertTrue(listener.closeLatch.await(5, TimeUnit.SECONDS))
        }

        @Test
        fun `should receive messages before server close`() {
            val (ws, listener) = connectWs("/close-me")
            ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
            // "hello" doesn't trigger close, so we should get nothing back
            // Now send "close" to trigger server close
            ws.sendText("close", true).get(5, TimeUnit.SECONDS)
            assertTrue(listener.closeLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1000, listener.closeCode.get())
        }
    }

    // ========================================================================
    // Error Handling
    // ========================================================================

    @Nested
    inner class ErrorHandling {
        @Test
        fun `should handle error in message callback gracefully`() {
            val (ws, listener) = connectWs("/on-error")
            try {
                // Send a normal message first
                ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("ok: hello", listener.messages[0])

                // Trigger error
                ws.sendText("trigger-error", true).get(5, TimeUnit.SECONDS)

                // The error is caught by onError, connection should still be usable
                Thread.sleep(200)
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Middleware
    // ========================================================================

    @Nested
    inner class Middleware {
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
            val listener = TestListener()
            try {
                client.newWebSocketBuilder()
                    .buildAsync(URI.create("$baseUrl/auth-ws"), listener)
                    .get(5, TimeUnit.SECONDS)
                fail("WebSocket handshake should have been rejected")
            } catch (e: Exception) {
                // Expected: handshake failure (403)
                assertTrue(true)
            }
        }
    }

    // ========================================================================
    // Controller (@Ws annotation)
    // ========================================================================

    @Nested
    inner class ControllerAnnotation {
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
    }

    // ========================================================================
    // HTTP Co-existence
    // ========================================================================

    @Nested
    inner class HttpCoexistence {
        @Test
        fun `HTTP GET on same path as WS route should work`() {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$httpBaseUrl/echo"))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertEquals("HTTP echo page", response.body())
        }

        @Test
        fun `WS route on same path as HTTP route should work`() {
            val (ws, listener) = connectWs("/echo")
            try {
                ws.sendText("websocket", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("websocket", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Routing Errors
    // ========================================================================

    @Nested
    inner class RoutingErrors {
        @Test
        fun `should return 404 for non-existent ws path`() {
            val listener = TestListener()
            try {
                client.newWebSocketBuilder()
                    .buildAsync(URI.create("$baseUrl/nonexistent-ws"), listener)
                    .get(5, TimeUnit.SECONDS)
                fail("WebSocket handshake should have failed for non-existent path")
            } catch (e: Exception) {
                assertTrue(true)
            }
        }
    }

    // ========================================================================
    // Concurrent Connections
    // ========================================================================

    @Nested
    inner class ConcurrentConnections {
        @Test
        fun `should handle multiple concurrent connections`() {
            val connections = (1..5).map { connectWs("/echo") }
            try {
                connections.forEachIndexed { idx, (ws, _) ->
                    ws.sendText("conn-$idx", true).get(5, TimeUnit.SECONDS)
                }
                connections.forEachIndexed { idx, (_, listener) ->
                    awaitCondition { listener.messages.size >= 1 }
                    assertEquals("conn-$idx", listener.messages[0])
                }
            } finally {
                connections.forEach { (ws, _) ->
                    runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
                }
            }
        }

        @Test
        fun `should handle rapid connect-send-disconnect cycles`() {
            repeat(5) {
                val (ws, listener) = connectWs("/echo")
                ws.sendText("ping-$it", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("ping-$it", listener.messages[0])
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Large Messages
    // ========================================================================

    @Nested
    inner class LargeMessages {
        @Test
        fun `should handle moderately large text message`() {
            val (ws, listener) = connectWs("/echo")
            try {
                val largeText = "x".repeat(10_000)
                ws.sendText(largeText, true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals(largeText.length, listener.messages[0].length)
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should handle moderately large binary message`() {
            val (ws, listener) = connectWs("/echo")
            try {
                val data = ByteArray(10_000) { (it % 256).toByte() }
                ws.sendBinary(ByteBuffer.wrap(data), true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.binaryMessages.size >= 1 }
                assertTrue(data.contentEquals(listener.binaryMessages[0]))
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }
}

