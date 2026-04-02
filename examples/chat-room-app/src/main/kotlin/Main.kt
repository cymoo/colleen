import config.DatabaseConfig
import controller.*
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.Cors
import io.github.cymoo.colleen.middleware.RequestLogger
import middleware.WsAuthMiddleware
import service.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

fun main() {
    val app = Colleen()

    // Configure server
    app.config.server {
        maxRequestSize = 30 * 1024 * 1024  // 30MB for file uploads
    }

    // Configure WebSocket
    app.config {
        ws {
            idleTimeoutMs = 600_000          // 10 minutes
            maxMessageSizeBytes = 256 * 1024 // 256 KB
            pingIntervalMs = 30_000          // 30 seconds
            pingTimeoutMs = 10_000           // 10 seconds
            maxConnections = 500             // Max 500 connections
        }
    }

    // Setup database
    val dataSource = DatabaseConfig.createDataSource()
    DatabaseConfig.runMigrations(dataSource)
    val dsl = DatabaseConfig.createDSLContext(dataSource)

    // Create ObjectMapper
    val objectMapper = jacksonObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    // Create services
    val userService = UserService(dsl)
    val roomService = RoomService(dsl)
    val chatService = ChatService(dsl, objectMapper)
    val fileService = FileService("uploads")

    // Register services
    app.provide(userService)
    app.provide(roomService)
    app.provide(chatService)
    app.provide(fileService)
    app.provide(objectMapper)

    // Middleware
    app.use(RequestLogger())
    app.use(Cors.permissive())

    // WebSocket authentication middleware
    app.wsUse("/chat", WsAuthMiddleware(userService))

    // Controllers
    app.addController(ApiController(roomService, fileService))
    app.addController(FileController(fileService))
    app.addController(ChatController(chatService, roomService, objectMapper))

    // Serve static frontend
    app.get("/") { ctx ->
        ctx.html(loadFrontendHtml())
    }

    // Serve CSS
    app.get("/static/style.css") { ctx ->
        ctx.header("Content-Type", "text/css")
        ctx.text(loadCss())
    }

    // Serve JavaScript
    app.get("/static/chat.js") { ctx ->
        ctx.header("Content-Type", "application/javascript")
        ctx.text(loadJavaScript())
    }

    app.listen(8000)
    println("✅ Chat Room Server running on http://localhost:8000")
    println("📊 Database migrations applied")
    println("🚀 WebSocket server ready")
}

private fun loadFrontendHtml(): String {
    return Thread.currentThread().contextClassLoader
        .getResourceAsStream("static/index.html")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: "<h1>Error: Frontend not found</h1>"
}

private fun loadCss(): String {
    return Thread.currentThread().contextClassLoader
        .getResourceAsStream("static/css/style.css")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: "/* CSS not found */"
}

private fun loadJavaScript(): String {
    return Thread.currentThread().contextClassLoader
        .getResourceAsStream("static/js/chat.js")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: "console.error('JavaScript not found');"
}
