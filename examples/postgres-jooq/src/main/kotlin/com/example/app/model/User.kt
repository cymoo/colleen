package com.example.app.model

import java.time.LocalDateTime

data class User(
    val id: Int,
    val username: String,
    val email: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class CreateUserRequest(
    val username: String,
    val email: String,
)

data class UpdateUserRequest(
    val username: String? = null,
    val email: String? = null,
)
