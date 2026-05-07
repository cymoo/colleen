package com.example.app.repository

import com.example.app.model.User
import com.example.jooq.generated.tables.Users.USERS
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import java.time.LocalDateTime

class UserRepository(private val dsl: DSLContext) {
    fun findAll(): List<User> {
        return dsl.select(USERS.ID, USERS.USERNAME, USERS.EMAIL, USERS.CREATED_AT, USERS.UPDATED_AT)
            .from(USERS)
            .orderBy(USERS.ID)
            .fetch()
            .map(::toUser)
    }

    fun findById(id: Int): User? {
        return dsl.select(USERS.ID, USERS.USERNAME, USERS.EMAIL, USERS.CREATED_AT, USERS.UPDATED_AT)
            .from(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne()
            ?.let(::toUser)
    }

    fun findByUsername(username: String): User? {
        return dsl.select(USERS.ID, USERS.USERNAME, USERS.EMAIL, USERS.CREATED_AT, USERS.UPDATED_AT)
            .from(USERS)
            .where(USERS.USERNAME.eq(username))
            .fetchOne()
            ?.let(::toUser)
    }

    fun findByEmail(email: String): User? {
        return dsl.select(USERS.ID, USERS.USERNAME, USERS.EMAIL, USERS.CREATED_AT, USERS.UPDATED_AT)
            .from(USERS)
            .where(USERS.EMAIL.eq(email))
            .fetchOne()
            ?.let(::toUser)
    }

    fun create(username: String, email: String): User {
        return dsl.insertInto(USERS)
            .set(USERS.USERNAME, username)
            .set(USERS.EMAIL, email)
            .returning(USERS.ID, USERS.USERNAME, USERS.EMAIL, USERS.CREATED_AT, USERS.UPDATED_AT)
            .fetchOne()
            ?.let(::toUser)
            ?: error("Failed to insert user")
    }

    fun update(id: Int, username: String?, email: String?): Int {
        val fields = mutableMapOf<Field<*>, Any>()
        username?.let { fields[USERS.USERNAME] = it }
        email?.let { fields[USERS.EMAIL] = it }

        if (fields.isEmpty()) {
            return 0
        }

        fields[USERS.UPDATED_AT] = LocalDateTime.now()
        return dsl.update(USERS)
            .set(fields)
            .where(USERS.ID.eq(id))
            .execute()
    }

    fun delete(id: Int): Int {
        return dsl.deleteFrom(USERS)
            .where(USERS.ID.eq(id))
            .execute()
    }

    private fun toUser(record: Record): User {
        return User(
            id = record.get(USERS.ID)!!,
            username = record.get(USERS.USERNAME)!!,
            email = record.get(USERS.EMAIL)!!,
            createdAt = record.get(USERS.CREATED_AT)!!,
            updatedAt = record.get(USERS.UPDATED_AT)!!,
        )
    }
}
