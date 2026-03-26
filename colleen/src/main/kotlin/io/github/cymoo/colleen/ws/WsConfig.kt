package io.github.cymoo.colleen.ws

/**
 * WebSocket configuration.
 *
 * Controls WebSocket-specific settings such as timeouts and message size limits.
 */
data class WsConfig(
    /**
     * Idle timeout for WebSocket connections in milliseconds.
     *
     * If no messages are sent or received within this period, the connection is closed.
     * Set to 0 for no timeout.
     *
     * Default: 5 minutes (300,000 ms).
     */
    @JvmField
    var idleTimeoutMs: Long = 300_000,

    /**
     * Maximum size of a single WebSocket message in bytes.
     *
     * Messages exceeding this limit will cause the connection to be closed.
     *
     * Default: 64 KB.
     */
    @JvmField
    var maxMessageSizeBytes: Long = 64 * 1024,
)
