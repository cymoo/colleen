package io.github.cymoo.colleen

import io.github.cymoo.colleen.json.JacksonMapper
import io.github.cymoo.colleen.json.JsonMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

inline val <reified T> T.logger: Logger
    get() = LoggerFactory.getLogger(T::class.java)

/**
 * Server configuration for network and connection settings.
 */
data class ServerConfig(
    @JvmField
    var host: String = "127.0.0.1",

    @JvmField
    var port: Int = 8000,

    /** Whether to use virtual threads (Java 21+, JDK 25 recommended for high concurrency). */
    @JvmField
    var useVirtualThreads: Boolean = true,

    /**
     * Maximum worker threads (default: cpu count * 8).
     *
     * Only effective when [useVirtualThreads] is false.
     */
    @JvmField
    var maxThreads: Int = Runtime.getRuntime().availableProcessors() * 8,

    /** Maximum concurrent requests (default: no limit; set an explicit limit in production). */
    @JvmField
    var maxConcurrentRequests: Int = 0,

    /** Maximum size of the entire request in bytes (default: 30MB). */
    @JvmField
    var maxRequestSize: Long = 30 * 1024 * 1024,

    /** Maximum size of a single uploaded file in bytes (default: 10MB). */
    @JvmField
    var maxFileSize: Long = 10 * 1024 * 1024,

    /** File size threshold for writing to disk in bytes (default: 256KB). */
    @JvmField
    var fileSizeThreshold: Long = 256 * 1024,

    /** Graceful shutdown timeout in milliseconds (default: 30s). */
    @JvmField
    var shutdownTimeout: Long = 30_000,

    /** Connection idle timeout in milliseconds (0 = infinite, default: 30s). */
    @JvmField
    var idleTimeout: Long = 30_000,
)

/**
 * JSON serialization configuration.
 */
data class JsonConfig(
    /** Pretty print JSON output (default: false). */
    @JvmField
    var pretty: Boolean = false,

    /** Include null values in JSON output (default: false). */
    @JvmField
    var includeNulls: Boolean = false,

    /** Fail on unknown properties during deserialization (default: true). */
    @JvmField
    var failOnUnknownProperties: Boolean = true,

    /** Whether to fail deserialization when a JSON null is mapped to a primitive type (default: true). */
    @JvmField
    var failOnNullForPrimitives: Boolean = true,

    /** Write dates as timestamps (true) or ISO-8601 strings (default: false). */
    @JvmField
    var writeDatesAsTimestamps: Boolean = false,

    /** Date format pattern (e.g., "yyyy-MM-dd'T'HH:mm:ss", default: null = ISO-8601). */
    @JvmField
    var dateFormat: String? = null,

    /** Fail on empty beans (classes with no properties, default: false). */
    @JvmField
    var failOnEmptyBeans: Boolean = false,

    /** Accept single value as array during deserialization (default: false). */
    @JvmField
    var acceptSingleValueAsArray: Boolean = false,

    /** Write enums using toString() instead of name() (default: false). */
    @JvmField
    var writeEnumsUsingToString: Boolean = false,

    /** Read enums using toString() instead of name() (default: false). */
    @JvmField
    var readEnumsUsingToString: Boolean = false,
)

/**
 * Root configuration for a Colleen application.
 *
 * This configuration defines global behaviors and subsystem settings
 * such as server, JSON handling, and exception propagation.
 */
data class Config(
    /**
     * Whether to propagate exceptions from mounted sub-applications.
     * If true, exceptions from sub-apps bubble up to the parent handler.
     * If false, sub-apps handle their own exceptions independently.
     */
    @JvmField
    var propagateExceptions: Boolean = true,

    @JvmField
    var server: ServerConfig = ServerConfig(),

    @JvmField
    var json: JsonConfig = JsonConfig(),
) {
    internal var jsonMapperInstance: JsonMapper? = null

    /**
     * The configured JsonMapper instance.
     *
     * Lazily initialized on first access using the default [JacksonMapper]
     * with settings from [json] and [server] configurations.
     *
     * Can be replaced using [jsonMapper] setter.
     */
    val jsonMapper: JsonMapper
        get() = jsonMapperInstance ?: createDefaultJsonMapper().also { jsonMapperInstance = it }

    /**
     * Replace the default JsonMapper with a custom implementation.
     *
     * Use this when you want to use a different JSON library (e.g., Gson, Moshi)
     * or a pre-configured mapper instance.
     *
     * Example:
     * ```kotlin
     * app.config {
     *     jsonMapper(MyCustomGsonMapper())
     * }
     * ```
     *
     * @param mapper The custom JsonMapper implementation to use
     */
    fun jsonMapper(mapper: JsonMapper) {
        jsonMapperInstance = mapper
    }

    /**
     * Configure JSON serialization/deserialization behavior.
     *
     * These settings apply to the default JacksonMapper.
     * For Jackson-specific advanced configuration, use the jackson extension function.
     */
    fun json(block: JsonConfig.() -> Unit) {
        json.apply(block)
    }

    fun server(block: ServerConfig.() -> Unit) {
        server.apply(block)
    }

    internal fun createDefaultJsonMapper(): JacksonMapper {
        return JacksonMapper(useVirtualThreads = server.useVirtualThreads).apply {
            configure(json)
        }
    }
}
