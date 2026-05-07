package com.example.app

import com.example.app.config.AppConfig
import com.example.app.config.DatabaseConfig
import com.example.app.config.ServerConfig
import com.example.app.model.CreateUserRequest
import com.example.app.model.UpdateUserRequest
import io.github.cymoo.colleen.TestClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
class ApplicationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("colleen_app_test")
            withUsername("colleen")
            withPassword("colleen")
        }
    }

    private lateinit var resources: ApplicationResources

    @BeforeTest
    fun setUp() {
        val config = AppConfig(
            env = "test",
            server = ServerConfig(host = "127.0.0.1", port = 0),
            database = DatabaseConfig(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maximumPoolSize = 4,
                migrateOnStart = true,
            ),
            corsAllowedOrigins = emptySet(),
            openApiEnabled = true,
        )

        resources = createApplication(config)
    }

    @AfterTest
    fun tearDown() {
        resources.close()
    }

    @Test
    fun `health and users endpoints work`() {
        val client = TestClient(resources.app)

        client.get("/health")
            .send()
            .assertStatus(200)
            .assertHeader("X-Request-ID")
            .assertBodyContains("ok")

        client.get("/ready")
            .send()
            .assertStatus(200)
            .assertBodyContains("ready")

        client.get("/users/")
            .send()
            .assertStatus(200)
            .assertBodyContains("ada@example.com")

        val created = client.post("/users/")
            .json(CreateUserRequest(username = "linus", email = "linus@example.com"))
            .send()
            .assertStatus(201)
            .json<Map<String, Any>>()

        val createdId = (assertNotNull(created)["id"] as Number).toInt()

        client.get("/users/$createdId")
            .send()
            .assertStatus(200)
            .assertBodyContains("linus@example.com")

        client.put("/users/$createdId")
            .json(UpdateUserRequest(email = "torvalds@example.com"))
            .send()
            .assertStatus(200)
            .assertBodyContains("torvalds@example.com")

        client.delete("/users/$createdId")
            .send()
            .assertStatus(204)

        client.get("/users/$createdId")
            .send()
            .assertStatus(404)
    }
}
