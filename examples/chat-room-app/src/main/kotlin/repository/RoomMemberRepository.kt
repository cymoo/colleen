package repository

import chatroom.jooq.generated.Tables.ROOM_MEMBERS
import chatroom.jooq.generated.Tables.USERS
import model.User
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class RoomMemberRepository(private val dsl: DSLContext) {

    fun addMember(roomId: Int, userId: Int, role: String = "member") {
        dsl.insertInto(ROOM_MEMBERS)
            .set(ROOM_MEMBERS.ROOM_ID, roomId)
            .set(ROOM_MEMBERS.USER_ID, userId)
            .set(ROOM_MEMBERS.ROLE, role)
            .onDuplicateKeyIgnore()
            .execute()
    }

    fun removeMember(roomId: Int, userId: Int) {
        dsl.deleteFrom(ROOM_MEMBERS)
            .where(ROOM_MEMBERS.ROOM_ID.eq(roomId))
            .and(ROOM_MEMBERS.USER_ID.eq(userId))
            .execute()
    }

    fun getRoomMembers(roomId: Int): List<User> {
        return dsl.select(
            USERS.ID, USERS.USERNAME, USERS.AVATAR_URL,
            USERS.CREATED_AT, USERS.LAST_SEEN, USERS.BIO, USERS.STATUS, ROOM_MEMBERS.ROLE
        )
            .from(ROOM_MEMBERS)
            .join(USERS).on(ROOM_MEMBERS.USER_ID.eq(USERS.ID))
            .where(ROOM_MEMBERS.ROOM_ID.eq(roomId))
            .fetch()
            .map { record ->
                User(
                    id = record.get(USERS.ID)!!,
                    username = record.get(USERS.USERNAME)!!,
                    avatarUrl = record.get(USERS.AVATAR_URL),
                    bio = record.get(USERS.BIO),
                    status = record.get(USERS.STATUS),
                    role = record.get(ROOM_MEMBERS.ROLE) ?: "member",
                    createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
                    lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
                )
            }
    }

    fun getMemberRole(roomId: Int, userId: Int): String? {
        return dsl.select(ROOM_MEMBERS.ROLE)
            .from(ROOM_MEMBERS)
            .where(ROOM_MEMBERS.ROOM_ID.eq(roomId))
            .and(ROOM_MEMBERS.USER_ID.eq(userId))
            .fetchOne()?.get(ROOM_MEMBERS.ROLE)
    }

    fun updateMemberRole(roomId: Int, userId: Int, role: String) {
        dsl.update(ROOM_MEMBERS)
            .set(ROOM_MEMBERS.ROLE, role)
            .where(ROOM_MEMBERS.ROOM_ID.eq(roomId))
            .and(ROOM_MEMBERS.USER_ID.eq(userId))
            .execute()
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}