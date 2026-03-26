import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.ServeStatic
import java.io.File
import java.nio.file.Files as NioFiles

/**
 * ServeStatic Demo
 *
 * Demonstrates the ServeStatic middleware for serving files from the filesystem.
 * Sample files are created in temporary directories at startup and cleaned up
 * on server shutdown.
 *
 * Features:
 * - Basic filesystem file serving
 * - Cache-Control headers (maxAge)
 * - Extension auto-matching (access /about instead of /about.html)
 * - Security: path traversal is prevented automatically
 *
 * Try:
 *   curl -i http://localhost:8085/serve-static/files/hello.txt
 *   curl -i http://localhost:8085/serve-static/files/data.json
 *   curl -I http://localhost:8085/serve-static/assets/style.css   # Cache-Control header
 *   curl -i http://localhost:8085/serve-static/pages/about         # .html auto-appended
 *   curl -i http://localhost:8085/serve-static/files/../etc/passwd # path traversal → 404
 */
fun serveStaticApp(parentApp: Colleen): Colleen {
    val app = Colleen()

    val (publicDir, assetsDir, pagesDir) = createSampleFiles()

    // Clean up temp directories when the server shuts down
    parentApp.onShutdown {
        listOf(publicDir, assetsDir, pagesDir).forEach { it.deleteRecursively() }
        println("🧹 Cleaned up temporary static file directories.")
    }

    // Basic static file serving — no caching, mapped to /files
    app.use(ServeStatic(root = publicDir.absolutePath, baseUrl = "/files"))

    // Cached assets — 1-day Cache-Control: public, max-age=86400
    app.use(ServeStatic(root = assetsDir.absolutePath, baseUrl = "/assets", maxAge = 86400))

    // Extension auto-matching: /about resolves to about.html
    app.use(ServeStatic(root = pagesDir.absolutePath, baseUrl = "/pages", extensions = listOf(".html")))

    app.get("/") {
        mapOf(
            "message" to "Static files are being served from temporary directories.",
            "endpoints" to mapOf(
                "/serve-static/files/hello.txt" to "plain text file",
                "/serve-static/files/data.json" to "JSON file",
                "/serve-static/assets/style.css" to "CSS with Cache-Control: max-age=86400",
                "/serve-static/pages/about" to "HTML page (extension auto-appended)"
            )
        )
    }

    return app
}

private data class StaticDirs(val publicDir: File, val assetsDir: File, val pagesDir: File)

private fun createSampleFiles(): StaticDirs {
    val publicDir = NioFiles.createTempDirectory("colleen-static-public").toFile()
    val assetsDir = NioFiles.createTempDirectory("colleen-static-assets").toFile()
    val pagesDir = NioFiles.createTempDirectory("colleen-static-pages").toFile()

    File(publicDir, "hello.txt").writeText("Hello from a static file!")
    File(publicDir, "data.json").writeText("""{"message": "static JSON", "framework": "Colleen"}""")
    File(publicDir, "index.html").writeText(
        """<!DOCTYPE html><html><body><h1>Static Directory Index</h1></body></html>"""
    )

    File(assetsDir, "style.css").writeText(
        """body { font-family: Arial, sans-serif; margin: 40px; } h1 { color: #333; }"""
    )
    File(assetsDir, "app.js").writeText("""console.log("Hello from static JS");""")

    File(pagesDir, "about.html").writeText(
        """<!DOCTYPE html><html><body><h1>About</h1><p>Accessed via /pages/about (no .html needed)</p></body></html>"""
    )

    return StaticDirs(publicDir, assetsDir, pagesDir)
}
