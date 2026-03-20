package io.github.cymoo.colleen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSocketConnectionTest {

    // -------------------------------------------------------------------------
    // Test adapter
    // -------------------------------------------------------------------------

    private class TestAdapter : WebSocketChannelAdapter {
        val sentTexts = mutableListOf<String>()
        val sentBinaries = mutableListOf<ByteArray>()
        var closedCode: Int? = null
        var closedReason: String? = null
        var throwOnSend = false

        override fun sendText(text: String) {
            if (throwOnSend) throw RuntimeException("Send failed")
            sentTexts.add(text)
        }

        override fun sendBinary(bytes: ByteArray) {
            if (throwOnSend) throw RuntimeException("Send failed")
            sentBinaries.add(bytes)
        }

        override fun close(code: Int, reason: String) {
            closedCode = code
            closedReason = reason
        }

        override val isClosed: Boolean get() = closedCode != null

        override fun close() = close(WebSocketCloseReason.NORMAL, "")
    }

    private fun createConnection(
        pathParams: Map<String, String> = emptyMap(),
        queryString: String = "",
        headers: Map<String, String> = emptyMap(),
        attributes: Map<String, Any?> = emptyMap(),
    ): Pair<WebSocketConnection, TestAdapter> {
        val adapter = TestAdapter()
        val conn = WebSocketConnection(adapter, pathParams, queryString, headers, attributes)
        return conn to adapter
    }

    // -------------------------------------------------------------------------
    // WebSocketMessage tests
    // -------------------------------------------------------------------------

    @Test
    fun `WebSocketMessage Text returns correct text and bytes`() {
        val msg = WebSocketMessage.Text("hello")
        assertEquals("hello", msg.text())
        assertTrue(msg.bytes().contentEquals("hello".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `WebSocketMessage Binary returns correct bytes and text`() {
        val bytes = "world".toByteArray()
        val msg = WebSocketMessage.Binary(bytes)
        assertTrue(msg.bytes().contentEquals(bytes))
        assertEquals("world", msg.text())
    }

    // -------------------------------------------------------------------------
    // WebSocketCloseReason tests
    // -------------------------------------------------------------------------

    @Test
    fun `WebSocketCloseReason constants have correct values`() {
        assertEquals(1000, WebSocketCloseReason.NORMAL)
        assertEquals(1001, WebSocketCloseReason.GOING_AWAY)
    }

    @Test
    fun `WebSocketCloseReason error factory returns code 1011`() {
        val reason = WebSocketCloseReason.error("oops")
        assertEquals(1011, reason.code)
        assertEquals("oops", reason.reason)
    }

    // -------------------------------------------------------------------------
    // send() tests
    // -------------------------------------------------------------------------

    @Test
    fun `send text delivers text to adapter`() {
        val (conn, adapter) = createConnection()
        conn.send("hello")
        assertEquals(listOf("hello"), adapter.sentTexts)
    }

    @Test
    fun `send binary delivers bytes to adapter`() {
        val (conn, adapter) = createConnection()
        val data = byteArrayOf(1, 2, 3)
        conn.send(data)
        assertEquals(1, adapter.sentBinaries.size)
        assertTrue(adapter.sentBinaries[0].contentEquals(data))
    }

    @Test
    fun `send after close is silently ignored`() {
        val (conn, adapter) = createConnection()
        conn.close()
        conn.send("should be ignored")
        conn.send(byteArrayOf(1, 2))
        assertTrue(adapter.sentTexts.isEmpty())
        assertTrue(adapter.sentBinaries.isEmpty())
    }

    @Test
    fun `send survives adapter exception without propagating`() {
        val (conn, adapter) = createConnection()
        adapter.throwOnSend = true
        // Should not throw
        conn.send("text")
        conn.send(byteArrayOf(1))
    }

    // -------------------------------------------------------------------------
    // close() tests
    // -------------------------------------------------------------------------

    @Test
    fun `close marks connection as closed`() {
        val (conn, _) = createConnection()
        assertFalse(conn.isClosed)
        conn.close()
        assertTrue(conn.isClosed)
    }

    @Test
    fun `close with code and reason sends correct values to adapter`() {
        val (conn, adapter) = createConnection()
        conn.close(1001, "going away")
        assertEquals(1001, adapter.closedCode)
        assertEquals("going away", adapter.closedReason)
    }

    @Test
    fun `close with default args uses NORMAL code`() {
        val (conn, adapter) = createConnection()
        conn.close()
        assertEquals(WebSocketCloseReason.NORMAL, adapter.closedCode)
        assertEquals("", adapter.closedReason)
    }

    @Test
    fun `multiple close calls are idempotent`() {
        val (conn, _) = createConnection()
        var callbackCount = 0
        conn.onClose { callbackCount++ }
        conn.close()
        conn.close()
        conn.close()
        assertEquals(1, callbackCount)
    }

    // -------------------------------------------------------------------------
    // onClose callback tests
    // -------------------------------------------------------------------------

    @Test
    fun `onClose callback is invoked with correct reason`() {
        val (conn, _) = createConnection()
        var received: WebSocketCloseReason? = null
        conn.onClose { received = it }
        conn.close(1001, "bye")
        assertNotNull(received)
        assertEquals(1001, received!!.code)
        assertEquals("bye", received!!.reason)
    }

    @Test
    fun `multiple onClose callbacks all invoked`() {
        val (conn, _) = createConnection()
        val count = AtomicInteger(0)
        conn.onClose { count.incrementAndGet() }
        conn.onClose { count.incrementAndGet() }
        conn.onClose { count.incrementAndGet() }
        conn.close()
        assertEquals(3, count.get())
    }

    // -------------------------------------------------------------------------
    // dispatchClose tests
    // -------------------------------------------------------------------------

    @Test
    fun `dispatchClose invokes close callbacks`() {
        val (conn, _) = createConnection()
        var received: WebSocketCloseReason? = null
        conn.onClose { received = it }
        conn.dispatchClose(1000, "server closed")
        assertNotNull(received)
        assertEquals(1000, received!!.code)
    }

    @Test
    fun `dispatchClose marks connection as closed`() {
        val (conn, _) = createConnection()
        conn.dispatchClose(1000, "")
        assertTrue(conn.isClosed)
    }

    @Test
    fun `dispatchClose after close is no-op`() {
        val (conn, _) = createConnection()
        var callbackCount = 0
        conn.onClose { callbackCount++ }
        conn.close()
        conn.dispatchClose(1000, "")
        assertEquals(1, callbackCount)
    }

    // -------------------------------------------------------------------------
    // onOpen / onMessage / onError tests
    // -------------------------------------------------------------------------

    @Test
    fun `dispatchOpen calls onOpen handler`() {
        val (conn, _) = createConnection()
        var opened = false
        conn.onOpen { opened = true }
        conn.dispatchOpen()
        assertTrue(opened)
    }

    @Test
    fun `dispatchMessage calls onMessage handler`() {
        val (conn, _) = createConnection()
        var received: WebSocketMessage? = null
        conn.onMessage { received = it }
        conn.dispatchMessage(WebSocketMessage.Text("hi"))
        assertTrue(received is WebSocketMessage.Text)
        assertEquals("hi", (received as WebSocketMessage.Text).text())
    }

    @Test
    fun `dispatchError calls onError handler`() {
        val (conn, _) = createConnection()
        var caught: Throwable? = null
        conn.onError { caught = it }
        val err = RuntimeException("oops")
        conn.dispatchError(err)
        assertEquals(err, caught)
    }

    @Test
    fun `later onMessage registration replaces earlier one`() {
        val (conn, _) = createConnection()
        var first = false
        var second = false
        conn.onMessage { first = true }
        conn.onMessage { second = true }
        conn.dispatchMessage(WebSocketMessage.Text("x"))
        assertFalse(first, "First handler should be replaced")
        assertTrue(second)
    }

    // -------------------------------------------------------------------------
    // Request data access tests
    // -------------------------------------------------------------------------

    @Test
    fun `pathParam returns correct value`() {
        val (conn, _) = createConnection(pathParams = mapOf("id" to "42", "name" to "bob"))
        assertEquals("42", conn.pathParam("id"))
        assertEquals("bob", conn.pathParam("name"))
        assertNull(conn.pathParam("missing"))
    }

    @Test
    fun `query returns correct value from queryString`() {
        val (conn, _) = createConnection(queryString = "token=abc&v=2")
        assertEquals("abc", conn.query("token"))
        assertEquals("2", conn.query("v"))
        assertNull(conn.query("missing"))
    }

    @Test
    fun `query returns null for empty queryString`() {
        val (conn, _) = createConnection(queryString = "")
        assertNull(conn.query("any"))
    }

    @Test
    fun `attribute returns typed value`() {
        val (conn, _) = createConnection(attributes = mapOf("userId" to "user123", "count" to 5))
        assertEquals("user123", conn.attribute<String>("userId"))
        assertEquals(5, conn.attribute<Int>("count"))
        assertNull(conn.attribute<String>("missing"))
    }

    @Test
    fun `headers map is accessible`() {
        val headers = mapOf("authorization" to "Bearer token123")
        val (conn, _) = createConnection(headers = headers)
        assertEquals("Bearer token123", conn.headers["authorization"])
    }

    // -------------------------------------------------------------------------
    // Thread safety tests
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent send calls are safe`() {
        val (conn, adapter) = createConnection()
        val threadCount = 10
        val messagesPerThread = 20
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { t ->
            Thread {
                repeat(messagesPerThread) { m ->
                    conn.send("thread-$t-msg-$m")
                }
                latch.countDown()
            }.start()
        }

        latch.await(5, TimeUnit.SECONDS)
        assertEquals(threadCount * messagesPerThread, adapter.sentTexts.size)
    }

    @Test
    fun `concurrent close calls result in single callback invocation`() {
        val (conn, _) = createConnection()
        val callbackCount = AtomicInteger(0)
        conn.onClose { callbackCount.incrementAndGet() }

        val threadCount = 20
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                conn.close()
                latch.countDown()
            }.start()
        }

        latch.await(5, TimeUnit.SECONDS)
        assertEquals(1, callbackCount.get())
        assertTrue(conn.isClosed)
    }

    @Test
    fun `send and close from different threads is safe`() {
        val (conn, _) = createConnection()
        val sendCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val latch = CountDownLatch(2)

        Thread {
            repeat(200) {
                runCatching { conn.send("msg-$it"); sendCount.incrementAndGet() }
                    .onFailure { errorCount.incrementAndGet() }
                Thread.sleep(1)
            }
            latch.countDown()
        }.start()

        Thread {
            Thread.sleep(50)
            conn.close()
            latch.countDown()
        }.start()

        latch.await(5, TimeUnit.SECONDS)
        assertTrue(conn.isClosed)
    }

    // -------------------------------------------------------------------------
    // WebSocketChannelAdapter.close() method
    // -------------------------------------------------------------------------

    @Test
    fun `close(code, reason) with only code param uses empty reason`() {
        val (conn, adapter) = createConnection()
        conn.close(1001)
        assertEquals(1001, adapter.closedCode)
        assertEquals("", adapter.closedReason)
    }
}
