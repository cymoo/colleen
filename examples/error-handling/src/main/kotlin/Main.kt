import io.github.cymoo.colleen.BadRequest
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.HttpException
import io.github.cymoo.colleen.NotFound
import io.github.cymoo.colleen.ServerError
import io.github.cymoo.colleen.Unauthorized
import io.github.cymoo.colleen.ValidationException
import io.github.cymoo.colleen.expect
import io.github.cymoo.colleen.middleware.RequestLogger

/**
 * Error Handling Example
 *
 * Features demonstrated:
 * - Global error handlers
 * - Sub-application error propagation
 * - Custom error middleware
 */

// ========================================================================
// 1. Global Error Handlers
// ========================================================================

fun globalErrorHandlers() {
    val app = Colleen()

    app.use(RequestLogger())

    // Register type-specific error handlers
    app.onError<ValidationException> { e, ctx ->
        ctx.status(422).json(
            mapOf(
                "error" to "Validation Failed",
                "fields" to e.errors
            )
        )
    }

    app.onError<NotFound> { _, ctx ->
        ctx.status(404).json(
            mapOf(
                "error" to "Not Found",
                "path" to ctx.path
            )
        )
    }

    app.onError<Unauthorized> { e, ctx ->
        ctx.status(401).json(
            mapOf(
                "error" to "Unauthorized",
                "message" to e.message
            )
        )
    }

    // Routes that throw exceptions
    app.get("/users") { ctx ->
        val name = ctx.query("name")

        expect {
            field("name", name).required().notBlank()
        }

        mapOf("name" to name)
    }

    app.get("/protected") { ctx ->
        ctx.header("Authorization") ?: throw Unauthorized("Token required")

        mapOf("data" to "secret")
    }

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Global Error Handlers</title></head>
            <body>
                <h1>Global Error Handlers</h1>
                <ul>
                    <li><a href="/users?name=">/users?name=</a> - ValidationException</li>
                    <li><a href="/protected">/protected</a> - Unauthorized</li>
                    <li><a href="/unknown">/unknown</a> - NotFound</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ Global error handlers running on http://localhost:8000")
}

// ========================================================================
// 2. Sub-Application Error Propagation
// ========================================================================

fun subAppErrorPropagation() {
    val app = Colleen()

    app.use(RequestLogger())

    // Parent error handler
    app.onError<BadRequest> { e, ctx ->
        ctx.status(400).json(
            mapOf(
                "error" to "Parent handled",
                "message" to e.message
            )
        )
    }

    // Sub-app that propagates errors to parent
    val apiV1 = Colleen()
    apiV1.config.propagateExceptions = true  // Propagate to parent

    apiV1.get("/data") {
        throw BadRequest("Invalid data format")
    }

    // Sub-app that handles errors itself
    val apiV2 = Colleen()
    apiV2.config.propagateExceptions = false  // Handle by itself

    apiV2.onError<BadRequest> { e, ctx ->
        ctx.status(400).json(
            mapOf(
                "error" to "V2 handled",
                "message" to e.message
            )
        )
    }

    apiV2.get("/data") {
        throw BadRequest("Invalid data format")
    }

    app.mount("/api/v1", apiV1)
    app.mount("/api/v2", apiV2)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Sub-App Error Propagation</title></head>
            <body>
                <h1>Sub-Application Error Propagation</h1>
                <ul>
                    <li><a href="/api/v1/data">/api/v1/data</a> - Propagates to parent</li>
                    <li><a href="/api/v2/data">/api/v2/data</a> - Handled by sub-app</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8001)
    println("✅ Sub-app error propagation running on http://localhost:8001")
}

// ========================================================================
// 3. Error Handling Middleware
// ========================================================================

fun errorHandlingMiddleware() {
    val app = Colleen()

    app.use(RequestLogger())

    // Custom error handling middleware
    app.use { ctx, next ->
        next()

        val errorState = ctx.error ?: return@use
        errorState.handled = true  // Mark as handled

        // Log the error
        println("Error caught by middleware: ${errorState.cause.message}")

        // Custom error response based on exception type
        when (val error = errorState.cause) {
            is HttpException -> ctx.status(error.status).json(
                mapOf(
                    "error" to error.code,
                    "message" to error.message
                )
            )

            else -> ctx.status(500).json(
                mapOf(
                    "error" to "INTERNAL_ERROR",
                    "message" to (error.message ?: "Unknown error")
                )
            )
        }
    }

    app.get("/bad-request") {
        throw BadRequest("Invalid parameter")
    }

    app.get("/not-found") {
        throw NotFound("Resource not found")
    }

    app.get("/server-error") {
        throw ServerError("Something went wrong")
    }

    app.get("/generic") {
        throw IllegalStateException("Unexpected state")
    }

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head><title>Error Middleware</title></head>
            <body>
                <h1>Error Handling Middleware</h1>
                <p>All errors are caught and processed by custom middleware</p>
                <ul>
                    <li><a href="/bad-request">/bad-request</a> - BadRequest (400)</li>
                    <li><a href="/not-found">/not-found</a> - NotFound (404)</li>
                    <li><a href="/server-error">/server-error</a> - ServerError (500)</li>
                    <li><a href="/generic">/generic</a> - Generic exception (500)</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8002)
    println("✅ Error middleware running on http://localhost:8002")
}

// ========================================================================
// Main
// ========================================================================

fun main() {
    println("Choose an example to run:")
    println("1. Global error handlers")
    println("2. Sub-app error propagation")
    println("3. Error handling middleware")
    println()
    print("Enter choice (1-3): ")

    when (readlnOrNull()?.trim()) {
        "1" -> globalErrorHandlers()
        "2" -> subAppErrorPropagation()
        "3" -> errorHandlingMiddleware()
        else -> {
            println("Running all examples on different ports...")
            globalErrorHandlers()
            subAppErrorPropagation()
            errorHandlingMiddleware()
        }
    }
}