package io.github.cymoo.colleen.util

import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputStreamFactoryTest {

    // ========================================
    // Basic Functionality Tests
    // ========================================

    @Test
    fun `should transfer simple string data`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "Hello, World!"

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should transfer empty data`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()

        // Act
        val inputStream = factory.open { _ ->
            // Write nothing
        }
        val result = inputStream.readBytes()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should transfer large data exceeding buffer size`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 1024)
        val testData = "X".repeat(100000) // 100KB, much larger than 1KB buffer

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
        assertEquals(100000, result.length)
    }

    @Test
    fun `should transfer binary data correctly`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = ByteArray(256) { it.toByte() } // All byte values 0-255

        // Act
        val inputStream = factory.open { output ->
            output.write(testData)
        }
        val result = inputStream.readBytes()

        // Assert
        assertContentEquals(testData, result)
    }

    // ========================================
    // Single-byte Read/Write Tests
    // ========================================

    @Test
    fun `should support single-byte writes`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "ABC"

        // Act
        val inputStream = factory.open { output ->
            testData.forEach { output.write(it.code) }
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should support single-byte reads`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "ABC"

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = buildString {
            var byte = inputStream.read()
            while (byte != -1) {
                append(byte.toChar())
                byte = inputStream.read()
            }
        }

        // Assert
        assertEquals(testData, result)
    }

    // ========================================
    // Batch Read/Write Tests
    // ========================================

    @Test
    fun `should support batch writes`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "Hello, World!".repeat(100)

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should support batch reads`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "Hello, World!".repeat(100)
        val buffer = ByteArray(128)

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = buildString {
            var read = inputStream.read(buffer)
            while (read != -1) {
                append(buffer.copyOf(read).decodeToString())
                read = inputStream.read(buffer)
            }
        }

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should handle partial buffer reads`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "Hello"
        val buffer = ByteArray(10)

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val bytesRead = inputStream.read(buffer, 0, 10)

        // Assert
        assertEquals(5, bytesRead)
        assertEquals("Hello", buffer.copyOf(5).decodeToString())
    }

    @Test
    fun `should return 0 when reading zero-length buffer`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val buffer = ByteArray(10)

        // Act
        val inputStream = factory.open { output ->
            output.write("test".toByteArray())
        }
        val result = inputStream.read(buffer, 0, 0)

        // Assert
        assertEquals(0, result)
    }

    @Test
    fun `should handle write with offset and length`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val data = "XXXHelloXXX".toByteArray()

        // Act
        val inputStream = factory.open { output ->
            output.write(data, 3, 5) // Write only "Hello"
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals("Hello", result)
    }

    // ========================================
    // Ring Buffer Wrapping Tests
    // ========================================

    @Test
    fun `should handle data wrapping around buffer end`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 100)
        val chunk1 = "A".repeat(80)
        val chunk2 = "B".repeat(80)

        // Act
        val inputStream = factory.open { output ->
            output.write(chunk1.toByteArray())
            output.write(chunk2.toByteArray())
        }

        val buffer = ByteArray(70)
        val result = buildString {
            var read = inputStream.read(buffer)
            while (read != -1) {
                append(buffer.copyOf(read).decodeToString())
                read = inputStream.read(buffer)
            }
        }

        // Assert
        assertEquals(chunk1 + chunk2, result)
    }

    @Test
    fun `should handle multiple wrap-around cycles`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 100)
        val iterations = 50
        val chunk = "X".repeat(30)

        // Act
        val inputStream = factory.open { output ->
            repeat(iterations) {
                output.write(chunk.toByteArray())
            }
        }
        val result = inputStream.readBytes()

        // Assert
        assertEquals(iterations * 30, result.size)
    }

    // ========================================
    // EOF Handling Tests
    // ========================================

    @Test
    fun `should return -1 when reading after EOF`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()

        // Act
        val inputStream = factory.open { output ->
            output.write("test".toByteArray())
        }
        inputStream.readBytes() // Read all data
        val result1 = inputStream.read()
        val result2 = inputStream.read()

        // Assert
        assertEquals(-1, result1)
        assertEquals(-1, result2)
    }

    @Test
    fun `should return -1 for batch read after EOF`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val buffer = ByteArray(10)

        // Act
        val inputStream = factory.open { output ->
            output.write("test".toByteArray())
        }
        inputStream.readBytes() // Read all data
        val result = inputStream.read(buffer)

        // Assert
        assertEquals(-1, result)
    }

    // ========================================
    // Exception Handling Tests
    // ========================================

    @Test
    fun `should propagate exception from producer to consumer`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val expectedException = RuntimeException("Producer failed")

        // Act
        val inputStream = factory.open { output ->
            output.write("partial".toByteArray())
            throw expectedException
        }

        // Assert
        val exception = assertThrows<IOException> {
            inputStream.readBytes()
        }
        assertEquals("Producer thread failed", exception.message)
        assertEquals(expectedException, exception.cause)
    }

    @Test
    fun `should handle exception after partial data write`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 1024)
        val testData = "buffered data"
        val dataAvailable = CountDownLatch(1)

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
            dataAvailable.countDown()
            // Keep writer alive so data can be read
            Thread.sleep(200)
            throw RuntimeException("Later failure")
        }

        // Wait for data to be available and read it
        dataAvailable.await()
        val buffer = ByteArray(testData.length)
        val bytesRead = inputStream.read(buffer)

        // Assert - should successfully read the data
        assertEquals(testData.length, bytesRead)
        assertEquals(testData, buffer.decodeToString())

        // After writer throws exception and closes, next read will see EOF with exception
        Thread.sleep(250) // Wait for writer to fail
        assertThrows<IOException> {
            inputStream.read()
        }
    }

    // ========================================
    // Stream Lifecycle Tests
    // ========================================

    @Test
    fun `should throw exception when writing to closed output stream`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        var outputStream: OutputStream? = null

        // Act
        factory.open { output ->
            outputStream = output
        }
        Thread.sleep(100) // Wait for producer to finish

        // Assert
        assertThrows<IOException> {
            outputStream?.write(42)
        }
    }

    @Test
    fun `should allow explicit close of output stream`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "test"

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
            output.close()
            // Subsequent writes should fail
            assertThrows<IOException> {
                output.write(42)
            }
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should handle input stream close before reading all data`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val largeData = "X".repeat(10000)
        val writerFinished = AtomicBoolean(false)

        // Act
        val inputStream = factory.open { output ->
            output.write(largeData.toByteArray())
            writerFinished.set(true)
        }
        inputStream.read() // Read only one byte
        inputStream.close()

        // Wait for writer to finish
        var attempts = 0
        while (!writerFinished.get() && attempts < 50) {
            Thread.sleep(10)
            attempts++
        }

        // Assert - writer should finish without hanging
        assertTrue(writerFinished.get())
    }

    @Test
    fun `should allow close to be called multiple times on input stream`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()

        // Act
        val inputStream = factory.open { output ->
            output.write("test".toByteArray())
        }
        inputStream.close()
        inputStream.close() // Should not throw
        inputStream.close() // Should not throw

        // Assert - no exception thrown
        assertTrue(true)
    }

    // ========================================
    // Concurrency Tests
    // ========================================

    @Test
    fun `should handle concurrent small writes correctly`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 256)
        val iterations = 1000
        val expectedSize = iterations * 5

        // Act
        val inputStream = factory.open { output ->
            repeat(iterations) {
                output.write("Hello".toByteArray())
            }
        }
        val result = inputStream.readBytes()

        // Assert
        assertEquals(expectedSize, result.size)
    }

    @Test
    fun `should support multiple independent streams`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val data1 = "Stream One"
        val data2 = "Stream Two"

        // Act
        val stream1 = factory.open { it.write(data1.toByteArray()) }
        val stream2 = factory.open { it.write(data2.toByteArray()) }
        val result1 = stream1.readBytes().decodeToString()
        val result2 = stream2.readBytes().decodeToString()

        // Assert
        assertEquals(data1, result1)
        assertEquals(data2, result2)
    }

    // ========================================
    // Backpressure Tests
    // ========================================

    @Test
    fun `should handle slow consumer without data loss`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 256)
        val testData = "X".repeat(10000)

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }

        val result = buildString {
            val buffer = ByteArray(17) // Odd size for unaligned reads
            var read = inputStream.read(buffer)
            while (read != -1) {
                append(buffer.copyOf(read).decodeToString())
                Thread.sleep(1) // Simulate slow consumer
                read = inputStream.read(buffer)
            }
        }

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should block producer when buffer is full`() {
        // Arrange - Use small buffer to ensure blocking
        val factory = RingBufferInputStreamFactory(bufferSize = 100)
        val writeCount = AtomicInteger(0)
        val producerStarted = CountDownLatch(1)
        val consumerReady = CountDownLatch(1)

        // Act
        val inputStream = factory.open { output ->
            producerStarted.countDown()
            // Write much more than buffer size
            repeat(50) { i ->
                output.write("0123456789ABCDEF".toByteArray()) // 16 bytes each
                writeCount.incrementAndGet()
                if (i == 0) {
                    // Wait for consumer to be ready before continuing
                    consumerReady.await()
                }
            }
        }

        // Wait for producer to start
        producerStarted.await()
        Thread.sleep(50)

        val countBeforeRead = writeCount.get()

        // Producer should be blocked now because buffer is full
        assertTrue(countBeforeRead < 50, "Producer should be blocked, but wrote $countBeforeRead/50 chunks")

        // Signal consumer is ready and start consuming
        consumerReady.countDown()
        val result = inputStream.readBytes()

        // Assert
        assertEquals(50, writeCount.get()) // All writes completed
        assertEquals(800, result.size) // 50 * 16 bytes
    }

    // ========================================
    // Available Bytes Tests
    // ========================================

    @Test
    fun `should report available bytes correctly`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()
        val testData = "Test"

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        Thread.sleep(50) // Wait for data to be written
        val available = inputStream.available()

        // Assert
        assertTrue(available > 0)
        assertTrue(available <= testData.length)
    }

    @Test
    fun `should return 0 for available after reading all data`() {
        // Arrange
        val factory = RingBufferInputStreamFactory()

        // Act
        val inputStream = factory.open { output ->
            output.write("test".toByteArray())
        }
        inputStream.readBytes()
        Thread.sleep(50) // Wait for EOF
        val available = inputStream.available()

        // Assert
        assertEquals(0, available)
    }

    // ========================================
    // Virtual Thread Tests
    // ========================================

    @Test
    fun `should work with virtual threads enabled`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(useVirtualThreads = true)
        val testData = "Virtual thread test"

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should work with platform threads`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(useVirtualThreads = false)
        val testData = "Platform thread test"

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    // ========================================
    // Stress Tests
    // ========================================

    @Test
    fun `should handle very large data transfer`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 8192)
        val size = 10 * 1024 * 1024 // 10MB
        val testData = ByteArray(size) { (it % 256).toByte() }

        // Act
        val inputStream = factory.open { output ->
            output.write(testData)
        }
        val result = inputStream.readBytes()

        // Assert
        assertEquals(size, result.size)
        assertContentEquals(testData, result)
    }

    @Test
    fun `should handle many small writes`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(bufferSize = 1024)
        val iterations = 10000
        val chunk = "X"

        // Act
        val inputStream = factory.open { output ->
            repeat(iterations) {
                output.write(chunk.toByteArray())
            }
        }
        val result = inputStream.readBytes()

        // Assert
        assertEquals(iterations, result.size)
    }

    // ========================================
    // Timeout Tests
    // ========================================

    @Test
    fun `should timeout on write when buffer is full and not being read`() {
        // Arrange
        val smallBufferSize = 1024 // 1KB
        val writeTimeout = 100L // 100ms
        val factory = RingBufferInputStreamFactory(
            bufferSize = smallBufferSize,
            writeTimeoutMs = writeTimeout
        )

        val largeData = ByteArray(smallBufferSize * 2) { it.toByte() } // 2KB data
        val writerException = AtomicReference<Exception?>()
        val writeLatch = CountDownLatch(1)

        // Act
        val inputStream = factory.open { output ->
            try {
                // This should timeout because we're writing more than buffer size
                // and no one is reading
                output.write(largeData)
            } catch (e: Exception) {
                writerException.set(e)
            } finally {
                writeLatch.countDown()
            }
        }

        // Wait for writer to complete
        writeLatch.await()

        // Assert
        val exception = writerException.get()
        assertTrue(exception is IOException, "Should throw IOException")
        assertEquals(exception.message?.contains("Write timed out"), true, "Exception should indicate write timeout")

        // Clean up
        inputStream.close()
    }

    @Test
    fun `should timeout on read when no data is written`() {
        // Arrange
        val readTimeout = 100L // 100ms
        val factory = RingBufferInputStreamFactory(
            readTimeoutMs = readTimeout
        )
        val startLatch = CountDownLatch(1)

        // Act
        val inputStream = factory.open { _ ->
            startLatch.countDown()
            // Writer does nothing, just waits
            Thread.sleep(500)
        }

        // Wait for writer to start
        startLatch.await()

        // Assert - read should timeout
        assertThrows<IOException> {
            inputStream.read()
        }

        // Clean up
        inputStream.close()
    }

    @Test
    fun `should not timeout when data is transferred within timeout period`() {
        // Arrange
        val readTimeout = 1000L // 1 second
        val writeTimeout = 1000L
        val factory = RingBufferInputStreamFactory(
            readTimeoutMs = readTimeout,
            writeTimeoutMs = writeTimeout
        )
        val testData = "Quick transfer"

        // Act
        val inputStream = factory.open { output ->
            output.write(testData.toByteArray())
        }
        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should timeout on read with partial data available`() {
        // Arrange
        val readTimeout = 100L
        val factory = RingBufferInputStreamFactory(
            readTimeoutMs = readTimeout
        )
        val partialData = "Hello"
        val writeLatch = CountDownLatch(1)

        // Act
        val inputStream = factory.open { output ->
            output.write(partialData.toByteArray())
            output.flush()
            writeLatch.countDown()
            // Keep writer alive but write no more data
            Thread.sleep(500)
        }

        // Wait for initial write
        writeLatch.await()

        // Read the partial data successfully
        val buffer = ByteArray(partialData.length)
        val read = inputStream.read(buffer)
        assertEquals(partialData.length, read)
        assertContentEquals(partialData.toByteArray(), buffer)

        // Assert - next read should timeout
        assertThrows<IOException> {
            inputStream.read()
        }

        // Clean up
        inputStream.close()
    }

    @Test
    fun `should timeout on write with slow reader`() {
        // Arrange
        val smallBufferSize = 512
        val writeTimeout = 100L
        val factory = RingBufferInputStreamFactory(
            bufferSize = smallBufferSize,
            writeTimeoutMs = writeTimeout
        )

        val largeData = ByteArray(smallBufferSize * 3) { it.toByte() }
        val writerException = AtomicReference<Exception?>()
        val writeLatch = CountDownLatch(1)

        // Act
        val inputStream = factory.open { output ->
            try {
                output.write(largeData)
            } catch (e: Exception) {
                writerException.set(e)
            } finally {
                writeLatch.countDown()
            }
        }

        // Slow reader - read only a small amount
        Thread.sleep(50) // Give writer time to fill buffer

        val smallBuffer = ByteArray(100)
        inputStream.read(smallBuffer)

        // Wait for writer to complete/timeout
        writeLatch.await()

        // Assert
        val exception = writerException.get()
        assertTrue(exception is IOException, "Writer should have timed out")
        assertEquals(exception.message?.contains("Write timed out"), true, "Exception should indicate write timeout")

        // Clean up
        inputStream.close()
    }

    @Test
    fun `should work correctly with zero timeout (infinite wait)`() {
        // Arrange
        val factory = RingBufferInputStreamFactory(
            readTimeoutMs = 0L, // infinite
            writeTimeoutMs = 0L  // infinite
        )
        val testData = "No timeout test"

        // Act
        val inputStream = factory.open { output ->
            Thread.sleep(100) // Simulate some delay
            output.write(testData.toByteArray())
        }

        val result = inputStream.readBytes().decodeToString()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `should return EOF when writer closes after reading all data`() {
        // Arrange
        val smallBufferSize = 256
        val timeout = 150L
        val factory = RingBufferInputStreamFactory(
            bufferSize = smallBufferSize,
            readTimeoutMs = timeout,
            writeTimeoutMs = timeout
        )

        val data = ByteArray(smallBufferSize / 2) { it.toByte() }
        val readCount = AtomicInteger(0)

        // Act
        val inputStream = factory.open { output ->
            // Write some data
            output.write(data)
            Thread.sleep(50)
            // Write more data
            output.write(data)
            // Writer will close automatically when lambda completes
        }

        // Read first chunk
        val buffer1 = ByteArray(data.size)
        inputStream.read(buffer1)
        readCount.incrementAndGet()

        // Read second chunk
        val buffer2 = ByteArray(data.size)
        inputStream.read(buffer2)
        readCount.incrementAndGet()

        // Try to read more - should return -1 (EOF) since writer is done
        val result = inputStream.read()

        // Assert
        assertEquals(2, readCount.get())
        assertContentEquals(data, buffer1)
        assertContentEquals(data, buffer2)
        assertEquals(-1, result, "Should return -1 (EOF) when writer is closed")

        // Clean up
        inputStream.close()
    }

    @Test
    fun `should timeout on read when writer is alive but not writing`() {
        // Arrange
        val timeout = 100L
        val factory = RingBufferInputStreamFactory(
            readTimeoutMs = timeout
        )

        val data = "Initial data"
        val writeLatch = CountDownLatch(1)
        val keepAlive = CountDownLatch(1)

        // Act
        val inputStream = factory.open { output ->
            // Write initial data
            output.write(data.toByteArray())
            writeLatch.countDown()

            // Keep writer alive but don't write more data
            keepAlive.await() // Wait indefinitely to keep writer thread alive
        }

        // Wait for initial write
        writeLatch.await()

        // Read the initial data successfully
        val buffer = ByteArray(data.length)
        inputStream.read(buffer)
        assertContentEquals(data.toByteArray(), buffer)

        // Assert - next read should timeout because writer is alive but not writing
        assertThrows<IOException> {
            inputStream.read()
        }

        // Clean up
        keepAlive.countDown() // Release the writer
        inputStream.close()
    }

    @Test
    fun `should propagate timeout exception message correctly`() {
        // Arrange
        val readTimeout = 50L
        val factory = RingBufferInputStreamFactory(
            readTimeoutMs = readTimeout
        )

        // Act
        val inputStream = factory.open { _ ->
            Thread.sleep(200) // Writer waits longer than read timeout
        }

        // Assert
        val exception = assertThrows<IOException> {
            inputStream.read()
        }
        assertEquals(
            exception.message?.contains("Read timed out"),
            true,
            "Exception message should indicate read timeout"
        )

        // Clean up
        inputStream.close()
    }

    @Test
    fun `write timeout should occur when buffer remains full`() {
        // Arrange
        val bufferSize = 1024
        val writeTimeout = 100L
        val factory = RingBufferInputStreamFactory(
            bufferSize = bufferSize,
            writeTimeoutMs = writeTimeout
        )

        val largeData = ByteArray(bufferSize * 2) { it.toByte() }
        val writerException = AtomicReference<Exception?>()
        val writeLatch = CountDownLatch(1)

        // Act
        val inputStream = factory.open { output ->
            try {
                // Fill buffer completely and attempt to write more
                output.write(largeData)
            } catch (e: Exception) {
                writerException.set(e)
            } finally {
                writeLatch.countDown()
            }
        }

        // Don't read anything, let writer timeout
        writeLatch.await()

        // Assert
        val exception = writerException.get()
        assertTrue(exception is IOException, "Should throw IOException")
        assertEquals(exception.message?.contains("Write timed out"), true, "Write timeout should have occurred")

        // Clean up
        inputStream.close()
    }
}