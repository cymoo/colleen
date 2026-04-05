package repository

import chatroom.jooq.generated.Tables.ROOMS
import chatroom.jooq.generated.Tables.ROOM_MEMBERS
import chatroom.jooq.generated.tables.records.RoomsRecord
import model.Room
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class RoomRepository(private val dsl: DSLContext) {

    fun findAll(): List<Room> {
        return dsl.selectFrom(ROOMS)
            .fetch()
            .map { it.toModel() }
    }

    fun findById(id: Int): Room? {
        return dsl.selectFrom(ROOMS)
            .where(ROOMS.ID.eq(id))
            .fetchOne()
            ?.toModel()
    }

    fun findByName(name: String): Room? {
        return dsl.selectFrom(ROOMS)
            .where(ROOMS.NAME.eq(name))
            .fetchOne()
            ?.toModel()
    }

    fun create(name: String, description: String?, isPrivate: Boolean = false, passwordHash: String? = null, creatorId: Int? = null): Room {
        val id = dsl.insertInto(ROOMS)
            .set(ROOMS.NAME, name)
            .set(ROOMS.DESCRIPTION, description)
            .set(ROOMS.IS_PRIVATE, if (isPrivate) 1 else 0)
            .set(ROOMS.PASSWORD_HASH, passwordHash)
            .set(ROOMS.CREATOR_ID, creatorId)
            .returning(ROOMS.ID)
            .fetchOne()!!
            .get(ROOMS.ID)!!

        return findById(id)!!
    }

    /** Returns the stored password hash for a private room, null if room is public or has no password. */
    fun getPasswordHash(roomId: Int): String? {
        return dsl.select(ROOMS.PASSWORD_HASH)
            .from(ROOMS)
            .where(ROOMS.ID.eq(roomId))
            .fetchOne()
            ?.get(ROOMS.PASSWORD_HASH)
    }

    fun getOnlineUserCount(roomId: Int): Int {
        return dsl.selectCount()
            .from(ROOM_MEMBERS)
            .where(ROOM_MEMBERS.ROOM_ID.eq(roomId))
            .fetchOne(0, Int::class.java) ?: 0
    }

    private fun RoomsRecord.toModel(): Room {
        return Room(
            id = this.id!!,
            name = this.name!!,
            description = this.description,
            isPrivate = (this.isPrivate ?: 0) != 0,
            creatorId = this.creatorId,
            createdAt = parseTimestamp(this.createdAt),
            maxUsers = this.maxUsers ?: 100
        )
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}