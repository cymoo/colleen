package io.github.cymoo.colleen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// -------------------------------------------------------------------------
// Test controllers
// -------------------------------------------------------------------------

@Controller("/realtime")
class WsController {

    @Ws("/chat/{room}")
    fun chat(conn: WebSocketConnection) {
        conn.onMessage { conn.send("Echo: ${(it as WebSocketMessage.Text).text()}") }
    }

    @Ws("/feed")
    fun feed(conn: WebSocketConnection) {
        conn.onClose { }
    }
}

@Controller("/multi")
class MultiWsController {
    @Ws
    @Ws("/home")
    fun index(conn: WebSocketConnection) {}
}

@Controller("/invalid-param-count")
class WsInvalidParamCountController {
    @Ws("/bad")
    fun handler() {} // no params
}

@Controller("/invalid-param-type")
class WsInvalidParamTypeController {
    @Ws("/bad")
    fun handler(ctx: Context) {} // wrong param type
}

@Controller("/mixed")
class MixedController {
    @Get("/users")
    fun getUsers(): String = "users"

    @Ws("/live")
    fun live(conn: WebSocketConnection) {}
}

// -------------------------------------------------------------------------
// Tests
// -------------------------------------------------------------------------

class WebSocketScannerTest {

    @Test
    fun `scans Ws routes with correct method and path`() {
        val info = ControllerScanner.scan(WsController())
        val routes = info.routes.filter { it.method == "WS" }
        assertEquals(2, routes.size)

        val chatRoute = routes.find { it.path == "/chat/{room}" }
        val feedRoute = routes.find { it.path == "/feed" }

        assertTrue(chatRoute != null, "Expected /chat/{room} WS route")
        assertTrue(feedRoute != null, "Expected /feed WS route")
        assertEquals("WS", chatRoute!!.method)
        assertEquals("WS", feedRoute!!.method)
    }

    @Test
    fun `validates that @Ws method has exactly one parameter`() {
        val ex = assertThrows<IllegalArgumentException> {
            ControllerScanner.scan(WsInvalidParamCountController())
        }
        assertTrue(ex.message!!.contains("exactly 1 parameter"), "Message: ${ex.message}")
    }

    @Test
    fun `validates that @Ws method parameter is WebSocketConnection`() {
        val ex = assertThrows<IllegalArgumentException> {
            ControllerScanner.scan(WsInvalidParamTypeController())
        }
        assertTrue(ex.message!!.contains("WebSocketConnection"), "Message: ${ex.message}")
    }

    @Test
    fun `multiple @Ws annotations on one method produce multiple routes`() {
        val info = ControllerScanner.scan(MultiWsController())
        val wsRoutes = info.routes.filter { it.method == "WS" }
        assertEquals(2, wsRoutes.size)
        assertTrue(wsRoutes.any { it.path == "/" })
        assertTrue(wsRoutes.any { it.path == "/home" })
    }

    @Test
    fun `mixed controller scans both HTTP and WS routes`() {
        val info = ControllerScanner.scan(MixedController())
        val httpRoutes = info.routes.filter { it.method == "GET" }
        val wsRoutes = info.routes.filter { it.method == "WS" }
        assertEquals(1, httpRoutes.size)
        assertEquals(1, wsRoutes.size)
    }

    @Test
    fun `Ws route handler method is accessible`() {
        val info = ControllerScanner.scan(WsController())
        info.routes.filter { it.method == "WS" }.forEach { route ->
            assertTrue(route.handler.trySetAccessible())
        }
    }
}
