import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.HttpException
import io.github.cymoo.colleen.middleware.RequestLogger

/**
 * Sub-Application Mounting Example
 *
 * Demonstrates:
 * - Mounting sub-applications
 * - Modular application architecture
 * - Independent middleware and error handling
 * - Service and state isolation and inheritance
 * - Path rewriting for mounted apps
 */

// ============================================================================
// Services
// ============================================================================

class Database {
    private val data = mutableMapOf<String, String>()
    fun set(key: String, value: String) {
        data[key] = value
    }

    fun get(key: String): String? = data[key]
}

class AdminLogger {
    fun log(message: String) = println("[Admin] $message")
}

// ============================================================================
// Admin Sub-Application
// ============================================================================

fun createAdminApp(): Colleen {
    val app = Colleen()

    // Service isolated to this sub-app
    app.provide(AdminLogger())

    // Sub-app specific middleware
    app.use { ctx, next ->
        val token = ctx.header("Authorization")
        if (token != "Bearer admin-secret") {
            throw HttpException(401, "Unauthorized")
        }
        next()
    }

    // Access both parent service and sub-app service
    app.get("/status") { ctx ->
        val db = ctx.getService<Database>()  // Inherited from parent
        val logger = ctx.getService<AdminLogger>()  // Sub-app only
        val username = ctx.getState<String>("username")  // Parent state

        logger.log("Status checked")

        mapOf(
            "module" to "admin",
            "username" to username,
            "version" to db.get("version")
        )
    }

    // Sub-app error handler only takes effects when `propagateExceptions = false`
    app.config.propagateExceptions = false

    app.onError<HttpException> { e, ctx ->
        ctx.status(e.status).json(
            mapOf(
                "module" to "admin",
                "error" to e.message
            )
        )
    }

    return app
}

// ============================================================================
// API Sub-Application
// ============================================================================

fun createApiApp(): Colleen {
    val app = Colleen()

    // API-specific middleware
    app.use { ctx, next ->
        ctx.header("X-API-Version", "1.0")
        next()
    }

    app.get("/info") { ctx ->
        val db = ctx.getService<Database>()  // Access parent service
        val username = ctx.getState<String>("username")  // Access parent state

        mapOf(
            "module" to "api",
            "username" to username,
            "version" to db.get("version")
        )
    }

    return app
}

// ============================================================================
// Main Application
// ============================================================================

fun main() {
    val app = Colleen()

    // Global service (accessible by sub-apps)
    val db = Database()
    db.set("version", "1.0.0")
    app.provide(db)

    // Global middleware
    app.use(RequestLogger())

    app.use { ctx, next ->
        val userName = ctx.query("username") ?: "guest"
        ctx.setState("username", userName)
        next()
    }

    // Mount sub-applications
    app.mount("/admin", createAdminApp())
    app.mount("/api", createApiApp())

    // Root endpoint
    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Sub-App Example</title>
                <style>
                    body { font-family: Arial; max-width: 700px; margin: 50px auto; }
                    .endpoint { 
                        background: #f5f5f5; 
                        padding: 15px; 
                        margin: 10px 0; 
                        border-radius: 5px; 
                    }
                    button { 
                        background: #007bff; 
                        color: white; 
                        border: none; 
                        padding: 8px 15px; 
                        cursor: pointer; 
                        border-radius: 3px; 
                    }
                    .result { 
                        background: #f8f9fa; 
                        padding: 15px; 
                        margin: 10px 0;
                        white-space: pre; 
                        font-family: monospace; 
                    }
                </style>
            </head>
            <body>
                <h1>Sub-Application Mounting</h1>
                
                <h2>Admin Module (requires auth)</h2>
                <div class="endpoint">
                    <code>GET /admin/status</code>
                    <p>Demonstrates: service inheritance + isolation + parent state access</p>
                    <button onclick="test('/admin/status', true)">Test with auth</button>
                    <button onclick="test('/admin/status', false)">Test without auth</button>
                </div>
                
                <h2>API Module</h2>
                <div class="endpoint">
                    <code>GET /api/info</code>
                    <p>Demonstrates: independent middleware + parent service/state access</p>
                    <button onclick="test('/api/info', false)">Test</button>
                </div>
                
                <h2>Result</h2>
                <div id="result" class="result">Click a button above</div>
                
                <script>
                    async function test(url, withAuth) {
                        const result = document.getElementById('result');
                        try {
                            const headers = {};
                            if (withAuth) headers['Authorization'] = 'Bearer admin-secret';
                            
                            const res = await fetch(url, { headers });
                            const data = await res.json();
                            
                            result.textContent = JSON.stringify({
                                status: res.status,
                                body: data
                            }, null, 2);
                        } catch (err) {
                            result.textContent = 'Error: ' + err.message;
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
        )
    }

    // Global error handler
    app.onError<Exception> { err, ctx ->
        val (status, message) = when (err) {
            is HttpException -> err.status to err.message
            else -> 500 to "Internal Server Error"
        }
        ctx.status(status).json(mapOf("error" to message))
    }

    app.listen(8000)
    println("✅ Server running on http://localhost:8000")
    println("📁 Admin: http://localhost:8000/admin/status")
    println("📁 API: http://localhost:8000/api/info")
}