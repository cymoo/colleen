package service

import model.AuthResponse
import model.User
import org.jooq.DSLContext
import repository.UserRepository
import util.PasswordUtils

/**
 * User service with registration and login support
 */
class UserService(dsl: DSLContext) {

    private val userRepo = UserRepository(dsl)

    fun register(username: String, password: String): AuthResponse {
        require(username.isNotBlank()) { "Username is required" }
        require(password.length >= 6) { "Password must be at least 6 characters" }

        val existing = userRepo.findByUsernameForAuth(username)
        val passwordHash = PasswordUtils.hashPassword(password)

        val user = if (existing != null) {
            // Legacy account (no password) can claim ownership by registering
            require(existing.passwordHash == null) { "Username already taken" }
            userRepo.setPasswordHash(existing.user.id, passwordHash)
            existing.user
        } else {
            userRepo.createUser(username, passwordHash)
        }

        return AuthResponse(token = "", user = user)
    }

    fun login(username: String, password: String): User? {
        val authUser = userRepo.findByUsernameForAuth(username) ?: return null
        if (authUser.passwordHash == null) return null // unregistered legacy account
        if (!PasswordUtils.verifyPassword(password, authUser.passwordHash)) return null
        userRepo.updateLastSeen(authUser.user.id)
        return authUser.user
    }

    /** Finds or creates a guest user (no password). Used for legacy/system purposes. */
    fun findOrCreateUser(username: String): User {
        return userRepo.findByUsername(username)
            ?: userRepo.createUser(username)
    }

    fun getUserById(id: Int): User? {
        return userRepo.findById(id)
    }

    fun getUserByUsername(username: String): User? {
        return userRepo.findByUsername(username)
    }

    fun updateProfile(userId: Int, avatarUrl: String?, bio: String?, status: String?): User? {
        userRepo.updateProfile(userId, avatarUrl, bio, status)
        return userRepo.findById(userId)
    }
}