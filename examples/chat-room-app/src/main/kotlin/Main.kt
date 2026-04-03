import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import config.DatabaseConfig
import controller.ApiController
import controller.ChatController
import controller.FileController
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.Cors
import io.github.cymoo.colleen.middleware.RequestLogger
import io.github.cymoo.colleen.middleware.ServeStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import service.ChatService
import service.FileService
import service.RoomService
import service.UserService

val logger: Logger = LoggerFactory.getLogger("App")

fun main() {
    val app = Colleen()

    app.config {
        // Configure server
        server {
            maxRequestSize = 30 * 1024 * 1024  // 30MB for file uploads
        }
        // Configure WebSocket
        ws {
            idleTimeoutMs = 600_000            // 10 minutes
            maxMessageSizeBytes = 256 * 1024   // 256 KB
            pingIntervalMs = 30_000            // 30 seconds
            pingTimeoutMs = 10_000             // 10 seconds
            maxConnections = 500               // Max 500 connections
        }
    }

    // Setup database
    val projectDir: String = System.getProperty("user.dir")
    val dbUrl = "jdbc:sqlite:${projectDir}/examples/chat-room-app/chat.db"
    val dataSource = DatabaseConfig.createDataSource(dbUrl)
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

    // Middleware
    app.use(RequestLogger())
    app.use(Cors.permissive())
    app.use(ServeStatic("classpath:static"))

    // Controllers
    app.addController(ApiController(roomService, fileService, userService))
    app.addController(FileController(fileService))
    app.addController(ChatController(userService, chatService, roomService, objectMapper))

    app.get("/") { ctx ->
        ctx.sendFile("static/index.html", classpathOnly = true)
    }

    app.listen(8000)
    logger.info("✅ Chat Room Server running on http://localhost:8000")
}
