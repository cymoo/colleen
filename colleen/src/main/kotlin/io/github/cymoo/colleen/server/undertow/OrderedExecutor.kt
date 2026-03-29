package io.github.cymoo.colleen.server.undertow

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [Executor] that guarantees tasks submitted to it are executed sequentially,
 * in the order they were submitted, even though the actual work is offloaded to
 * a shared [delegate] thread pool.
 *
 * Problem this solves:
 *   A WebSocket server receives messages on IO threads. IO threads must not block,
 *   so message handling is dispatched to a thread pool. But a thread pool may run
 *   multiple tasks concurrently — two messages from the same connection could be
 *   processed out of order, or simultaneously. This class ensures that for one
 *   logical "lane" (e.g. one connection), tasks run one at a time, in order.
 *
 * How it works:
 *   Tasks are enqueued into a lock-free FIFO queue. A single "drain" job is
 *   submitted to the delegate executor to process the queue. At most one drain
 *   job runs at a time, enforced by a CAS lock ([running]). When the drain job
 *   finishes, it re-checks the queue for any tasks that arrived in the narrow
 *   window between the last poll() and the lock release — if any are found, a
 *   new drain job is scheduled.
 */
internal class OrderedExecutor(private val delegate: Executor) : Executor {

    // A thread-safe, lock-free FIFO queue holding tasks waiting to be executed.
    // ConcurrentLinkedQueue.add() and poll() are both O(1) and non-blocking.
    private val queue = ConcurrentLinkedQueue<Runnable>()

    // Acts as a lightweight mutex: true means a drain job is currently running
    // (or has been submitted and is about to run). Only one drain job may be
    // active at any time, which is what serializes task execution.
    private val running = AtomicBoolean(false)

    /**
     * Enqueues [task] and attempts to schedule a drain job if none is running.
     *
     * This method is safe to call from any thread, including IO threads.
     * It returns immediately — the task is not executed inline.
     */
    override fun execute(task: Runnable) {
        queue.add(task)   // always succeeds; ConcurrentLinkedQueue is unbounded
        trySchedule()
    }

    /**
     * Tries to schedule a [drain] job on the delegate executor.
     *
     * Uses a CAS operation to ensure only one drain job is ever in-flight.
     * If another thread already holds the "running" lock (i.e. a drain job is
     * active), this call is a no-op — the active drain will pick up the task
     * we just enqueued.
     *
     * On [RejectedExecutionException] (the delegate executor has been shut down),
     * we release the lock so the system does not deadlock: if the executor ever
     * becomes available again, the next [execute] call can re-acquire the lock
     * and reschedule.
     */
    private fun trySchedule() {
        // CAS: atomically flip false → true.
        // Exactly one thread wins this race; all others return immediately.
        if (!running.compareAndSet(false, true)) return
        try {
            delegate.execute(::drain)
        } catch (_: RejectedExecutionException) {
            // Executor is shut down. Release the lock so we don't permanently
            // block future scheduling attempts if the executor recovers.
            running.set(false)
        }
    }

    /**
     * The core drain loop, executed on a thread from the delegate executor.
     *
     * Repeatedly polls and runs tasks until the queue is empty, then releases
     * the "running" lock. After releasing the lock, it performs one final
     * re-check of the queue to close a subtle TOCTOU race window:
     *
     *   Thread A (drain): sees queue empty → about to exit loop
     *   Thread B (caller): enqueues a task → calls trySchedule() → CAS fails
     *                      (running is still true) → returns, relying on drain
     *                      to pick up the task
     *   Thread A (drain): sets running = false → exits — task is now orphaned!
     *
     * The re-check after set(false) catches this: if the queue is non-empty,
     * we race to re-acquire the lock via trySchedule() and schedule a new drain.
     * Because trySchedule() itself does a CAS, it is safe to call from multiple
     * threads simultaneously — only one will win.
     */
    private fun drain() {
        try {
            while (true) {
                // poll() returns null if the queue is empty (non-blocking).
                val task = queue.poll() ?: break
                try {
                    task.run()
                } catch (_: Exception) {
                    // Swallow task-level exceptions so one bad task cannot
                    // kill the drain loop and starve all subsequent tasks.
                    // Tasks are expected to handle their own errors internally.
                }
            }
        } finally {
            // Always release the lock, even if a task threw an Error (which
            // propagates through finally and will crash the thread, but at
            // least we don't leave the executor permanently locked).
            running.set(false)
        }

        // TOCTOU re-check: a task may have been enqueued between our last
        // poll() returning null and the running.set(false) above.
        // If so, no active drain job exists to process it — schedule one now.
        if (queue.isNotEmpty()) trySchedule()
    }
}