package repository

import chatroom.jooq.generated.Tables.USERS
import model.User
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Internal record that includes sensitive auth fields not exposed in the User model. */
data class AuthUser(val user: User, val passwordHash: String?)

class UserRepository(private val dsl: DSLContext) {

    fun findByUsername(username: String): User? = findByUsernameForAuth(username)?.user

    fun findByUsernameForAuth(username: String): AuthUser? {
        return dsl.select(
            USERS.ID, USERS.USERNAME, USERS.AVATAR_URL,
            USERS.CREATED_AT, USERS.LAST_SEEN, USERS.BIO, USERS.STATUS, USERS.PASSWORD_HASH
        )
            .from(USERS)
            .where(USERS.USERNAME.eq(username))
            .fetchOne()
            ?.let { record ->
                AuthUser(
                    user = User(
                        id = record.get(USERS.ID)!!,
                        username = record.get(USERS.USERNAME)!!,
                        avatarUrl = record.get(USERS.AVATAR_URL),
                        bio = record.get(USERS.BIO),
                        status = record.get(USERS.STATUS),
                        createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
                        lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
                    ),
                    passwordHash = record.get(USERS.PASSWORD_HASH)
                )
            }
    }

    fun findById(id: Int): User? {
        return dsl.select(
            USERS.ID, USERS.USERNAME, USERS.AVATAR_URL,
            USERS.CREATED_AT, USERS.LAST_SEEN, USERS.BIO, USERS.STATUS
        )
            .from(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne()
            ?.let { record ->
                User(
                    id = record.get(USERS.ID)!!,
                    username = record.get(USERS.USERNAME)!!,
                    avatarUrl = record.get(USERS.AVATAR_URL),
                    bio = record.get(USERS.BIO),
                    status = record.get(USERS.STATUS),
                    createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
                    lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
                )
            }
    }

    fun createUser(username: String, passwordHash: String? = null): User {
        val record = dsl.insertInto(USERS)
            .set(USERS.USERNAME, username)
            .set(USERS.DISPLAY_NAME, username)
            .set(USERS.PASSWORD_HASH, passwordHash)
            .returningResult(USERS.ID, USERS.USERNAME, USERS.AVATAR_URL, USERS.CREATED_AT, USERS.LAST_SEEN)
            .fetchOne()!!

        return User(
            id = record.get(USERS.ID)!!,
            username = record.get(USERS.USERNAME)!!,
            avatarUrl = record.get(USERS.AVATAR_URL),
            createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
            lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
        )
    }

    fun setPasswordHash(userId: Int, passwordHash: String) {
        dsl.update(USERS)
            .set(USERS.PASSWORD_HASH, passwordHash)
            .where(USERS.ID.eq(userId))
            .execute()
    }

    fun updateLastSeen(userId: Int) {
        dsl.update(USERS)
            .set(USERS.LAST_SEEN, LocalDateTime.now(ZoneOffset.UTC))
            .where(USERS.ID.eq(userId))
            .execute()
    }

    fun updateProfile(userId: Int, avatarUrl: String?, bio: String?, status: String?) {
        if (avatarUrl == null && bio == null && status == null) return

        var step = dsl.update(USERS).set(USERS.LAST_SEEN, USERS.LAST_SEEN)
        if (avatarUrl != null) step = step.set(USERS.AVATAR_URL, avatarUrl)
        if (bio != null) step = step.set(USERS.BIO, bio)
        if (status != null) step = step.set(USERS.STATUS, status)
        step.where(USERS.ID.eq(userId)).execute()
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}
