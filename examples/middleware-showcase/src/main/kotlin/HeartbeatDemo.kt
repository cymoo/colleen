import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.HeartBeat

/**
 * HeartBeat Demo
 *
 * Demonstrates the HeartBeat middleware which creates a lightweight health check
 * endpoint. The endpoint handles GET and HEAD requests at the specified path and
 * short-circuits the middleware chain (no downstream handlers are invoked).
 *
 * Useful for:
 * - Load balancer health checks
 * - Container liveness/readiness probes (Kubernetes, Docker Compose)
 * - Uptime monitoring
 *
 * The response includes Cache-Control: no-cache to prevent stale health responses.
 *
 * Try:
 *   curl -i http://localhost:8085/heartbeat/health
 *   curl -i http://localhost:8085/heartbeat/ping
 *   curl -i -X HEAD http://localhost:8085/heartbeat/health
 */
fun heartbeatApp(): Colleen {
    val app = Colleen()

    // Standard health check endpoint: responds with "ok"
    app.use(HeartBeat("/health"))

    // Custom response text: useful for distinguishing multiple services or environments
    app.use(HeartBeat("/ping", "pong"))

    app.get("/") {
        mapOf(
            "message" to "Two health check endpoints are registered on this sub-app.",
            "endpoints" to mapOf(
                "/heartbeat/health" to "GET → 200 ok",
                "/heartbeat/ping" to "GET → 200 pong"
            ),
            "note" to "HeartBeat handles GET and HEAD requests and bypasses all other middleware."
        )
    }

    return app
}
