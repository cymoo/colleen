package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next

/**
 * HeartBeat middleware
 * Health check endpoint that returns a simple success response
 *
 * @param endpoint Health check path
 * @param responseText Response content, defaults to "ok"
 */
class HeartBeat @JvmOverloads constructor(
    private val endpoint: String,
    private val responseText: String = "ok"
) : Middleware {

    override fun invoke(ctx: Context, next: Next) {
        val method = ctx.method.uppercase()

        if ((method == "GET" || method == "HEAD") && ctx.path == endpoint) {
            ctx.status(200)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Content-Type", "text/plain; charset=utf-8")
            if (method == "GET") {
                ctx.text(responseText)
            }
            return
        }

        next()
    }
}