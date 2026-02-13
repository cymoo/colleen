package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.HttpException
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * HTTP request logging middleware
 * Logs each request with method, path, status code, and response time in Flask-like format
 */
class RequestLogger @JvmOverloads constructor(
    private val skip: (Context) -> Boolean = { false }
) : Middleware {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    override fun invoke(ctx: Context, next: Next) {
        if (skip(ctx)) {
            next()
            return
        }

        val startTime = System.nanoTime()

        next()

        var status = ctx.response.status
        if (ctx.error != null) {
            status = (ctx.error?.cause as? HttpException)?.status ?: 500
        }

        // Use nanoTime for accurate duration measurement
        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        val message = formatLog(ctx, status, durationMs)

        // Log based on response status code
        when {
            status >= 500 -> logger.error(message)
            status >= 400 -> logger.warn(message)
            else -> logger.info(message)
        }
    }

    /**
     * Format log message in Flask default style:
     * 127.0.0.1 - - [2025-12-07 15:30:45] "GET /api/users" 200 - 2ms
     */
    private fun formatLog(ctx: Context, status: Int, durationMs: Long): String {
        val ip = ctx.request.ip
        val timestamp = LocalDateTime.now().format(DATE_FORMATTER)
        val method = ctx.method
        val path = ctx.path

        return """$ip - - [$timestamp] "$method $path" $status - ${durationMs}ms"""
    }
}