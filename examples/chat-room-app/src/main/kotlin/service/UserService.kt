package service

import model.User
import org.jooq.DSLContext
import repository.UserRepository

/**
 * User service
 */
class UserService(dsl: DSLContext) {

    private val userRepo = UserRepository(dsl)

    fun findOrCreateUser(username: String, displayName: String? = null): User {
        return userRepo.findByUsername(username)
            ?: userRepo.createUser(username, displayName ?: username)
    }

    fun getUserById(id: Int): User? {
        return userRepo.findById(id)
    }
}