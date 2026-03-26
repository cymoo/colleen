import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.RequestId
import io.github.cymoo.colleen.middleware.getRequestId
import java.util.concurrent.atomic.AtomicLong

/**
 * RequestId Demo
 *
 * Demonstrates the RequestId middleware which assigns a unique ID to each request.
 * This enables distributed tracing, log correlation, and debugging.
 *
 * Behavior:
 * - If the incoming request already has an X-Request-ID header, that value is reused.
 * - Otherwise, a new UUID is generated automatically.
 * - The ID is added to the response header (X-Request-ID by default).
 * - The ID is stored in the context and accessible via ctx.getRequestId().
 *
 * Try:
 *   # Server generates an ID:
 *   curl -i http://localhost:8085/request-id/
 *
 *   # Client provides its own ID (e.g. for end-to-end tracing):
 *   curl -i -H 'X-Request-ID: my-trace-abc123' http://localhost:8085/request-id/
 *
 *   # Custom header name (X-Trace-ID):
 *   curl -i http://localhost:8085/request-id/custom
 *   curl -i -H 'X-Trace-ID: span-xyz-789' http://localhost:8085/request-id/custom
 */
fun requestIdApp(): Colleen {
    val app = Colleen()

    // Default: reads/writes X-Request-ID header, generates UUID if absent
    app.use(RequestId())

    app.get("/") { ctx ->
        mapOf(
            "requestId" to ctx.getRequestId(),
            "message" to "A unique ID has been assigned to this request.",
            "hint" to "Check the X-Request-ID response header. Pass your own with -H 'X-Request-ID: ...' to reuse it."
        )
    }

    // Custom header name and sequential generator (useful for internal services)
    val counter = AtomicLong(0)
    val customRequestIdApp = Colleen()
    customRequestIdApp.use(
        RequestId(
            headerName = "X-Trace-ID",
            generator = { "req-${counter.incrementAndGet()}" },
            setResponseHeader = true
        )
    )
    customRequestIdApp.get("/custom") { ctx ->
        mapOf(
            "requestId" to ctx.getRequestId(),
            "message" to "This endpoint uses a custom header name (X-Trace-ID) and a sequential ID generator.",
            "hint" to "Check the X-Trace-ID response header."
        )
    }

    app.mount("/", customRequestIdApp)

    return app
}
