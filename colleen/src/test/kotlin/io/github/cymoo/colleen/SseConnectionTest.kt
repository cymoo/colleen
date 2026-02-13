package io.github.cymoo.colleen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SseConnectionTest {

    private fun createTestWriter(): TestSseWriter = TestSseWriter()

    private class TestSseWriter : SseWriter {
        private val buffer = StringBuilder()
        private var closed = false
        private var shouldThrowOnWrite = false
        private var shouldThrowOnFlush = false

        val output: String get() = buffer.toString()
        val isClosed: Boolean get() = closed

        fun throwOnWrite() {
            shouldThrowOnWrite = true
        }

        fun throwOnFlush() {
            shouldThrowOnFlush = true
        }

        override fun write(text: String) {
            if (closed) throw IOException("Writer is closed")
            if (shouldThrowOnWrite) throw IOException("Write failed")
            buffer.append(text)
        }

        override fun flush() {
            if (closed) throw IOException("Writer is closed")
            if (shouldThrowOnFlush) throw IOException("Flush failed")
        }

        override fun close() {
            closed = true
        }

        fun clear() {
            buffer.clear()
        }
    }

    // ---------- Basic send tests ----------

    @Test
    fun `should send simple data event`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.send("Hello World")

        // Assert
        val expected = "data: Hello World\n\n"
        assertEquals(expected, writer.output)
        assertFalse(connection.isClosed)
    }

    @Test
    fun `should send event with all fields`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        val event = SseEvent(
            data = "test data",
            event = "message",
            id = "123",
            retry = 5000L
        )

        // Act
        connection.send(event)

        // Assert
        val output = writer.output
        assertTrue(output.contains("id: 123\n"))
        assertTrue(output.contains("event: message\n"))
        assertTrue(output.contains("retry: 5000\n"))
        assertTrue(output.contains("data: test data\n"))
        assertTrue(output.endsWith("\n"))
    }

    @Test
    fun `should send multiline data correctly`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        val multilineData = "line1\nline2\nline3"

        // Act
        connection.send(multilineData)

        // Assert
        val expected = "data: line1\ndata: line2\ndata: line3\n\n"
        assertEquals(expected, writer.output)
    }

    @Test
    fun `should send event with only event name and data`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        val event = SseEvent(data = "test", event = "custom")

        // Act
        connection.send(event)

        // Assert
        val output = writer.output
        assertTrue(output.contains("event: custom\n"))
        assertTrue(output.contains("data: test\n"))
        assertFalse(output.contains("id:"))
        assertFalse(output.contains("retry:"))
    }

    // ---------- Comment tests ----------

    @Test
    fun `should send comment as keep-alive`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.comment()

        // Assert
        assertEquals(": \n\n", writer.output)
    }

    @Test
    fun `should send comment with text`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.comment("keep-alive ping")

        // Assert
        assertEquals(": keep-alive ping\n\n", writer.output)
    }

    // ---------- Close tests ----------

    @Test
    fun `should close connection normally`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.close()

        // Assert
        assertTrue(connection.isClosed)
        assertTrue(writer.isClosed)
    }

    @Test
    fun `should throw IOException when sending after close`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        connection.close()

        // Act & Assert
        assertThrows<IOException> {
            connection.send("test")
        }
    }

    @Test
    fun `should throw IOException when sending comment after close`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        connection.close()

        // Act & Assert
        assertThrows<IOException> {
            connection.comment()
        }
    }

    @Test
    fun `should handle multiple close calls safely`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.close()
        connection.close()
        connection.close()

        // Assert
        assertTrue(connection.isClosed)
    }

    @Test
    fun `should close with specific reason`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        var capturedReason: SseCloseReason? = null
        connection.onClose { capturedReason = it }

        // Act
        connection.close(SseCloseReason.ClientDisconnected)

        // Assert
        assertTrue(connection.isClosed)
        assertEquals(SseCloseReason.ClientDisconnected, capturedReason)
    }

    @Test
    fun `should close with error reason`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        var capturedReason: SseCloseReason? = null
        connection.onClose { capturedReason = it }
        val testException = RuntimeException("test error")

        // Act
        connection.close(SseCloseReason.Error(testException))

        // Assert
        assertTrue(connection.isClosed)
        assertTrue(capturedReason is SseCloseReason.Error)
        assertEquals(testException, (capturedReason as SseCloseReason.Error).cause)
    }

    // ---------- onClose callback tests ----------

    @Test
    fun `should invoke onClose callback when connection closes`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        var callbackInvoked = false
        var receivedReason: SseCloseReason? = null

        connection.onClose { reason ->
            callbackInvoked = true
            receivedReason = reason
        }

        // Act
        connection.close()

        // Assert
        assertTrue(callbackInvoked)
        assertEquals(SseCloseReason.Normal, receivedReason)
    }

    @Test
    fun `should invoke multiple onClose callbacks`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        val callbackCount = AtomicInteger(0)

        connection.onClose { callbackCount.incrementAndGet() }
        connection.onClose { callbackCount.incrementAndGet() }
        connection.onClose { callbackCount.incrementAndGet() }

        // Act
        connection.close()

        // Assert
        assertEquals(3, callbackCount.get())
    }

    @Test
    fun `should invoke callback immediately if already closed`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        connection.close(SseCloseReason.ClientDisconnected)

        var callbackInvoked = false
        var receivedReason: SseCloseReason? = null

        // Act
        connection.onClose { reason ->
            callbackInvoked = true
            receivedReason = reason
        }

        // Assert
        assertTrue(callbackInvoked)
        assertEquals(SseCloseReason.ClientDisconnected, receivedReason)
    }

    // ---------- Error handling tests ----------

    @Test
    fun `should close connection on write error`() {
        // Arrange
        val writer = createTestWriter()
        writer.throwOnWrite()
        val connection = SseConnection(writer)

        // Act & Assert
        assertThrows<IOException> {
            connection.send("test")
        }
        assertTrue(connection.isClosed)
    }

    @Test
    fun `should close connection on flush error`() {
        // Arrange
        val writer = createTestWriter()
        writer.throwOnFlush()
        val connection = SseConnection(writer)

        // Act & Assert
        assertThrows<IOException> {
            connection.send("test")
        }
        assertTrue(connection.isClosed)
    }

    @Test
    fun `should set ClientDisconnected reason on IO error`() {
        // Arrange
        val writer = createTestWriter()
        writer.throwOnWrite()
        val connection = SseConnection(writer)
        var capturedReason: SseCloseReason? = null
        connection.onClose { capturedReason = it }

        // Act
        runCatching { connection.send("test") }

        // Assert
        assertEquals(SseCloseReason.ClientDisconnected, capturedReason)
    }

    // ---------- Keep-alive tests ----------

    @Test
    fun `should send periodic keep-alive comments`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.keepAlive(Duration.ofMillis(100))
        Thread.sleep(350) // Wait for ~3 keep-alives

        // Assert
        val output = writer.output
        val commentCount = output.split(": \n\n").size - 1
        assertTrue(commentCount >= 2, "Should have sent at least 2 keep-alive comments")
    }

    @Test
    fun `should replace previous keep-alive task`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.keepAlive(Duration.ofMillis(50))
        Thread.sleep(100)
        writer.clear()
        connection.keepAlive(Duration.ofMillis(200))
        Thread.sleep(150)

        // Assert
        val output = writer.output
        // Should have 0-1 comments with the new interval, not multiple
        val commentCount = output.split(": \n\n").size - 1
        assertTrue(commentCount <= 1)
    }

    @Test
    fun `should not start keep-alive with zero duration`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.keepAlive(Duration.ZERO)
        Thread.sleep(100)

        // Assert
        assertEquals("", writer.output)
    }

    @Test
    fun `should not start keep-alive with negative duration`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.keepAlive(Duration.ofMillis(-100))
        Thread.sleep(100)

        // Assert
        assertEquals("", writer.output)
    }

    @Test
    fun `should stop keep-alive when connection closes`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.keepAlive(Duration.ofMillis(50))
        Thread.sleep(120)
        val countBeforeClose = writer.output.split(": \n\n").size - 1
        connection.close()
        Thread.sleep(150)
        val countAfterClose = writer.output.split(": \n\n").size - 1

        // Assert
        assertEquals(countBeforeClose, countAfterClose, "Should not send keep-alive after close")
    }

    @Test
    fun `should accept keep-alive interval in seconds`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)

        // Act
        connection.keepAlive(1) // 1 second
        Thread.sleep(1200)

        // Assert
        val commentCount = writer.output.split(": \n\n").size - 1
        assertTrue(commentCount >= 1)
    }

    // ---------- Thread safety tests ----------

    @Test
    fun `should handle concurrent send calls safely`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        val threadCount = 10
        val messagesPerThread = 10
        val latch = CountDownLatch(threadCount)

        // Act
        repeat(threadCount) { threadNum ->
            Thread {
                repeat(messagesPerThread) { msgNum ->
                    runCatching {
                        connection.send("thread-$threadNum-msg-$msgNum")
                    }
                }
                latch.countDown()
            }.start()
        }

        latch.await(5, TimeUnit.SECONDS)

        // Assert
        val output = writer.output
        val eventCount = output.split("\n\n").count { it.isNotBlank() }
        assertEquals(threadCount * messagesPerThread, eventCount)
    }

    @Test
    fun `should handle concurrent close calls safely`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        val callbackCount = AtomicInteger(0)
        connection.onClose { callbackCount.incrementAndGet() }

        val threadCount = 10
        val latch = CountDownLatch(threadCount)

        // Act
        repeat(threadCount) {
            Thread {
                connection.close()
                latch.countDown()
            }.start()
        }

        latch.await(5, TimeUnit.SECONDS)

        // Assert
        assertTrue(connection.isClosed)
        assertEquals(1, callbackCount.get(), "Callback should be invoked exactly once")
    }

    @Test
    fun `should handle send and close concurrently`() {
        // Arrange
        val writer = createTestWriter()
        val connection = SseConnection(writer)
        val sendCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        // Act
        Thread {
            Thread.sleep(50)
            connection.close()
        }.start()

        Thread {
            repeat(100) {
                runCatching {
                    connection.send("message-$it")
                    sendCount.incrementAndGet()
                }.onFailure {
                    errorCount.incrementAndGet()
                }
                Thread.sleep(1)
            }
        }.start()

        Thread.sleep(200)

        // Assert
        assertTrue(connection.isClosed)
        assertTrue(sendCount.get() > 0, "Some messages should be sent before close")
        assertTrue(errorCount.get() > 0, "Some messages should fail after close")
    }
}