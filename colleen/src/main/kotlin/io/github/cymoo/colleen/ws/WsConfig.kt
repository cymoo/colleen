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
     * Messages exceeding this limit will cause the connection to be closed
     * with close code 1009 (Message Too Big).
     *
     * Default: 64 KB.
     */
    @JvmField
    var maxMessageSizeBytes: Long = 64 * 1024,

    /**
     * Interval between server-initiated ping frames in milliseconds.
     *
     * Ping frames keep the connection alive through NAT gateways, load balancers,
     * and transparent proxies that may silently drop idle connections.
     * Set to 0 to disable ping/pong heartbeat.
     *
     * Default: 30 seconds.
     */
    @JvmField
    var pingIntervalMs: Long = 30_000,

    /**
     * Maximum time to wait for a pong response after sending a ping, in milliseconds.
     *
     * If no pong is received within this period after the last ping,
     * the connection is considered dead and will be closed.
     * Only effective when [pingIntervalMs] > 0.
     *
     * Default: 10 seconds.
     */
    @JvmField
    var pingTimeoutMs: Long = 10_000,

    /**
     * Maximum number of concurrent WebSocket connections.
     *
     * When the limit is reached, new WebSocket upgrade requests are rejected
     * with HTTP 503 (Service Unavailable) before the WebSocket handshake completes.
     * Set to 0 for no limit.
     *
     * Default: 0 (unlimited).
     */
    @JvmField
    var maxConnections: Int = 0,
)
