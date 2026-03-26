import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.NoCache

/**
 * NoCache Demo
 *
 * Demonstrates the NoCache middleware which prevents browsers and proxies from
 * caching responses. It sets the following headers on every response:
 *
 * - Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate
 * - Pragma: no-cache  (HTTP/1.0 compatibility)
 * - Expires: Thu, 01 Jan 1970 00:00:00 GMT  (immediately expired)
 * - X-Accel-Expires: 0  (disables Nginx proxy caching)
 *
 * Useful for:
 * - Dynamic API responses that must always be fresh
 * - Authenticated endpoints where stale data is a security concern
 * - Admin dashboards and real-time data feeds
 *
 * Try:
 *   curl -i http://localhost:8085/no-cache/
 *   curl -i http://localhost:8085/no-cache/data
 */
fun noCacheApp(): Colleen {
    val app = Colleen()

    app.use(NoCache())

    app.get("/") {
        mapOf(
            "message" to "This response will never be cached.",
            "hint" to "Check Cache-Control, Pragma, and Expires in the response headers."
        )
    }

    // A typical use case: real-time data that must be fetched fresh on every request
    app.get("/data") {
        mapOf(
            "timestamp" to System.currentTimeMillis(),
            "random" to (Math.random() * 1000).toInt(),
            "note" to "Every request returns a fresh response due to NoCache middleware."
        )
    }

    return app
}
