package com.example.app

import com.example.app.config.AppConfig
import com.example.app.controller.HealthController
import com.example.app.controller.UserController
import com.example.app.db.Database
import com.example.app.repository.UserRepository
import com.example.app.service.UserService
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.Cors
import io.github.cymoo.colleen.middleware.RequestId
import io.github.cymoo.colleen.middleware.RequestLogger
import io.github.cymoo.colleen.middleware.SecurityHeaders

data class ApplicationResources(
    val app: Colleen,
    val database: Database,
) : AutoCloseable {
    override fun close() {
        database.close()
    }
}

fun createApplication(config: AppConfig = AppConfig.load()): ApplicationResources {
    val database = Database(config.database)
    val app = Colleen()

    app.use(RequestId())
    app.use(RequestLogger(skip = { it.path == "/health" }))
    app.use(SecurityHeaders())
    if (config.corsAllowedOrigins.isNotEmpty()) {
        app.use(Cors(allowOrigins = config.corsAllowedOrigins))
    }

    val userRepository = UserRepository(database.dsl)
    val userService = UserService(userRepository)

    app.addController(HealthController(database))
    app.addController(UserController(userService))

    if (config.openApiEnabled) {
        app.openApi(
            title = "Colleen PostgreSQL + jOOQ Template",
            version = "1.0.0",
            description = "A Colleen application template backed by PostgreSQL, Flyway, HikariCP, and jOOQ.",
        )
    }

    return ApplicationResources(app, database)
}

fun main() {
    val config = AppConfig.load()
    val resources = createApplication(config)

    Runtime.getRuntime().addShutdownHook(Thread { resources.close() })
    resources.app.listen(port = config.server.port, host = config.server.host)
    println("Server running on http://${config.server.host}:${config.server.port}")
}
