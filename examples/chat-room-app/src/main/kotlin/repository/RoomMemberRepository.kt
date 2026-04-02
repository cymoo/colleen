package repository

import chatroom.jooq.generated.Tables.ROOM_MEMBERS
import chatroom.jooq.generated.Tables.USERS
import model.User
import org.jooq.DSLContext

class RoomMemberRepository(private val dsl: DSLContext) {

    fun addMember(roomId: Int, userId: Int) {
        dsl.insertInto(ROOM_MEMBERS)
            .set(ROOM_MEMBERS.ROOM_ID, roomId)
            .set(ROOM_MEMBERS.USER_ID, userId)
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
        return dsl.select(USERS.fields().toList())
            .from(ROOM_MEMBERS)
            .join(USERS).on(ROOM_MEMBERS.USER_ID.eq(USERS.ID))
            .where(ROOM_MEMBERS.ROOM_ID.eq(roomId))
            .fetchInto(User::class.java)
    }
}