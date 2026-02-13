package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * NoCache middleware
 * Sets HTTP headers to prevent response caching
 */
class NoCache : Middleware {

    companion object {
        private val EPOCH = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.EPOCH.atZone(ZoneId.of("GMT"))
        )

        private val NO_CACHE_HEADERS = mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate, private, max-age=0",
            "Pragma" to "no-cache",
            "Expires" to EPOCH,
            "X-Accel-Expires" to "0"
        )
    }

    override fun invoke(ctx: Context, next: Next) {
        next()
        // Set no-cache response headers
        NO_CACHE_HEADERS.forEach { (key, value) ->
            ctx.response.header(key, value)
        }
    }
}