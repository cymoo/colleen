package com.example.app.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class LoadedEnv(
    val appEnv: String,
    val values: Map<String, String>,
    val baseDir: Path,
)

object EnvLoader {
    fun load(baseDir: Path = defaultBaseDir()): LoadedEnv {
        val base = readEnvFile(baseDir.resolve(".env"))
        val appEnv = System.getenv("APP_ENV")
            ?: base["APP_ENV"]
            ?: "development"
        val environment = readEnvFile(baseDir.resolve(".env.$appEnv"))
        val values = base + environment + System.getenv()

        return LoadedEnv(
            appEnv = values["APP_ENV"] ?: appEnv,
            values = values,
            baseDir = baseDir,
        )
    }

    private fun defaultBaseDir(): Path {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        if (Files.exists(cwd.resolve(".env.development")) || Files.exists(cwd.resolve(".env.example"))) {
            return cwd
        }

        val exampleDir = cwd.resolve("examples/postgres-jooq")
        if (Files.exists(exampleDir.resolve(".env.development"))) {
            return exampleDir
        }

        return cwd
    }

    private fun readEnvFile(path: Path): Map<String, String> {
        if (!Files.isRegularFile(path)) {
            return emptyMap()
        }

        return Files.readAllLines(path)
            .mapNotNull(::parseLine)
            .toMap()
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null
        }

        val separator = trimmed.indexOf('=')
        require(separator > 0) {
            "Invalid .env line: $line"
        }

        val key = trimmed.substring(0, separator).trim()
        val rawValue = trimmed.substring(separator + 1).trim()
        require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Invalid .env key: $key"
        }

        return key to rawValue.stripMatchingQuotes()
    }

    private fun String.stripMatchingQuotes(): String {
        if (length < 2) {
            return this
        }

        val first = first()
        val last = last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            substring(1, length - 1)
        } else {
            this
        }
    }
}
