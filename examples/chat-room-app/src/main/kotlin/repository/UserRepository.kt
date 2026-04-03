package repository

import chatroom.jooq.generated.Tables.USERS
import chatroom.jooq.generated.tables.records.UsersRecord
import model.User
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class UserRepository(private val dsl: DSLContext) {

    private val USER_BIO = DSL.field("users.bio", String::class.java)
    private val USER_STATUS = DSL.field("users.status", String::class.java)

    fun findByUsername(username: String): User? {
        return dsl.select(
            USERS.ID, USERS.USERNAME, USERS.DISPLAY_NAME, USERS.AVATAR_URL,
            USERS.CREATED_AT, USERS.LAST_SEEN, USER_BIO, USER_STATUS
        )
            .from(USERS)
            .where(USERS.USERNAME.eq(username))
            .fetchOne()
            ?.let { record ->
                User(
                    id = record.get(USERS.ID)!!,
                    username = record.get(USERS.USERNAME)!!,
                    displayName = record.get(USERS.DISPLAY_NAME)!!,
                    avatarUrl = record.get(USERS.AVATAR_URL),
                    bio = record.get(USER_BIO),
                    status = record.get(USER_STATUS),
                    createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
                    lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
                )
            }
    }

    fun findById(id: Int): User? {
        return dsl.select(
            USERS.ID, USERS.USERNAME, USERS.DISPLAY_NAME, USERS.AVATAR_URL,
            USERS.CREATED_AT, USERS.LAST_SEEN, USER_BIO, USER_STATUS
        )
            .from(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne()
            ?.let { record ->
                User(
                    id = record.get(USERS.ID)!!,
                    username = record.get(USERS.USERNAME)!!,
                    displayName = record.get(USERS.DISPLAY_NAME)!!,
                    avatarUrl = record.get(USERS.AVATAR_URL),
                    bio = record.get(USER_BIO),
                    status = record.get(USER_STATUS),
                    createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
                    lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
                )
            }
    }

    fun createUser(username: String, displayName: String, avatarUrl: String? = null): User {
        val record = dsl.insertInto(USERS)
            .set(USERS.USERNAME, username)
            .set(USERS.DISPLAY_NAME, displayName)
            .set(USERS.AVATAR_URL, avatarUrl)
            .returningResult(USERS.ID, USERS.USERNAME, USERS.DISPLAY_NAME, USERS.AVATAR_URL, USERS.CREATED_AT, USERS.LAST_SEEN)
            .fetchOne()!!

        return User(
            id = record.get(USERS.ID)!!,
            username = record.get(USERS.USERNAME)!!,
            displayName = record.get(USERS.DISPLAY_NAME)!!,
            avatarUrl = record.get(USERS.AVATAR_URL),
            createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
            lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
        )
    }

    fun updateLastSeen(userId: Int) {
        dsl.update(USERS)
            .set(USERS.LAST_SEEN, LocalDateTime.now(ZoneOffset.UTC))
            .where(USERS.ID.eq(userId))
            .execute()
    }

    fun updateProfile(userId: Int, displayName: String?, avatarUrl: String?, bio: String?, status: String?) {
        if (displayName == null && avatarUrl == null && bio == null && status == null) return

        var step = dsl.update(USERS).set(USERS.LAST_SEEN, USERS.LAST_SEEN) // baseline to start fluent chain
        if (displayName != null) step = step.set(USERS.DISPLAY_NAME, displayName)
        if (avatarUrl != null) step = step.set(USERS.AVATAR_URL, avatarUrl)
        if (bio != null) step = step.set(USER_BIO, bio)
        if (status != null) step = step.set(USER_STATUS, status)
        step.where(USERS.ID.eq(userId)).execute()
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}