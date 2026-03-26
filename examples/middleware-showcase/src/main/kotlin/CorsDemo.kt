import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.Cors

/**
 * CORS Demo
 *
 * Demonstrates the CORS (Cross-Origin Resource Sharing) middleware which enables
 * browsers to make cross-origin requests. It handles:
 *
 * - Simple requests: adds Access-Control-Allow-Origin (and related headers) to responses
 * - Preflight requests: responds to OPTIONS requests without invoking route handlers
 *
 * Three common configurations are shown:
 * 1. Permissive  — wildcard origin, no credentials, all methods (for public APIs)
 * 2. Specific origin — single trusted origin with credentials enabled (for web apps)
 * 3. Custom — full control over every CORS parameter
 *
 * Try (simulate a cross-origin request by sending an Origin header):
 *   curl -i -H 'Origin: https://example.com' http://localhost:8085/cors/permissive/
 *   curl -i -X OPTIONS \
 *     -H 'Origin: https://app.example.com' \
 *     -H 'Access-Control-Request-Method: POST' \
 *     http://localhost:8085/cors/trusted/
 *   curl -i -H 'Origin: https://app.example.com' http://localhost:8085/cors/trusted/
 */
fun corsApp(): Colleen {
    val app = Colleen()

    // 1. Permissive: allow all origins (suitable for fully public APIs)
    //    Cannot be combined with allowCredentials = true
    val permissive = Colleen()
    permissive.use(Cors.permissive())
    permissive.get("/") {
        mapOf(
            "message" to "Open to all origins.",
            "hint" to "Access-Control-Allow-Origin: * is set on this response."
        )
    }

    // 2. Specific origin with credentials: cookies and Authorization headers are forwarded
    //    Wildcard origin is not allowed when credentials are enabled
    val trusted = Colleen()
    trusted.use(Cors.forOrigin("https://app.example.com", allowCredentials = true))
    trusted.get("/") {
        mapOf(
            "message" to "Only https://app.example.com is allowed.",
            "hint" to "Access-Control-Allow-Credentials: true is set."
        )
    }
    trusted.post("/") {
        mapOf("message" to "POST also works — try a preflight OPTIONS first.")
    }

    // 3. Custom: full control over every parameter
    val custom = Colleen()
    custom.use(
        Cors(
            allowOrigins = setOf("https://app1.example.com", "https://app2.example.com"),
            allowMethods = "GET,POST,DELETE",
            allowHeaders = "Content-Type,Authorization,X-Custom-Header",
            exposeHeaders = "X-Request-ID,X-Total-Count",
            allowCredentials = true,
            maxAge = 3600  // Preflight cached for 1 hour
        )
    )
    custom.get("/") {
        mapOf(
            "message" to "Multiple trusted origins with custom method/header lists.",
            "allowedOrigins" to listOf("https://app1.example.com", "https://app2.example.com")
        )
    }

    // Each CORS config is isolated in its own sub-app to avoid middleware interference
    app.mount("/permissive", permissive)
    app.mount("/trusted", trusted)
    app.mount("/custom", custom)

    return app
}
