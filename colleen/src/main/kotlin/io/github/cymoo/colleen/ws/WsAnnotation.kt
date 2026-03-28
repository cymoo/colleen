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

/**
 * Attaches a WebSocket middleware to a controller.
 *
 * The annotated method must have the same signature as an HTTP middleware:
 * two parameters of types [io.github.cymoo.colleen.Context] and [io.github.cymoo.colleen.Next],
 * returning void/Unit.
 *
 * WebSocket middlewares registered via `@WsUse` run during the WebSocket handshake
 * phase only, before the connection is established. They are scoped to the
 * controller's base path.
 *
 * ### Example
 * ```kotlin
 * @Controller("/chat")
 * class ChatController {
 *     @WsUse
 *     fun authenticate(ctx: Context, next: Next) {
 *         val token = ctx.header("Authorization")
 *         if (token != null) {
 *             next()
 *         } else {
 *             ctx.status(401).text("Unauthorized")
 *         }
 *     }
 *
 *     @Ws("/{room}")
 *     fun chat(conn: WsConnection) {
 *         conn.onMessage { msg -> conn.send("Echo: $msg") }
 *     }
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WsUse
