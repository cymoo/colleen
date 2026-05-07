package com.example.app

import com.example.app.config.AppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class EnvLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads base env then app env overrides`() {
        Files.writeString(
            tempDir.resolve(".env"),
            """
            APP_ENV=test
            APP_PORT=8000
            DB_USER=base
            DB_PASSWORD=base-password
            DATABASE_URL=jdbc:postgresql://localhost:5432/base
            """.trimIndent(),
        )
        Files.writeString(
            tempDir.resolve(".env.test"),
            """
            APP_PORT=9000
            DB_USER=test-user
            DATABASE_URL=jdbc:postgresql://localhost:5433/test_db
            """.trimIndent(),
        )

        val config = AppConfig.load(tempDir)

        assertEquals("test", config.env)
        assertEquals(9000, config.server.port)
        assertEquals("jdbc:postgresql://localhost:5433/test_db", config.database.jdbcUrl)
        assertEquals("test-user", config.database.username)
        assertEquals("base-password", config.database.password)
    }
}
