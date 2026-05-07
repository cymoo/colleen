package com.example.app.config

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

data class AppConfig(
    val env: String,
    val server: ServerConfig,
    val database: DatabaseConfig,
    val corsAllowedOrigins: Set<String>,
    val openApiEnabled: Boolean,
) {
    companion object {
        fun load(baseDir: Path? = null): AppConfig {
            val loaded = if (baseDir == null) EnvLoader.load() else EnvLoader.load(baseDir)
            return from(loaded)
        }

        fun from(loaded: LoadedEnv): AppConfig {
            val env = loaded.values
            if (loaded.appEnv == "production") {
                validateProductionDatabaseConfig()
            }

            val databaseUrl = resolveDatabaseUrl(env)
            val databaseCredentials = resolveDatabaseCredentials(env)

            return AppConfig(
                env = loaded.appEnv,
                server = ServerConfig(
                    host = env.value("APP_HOST", "127.0.0.1"),
                    port = env.intValue("APP_PORT", 8000),
                ),
                database = DatabaseConfig(
                    jdbcUrl = databaseUrl,
                    username = databaseCredentials.username,
                    password = databaseCredentials.password,
                    maximumPoolSize = env.intValue("DB_POOL_SIZE", 10),
                    migrateOnStart = env.boolValue("DB_MIGRATE_ON_START", true),
                ),
                corsAllowedOrigins = env.listValue("APP_CORS_ALLOWED_ORIGINS").toSet(),
                openApiEnabled = env.boolValue("APP_OPENAPI_ENABLED", true),
            )
        }

        private fun validateProductionDatabaseConfig() {
            val system = System.getenv()
            val databaseUrl = system["DATABASE_URL"]
            val hasUrl = !databaseUrl.isNullOrBlank()
            val hasSplitConfig = listOf("DB_HOST", "DB_PORT", "DB_NAME").all { !system[it].isNullOrBlank() }
            val urlHasCredentials = databaseUrl?.let(::uriContainsCredentials) == true
            val hasCredentials = !system["DB_USER"].isNullOrBlank() && !system["DB_PASSWORD"].isNullOrBlank()

            require(hasUrl || hasSplitConfig) {
                "APP_ENV=production requires DATABASE_URL or DB_HOST/DB_PORT/DB_NAME from system environment"
            }
            require(urlHasCredentials || hasCredentials) {
                "APP_ENV=production requires DB_USER and DB_PASSWORD from system environment unless DATABASE_URL contains credentials"
            }
        }

        private fun uriContainsCredentials(value: String): Boolean {
            if (value.startsWith("jdbc:", ignoreCase = true)) {
                return false
            }

            return runCatching {
                URI(value).rawUserInfo?.isNotBlank() == true
            }.getOrDefault(false)
        }

        private fun resolveDatabaseUrl(env: Map<String, String>): String {
            val url = env.value("DATABASE_URL", "")
            if (url.isNotBlank()) {
                return normalizeDatabaseUrl(url).jdbcUrl
            }

            val host = env.value("DB_HOST", "localhost")
            val port = env.intValue("DB_PORT", 5432)
            val database = env.value("DB_NAME", "colleen_app")
            return "jdbc:postgresql://$host:$port/$database"
        }

        private fun resolveDatabaseCredentials(env: Map<String, String>): DatabaseCredentials {
            val normalizedUrl = normalizeDatabaseUrl(env.value("DATABASE_URL", ""))
            return DatabaseCredentials(
                username = env.value("DB_USER", normalizedUrl.username ?: "colleen"),
                password = env.value("DB_PASSWORD", normalizedUrl.password ?: "colleen"),
            )
        }

        private fun normalizeDatabaseUrl(value: String): NormalizedDatabaseUrl {
            if (value.isBlank()) {
                return NormalizedDatabaseUrl("")
            }
            if (value.startsWith("jdbc:", ignoreCase = true)) {
                return NormalizedDatabaseUrl(value)
            }

            val uri = URI(value)
            require(uri.scheme == "postgres" || uri.scheme == "postgresql") {
                "DATABASE_URL must be a JDBC PostgreSQL URL or postgres:// URI"
            }

            val port = if (uri.port > 0) ":${uri.port}" else ""
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val jdbcUrl = "jdbc:postgresql://${uri.host}$port${uri.rawPath}$query"
            val credentials = uri.rawUserInfo
                ?.split(":", limit = 2)
                ?.map { URLDecoder.decode(it, UTF_8) }

            return NormalizedDatabaseUrl(
                jdbcUrl = jdbcUrl,
                username = credentials?.getOrNull(0),
                password = credentials?.getOrNull(1),
            )
        }
    }
}

data class ServerConfig(
    val host: String,
    val port: Int,
)

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
    val migrateOnStart: Boolean,
)

private data class DatabaseCredentials(
    val username: String,
    val password: String,
)

private data class NormalizedDatabaseUrl(
    val jdbcUrl: String,
    val username: String? = null,
    val password: String? = null,
)

private fun Map<String, String>.value(name: String, default: String): String {
    return this[name]?.takeIf { it.isNotBlank() } ?: default
}

private fun Map<String, String>.intValue(name: String, default: Int): Int {
    val value = this[name]?.takeIf { it.isNotBlank() } ?: return default
    return value.toIntOrNull() ?: error("$name must be an integer")
}

private fun Map<String, String>.boolValue(name: String, default: Boolean): Boolean {
    val value = this[name]?.takeIf { it.isNotBlank() } ?: return default
    return when (value.lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> error("$name must be a boolean")
    }
}

private fun Map<String, String>.listValue(name: String): List<String> {
    return this[name]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
}
