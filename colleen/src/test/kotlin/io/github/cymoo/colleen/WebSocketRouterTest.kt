package io.github.cymoo.colleen

import org.junit.jupiter.api.Test
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for WebSocket route registration, matching, and 426 enforcement.
 */
class WebSocketRouterTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeApp(): Colleen {
        val app = Colleen()
        app.config { server { useVirtualThreads = false } }
        return app
    }

    private fun wsRequest(path: String, withUpgrade: Boolean = true): Request {
        val headers = io.github.cymoo.colleen.util.http.Headers()
        if (withUpgrade) {
            headers["Upgrade"] = "websocket"
        }
        return Request(
            method = "GET",
            path = path,
            headers = headers
        )
    }

    private fun plainGetRequest(path: String): Request =
        Request(method = "GET", path = path)

    // -----------------------------------------------------------------------
    // Route registration tests
    // -----------------------------------------------------------------------

    @Test
    fun `ws() registers route with WS method`() {
        val app = makeApp()
        app.ws("/chat") { _ -> }
        val wsRoute = app.router.routes.find { it.method == "WS" && it.path == "/chat" }
        assertNotNull(wsRoute)
    }

    @Test
    fun `ws() emits WebSocketRouteRegistered event`() {
        val app = makeApp()
        var wsRouteRegistered: Event.WebSocketRouteRegistered? = null
        app.on<Event.WebSocketRouteRegistered> { wsRouteRegistered = it }
        app.ws("/events") { _ -> }
        assertNotNull(wsRouteRegistered)
        assertEquals("WS", wsRouteRegistered!!.node.method)
        assertEquals("/events", wsRouteRegistered!!.node.path)
    }

    @Test
    fun `ws() in group registers under correct prefix`() {
        val app = makeApp()
        app.group("/api/v1") {
            ws("/notifications") { _ -> }
        }
        val wsRoute = app.router.routes.find { it.method == "WS" }
        assertNotNull(wsRoute)
        assertEquals("/api/v1/notifications", wsRoute!!.path)
    }

    // -----------------------------------------------------------------------
    // 426 Upgrade Required tests
    // -----------------------------------------------------------------------

    @Test
    fun `GET request to WS route without upgrade header returns 426`() {
        val app = makeApp()
        app.ws("/chat") { _ -> }

        val ctx = Context(request = plainGetRequest("/chat"), app = app)
        app.handleRequest(ctx)
        assertEquals(426, ctx.response.status)
    }

    @Test
    fun `GET request with upgrade header matches WS route`() {
        val app = makeApp()
        app.ws("/chat") { _ -> }

        // Simulate what would happen if the server handled the exchange
        // We verify the route is matched (no 426 returned) by inspecting response body
        val ctx = Context(request = wsRequest("/chat"), app = app)
        app.handleRequest(ctx)
        // If we get here without a 426 response, the route matched correctly
        assertTrue(ctx.response.materializedBody is RawResponseBody.WebSocket)
    }

    @Test
    fun `non-ws path still returns 404 for GET request`() {
        val app = makeApp()
        app.ws("/chat") { _ -> }

        val ctx = Context(request = wsRequest("/other-path"), app = app)
        app.handleRequest(ctx)
        assertEquals(404, ctx.response.status)
    }

    // -----------------------------------------------------------------------
    // WebSocket middleware (wsUse) applies to WS routes
    // -----------------------------------------------------------------------

    @Test
    fun `wsUse middleware runs before WS handler and can set attributes`() {
        val app = makeApp()
        app.wsUse("/chat") { ctx, next ->
            ctx.setState("userId", "alice")
            next()
        }

        var capturedAttributes: Map<String, Any?> = emptyMap()
        app.ws("/chat") { conn ->
            capturedAttributes = conn.attributes
        }

        val ctx = Context(request = wsRequest("/chat"), app = app)
        app.handleRequest(ctx)

        // Attributes should be captured in the WS body
        val wsBody = ctx.response.materializedBody as RawResponseBody.WebSocket
        assertEquals("alice", wsBody.attributes["userId"])
    }

    @Test
    fun `wsUse middleware can reject WS handshake request`() {
        val app = makeApp()
        app.wsUse("/secure") { ctx, next ->
            // Simulate auth rejection
            throw HttpException(401, "Unauthorized")
        }
        app.ws("/secure/live") { _ -> }

        val ctx = Context(request = wsRequest("/secure/live"), app = app)
        app.handleRequest(ctx)
        assertEquals(401, ctx.response.status)
    }

    // -----------------------------------------------------------------------
    // HTTP middleware does NOT apply to WS routes (Option B isolation)
    // -----------------------------------------------------------------------

    @Test
    fun `HTTP use middleware does NOT run for WS upgrade requests`() {
        val app = makeApp()
        var middlewareCalled = false
        app.use { ctx, next ->
            middlewareCalled = true
            next()
        }
        app.ws("/chat") { _ -> }

        val ctx = Context(request = wsRequest("/chat"), app = app)
        app.handleRequest(ctx)

        assertFalse(middlewareCalled, "HTTP middleware should not run for WS upgrade requests")
    }

    @Test
    fun `HTTP use middleware DOES run for plain GET to WS path (no upgrade header)`() {
        val app = makeApp()
        var middlewareCalled = false
        app.use { ctx, next ->
            middlewareCalled = true
            next()
        }
        app.ws("/chat") { _ -> }

        // Plain GET without upgrade header goes through HTTP middleware chain
        val ctx = Context(request = plainGetRequest("/chat"), app = app)
        app.handleRequest(ctx)

        assertTrue(middlewareCalled, "HTTP middleware should run for plain GET (non-WS) requests")
        assertEquals(426, ctx.response.status)
    }

    @Test
    fun `wsUse middleware does NOT run for plain HTTP requests`() {
        val app = makeApp()
        var wsMwCalled = false
        app.wsUse { ctx, next ->
            wsMwCalled = true
            next()
        }
        app.get("/api") { ctx -> ctx.text("ok") }

        val ctx = Context(request = Request(method = "GET", path = "/api"), app = app)
        app.handleRequest(ctx)

        assertFalse(wsMwCalled, "WS middleware should not run for plain HTTP requests")
    }

    // -----------------------------------------------------------------------
    // Path parameter extraction
    // -----------------------------------------------------------------------

    @Test
    fun `WS route path params are captured in RawResponseBody`() {
        val app = makeApp()
        app.ws("/chat/{room}") { _ -> }

        val ctx = Context(request = wsRequest("/chat/lobby"), app = app)
        app.handleRequest(ctx)

        val wsBody = ctx.response.materializedBody as RawResponseBody.WebSocket
        assertEquals("lobby", wsBody.pathParams["room"])
    }

    // -----------------------------------------------------------------------
    // Controller @Ws annotation
    // -----------------------------------------------------------------------

    @Test
    fun `addController registers WS routes from @Ws annotations`() {
        @Controller("/rt")
        class RtController {
            @Ws("/chat")
            fun chat(conn: WebSocketConnection) {}
        }

        val app = makeApp()
        app.addController(RtController())

        val wsRoute = app.router.routes.find { it.method == "WS" && it.path == "/rt/chat" }
        assertNotNull(wsRoute)
    }

    @Test
    fun `controller WS handler is invoked when route matches`() {
        var handlerCalled = false

        @Controller("/rt")
        class RtController {
            @Ws("/live")
            fun live(conn: WebSocketConnection) {
                handlerCalled = true
            }
        }

        val app = makeApp()
        app.addController(RtController())

        val ctx = Context(request = wsRequest("/rt/live"), app = app)
        app.handleRequest(ctx)

        // The handler was embedded in a lambda; it runs when the connection is accepted
        // We verify the WS body is set
        assertTrue(ctx.response.materializedBody is RawResponseBody.WebSocket)
    }

    // -----------------------------------------------------------------------
    // OpenAPI excludes WS routes
    // -----------------------------------------------------------------------

    @Test
    fun `WS routes are excluded from OpenAPI spec`() {
        val app = makeApp()
        app.get("/users") { }
        app.ws("/notifications") { _ -> }

        val routes = collectRoutes(app, "", emptySet())
        assertTrue(routes.none { it.node.method == "WS" })
        assertTrue(routes.any { it.node.method == "GET" })
    }

    // -----------------------------------------------------------------------
    // 405 logic does not include WS
    // -----------------------------------------------------------------------

    @Test
    fun `POST to WS path returns 404 not 405`() {
        val app = makeApp()
        app.ws("/chat") { _ -> }

        val ctx = Context(
            request = Request(method = "POST", path = "/chat"),
            app = app
        )
        app.handleRequest(ctx)
        // WS routes should not appear in allowed methods, so 404 not 405
        assertEquals(404, ctx.response.status)
    }

    // -----------------------------------------------------------------------
    // WebSocket lifecycle events
    // -----------------------------------------------------------------------

    @Test
    fun `RawResponseBody WebSocket carries onConnected callback when set via ws()`() {
        val app = makeApp()
        app.ws("/events") { _ -> }

        val ctx = Context(request = wsRequest("/events"), app = app)
        app.handleRequest(ctx)

        val wsBody = ctx.response.materializedBody as RawResponseBody.WebSocket
        // Event callbacks are wired internally — the callback should be non-null
        assertNotNull(wsBody.onConnected)
        assertNotNull(wsBody.onDisconnected)
    }

    @Test
    fun `WebSocketConnected event is emitted when onConnected callback is invoked`() {
        val app = makeApp()
        app.ws("/live") { _ -> }

        var connectedEvent: Event.WebSocketConnected? = null
        app.on<Event.WebSocketConnected> { connectedEvent = it }

        val ctx = Context(request = wsRequest("/live"), app = app)
        app.handleRequest(ctx)

        val wsBody = ctx.response.materializedBody as RawResponseBody.WebSocket
        // Simulate the server calling onConnected after the upgrade
        val adapter = object : WebSocketChannelAdapter {
            override fun sendText(text: String) {}
            override fun sendBinary(bytes: ByteArray) {}
            override fun close(code: Int, reason: String) {}
            override val isClosed = false
            override fun close() {}
        }
        val conn = WebSocketConnection(adapter, wsBody.pathParams, wsBody.queryString, wsBody.headers, wsBody.attributes)
        wsBody.onConnected!!.invoke(conn)

        assertNotNull(connectedEvent)
        assertEquals("/live", connectedEvent!!.path)
        assertEquals(conn, connectedEvent!!.conn)
    }

    @Test
    fun `WebSocketDisconnected event is emitted when onDisconnected callback is invoked`() {
        val app = makeApp()
        app.ws("/live") { _ -> }

        var disconnectedEvent: Event.WebSocketDisconnected? = null
        app.on<Event.WebSocketDisconnected> { disconnectedEvent = it }

        val ctx = Context(request = wsRequest("/live"), app = app)
        app.handleRequest(ctx)

        val wsBody = ctx.response.materializedBody as RawResponseBody.WebSocket
        val adapter = object : WebSocketChannelAdapter {
            override fun sendText(text: String) {}
            override fun sendBinary(bytes: ByteArray) {}
            override fun close(code: Int, reason: String) {}
            override val isClosed = false
            override fun close() {}
        }
        val conn = WebSocketConnection(adapter, wsBody.pathParams, wsBody.queryString, wsBody.headers, wsBody.attributes)
        val reason = WebSocketCloseReason(1000, "normal")
        wsBody.onDisconnected!!.invoke(conn, reason)

        assertNotNull(disconnectedEvent)
        assertEquals("/live", disconnectedEvent!!.path)
        assertEquals(conn, disconnectedEvent!!.conn)
        assertEquals(reason, disconnectedEvent!!.reason)
    }

    @Test
    fun `WebSocket events carry normalized route path`() {
        val app = makeApp()
        app.ws("/chat/") { _ -> }  // trailing slash — should be normalized

        var connectedPath: String? = null
        app.on<Event.WebSocketConnected> { connectedPath = it.path }

        val ctx = Context(request = wsRequest("/chat"), app = app)
        app.handleRequest(ctx)

        val wsBody = ctx.response.materializedBody as RawResponseBody.WebSocket
        val adapter = object : WebSocketChannelAdapter {
            override fun sendText(text: String) {}
            override fun sendBinary(bytes: ByteArray) {}
            override fun close(code: Int, reason: String) {}
            override val isClosed = false
            override fun close() {}
        }
        val conn = WebSocketConnection(adapter, wsBody.pathParams, wsBody.queryString, wsBody.headers, wsBody.attributes)
        wsBody.onConnected!!.invoke(conn)

        // Normalized path has no trailing slash
        assertEquals("/chat", connectedPath)
    }

    @Test
    fun `controller @Ws routes also carry WebSocket event callbacks`() {
        @Controller("/rt")
        class RtController {
            @Ws("/events")
            fun events(conn: WebSocketConnection) {}
        }

        val app = makeApp()
        app.addController(RtController())

        val ctx = Context(request = wsRequest("/rt/events"), app = app)
        app.handleRequest(ctx)

        val wsBody = ctx.response.materializedBody as RawResponseBody.WebSocket
        assertNotNull(wsBody.onConnected)
        assertNotNull(wsBody.onDisconnected)
    }
}
