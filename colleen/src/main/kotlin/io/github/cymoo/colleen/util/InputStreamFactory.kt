package io.github.cymoo.colleen.util

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Factory for creating an [InputStream] whose contents are produced asynchronously.
 *
 * The provided producer is executed in background and is given an
 * [OutputStream] to write data into. Data written by the producer becomes
 * immediately available for reading from the returned [InputStream].
 *
 * ### Lifecycle
 * - The producer is started when [open] is called.
 * - Closing the returned [InputStream] signals the producer to stop writing.
 * - When the producer completes normally, the [InputStream] reaches EOF.
 *
 * ### Error handling
 * - If the producer throws an exception, it will be rethrown as an [IOException]
 *   when the consumer reads from the [InputStream].
 *
 * Implementations may use different buffering or threading strategies.
 */
@FunctionalInterface
fun interface InputStreamFactory {
    fun open(producer: (OutputStream) -> Unit): InputStream
}

/**
 * Utility for creating an InputStream backed by a ring buffer.
 *
 * This implementation uses a lock-based ring buffer which is virtual-thread friendly.
 *
 * Supports optional read/write timeouts to avoid unbounded blocking.
 */
class RingBufferInputStreamFactory(
    useVirtualThreads: Boolean = true,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    val readTimeoutMs: Long = 0L,   // 0 = infinite
    val writeTimeoutMs: Long = 0L   // 0 = infinite
) : InputStreamFactory {

    private val executorService: ExecutorService = if (useVirtualThreads) {
        sharedVirtualThreadExecutor
    } else {
        sharedThreadPoolExecutor
    }

    /**
     * Returns an InputStream whose content is produced asynchronously.
     */
    override fun open(producer: (OutputStream) -> Unit): InputStream {
        val ringBuffer = RingBuffer(
            bufferSize,
            readTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(readTimeoutMs),
            writeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(writeTimeoutMs)
        )
        val exceptionRef = AtomicReference<Exception?>()

        executorService.execute {
            try {
                producer(ringBuffer.outputStream)
            } catch (e: Exception) {
                exceptionRef.set(e)
            } finally {
                ringBuffer.closeWriter()
            }
        }

        return RingBufferInputStream(ringBuffer, exceptionRef)
    }

    /**
     * A simple ring buffer for single-producer single-consumer scenarios.
     */
    private class RingBuffer(
        capacity: Int,
        private val readTimeoutNanos: Long,
        private val writeTimeoutNanos: Long
    ) {
        private val buffer = ByteArray(capacity)
        private var readPos = 0
        private var writePos = 0
        private var available = 0 // Number of bytes available to read

        private val writerClosed = AtomicBoolean(false)
        private val readerClosed = AtomicBoolean(false)

        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()
        private val notFull = lock.newCondition()

        val outputStream = object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len == 0) return
                checkWriterNotClosed()

                var offset = off
                var remaining = len

                while (remaining > 0) {
                    val written = lock.withLock {
                        var remainingNanos = writeTimeoutNanos

                        // Wait for space or reader closed
                        while (available == buffer.size && !readerClosed.get()) {
                            if (writeTimeoutNanos == 0L) {
                                notFull.await()
                            } else {
                                if (remainingNanos <= 0L) {
                                    throw InterruptedIOException("Write timed out")
                                }
                                remainingNanos = notFull.awaitNanos(remainingNanos)
                            }
                        }

                        // If reader closed, stop writing
                        if (readerClosed.get()) {
                            return@write
                        }

                        // Calculate how much we can write in one go
                        val space = buffer.size - available
                        val toWrite = minOf(remaining, space, buffer.size - writePos)

                        // Write data
                        System.arraycopy(b, offset, buffer, writePos, toWrite)
                        writePos = (writePos + toWrite) % buffer.size
                        available += toWrite

                        notEmpty.signal()
                        toWrite
                    }

                    offset += written
                    remaining -= written
                }
            }

            override fun flush() {
                // Ring buffer writes are immediately visible, no buffering needed
            }

            override fun close() {
                closeWriter()
            }

            private fun checkWriterNotClosed() {
                if (writerClosed.get()) {
                    throw IOException("OutputStream is closed")
                }
            }
        }

        fun read(): Int {
            val result = ByteArray(1)
            return if (read(result, 0, 1) == -1) -1 else result[0].toInt() and 0xFF
        }

        fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (readerClosed.get()) return -1

            lock.withLock {
                var remainingNanos = readTimeoutNanos

                // Wait for data or writer closed
                while (available == 0 && !writerClosed.get()) {
                    if (readTimeoutNanos == 0L) {
                        notEmpty.await()
                    } else {
                        if (remainingNanos <= 0L) {
                            throw InterruptedIOException("Read timed out")
                        }
                        remainingNanos = notEmpty.awaitNanos(remainingNanos)
                    }
                }

                // Check EOF (writer closed and no data)
                if (available == 0 && writerClosed.get()) {
                    return -1
                }

                // Calculate how much we can read in one go
                val toRead = minOf(len, available, buffer.size - readPos)

                // Read data
                System.arraycopy(buffer, readPos, b, off, toRead)
                readPos = (readPos + toRead) % buffer.size
                available -= toRead

                notFull.signal()
                return toRead
            }
        }

        fun availableToRead(): Int = lock.withLock { available }

        fun closeWriter() {
            if (writerClosed.compareAndSet(false, true)) {
                lock.withLock {
                    notEmpty.signalAll() // Wake up blocked readers
                }
            }
        }

        fun closeReader() {
            if (readerClosed.compareAndSet(false, true)) {
                lock.withLock {
                    notFull.signalAll() // Wake up blocked writers
                }
            }
        }
    }

    /**
     * InputStream wrapper around RingBuffer.
     */
    private class RingBufferInputStream(
        private val ringBuffer: RingBuffer,
        private val exceptionRef: AtomicReference<Exception?>
    ) : InputStream() {

        override fun read(): Int {
            val result = ringBuffer.read()
            if (result == -1) {
                checkException()
            }
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = ringBuffer.read(b, off, len)
            if (result == -1) {
                checkException()
            }
            return result
        }

        override fun available(): Int {
            return ringBuffer.availableToRead()
        }

        override fun close() {
            ringBuffer.closeReader()
        }

        private fun checkException() {
            exceptionRef.get()?.let {
                throw IOException("Producer thread failed", it)
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 65536 // 64KB ring buffer

        private val sharedVirtualThreadExecutor: ExecutorService by lazy {
            // Virtual threads are always daemon threads.
            Executors.newVirtualThreadPerTaskExecutor()
        }

        private val sharedThreadPoolExecutor: ExecutorService by lazy {
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable).apply {
                    isDaemon = true
                    name = "ring-buffer-stream-worker-${hashCode()}"
                }
            }
        }
    }
}