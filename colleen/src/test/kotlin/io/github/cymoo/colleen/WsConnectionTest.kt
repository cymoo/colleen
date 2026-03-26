package io.github.cymoo.colleen

import io.github.cymoo.colleen.ws.*
import org.junit.jupiter.api.Nested
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WsConnectionTest {

    private fun createTestChannel(): TestWsChannel = TestWsChannel()

    private fun createConnection(
        channel: TestWsChannel = createTestChannel(),
        pathParams: Map<String, String> = emptyMap(),
        queryParams: Map<String, List<String>> = emptyMap(),
    ): WsConnection = WsConnection(channel, pathParams, queryParams)

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

    // ========================================================================
    // State
    // ========================================================================

    @Nested
    inner class State {
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

        @Test
        fun `close with specific reason reports isClosed`() {
            val conn = createConnection()
            conn.close(WsCloseReason.Error(RuntimeException("test")))
            assertTrue(conn.isClosed)
        }
    }

    // ========================================================================
    // Path Parameters
    // ========================================================================

    @Nested
    inner class PathParams {
        @Test
        fun `should expose path parameters`() {
            val conn = createConnection(pathParams = mapOf("room" to "lobby", "id" to "42"))
            assertEquals("lobby", conn.pathParam("room"))
            assertEquals("42", conn.pathParam("id"))
        }

        @Test
        fun `should return null for missing path parameter`() {
            val conn = createConnection(pathParams = mapOf("room" to "lobby"))
            assertNull(conn.pathParam("missing"))
        }

        @Test
        fun `should expose pathParams map directly`() {
            val params = mapOf("a" to "1", "b" to "2")
            val conn = createConnection(pathParams = params)
            assertEquals(params, conn.pathParams)
        }

        @Test
        fun `empty path params`() {
            val conn = createConnection()
            assertEquals(emptyMap(), conn.pathParams)
            assertNull(conn.pathParam("anything"))
        }
    }

    // ========================================================================
    // Query Parameters
    // ========================================================================

    @Nested
    inner class QueryParams {
        @Test
        fun `should return first query parameter value`() {
            val conn = createConnection(queryParams = mapOf("token" to listOf("abc123")))
            assertEquals("abc123", conn.query("token"))
        }

        @Test
        fun `should return first value for multi-valued query parameter`() {
            val conn = createConnection(queryParams = mapOf("tag" to listOf("a", "b", "c")))
            assertEquals("a", conn.query("tag"))
        }

        @Test
        fun `should return all values via queryList`() {
            val conn = createConnection(queryParams = mapOf("tag" to listOf("a", "b", "c")))
            assertEquals(listOf("a", "b", "c"), conn.queryList("tag"))
        }

        @Test
        fun `should return null for missing query parameter`() {
            val conn = createConnection(queryParams = mapOf("token" to listOf("abc")))
            assertNull(conn.query("missing"))
        }

        @Test
        fun `should return empty list for missing queryList`() {
            val conn = createConnection()
            assertEquals(emptyList(), conn.queryList("missing"))
        }

        @Test
        fun `empty query params`() {
            val conn = createConnection()
            assertEquals(emptyMap(), conn.queryParams)
        }
    }

    // ========================================================================
    // Send Text
    // ========================================================================

    @Nested
    inner class SendText {
        @Test
        fun `should send text message`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            conn.send("Hello")
            assertEquals(listOf("Hello"), channel.sentTexts)
        }

        @Test
        fun `should send empty text message`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            conn.send("")
            assertEquals(listOf(""), channel.sentTexts)
        }

        @Test
        fun `should send multiple text messages`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            conn.send("msg1")
            conn.send("msg2")
            conn.send("msg3")
            assertEquals(listOf("msg1", "msg2", "msg3"), channel.sentTexts)
        }

        @Test
        fun `should send unicode text`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            conn.send("你好世界 🌍 émojis")
            assertEquals("你好世界 🌍 émojis", channel.sentTexts[0])
        }

        @Test
        fun `should send large text message`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            val largeText = "x".repeat(100_000)
            conn.send(largeText)
            assertEquals(largeText, channel.sentTexts[0])
        }

        @Test
        fun `send text after close throws IOException`() {
            val conn = createConnection()
            conn.close()
            assertThrows<IOException> { conn.send("Hello") }
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
    }

    // ========================================================================
    // Send Binary
    // ========================================================================

    @Nested
    inner class SendBinary {
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
        fun `should send empty binary message`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            conn.send(byteArrayOf())
            assertEquals(1, channel.sentBinaries.size)
            assertEquals(0, channel.sentBinaries[0].size)
        }

        @Test
        fun `should send large binary message`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            val data = ByteArray(100_000) { (it % 256).toByte() }
            conn.send(data)
            assertTrue(data.contentEquals(channel.sentBinaries[0]))
        }

        @Test
        fun `send binary after close throws IOException`() {
            val conn = createConnection()
            conn.close()
            assertThrows<IOException> { conn.send(byteArrayOf(1, 2, 3)) }
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
    }

    // ========================================================================
    // Message Callbacks
    // ========================================================================

    @Nested
    inner class MessageCallbacks {
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
        fun `should invoke callbacks in registration order`() {
            val conn = createConnection()
            val order = mutableListOf<Int>()
            conn.onMessage { order.add(1) }
            conn.onMessage { order.add(2) }
            conn.onMessage { order.add(3) }

            conn.dispatchMessage(WsMessage.Text("test"))
            assertEquals(listOf(1, 2, 3), order)
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

        @Test
        fun `message callback exception does not stop other callbacks`() {
            val conn = createConnection()
            val count = AtomicInteger(0)
            conn.onMessage { throw RuntimeException("boom") }
            conn.onMessage { count.incrementAndGet() }
            conn.onError { } // suppress error

            conn.dispatchMessage(WsMessage.Text("test"))
            assertEquals(1, count.get())
        }

        @Test
        fun `no callbacks registered - dispatch does not throw`() {
            val conn = createConnection()
            conn.dispatchMessage(WsMessage.Text("hello"))
            conn.dispatchMessage(WsMessage.Binary(byteArrayOf(1, 2)))
        }

        @Test
        fun `dispatch after close does not invoke callbacks`() {
            val conn = createConnection()
            val count = AtomicInteger(0)
            conn.onMessage { count.incrementAndGet() }
            conn.close()

            conn.dispatchMessage(WsMessage.Text("after-close"))
            assertEquals(0, count.get())
        }
    }

    // ========================================================================
    // Close Callbacks
    // ========================================================================

    @Nested
    inner class CloseCallbacks {
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
            assertEquals("Policy violation", (reason.get() as WsCloseReason.Protocol).reason)
        }

        @Test
        fun `should invoke close callback with error reason`() {
            val conn = createConnection()
            val reason = AtomicReference<WsCloseReason>()
            conn.onClose { reason.set(it) }

            val exception = RuntimeException("test error")
            conn.close(WsCloseReason.Error(exception))
            assertTrue(reason.get() is WsCloseReason.Error)
            assertEquals(exception, (reason.get() as WsCloseReason.Error).cause)
        }

        @Test
        fun `should invoke close callback with ClientDisconnected`() {
            val conn = createConnection()
            val reason = AtomicReference<WsCloseReason>()
            conn.onClose { reason.set(it) }

            conn.close(WsCloseReason.ClientDisconnected)
            assertTrue(reason.get() is WsCloseReason.ClientDisconnected)
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
        fun `close callback exception does not prevent other callbacks`() {
            val conn = createConnection()
            val count = AtomicInteger(0)
            conn.onClose { throw RuntimeException("boom") }
            conn.onClose { count.incrementAndGet() }

            conn.close()
            assertEquals(1, count.get())
        }
    }

    // ========================================================================
    // Close Frame
    // ========================================================================

    @Nested
    inner class CloseFrame {
        @Test
        fun `Normal close sends code 1000`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)

            conn.close(WsCloseReason.Normal)
            assertEquals(1000, channel.closedCode)
            assertEquals("", channel.closedReason)
        }

        @Test
        fun `Protocol close sends specified code`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)

            conn.close(WsCloseReason.Protocol(1003, "Unsupported"))
            assertEquals(1003, channel.closedCode)
            assertEquals("Unsupported", channel.closedReason)
        }

        @Test
        fun `ClientDisconnected sends code 1001`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)

            conn.close(WsCloseReason.ClientDisconnected)
            assertEquals(1001, channel.closedCode)
            assertEquals("Client disconnected", channel.closedReason)
        }

        @Test
        fun `Error sends code 1001`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)

            conn.close(WsCloseReason.Error(RuntimeException("test")))
            assertEquals(1001, channel.closedCode)
            assertEquals("test", channel.closedReason)
        }

        @Test
        fun `Error with null message sends Error`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)

            conn.close(WsCloseReason.Error(RuntimeException()))
            assertEquals(1001, channel.closedCode)
            assertEquals("Error", channel.closedReason)
        }
    }

    // ========================================================================
    // Error Callbacks
    // ========================================================================

    @Nested
    inner class ErrorCallbacks {
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

        @Test
        fun `error callback exception does not propagate`() {
            val conn = createConnection()
            val count = AtomicInteger(0)
            conn.onError { throw RuntimeException("callback error") }
            conn.onError { count.incrementAndGet() }

            conn.dispatchError(RuntimeException("original"))
            assertEquals(1, count.get())
        }

        @Test
        fun `no error callbacks - dispatch does not throw`() {
            val conn = createConnection()
            conn.dispatchError(RuntimeException("unhandled"))
        }
    }

    // ========================================================================
    // WsMessage Types
    // ========================================================================

    @Nested
    inner class WsMessageTypes {
        @Test
        fun `Text message equality`() {
            assertEquals(WsMessage.Text("hello"), WsMessage.Text("hello"))
            assertNotEquals(WsMessage.Text("hello"), WsMessage.Text("world"))
        }

        @Test
        fun `Binary message equality`() {
            val data1 = byteArrayOf(1, 2, 3)
            val data2 = byteArrayOf(1, 2, 3)
            val data3 = byteArrayOf(4, 5, 6)
            assertEquals(WsMessage.Binary(data1), WsMessage.Binary(data2))
            assertNotEquals(WsMessage.Binary(data1), WsMessage.Binary(data3))
        }

        @Test
        fun `Binary message hashCode`() {
            val data1 = byteArrayOf(1, 2, 3)
            val data2 = byteArrayOf(1, 2, 3)
            assertEquals(WsMessage.Binary(data1).hashCode(), WsMessage.Binary(data2).hashCode())
        }

        @Test
        fun `Text and Binary are not equal`() {
            assertNotEquals<WsMessage>(WsMessage.Text("test"), WsMessage.Binary(byteArrayOf()))
        }

        @Test
        fun `Empty binary message`() {
            val empty = WsMessage.Binary(byteArrayOf())
            assertEquals(0, empty.data.size)
        }
    }

    // ========================================================================
    // WsCloseReason Types
    // ========================================================================

    @Nested
    inner class WsCloseReasonTypes {
        @Test
        fun `Normal toString`() {
            assertEquals("Normal", WsCloseReason.Normal.toString())
        }

        @Test
        fun `ClientDisconnected toString`() {
            assertEquals("ClientDisconnected", WsCloseReason.ClientDisconnected.toString())
        }

        @Test
        fun `Protocol equality`() {
            assertEquals(WsCloseReason.Protocol(1008, "Policy"), WsCloseReason.Protocol(1008, "Policy"))
            assertNotEquals(WsCloseReason.Protocol(1008, "A"), WsCloseReason.Protocol(1008, "B"))
        }

        @Test
        fun `Error wraps cause`() {
            val cause = RuntimeException("test")
            val reason = WsCloseReason.Error(cause)
            assertEquals(cause, reason.cause)
        }
    }

    // ========================================================================
    // Thread Safety
    // ========================================================================

    @Nested
    inner class ThreadSafety {
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

        @Test
        fun `concurrent send and close`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            val sendCount = AtomicInteger(0)
            val errorCount = AtomicInteger(0)

            Thread {
                Thread.sleep(50)
                conn.close()
            }.start()

            Thread {
                repeat(100) {
                    runCatching {
                        conn.send("message-$it")
                        sendCount.incrementAndGet()
                    }.onFailure {
                        errorCount.incrementAndGet()
                    }
                    Thread.sleep(1)
                }
            }.start()

            Thread.sleep(300)

            assertTrue(conn.isClosed)
            assertTrue(sendCount.get() > 0, "Some messages should be sent before close")
            assertTrue(errorCount.get() > 0, "Some messages should fail after close")
        }

        @Test
        fun `concurrent message registration`() {
            val conn = createConnection()
            val latch = CountDownLatch(10)
            val count = AtomicInteger(0)

            repeat(10) {
                Thread {
                    conn.onMessage { count.incrementAndGet() }
                    latch.countDown()
                }.start()
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            conn.dispatchMessage(WsMessage.Text("test"))
            assertEquals(10, count.get())
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    inner class EdgeCases {
        @Test
        fun `send after send failure`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            channel.shouldThrowOnSendText = true

            assertThrows<IOException> { conn.send("fail") }
            assertThrows<IOException> { conn.send("after fail") }
        }

        @Test
        fun `close after AutoCloseable use`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            conn.use { it.send("hello") }
            assertTrue(conn.isClosed)
            assertEquals(1000, channel.closedCode)
        }

        @Test
        fun `multiple send types interleaved`() {
            val channel = createTestChannel()
            val conn = createConnection(channel)
            conn.send("text1")
            conn.send(byteArrayOf(1, 2))
            conn.send("text2")
            conn.send(byteArrayOf(3, 4))

            assertEquals(listOf("text1", "text2"), channel.sentTexts)
            assertEquals(2, channel.sentBinaries.size)
        }

        @Test
        fun `dispatchError after close does not invoke callbacks`() {
            val conn = createConnection()
            val count = AtomicInteger(0)
            conn.onError { count.incrementAndGet() }
            conn.close()

            conn.dispatchError(RuntimeException("after close"))
            assertEquals(0, count.get())
        }

        @Test
        fun `pathParams and queryParams are independent`() {
            val conn = createConnection(
                pathParams = mapOf("id" to "42"),
                queryParams = mapOf("id" to listOf("99"))
            )
            assertEquals("42", conn.pathParam("id"))
            assertEquals("99", conn.query("id"))
        }
    }
}
