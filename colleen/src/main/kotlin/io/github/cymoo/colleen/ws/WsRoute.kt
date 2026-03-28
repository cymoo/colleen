package io.github.cymoo.colleen.ws

import io.github.cymoo.colleen.util.http.UrlPath
import java.util.function.Consumer

// ============================================================================
// WsHandler
// ============================================================================

/**
 * Functional interface for WebSocket handlers.
 *
 * The handler receives a [WsConnection] after a successful WebSocket handshake.
 * The handler should register callbacks (onMessage, onClose, onError) and
 * may perform initial setup.
 */
@FunctionalInterface
fun interface WsHandler {
    operator fun invoke(conn: WsConnection)
}

// ============================================================================
// WsRouteNode
// ============================================================================

/**
 * Represents a registered WebSocket route.
 *
 * @property path the path pattern for this WebSocket route
 * @property handler the handler invoked when a client connects
 */
class WsRouteNode private constructor(
    val path: String,
    val handler: Consumer<WsConnection>,
) {
    companion object {
        fun of(path: String, handler: Consumer<WsConnection>): WsRouteNode {
            val normalizedPath = UrlPath.normalize(path)
            return WsRouteNode(path = normalizedPath, handler = handler)
        }
    }

    override fun toString() = "WsRouteNode(path=$path)"
}
