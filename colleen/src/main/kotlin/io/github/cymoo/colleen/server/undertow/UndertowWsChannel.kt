package io.github.cymoo.colleen.server.undertow

import io.github.cymoo.colleen.ws.WsChannel
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.core.WebSockets
import java.nio.ByteBuffer

/**
 * Undertow-backed WebSocket channel implementation.
 *
 * Bridges Undertow's async WebSocketChannel to Colleen's blocking WsChannel interface.
 */
internal class UndertowWsChannel(private val channel: WebSocketChannel) : WsChannel {

    override fun sendText(text: String) {
        WebSockets.sendTextBlocking(text, channel)
    }

    override fun sendBinary(data: ByteBuffer) {
        WebSockets.sendBinaryBlocking(data, channel)
    }

    override fun close(code: Int, reason: String) {
        WebSockets.sendCloseBlocking(code, reason, channel)
    }

    override fun close() {
        close(1000, "")
    }
}
