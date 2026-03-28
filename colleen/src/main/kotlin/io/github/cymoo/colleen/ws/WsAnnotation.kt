package io.github.cymoo.colleen.ws

/**
 * Marks a controller method as a WebSocket endpoint.
 *
 * The annotated method must accept exactly one parameter of type [WsConnection].
 *
 * ### Example
 * ```kotlin
 * @Controller("/chat")
 * class ChatController {
 *     @Ws("/{room}")
 *     fun chat(conn: WsConnection) {
 *         conn.onMessage { msg ->
 *             conn.send("Echo: $msg")
 *         }
 *     }
 * }
 * ```
 *
 * @property value the WebSocket path pattern (relative to the controller base path)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ws(val value: String = "/")
