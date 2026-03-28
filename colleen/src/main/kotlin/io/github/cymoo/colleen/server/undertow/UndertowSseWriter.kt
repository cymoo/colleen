package io.github.cymoo.colleen.server.undertow

import io.github.cymoo.colleen.SseWriter
import io.undertow.server.HttpServerExchange

/**
 * Undertow-backed SSE writer implementation.
 *
 * Writes UTF-8 encoded event data directly to the response stream.
 */
internal class UndertowSseWriter(exchange: HttpServerExchange) : SseWriter {
    private val writer = exchange.outputStream.bufferedWriter(Charsets.UTF_8)

    override fun write(text: String) = writer.write(text)
    override fun flush() = writer.flush()
    override fun close() = writer.close()
}
