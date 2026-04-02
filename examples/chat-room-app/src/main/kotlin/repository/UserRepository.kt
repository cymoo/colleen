package repository

import chatroom.jooq.generated.Tables.USERS
import chatroom.jooq.generated.tables.records.UsersRecord
import model.User
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class UserRepository(private val dsl: DSLContext) {

    fun findByUsername(username: String): User? {
        return dsl.selectFrom(USERS)
            .where(USERS.USERNAME.eq(username))
            .fetchOne()
            ?.toModel()
    }

    fun findById(id: Int): User? {
        return dsl.selectFrom(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne()
            ?.toModel()
    }

    fun createUser(username: String, displayName: String, avatarUrl: String? = null): User {
        val record = dsl.insertInto(USERS)
            .set(USERS.USERNAME, username)
            .set(USERS.DISPLAY_NAME, displayName)
            .set(USERS.AVATAR_URL, avatarUrl)
            .returningResult(USERS.fields().toList())
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

    private fun UsersRecord.toModel(): User {
        return User(
            id = this.id!!,
            username = this.username!!,
            displayName = this.displayName!!,
            avatarUrl = this.avatarUrl,
            createdAt = parseTimestamp(this.createdAt),
            lastSeen = parseTimestamp(this.lastSeen)
        )
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}