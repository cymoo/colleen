package controller

import io.github.cymoo.colleen.*
import model.AuthResponse
import model.LoginRequest
import model.RegisterRequest
import model.User
import service.SessionService
import service.UserService

/**
 * Authentication controller: register, login, logout, current user
 */
@Controller("/api/auth")
class AuthController(
    private val userService: UserService,
    private val sessionService: SessionService
) {

    @Post("/register")
    fun register(body: Json<RegisterRequest>): AuthResponse {
        val req = body.value
        if (req.username.isBlank()) throw BadRequest("Username is required")
        if (req.password.length < 6) throw BadRequest("Password must be at least 6 characters")

        return try {
            val result = userService.register(req.username, req.password)
            val token = sessionService.createSession(result.user.id)
            AuthResponse(token = token, user = result.user)
        } catch (e: IllegalArgumentException) {
            throw BadRequest(e.message ?: "Registration failed")
        }
    }

    @Post("/login")
    fun login(body: Json<LoginRequest>): AuthResponse {
        val req = body.value
        val user = userService.login(req.username, req.password)
            ?: throw Unauthorized("Invalid username or password")
        val token = sessionService.createSession(user.id)
        return AuthResponse(token = token, user = user)
    }

    @Post("/logout")
    fun logout(ctx: Context) {
        val token = ctx.bearerToken() ?: return
        sessionService.invalidateSession(token)
        ctx.status(204)
    }

    @Get("/me")
    fun me(ctx: Context): User {
        val token = ctx.bearerToken() ?: throw Unauthorized("Authentication required")
        val userId = sessionService.validateSession(token) ?: throw Unauthorized("Invalid or expired session")
        return userService.getUserById(userId) ?: throw NotFound("User not found")
    }

    private fun Context.bearerToken(): String? {
        val auth = header("Authorization") ?: return null
        return if (auth.startsWith("Bearer ")) auth.removePrefix("Bearer ").trim() else null
    }
}
