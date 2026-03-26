import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.Sunset
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Sunset Demo
 *
 * Demonstrates the Sunset middleware which signals API deprecation via HTTP headers
 * as defined in RFC 8594 (https://www.rfc-editor.org/rfc/rfc8594.html).
 *
 * Headers added to deprecated endpoints:
 * - Sunset: <date>         - When the API will be shut down (required)
 * - Deprecation: <date>    - When the API was marked deprecated (optional)
 * - Link: <url>; rel="successor-version"  - Pointer to the replacement (optional)
 *
 * This allows API clients and monitoring tools to proactively detect and react
 * to upcoming API removals without relying on out-of-band communication.
 *
 * Try:
 *   # Deprecated v1 API — note the Sunset, Deprecation and Link headers:
 *   curl -i http://localhost:8085/sunset/v1/articles
 *
 *   # Current v2 API — no deprecation headers:
 *   curl -i http://localhost:8085/sunset/v2/articles
 */
fun sunsetApp(): Colleen {
    val app = Colleen()

    // v1 API: deprecated, will be shut down in 180 days
    val v1 = Colleen()
    val sunsetAt = Instant.now().plus(180, ChronoUnit.DAYS)
    val deprecatedAt = Instant.now().minus(30, ChronoUnit.DAYS)  // deprecated 30 days ago

    v1.use(
        Sunset(
            sunsetAt = sunsetAt,
            deprecatedAt = deprecatedAt,
            links = listOf("""<http://localhost:8085/sunset/v2/articles>; rel="successor-version"""")
        )
    )

    v1.get("/articles") {
        mapOf(
            "version" to "v1",
            "data" to listOf(
                mapOf("id" to 1, "title" to "Getting Started"),
                mapOf("id" to 2, "title" to "Advanced Usage")
            ),
            "warning" to "This API version is deprecated. Please migrate to v2."
        )
    }

    // v2 API: current version, no sunset headers
    val v2 = Colleen()

    v2.get("/articles") {
        mapOf(
            "version" to "v2",
            "data" to listOf(
                mapOf("id" to 1, "title" to "Getting Started", "author" to "Alice", "tags" to listOf("intro")),
                mapOf("id" to 2, "title" to "Advanced Usage", "author" to "Bob", "tags" to listOf("advanced"))
            )
        )
    }

    app.mount("/v1", v1)
    app.mount("/v2", v2)

    return app
}
