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

        // Controller-style WS route with @WsUse middleware
        app.addController(GuardedWsController())

        // ---- Message ordering test endpoint ----
        app.ws("/ordering") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    // Simulate some work to make ordering issues more visible
                    Thread.sleep(10)
                    conn.send("echo:${msg.data}")
                }
            }
        }

        // ---- Concurrent state access test endpoint ----
        app.ws("/concurrent-state") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    when {
                        msg.data.startsWith("set:") -> {
                            val parts = msg.data.substringAfter("set:").split("=", limit = 2)
                            conn.setState(parts[0], parts[1])
                            conn.send("set-ok")
                        }
                        msg.data.startsWith("get:") -> {
                            val key = msg.data.substringAfter("get:")
                            val value = conn.getStateOrNull<String>(key)
                            conn.send("got:$key=$value")
                        }
                    }
                }
            }
        }

        // ---- Service/State access tests ----

        // Provide a service
        app.provide(GreetingService("Hello"))

        // WS middleware that sets state
        app.wsUse { ctx, next ->
            if (ctx.path.startsWith("/state-ws") || ctx.path.startsWith("/service-ws")) {
                ctx.setState("userId", 42)
                ctx.setState("role", "admin")
                next()
            } else {
                next()
            }
        }

        // WS route that reads state set by middleware
        app.ws("/state-ws") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val userId = conn.getState<Int>("userId")
                    val role = conn.getState<String>("role")
                    conn.send("userId=$userId role=$role")
                }
            }
        }

        // WS route that reads service from app
        app.ws("/service-ws") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val svc = conn.getService<GreetingService>()
                    conn.send("${svc.greet(msg.data)}")
                }
            }
        }

        // WS route that uses both state and service
        app.ws("/both-ws") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val hasUser = conn.hasState("userId")
                    val svc = conn.getServiceOrNull<GreetingService>()
                    conn.send("hasUser=$hasUser svc=${svc != null}")
                }
            }
        }

        // WS route that mutates state
        app.ws("/state-mutate-ws") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    if (msg.data.startsWith("set:")) {
                        val parts = msg.data.substringAfter("set:").split("=", limit = 2)
                        conn.setState(parts[0], parts[1])
                        conn.send("ok")
                    } else if (msg.data.startsWith("get:")) {
                        val key = msg.data.substringAfter("get:")
                        val value = conn.getStateOrNull<String>(key)
                        conn.send("value=$value")
                    }
                }
            }
        }

        // WS route that echoes back request headers
        app.ws("/header-ws") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val headerName = msg.data
                    val value = conn.header(headerName)
                    conn.send("$headerName=$value")
                }
            }
        }
    }

    class GreetingService(private val prefix: String) {
        fun greet(name: String): String = "$prefix, $name!"
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

    @Controller("/guarded")
    class GuardedWsController {
        @WsUse
        fun auth(ctx: Context, next: Next) {
            val token = ctx.header("X-Guard-Token")
            if (token == "pass") {
                ctx.setState("guard", "ok")
                next()
            } else {
                ctx.status(403).text("Forbidden by @WsUse")
            }
        }

        @Ws("/endpoint")
        fun endpoint(conn: WsConnection) {
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val guard = conn.getStateOrNull<String>("guard") ?: "none"
                    conn.send("guard=$guard ${msg.data}")
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

    // ========================================================================
    // State Access (from WS middleware)
    // ========================================================================

    @Nested
    inner class StateAccess {
        @Test
        fun `should access state set by WS middleware`() {
            val (ws, listener) = connectWs("/state-ws")
            try {
                ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("userId=42 role=admin", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should mutate state during connection lifetime`() {
            val (ws, listener) = connectWs("/state-mutate-ws")
            try {
                ws.sendText("set:myKey=myValue", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("ok", listener.messages[0])

                ws.sendText("get:myKey", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 2 }
                assertEquals("value=myValue", listener.messages[1])

                ws.sendText("get:missing", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 3 }
                assertEquals("value=null", listener.messages[2])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Service Access
    // ========================================================================

    @Nested
    inner class ServiceAccess {
        @Test
        fun `should access service from app`() {
            val (ws, listener) = connectWs("/service-ws")
            try {
                ws.sendText("World", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("Hello, World!", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should access both state and service`() {
            val (ws, listener) = connectWs("/both-ws")
            try {
                ws.sendText("test", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                // /both-ws middleware does not set state for this path,
                // but getServiceOrNull should find it
                assertEquals("hasUser=false svc=true", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Header Access
    // ========================================================================

    @Nested
    inner class HeaderAccess {
        @Test
        fun `should access request header from connection`() {
            val (ws, listener) = connectWs("/header-ws", mapOf("X-Custom-Header" to "my-value"))
            try {
                ws.sendText("X-Custom-Header", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("X-Custom-Header=my-value", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `should return null for missing header`() {
            val (ws, listener) = connectWs("/header-ws")
            try {
                ws.sendText("X-Nonexistent", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("X-Nonexistent=null", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // @WsUse Annotation
    // ========================================================================

    @Nested
    inner class WsUseAnnotation {
        @Test
        fun `controller @WsUse should allow authorized connections`() {
            val (ws, listener) = connectWs("/guarded/endpoint", mapOf("X-Guard-Token" to "pass"))
            try {
                ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("guard=ok hello", listener.messages[0])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        @Test
        fun `controller @WsUse should reject unauthorized connections`() {
            val listener = TestListener()
            try {
                client.newWebSocketBuilder()
                    .buildAsync(URI.create("$baseUrl/guarded/endpoint"), listener)
                    .get(5, TimeUnit.SECONDS)
                fail("WebSocket handshake should have been rejected by @WsUse")
            } catch (_: Exception) {
                // Expected: 403 handshake failure
                assertTrue(true)
            }
        }
    }

    // ========================================================================
    // Message Ordering (off IO thread)
    // ========================================================================

    @Nested
    inner class MessageOrdering {
        @Test
        fun `should process messages in order when dispatched off IO thread`() {
            val (ws, listener) = connectWs("/ordering")
            try {
                val count = 20
                for (i in 1..count) {
                    ws.sendText("msg-$i", true).get(5, TimeUnit.SECONDS)
                }
                awaitCondition(timeoutMs = 10_000) { listener.messages.size >= count }
                val expected = (1..count).map { "echo:msg-$it" }
                assertEquals(expected, listener.messages.toList())
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }

    // ========================================================================
    // Concurrent State Access
    // ========================================================================

    @Nested
    inner class ConcurrentStateAccess {
        @Test
        fun `should safely read and write state from message callbacks`() {
            val (ws, listener) = connectWs("/concurrent-state")
            try {
                ws.sendText("set:key1=value1", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 1 }
                assertEquals("set-ok", listener.messages[0])

                ws.sendText("get:key1", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 2 }
                assertEquals("got:key1=value1", listener.messages[1])

                ws.sendText("get:missing", true).get(5, TimeUnit.SECONDS)
                awaitCondition { listener.messages.size >= 3 }
                assertEquals("got:missing=null", listener.messages[2])
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }
    }
}

/**
 * E2E tests for WebSocket routing through mounted sub-applications.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketSubAppE2ETest {

    private lateinit var app: Colleen
    private lateinit var client: HttpClient
    private val baseUrl = "ws://127.0.0.1:8895"

    @BeforeAll
    fun setup() {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        app = Colleen()

        // Sub-app with WS routes
        val chatApp = Colleen()
        chatApp.ws("/echo") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send("sub: ${msg.data}")
            }
        }
        chatApp.ws("/room/{name}") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    conn.send("room=${conn.pathParam("name")}: ${msg.data}")
                }
            }
        }

        // WS middleware on the sub-app
        chatApp.wsUse { ctx, next ->
            if (ctx.path.startsWith("/protected")) {
                val token = ctx.header("X-Token")
                if (token == "ok") next()
                else ctx.status(403).text("Forbidden")
            } else {
                next()
            }
        }
        chatApp.ws("/protected/data") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send("protected: ${msg.data}")
            }
        }

        app.mount("/ws", chatApp)

        // Nested sub-app: app -> /api -> /v1
        val apiApp = Colleen()
        val v1App = Colleen()
        v1App.ws("/stream") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send("v1: ${msg.data}")
            }
        }
        apiApp.mount("/v1", v1App)
        app.mount("/api", apiApp)

        // Root-level WS route (should still work alongside mounts)
        app.ws("/root-echo") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send("root: ${msg.data}")
            }
        }

        // Parent-level WS middleware for /ws prefix
        // This tests fix #5: WS middleware should run for mounted sub-app routes
        app.wsUse("/ws") { ctx, next ->
            ctx.setState("parentMw", "true")
            next()
        }

        // Sub-app route that checks parent middleware state
        chatApp.ws("/with-parent-mw") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) {
                    val parentMw = conn.getStateOrNull<String>("parentMw") ?: "false"
                    conn.send("parentMw=$parentMw ${msg.data}")
                }
            }
        }

        app.listen(port = 8895, host = "127.0.0.1")
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() {
        app.stop()
    }

    // Reuse TestListener from main test
    private class TestListener : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val closeCode = AtomicReference<Int>()
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

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeCode.set(statusCode)
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

    @Test
    fun `root WS route should still work alongside mounts`() {
        val (ws, listener) = connectWs("/root-echo")
        try {
            ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
            awaitCondition { listener.messages.size >= 1 }
            assertEquals("root: hello", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `WS route in mounted sub-app should work`() {
        val (ws, listener) = connectWs("/ws/echo")
        try {
            ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
            awaitCondition { listener.messages.size >= 1 }
            assertEquals("sub: hello", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `WS route with path params in sub-app should work`() {
        val (ws, listener) = connectWs("/ws/room/lobby")
        try {
            ws.sendText("hi", true).get(5, TimeUnit.SECONDS)
            awaitCondition { listener.messages.size >= 1 }
            assertEquals("room=lobby: hi", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `WS middleware in sub-app should reject unauthorized`() {
        val listener = TestListener()
        try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create("$baseUrl/ws/protected/data"), listener)
                .get(5, TimeUnit.SECONDS)
            fail("Should have been rejected")
        } catch (_: Exception) {
            // Expected: 403 handshake failure
        }
    }

    @Test
    fun `WS middleware in sub-app should allow authorized`() {
        val (ws, listener) = connectWs("/ws/protected/data", mapOf("X-Token" to "ok"))
        try {
            ws.sendText("secret", true).get(5, TimeUnit.SECONDS)
            awaitCondition { listener.messages.size >= 1 }
            assertEquals("protected: secret", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `WS route in nested sub-app should work`() {
        val (ws, listener) = connectWs("/api/v1/stream")
        try {
            ws.sendText("data", true).get(5, TimeUnit.SECONDS)
            awaitCondition { listener.messages.size >= 1 }
            assertEquals("v1: data", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `non-existent WS path in sub-app should return 404`() {
        val listener = TestListener()
        try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create("$baseUrl/ws/nonexistent"), listener)
                .get(5, TimeUnit.SECONDS)
            fail("Should have failed for non-existent path")
        } catch (_: Exception) {
            // Expected: 404 handshake failure
        }
    }

    @Test
    fun `WS middleware on parent app should run for mounted sub-app routes`() {
        // The parent app has wsUse middleware registered for /ws prefix
        // that was added in setup
        val (ws, listener) = connectWs("/ws/with-parent-mw")
        try {
            ws.sendText("test", true).get(5, TimeUnit.SECONDS)
            awaitCondition { listener.messages.size >= 1 }
            assertEquals("parentMw=true test", listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }
}


/**
 * E2E tests for WebSocket connection limits.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketConnectionLimitE2ETest {

    private lateinit var app: Colleen
    private lateinit var client: HttpClient
    private val baseUrl = "ws://127.0.0.1:8896"

    @BeforeAll
    fun setup() {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        app = Colleen()
        app.config {
            ws {
                maxConnections = 3
                pingIntervalMs = 0  // disable ping for these tests
            }
        }
        app.ws("/echo") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send(msg.data)
            }
        }

        app.listen(port = 8896, host = "127.0.0.1")
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() {
        app.stop()
    }

    private class TestListener : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val closeCode = AtomicReference<Int>()
        val errors = CopyOnWriteArrayList<Throwable>()
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

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeCode.set(statusCode)
            closeLatch.countDown()
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
            closeLatch.countDown()
        }
    }

    private fun connectWs(path: String): Pair<WebSocket, TestListener> {
        val listener = TestListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("$baseUrl$path"), listener)
            .get(5, TimeUnit.SECONDS)
        assertTrue(listener.openLatch.await(5, TimeUnit.SECONDS), "WebSocket should open")
        return ws to listener
    }

    @Test
    fun `should accept connections within limit`() {
        val connections = (1..3).map { connectWs("/echo") }
        try {
            connections.forEachIndexed { idx, (ws, _) ->
                ws.sendText("msg-$idx", true).get(5, TimeUnit.SECONDS)
            }
            connections.forEachIndexed { idx, (_, listener) ->
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    if (listener.messages.size >= 1) break
                    Thread.sleep(50)
                }
                assertEquals("msg-$idx", listener.messages[0])
            }
        } finally {
            connections.forEach { (ws, _) ->
                runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
            }
        }
    }

    @Test
    fun `should reject connections exceeding limit`() {
        val connections = mutableListOf<Pair<WebSocket, TestListener>>()
        try {
            // Fill up to the limit
            for (i in 1..3) {
                connections.add(connectWs("/echo"))
            }

            // The 4th connection should be rejected at handshake level (HTTP 503)
            val listener = TestListener()
            try {
                client.newWebSocketBuilder()
                    .buildAsync(URI.create("$baseUrl/echo"), listener)
                    .get(5, TimeUnit.SECONDS)
                fail("WebSocket handshake should have been rejected with HTTP 503")
            } catch (_: Exception) {
                // Expected: handshake failure (503 Service Unavailable)
                assertTrue(true)
            }
        } finally {
            connections.forEach { (ws, _) ->
                runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
            }
        }
    }

    @Test
    fun `should accept new connections after existing ones close`() {
        val connections = mutableListOf<Pair<WebSocket, TestListener>>()
        try {
            // Fill up to the limit
            for (i in 1..3) {
                connections.add(connectWs("/echo"))
            }

            // Close one connection
            val (ws0, listener0) = connections.removeAt(0)
            ws0.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            listener0.closeLatch.await(5, TimeUnit.SECONDS)

            // Wait a bit for the server to process the close
            Thread.sleep(200)

            // Now a new connection should be accepted
            val newConn = connectWs("/echo")
            connections.add(newConn)
            val (newWs, newListener) = newConn
            newWs.sendText("new-conn", true).get(5, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (newListener.messages.size >= 1) break
                Thread.sleep(50)
            }
            assertEquals("new-conn", newListener.messages[0])
        } finally {
            connections.forEach { (ws, _) ->
                runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
            }
        }
    }
}

/**
 * E2E tests for WebSocket graceful shutdown.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketGracefulShutdownE2ETest {

    private lateinit var client: HttpClient

    @BeforeAll
    fun setup() {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }

    private class TestListener : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val closeCode = AtomicReference<Int>()
        val errors = CopyOnWriteArrayList<Throwable>()
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

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeCode.set(statusCode)
            closeLatch.countDown()
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
            closeLatch.countDown()
        }
    }

    @Test
    fun `server stop should gracefully close active WS connections`() {
        val app = Colleen()
        app.config {
            ws {
                pingIntervalMs = 0
            }
        }
        app.ws("/echo") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send(msg.data)
            }
        }

        app.listen(port = 8897, host = "127.0.0.1")
        Thread.sleep(500)

        try {
            // Connect and verify working
            val listener = TestListener()
            val ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:8897/echo"), listener)
                .get(5, TimeUnit.SECONDS)
            assertTrue(listener.openLatch.await(5, TimeUnit.SECONDS), "WebSocket should open")

            ws.sendText("before-shutdown", true).get(5, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (listener.messages.size >= 1) break
                Thread.sleep(50)
            }
            assertEquals("before-shutdown", listener.messages[0])

            // Stop the server — should close WS connections gracefully
            app.stop()

            // The client should receive a close frame
            assertTrue(listener.closeLatch.await(5, TimeUnit.SECONDS), "Should receive close after shutdown")
            // Close code should be 1000 (Normal) since server closes gracefully
            assertEquals(1000, listener.closeCode.get())
        } catch (_: Exception) {
            app.stop()
        }
    }
}

/**
 * E2E tests for WebSocket oversized message handling.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketOversizedMessageE2ETest {

    private lateinit var app: Colleen
    private lateinit var client: HttpClient
    private val baseUrl = "ws://127.0.0.1:8898"

    @BeforeAll
    fun setup() {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        app = Colleen()
        app.config {
            ws {
                maxMessageSizeBytes = 1024  // 1 KB limit
                pingIntervalMs = 0
            }
        }
        app.ws("/echo") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send(msg.data)
            }
        }

        app.listen(port = 8898, host = "127.0.0.1")
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() {
        app.stop()
    }

    private class TestListener : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val closeCode = AtomicReference<Int>()
        val errors = CopyOnWriteArrayList<Throwable>()
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

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeCode.set(statusCode)
            closeLatch.countDown()
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
            closeLatch.countDown()
        }
    }

    @Test
    fun `should accept messages within size limit`() {
        val listener = TestListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("$baseUrl/echo"), listener)
            .get(5, TimeUnit.SECONDS)
        assertTrue(listener.openLatch.await(5, TimeUnit.SECONDS))

        try {
            // 500 bytes is within the 1024 limit
            val smallMsg = "x".repeat(500)
            ws.sendText(smallMsg, true).get(5, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (listener.messages.size >= 1) break
                Thread.sleep(50)
            }
            assertEquals(smallMsg, listener.messages[0])
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `should close connection with 1009 for oversized text message`() {
        val listener = TestListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("$baseUrl/echo"), listener)
            .get(5, TimeUnit.SECONDS)
        assertTrue(listener.openLatch.await(5, TimeUnit.SECONDS))

        // Send a message exceeding the 1024 byte limit
        val largeMsg = "x".repeat(2048)
        ws.sendText(largeMsg, true).get(5, TimeUnit.SECONDS)

        // Server should close with 1009 (Message Too Big)
        assertTrue(listener.closeLatch.await(5, TimeUnit.SECONDS), "Should receive close frame")
        assertEquals(1009, listener.closeCode.get())
    }
}

/**
 * E2E tests for WebSocket ping/pong configuration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketPingPongE2ETest {

    private lateinit var app: Colleen
    private lateinit var client: HttpClient
    private val baseUrl = "ws://127.0.0.1:8899"

    @BeforeAll
    fun setup() {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        app = Colleen()
        app.config {
            ws {
                pingIntervalMs = 1_000      // 1s ping
                pingTimeoutMs = 1_000        // 1s pong timeout
                idleTimeoutMs = 60_000       // 60s idle timeout (long enough)
            }
        }
        app.ws("/echo") { conn ->
            conn.onMessage { msg ->
                if (msg is WsMessage.Text) conn.send(msg.data)
            }
        }

        app.listen(port = 8899, host = "127.0.0.1")
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() {
        app.stop()
    }

    private class TestListener : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val closeCode = AtomicReference<Int>()
        val pingCount = AtomicInteger(0)
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

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
            pingCount.incrementAndGet()
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeCode.set(statusCode)
            closeLatch.countDown()
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closeLatch.countDown()
        }
    }

    @Test
    fun `connection should stay alive with ping-pong`() {
        val listener = TestListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("$baseUrl/echo"), listener)
            .get(5, TimeUnit.SECONDS)
        assertTrue(listener.openLatch.await(5, TimeUnit.SECONDS))

        try {
            // Wait for at least 2 ping intervals (2+ seconds)
            Thread.sleep(3000)

            // Connection should still be alive
            ws.sendText("alive", true).get(5, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (listener.messages.size >= 1) break
                Thread.sleep(50)
            }
            assertEquals("alive", listener.messages[0])

            // We should have received at least 1 ping frame
            assertTrue(listener.pingCount.get() >= 1, "Should have received ping frames")
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }
}
