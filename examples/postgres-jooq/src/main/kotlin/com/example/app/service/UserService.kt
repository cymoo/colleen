package com.example.app.service

import com.example.app.model.CreateUserRequest
import com.example.app.model.UpdateUserRequest
import com.example.app.model.User
import com.example.app.repository.UserRepository
import io.github.cymoo.colleen.BadRequest
import io.github.cymoo.colleen.Conflict
import io.github.cymoo.colleen.NotFound

class UserService(private val users: UserRepository) {
    fun list(): List<User> = users.findAll()

    fun get(id: Int): User {
        return users.findById(id) ?: throw NotFound("User not found")
    }

    fun create(request: CreateUserRequest): User {
        ensureUniqueUsername(request.username)
        ensureUniqueEmail(request.email)
        return users.create(request.username, request.email)
    }

    fun update(id: Int, request: UpdateUserRequest): User {
        val current = users.findById(id) ?: throw NotFound("User not found")
        if (request.username == null && request.email == null) {
            throw BadRequest("At least one field must be provided")
        }

        request.username
            ?.takeIf { it != current.username }
            ?.let(::ensureUniqueUsername)
        request.email
            ?.takeIf { it != current.email }
            ?.let(::ensureUniqueEmail)

        users.update(id, request.username, request.email)
        return get(id)
    }

    fun delete(id: Int) {
        if (users.delete(id) == 0) {
            throw NotFound("User not found")
        }
    }

    private fun ensureUniqueUsername(username: String) {
        if (users.findByUsername(username) != null) {
            throw Conflict("Username already exists")
        }
    }

    private fun ensureUniqueEmail(email: String) {
        if (users.findByEmail(email) != null) {
            throw Conflict("Email already exists")
        }
    }
}
