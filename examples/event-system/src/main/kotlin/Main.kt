import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Event
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.Query
import io.github.cymoo.colleen.middleware.Cors
import org.slf4j.LoggerFactory
import kotlin.time.DurationUnit

/**
 * Event System Example
 *
 * Demonstrates:
 * - Application lifecycle events
 * - Route and middleware registration events
 * - Request/response tracing
 * - Execution timing and exception events
 *
 * This example shows how to observe and log internal framework events
 * using Colleen's event listener mechanism.
 */

private val logger = LoggerFactory.getLogger("event-example")

fun main() {
    val app = Colleen()

    setupEventListeners(app)

    app.httpMethodOverride()
    app.use(Cors())
    app.get("/") { "Hello, World!" }
    app.get("/users/{id}", ::getUser)

    val adminApp = Colleen()
    adminApp.get("/") { "Admin Dashboard" }
    adminApp.get("/stats") { mapOf("users" to 150, "posts" to 342) }
    app.mount("/admin", adminApp)

    app.listen()
    logger.info("Server running at http://localhost:8000")
}

fun getUser(id: Path<Int>, followers: Query<Boolean?>) = mapOf(
    "id" to id.value,
    "name" to "User ${id.value}",
    "followers" to (followers.value ?: false)
)

fun setupEventListeners(app: Colleen) {
    // Lifecycle
    app.on<Event.ServerStarting> { logger.info("Server starting...") }
    app.on<Event.ServerStarted> { logger.info("Server started successfully") }
    app.on<Event.ServerStopping> { logger.info("Server stopping...") }
    app.on<Event.ServerStopped> { logger.info("Server stopped") }

    // Request/Response
    app.on<Event.RequestReceived> {
        logger.info("→ ${it.request.method} ${it.request.path}")
    }

    app.on<Event.ResponseReady> {
        logger.info("← ${it.ctx.response.status}")
    }

    app.on<Event.ResponseSent> { event ->
        val ms = event.total.toDouble(DurationUnit.MILLISECONDS)
        val kb = event.bytesSent / 1024.0
        logger.info(
            "✓ ${event.ctx.method} ${event.ctx.fullPath} " +
                    "[${String.format("%.2fms", ms)}, ${String.format("%.2fKB", kb)}]"
        )
    }

    // Execution
    app.on<Event.HandlerExecuted> {
        val ms = it.duration.toDouble(DurationUnit.MILLISECONDS)
        logger.info("Handler completed in ${String.format("%.2fms", ms)}")
    }

    app.on<Event.MiddlewareExecuted> {
        val ms = it.duration.toDouble(DurationUnit.MILLISECONDS)
        logger.info("${it.node.middleware.javaClass.simpleName} completed in ${String.format("%.2fms", ms)}")
    }

    // Errors
    app.on<Event.ExceptionCaught> {
        logger.error(
            "Exception in ${it.ctx.method} ${it.ctx.fullPath}: " +
                    "${it.exception.javaClass.simpleName} - ${it.exception.message}"
        )
    }

    app.on<Event.ExceptionHandled> {
        logger.info("Exception handled: ${it.exception.javaClass.simpleName}")
    }
}