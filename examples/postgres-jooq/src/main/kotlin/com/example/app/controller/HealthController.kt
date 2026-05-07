package com.example.app.controller

import com.example.app.db.Database
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Get
import io.github.cymoo.colleen.ServiceUnavailable
import io.github.cymoo.colleen.openapi.ResponseDesc
import io.github.cymoo.colleen.openapi.Summary
import io.github.cymoo.colleen.openapi.Tags

@Tags("system")
@Controller("/")
class HealthController(private val database: Database) {
    @Get("/health")
    @Summary("Liveness check")
    @ResponseDesc(200, "Application process is running")
    fun health(): Map<String, String> {
        return mapOf("status" to "ok")
    }

    @Get("/ready")
    @Summary("Readiness check")
    @ResponseDesc(200, "Application and database are ready")
    @ResponseDesc(503, "Database is not ready")
    fun ready(): Map<String, String> {
        if (!database.ready()) {
            throw ServiceUnavailable("Database is not ready")
        }

        return mapOf("status" to "ready")
    }
}
