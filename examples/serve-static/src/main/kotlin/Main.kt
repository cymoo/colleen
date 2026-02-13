import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.RequestLogger
import io.github.cymoo.colleen.middleware.ServeStatic
import java.io.File

/**
 * ServeStatic Middleware Example
 *
 * Features demonstrated:
 * - Static file serving
 * - Cache control
 * - Auto index file
 * - Extension auto-matching
 * - Security path checking
 */

fun main() {
    val app = Colleen()

    // Setup static files for demo
    val tempDirs = setupStaticFiles()

    // Cleanup temp files on shutdown
    app.onShutdown {
        tempDirs.forEach { dir ->
            dir.deleteRecursively()
            println("🧹 Cleaned up: ${dir.path}")
        }
    }

    app.use(RequestLogger())

    // Basic static file serving
    app.use(
        ServeStatic(
            root = "./public",
            baseUrl = "/static"
        )
    )

    // Static assets with cache (CSS, JS, images)
    app.use(
        ServeStatic(
            root = "./assets",
            baseUrl = "/assets",
            maxAge = 86400  // Cache for 1 day
        )
    )

    // Auto extension matching (access without extension)
    app.use(
        ServeStatic(
            root = "./pages",
            baseUrl = "/pages",
            extensions = listOf(".html", ".htm")
        )
    )

    // Home page
    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Static File Server</title>
                <link rel="stylesheet" href="/assets/style.css">
            </head>
            <body>
                <h1>📁 Static File Server Example</h1>
                <h2>Available Resources</h2>
                <ul>
                    <li><a href="/static/hello.txt">/static/hello.txt</a> - Text file</li>
                    <li><a href="/static/data.json">/static/data.json</a> - JSON data</li>
                    <li><a href="/static/">/static/</a> - Directory index (index.html)</li>
                    <li><a href="/assets/style.css">/assets/style.css</a> - CSS (with cache)</li>
                    <li><a href="/pages/about">/pages/about</a> - Auto add .html extension</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ Static File Server running on http://localhost:8000")
}

/**
 * Create example static files for demonstration
 * Returns list of created directories for cleanup
 */
fun setupStaticFiles(): List<File> {
    val dirs = mutableListOf<File>()

    // public directory
    File("./public").apply {
        mkdirs()
        dirs.add(this)
    }
    File("./public/hello.txt").writeText("Hello from static file!")
    File("./public/data.json").writeText("""{"message": "JSON data"}""")
    File("./public/index.html").writeText(
        """
        <!DOCTYPE html>
        <html>
        <head><title>Index</title></head>
        <body><h1>Static Directory Index</h1></body>
        </html>
    """.trimIndent()
    )

    // assets directory
    File("./assets").apply {
        mkdirs()
        dirs.add(this)
    }
    File("./assets/style.css").writeText(
        """
        body { font-family: Arial, sans-serif; margin: 40px; }
        h1 { color: #333; }
        a { color: #0066cc; text-decoration: none; }
        a:hover { text-decoration: underline; }
    """.trimIndent()
    )

    // pages directory
    File("./pages").apply {
        mkdirs()
        dirs.add(this)
    }
    File("./pages/about.html").writeText(
        """
        <!DOCTYPE html>
        <html>
        <head><title>About</title></head>
        <body><h1>About Page</h1><p>Accessed without .html extension</p></body>
        </html>
    """.trimIndent()
    )

    return dirs
}