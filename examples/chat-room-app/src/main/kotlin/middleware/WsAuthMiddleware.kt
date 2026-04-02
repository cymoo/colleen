package middleware

import io.github.cymoo.colleen.BadRequest
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import service.UserService

/**
 * WebSocket authentication middleware
 * Validates username and creates/retrieves user
 */
class WsAuthMiddleware(private val userService: UserService): Middleware {
    
    override operator fun invoke(ctx: Context, next: Next) {
        val username = ctx.query("username")
        val displayName = ctx.query("displayName")
        
        if (username.isNullOrBlank()) {
            ctx.status(400).json(mapOf("error" to "Username is required"))
            return
        }
        
        // Find or create user
        val user = userService.findOrCreateUser(username, displayName)
        
        // Store user in context state for WS handler
        ctx.setState("user", user)
        
        next()
    }
}
