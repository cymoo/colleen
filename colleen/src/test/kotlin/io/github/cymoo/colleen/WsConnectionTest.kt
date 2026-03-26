package io.github.cymoo.colleen

import io.github.cymoo.colleen.ws.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WsConnectionTest {

    private fun createTestChannel(): TestWsChannel = TestWsChannel()

    private fun createConnection(
        channel: TestWsChannel = createTestChannel(),
        pathParams: Map<String, String> = emptyMap(),
    ): WsConnection = WsConnection(channel, pathParams)

    private class TestWsChannel : WsChannel {
        val sentTexts = mutableListOf<String>()
        val sentBinaries = mutableListOf<ByteArray>()
        var closedCode: Int? = null
        var closedReason: String? = null
        var shouldThrowOnSendText = false
        var shouldThrowOnSendBinary = false
        var closed = false

        override fun sendText(text: String) {
            if (shouldThrowOnSendText) throw IOException("Send text failed")
            sentTexts.add(text)
        }

        override fun sendBinary(data: ByteBuffer) {
            if (shouldThrowOnSendBinary) throw IOException("Send binary failed")
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            sentBinaries.add(bytes)
        }

        override fun close(code: Int, reason: String) {
            closedCode = code
            closedReason = reason
        }

        override fun close() {
            closed = true
            close(1000, "")
        }
    }

    // ---------- Basic state tests ----------

    @Test
    fun `new connection is not closed`() {
        val conn = createConnection()
        assertFalse(conn.isClosed)
    }

    @Test
    fun `closed connection reports isClosed`() {
        val conn = createConnection()
        conn.close()
        assertTrue(conn.isClosed)
    }

    // ---------- Path params ----------

    @Test
    fun `should expose path parameters`() {
        val conn = createConnection(pathParams = mapOf("room" to "lobby", "id" to "42"))
        assertEquals("lobby", conn.pathParam("room"))
        assertEquals("42", conn.pathParam("id"))
        assertEquals(null, conn.pathParam("missing"))
    }

    // ---------- Send tests ----------

    @Test
    fun `should send text message`() {
        val channel = createTestChannel()
        val conn = createConnection(channel)

        conn.send("Hello")
        assertEquals(listOf("Hello"), channel.sentTexts)
    }

    @Test
    fun `should send binary message`() {
        val channel = createTestChannel()
        val conn = createConnection(channel)

        val data = byteArrayOf(1, 2, 3, 4)
        conn.send(data)
        assertEquals(1, channel.sentBinaries.size)
        assertTrue(data.contentEquals(channel.sentBinaries[0]))
    }

    @Test
    fun `send text after close throws IOException`() {
        val conn = createConnection()
        conn.close()
        assertThrows<IOException> { conn.send("Hello") }
    }

    @Test
    fun `send binary after close throws IOException`() {
        val conn = createConnection()
        conn.close()
        assertThrows<IOException> { conn.send(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `send text failure closes connection with ClientDisconnected`() {
        val channel = createTestChannel()
        val conn = createConnection(channel)
        channel.shouldThrowOnSendText = true

        val reason = AtomicReference<WsCloseReason>()
        conn.onClose { reason.set(it) }

        assertThrows<IOException> { conn.send("Hello") }

        assertTrue(conn.isClosed)
        assertTrue(reason.get() is WsCloseReason.ClientDisconnected)
    }

    @Test
    fun `send binary failure closes connection with ClientDisconnected`() {
        val channel = createTestChannel()
        val conn = createConnection(channel)
        channel.shouldThrowOnSendBinary = true

        val reason = AtomicReference<WsCloseReason>()
        conn.onClose { reason.set(it) }

        assertThrows<IOException> { conn.send(byteArrayOf(1, 2, 3)) }

        assertTrue(conn.isClosed)
        assertTrue(reason.get() is WsCloseReason.ClientDisconnected)
    }

    // ---------- onMessage callback tests ----------

    @Test
    fun `should dispatch text message to callbacks`() {
        val conn = createConnection()
        val received = mutableListOf<WsMessage>()
        conn.onMessage { received.add(it) }

        conn.dispatchMessage(WsMessage.Text("hello"))
        assertEquals(1, received.size)
        assertEquals(WsMessage.Text("hello"), received[0])
    }

    @Test
    fun `should dispatch binary message to callbacks`() {
        val conn = createConnection()
        val received = mutableListOf<WsMessage>()
        conn.onMessage { received.add(it) }

        val data = byteArrayOf(1, 2, 3)
        conn.dispatchMessage(WsMessage.Binary(data))
        assertEquals(1, received.size)
        assertTrue(received[0] is WsMessage.Binary)
        assertTrue(data.contentEquals((received[0] as WsMessage.Binary).data))
    }

    @Test
    fun `should invoke multiple message callbacks`() {
        val conn = createConnection()
        val count = AtomicInteger(0)
        conn.onMessage { count.incrementAndGet() }
        conn.onMessage { count.incrementAndGet() }

        conn.dispatchMessage(WsMessage.Text("test"))
        assertEquals(2, count.get())
    }

    @Test
    fun `message callback exception dispatches to error callbacks`() {
        val conn = createConnection()
        val error = AtomicReference<Throwable>()
        conn.onError { error.set(it) }
        conn.onMessage { throw RuntimeException("boom") }

        conn.dispatchMessage(WsMessage.Text("trigger"))
        assertTrue(error.get() is RuntimeException)
        assertEquals("boom", error.get()?.message)
    }

    // ---------- onClose callback tests ----------

    @Test
    fun `should invoke close callback on close`() {
        val conn = createConnection()
        val reason = AtomicReference<WsCloseReason>()
        conn.onClose { reason.set(it) }

        conn.close()
        assertTrue(reason.get() is WsCloseReason.Normal)
    }

    @Test
    fun `should invoke close callback with specific reason`() {
        val conn = createConnection()
        val reason = AtomicReference<WsCloseReason>()
        conn.onClose { reason.set(it) }

        conn.close(WsCloseReason.Protocol(1008, "Policy violation"))
        assertTrue(reason.get() is WsCloseReason.Protocol)
        assertEquals(1008, (reason.get() as WsCloseReason.Protocol).code)
    }

    @Test
    fun `should invoke multiple close callbacks`() {
        val conn = createConnection()
        val count = AtomicInteger(0)
        conn.onClose { count.incrementAndGet() }
        conn.onClose { count.incrementAndGet() }

        conn.close()
        assertEquals(2, count.get())
    }

    @Test
    fun `close is idempotent - callbacks invoked only once`() {
        val conn = createConnection()
        val count = AtomicInteger(0)
        conn.onClose { count.incrementAndGet() }

        conn.close()
        conn.close()
        conn.close()
        assertEquals(1, count.get())
    }

    @Test
    fun `onClose after close invokes callback immediately`() {
        val conn = createConnection()
        conn.close(WsCloseReason.ClientDisconnected)

        val reason = AtomicReference<WsCloseReason>()
        conn.onClose { reason.set(it) }
        assertTrue(reason.get() is WsCloseReason.ClientDisconnected)
    }

    @Test
    fun `close sends close frame via channel`() {
        val channel = createTestChannel()
        val conn = createConnection(channel)

        conn.close(WsCloseReason.Normal)
        assertEquals(1000, channel.closedCode)
        assertEquals("", channel.closedReason)
    }

    @Test
    fun `close with protocol reason sends close frame`() {
        val channel = createTestChannel()
        val conn = createConnection(channel)

        conn.close(WsCloseReason.Protocol(1003, "Unsupported"))
        assertEquals(1003, channel.closedCode)
        assertEquals("Unsupported", channel.closedReason)
    }

    // ---------- onError callback tests ----------

    @Test
    fun `should invoke error callbacks`() {
        val conn = createConnection()
        val error = AtomicReference<Throwable>()
        conn.onError { error.set(it) }

        conn.dispatchError(RuntimeException("test error"))
        assertTrue(error.get() is RuntimeException)
        assertEquals("test error", error.get()?.message)
    }

    @Test
    fun `should invoke multiple error callbacks`() {
        val conn = createConnection()
        val count = AtomicInteger(0)
        conn.onError { count.incrementAndGet() }
        conn.onError { count.incrementAndGet() }

        conn.dispatchError(RuntimeException("test"))
        assertEquals(2, count.get())
    }

    // ---------- Thread safety tests ----------

    @Test
    fun `concurrent send calls are serialized`() {
        val channel = createTestChannel()
        val conn = createConnection(channel)

        val threads = 10
        val messagesPerThread = 10
        val latch = CountDownLatch(threads)

        repeat(threads) { i ->
            Thread {
                repeat(messagesPerThread) { j ->
                    conn.send("$i-$j")
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(threads * messagesPerThread, channel.sentTexts.size)
    }

    @Test
    fun `concurrent close calls - callback invoked only once`() {
        val conn = createConnection()
        val count = AtomicInteger(0)
        conn.onClose { count.incrementAndGet() }

        val threads = 10
        val latch = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                conn.close()
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, count.get())
    }
}
