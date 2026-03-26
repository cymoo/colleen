import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.RequestLogger

/**
 * Middleware Showcase
 *
 * Demonstrates all general-purpose built-in middleware provided by Colleen.
 * Each middleware is demonstrated in its own file as a mounted sub-application.
 *
 * Run:
 *   mvn -pl examples/middleware-showcase exec:java
 *
 * Endpoints:
 *   GET  /                                   - Index: lists all demo endpoints
 *   --- SecurityHeaders ---
 *   GET  /security-headers/                  - HTTP security response headers
 *   --- BasicAuth ---
 *   GET  /basic-auth/public                  - No auth required
 *   GET  /basic-auth/private                 - Credentials map (admin/secret or user/pass)
 *   GET  /basic-auth/admin                   - Custom authenticate fn (superadmin/topsecret)
 *   --- RequestId ---
 *   GET  /request-id/                        - Auto-generated UUID tracing ID
 *   GET  /request-id/custom                  - Custom header name + sequential generator
 *   --- HeartBeat ---
 *   GET  /heartbeat/health                   - Health check → "ok"
 *   GET  /heartbeat/ping                     - Health check → "pong"
 *   --- NoCache ---
 *   GET  /no-cache/                          - Cache prevention headers
 *   GET  /no-cache/data                      - Fresh response on every request
 *   --- Sunset ---
 *   GET  /sunset/v1/articles                 - Deprecated API with Sunset + Deprecation headers
 *   GET  /sunset/v2/articles                 - Current API (no deprecation)
 *   --- Cors ---
 *   GET  /cors/permissive/               - Wildcard origin (public API)
 *   GET  /cors/trusted/                  - Single origin with credentials
 *   GET  /cors/custom/                   - Multiple origins with custom config
 *   --- RateLimiter ---
 *   GET  /rate-limit/default                 - 100 req capacity, 10/sec refill
 *   GET  /rate-limit/strict                  - 10 req capacity, 1/sec refill (easy to trigger)
 *   GET  /rate-limit/custom-response         - Custom JSON error on 429
 *   GET  /rate-limit/by-key                  - Rate limit by X-API-Key header
 *   --- ServeStatic ---
 *   GET  /serve-static/files/hello.txt       - Plain text file
 *   GET  /serve-static/files/data.json       - JSON file
 *   GET  /serve-static/assets/style.css      - CSS with Cache-Control: max-age=86400
 *   GET  /serve-static/pages/about           - HTML with extension auto-appended
 *   --- SignedCookie ---
 *   GET  /signed-cookie/login?username=alice - Set a signed session cookie
 *   GET  /signed-cookie/profile              - Read and verify the signed cookie
 *   GET  /signed-cookie/preferences          - Set multiple signed cookies
 *   GET  /signed-cookie/logout               - Delete the session cookie
 */
fun main() {
    val app = Colleen()

    app.use(RequestLogger())

    app.mount("/security-headers", securityHeadersApp())
    app.mount("/basic-auth", basicAuthApp())
    app.mount("/request-id", requestIdApp())
    app.mount("/heartbeat", heartbeatApp())
    app.mount("/no-cache", noCacheApp())
    app.mount("/sunset", sunsetApp())
    app.mount("/cors", corsApp())
    app.mount("/rate-limit", rateLimiterApp())
    app.mount("/serve-static", serveStaticApp(app))
    app.mount("/signed-cookie", signedCookieApp())

    app.get("/") {
        mapOf(
            "description" to "Colleen Middleware Showcase",
            "middleware" to listOf(
                "SecurityHeaders", "BasicAuth", "RequestId", "HeartBeat",
                "NoCache", "Sunset", "Cors", "RateLimiter", "ServeStatic", "SignedCookie"
            ),
            "hint" to "GET / on each sub-path for details, e.g. http://localhost:8085/cors/"
        )
    }

    app.listen(8085)
    println("✅ Middleware Showcase running on http://localhost:8085")
    println()
    println("Quick-start commands:")
    println("  curl -i http://localhost:8085/security-headers/")
    println("  curl -i -u admin:secret http://localhost:8085/basic-auth/private")
    println("  curl -i http://localhost:8085/request-id/")
    println("  curl -i http://localhost:8085/heartbeat/health")
    println("  curl -i http://localhost:8085/no-cache/data")
    println("  curl -i http://localhost:8085/sunset/v1/articles")
    println("  curl -i -H 'Origin: https://example.com' http://localhost:8085/cors/permissive")
    println("  for i in {1..15}; do curl -s -o /dev/null -w \"%{http_code}\\n\" http://localhost:8085/rate-limit/strict; done")
    println("  curl -i http://localhost:8085/serve-static/files/hello.txt")
    println("  curl -c /tmp/c.txt 'http://localhost:8085/signed-cookie/login?username=alice' && curl -b /tmp/c.txt http://localhost:8085/signed-cookie/profile")
}
