package io.github.cymoo.colleen.server.undertow

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executor that processes tasks sequentially in submission order.
 *
 * Tasks are dispatched to a shared delegate executor, but execution is serialized:
 * at most one task runs at a time for a given [OrderedExecutor] instance.
 * This ensures per-connection message ordering while offloading work from IO threads.
 */
internal class OrderedExecutor(private val delegate: Executor) : Executor {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val running = AtomicBoolean(false)

    override fun execute(task: Runnable) {
        queue.add(task)
        tryDrain()
    }

    private fun tryDrain() {
        if (running.compareAndSet(false, true)) {
            try {
                delegate.execute {
                    try {
                        while (true) {
                            val task = queue.poll() ?: break
                            try {
                                task.run()
                            } catch (_: Exception) {
                                // Task-level errors are handled by the task itself
                                // (WsConnection.dispatchMessage uses runCatching internally)
                            }
                        }
                    } finally {
                        running.set(false)
                    }
                    // Re-check after releasing the lock: compete fairly with other threads
                    // rather than recursing, which avoids potential stack overflow and
                    // eliminates any window between set(false) and the emptiness check.
                    if (queue.isNotEmpty() && running.compareAndSet(false, true)) {
                        delegate.execute(this::drain)
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Expected during shutdown — executor has been shut down.
                // Release lock so pending tasks can be drained if executor resumes.
                running.set(false)
            }
        }
    }

    private fun drain() {
        try {
            while (true) {
                val task = queue.poll() ?: break
                try {
                    task.run()
                } catch (_: Exception) {
                }
            }
        } finally {
            running.set(false)
        }
        if (queue.isNotEmpty() && running.compareAndSet(false, true)) {
            delegate.execute(this::drain)
        }
    }
}
