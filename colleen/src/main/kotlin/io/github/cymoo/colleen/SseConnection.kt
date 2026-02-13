package io.github.cymoo.colleen

import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.concurrent.withLock

data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
    val retry: Long? = null
)

sealed class SseCloseReason {
    /** Connection closed normally */
    object Normal : SseCloseReason()

    /** Client disconnected (IO error, broken pipe, etc.) */
    object ClientDisconnected : SseCloseReason()

    /** Connection closed due to server-side error */
    data class Error(val cause: Throwable) : SseCloseReason()
}

/**
 * Blocking text writer for Server-Sent Events.
 *
 * Implementations are not required to be thread-safe.
 * All concurrency control is handled by SseConnection.
 */
interface SseWriter : AutoCloseable {
    @Throws(IOException::class)
    fun write(text: String)

    @Throws(IOException::class)
    fun flush()
}

/**
 * Server-Sent Events (SSE) connection.
 *
 * Threading model:
 * - send(...) and comment(...) are serialized internally and may be called from multiple threads.
 * - close() may be called at any time from any thread.
 *
 * I/O model:
 * - Blocking writes with real backpressure.
 * - No internal buffering.
 * - Virtual-thread friendly.
 *
 * Once closed, further write attempts will fail with IOException.
 */
class SseConnection(private val writer: SseWriter) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val closeReason = AtomicReference<SseCloseReason>(SseCloseReason.Normal)
    private val closeCallbacks = ArrayList<Consumer<SseCloseReason>>()
    val isClosed: Boolean get() = closed.get()

    private val lock = ReentrantLock()

    @Volatile
    private var keepAliveTask: ScheduledFuture<*>? = null

    // ========================================================================
    // Send
    // ========================================================================

    @Throws(IOException::class)
    fun send(data: String) = send(SseEvent(data = data))


    /**
     * Sends a Server-Sent Event to the client.
     *
     * The event is written synchronously and flushed before this method returns.
     *
     * @throws IOException if the connection is closed or the write fails
     */
    @Throws(IOException::class)
    fun send(event: SseEvent) {
        lock.withLock {
            try {
                ensureOpen()
                event.id?.let { writer.write("id: $it\n") }
                event.event?.let { writer.write("event: $it\n") }
                event.retry?.let { writer.write("retry: $it\n") }

                event.data.lineSequence().forEach {
                    writer.write("data: $it\n")
                }

                writer.write("\n")
                writer.flush()
            } catch (e: IOException) {
                close(SseCloseReason.ClientDisconnected)
                throw e
            }
        }
    }

    /**
     * Sends an SSE comment.
     *
     * Commonly used as a keep-alive ping to prevent idle connection timeouts.
     *
     * @throws IOException if the connection is closed or the write fails
     */
    @Throws(IOException::class)
    fun comment(comment: String = "") {
        lock.withLock {
            try {
                ensureOpen()
                writer.write(": $comment\n\n")
                writer.flush()
            } catch (e: IOException) {
                close(SseCloseReason.ClientDisconnected)
                throw e
            }
        }
    }

    // ========================================================================
    // Keep Alive
    // ========================================================================

    fun keepAlive(secs: Int) = keepAlive(Duration.ofSeconds(secs.toLong()))

    /**
     * Enables periodic keep-alive comments.
     *
     * Calling this method again replaces the previous keep-alive task.
     */
    fun keepAlive(interval: Duration) {
        if (interval.isZero || interval.isNegative) return

        lock.withLock {
            if (isClosed) return

            keepAliveTask?.cancel(false)

            keepAliveTask = SseKeepAliveScheduler.scheduleAtFixedRate(
                {
                    if (!isClosed) runCatching { comment() }
                },
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
            )
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Registers a callback to be invoked when the connection is closed.
     *
     * The provided reason indicates whether the connection completed normally,
     * was closed by the client, or terminated due to an error.
     */
    fun onClose(callback: Consumer<SseCloseReason>) {
        lock.withLock {
            if (isClosed) {
                runCatching { callback.accept(closeReason.get()) }
            } else {
                closeCallbacks.add(callback)
            }
        }
    }

    override fun close() {
        close(SseCloseReason.Normal)
    }

    fun close(reason: SseCloseReason) {
        if (!closed.compareAndSet(false, true)) return

        // first writer wins
        closeReason.compareAndSet(SseCloseReason.Normal, reason)

        val callbacks: List<Consumer<SseCloseReason>>

        lock.withLock {
            try {
                keepAliveTask?.cancel(false)
                keepAliveTask = null

                runCatching { writer.close() }
            } finally {
                callbacks = ArrayList(closeCallbacks)
                closeCallbacks.clear()
            }
        }

        val finalReason = closeReason.get()
        callbacks.forEach {
            runCatching { it.accept(finalReason) }
        }
    }

    private fun ensureOpen() {
        if (isClosed) {
            throw IOException("SSE connection closed")
        }
    }

    object SseKeepAliveScheduler {
        private val executor = Executors.newScheduledThreadPool(
            2,
            Thread.ofVirtual().name("sse-keepalive-", 0).factory()
        )

        @Volatile
        private var closed = false

        fun scheduleAtFixedRate(command: Runnable, initial: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> {
            check(!closed) { "SseKeepAliveScheduler already shutdown" }
            return executor.scheduleAtFixedRate(command, initial, period, unit)
        }

        fun shutdown(
            timeout: Long = 3,
            unit: TimeUnit = TimeUnit.SECONDS
        ) {
            if (closed) return
            closed = true

            executor.shutdown()

            if (!executor.awaitTermination(timeout, unit)) {
                executor.shutdownNow()
            }
        }
    }
}