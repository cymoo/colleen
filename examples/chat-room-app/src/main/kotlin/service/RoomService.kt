package service

import model.Room
import model.RoomInfo
import org.jooq.DSLContext
import repository.RoomRepository
import util.PasswordUtils

/**
 * Room management service
 */
class RoomService(dsl: DSLContext) {

    private val roomRepo = RoomRepository(dsl)

    fun getAllRooms(): List<RoomInfo> {
        return roomRepo.findAll().map { room ->
            RoomInfo(
                id = room.id,
                name = room.name,
                description = room.description,
                isPrivate = room.isPrivate,
                creatorId = room.creatorId,
                onlineUsers = roomRepo.getOnlineUserCount(room.id),
                maxUsers = room.maxUsers
            )
        }
    }

    fun getRoomById(id: Int): Room? {
        return roomRepo.findById(id)
    }

    fun getRoomByName(name: String): Room? {
        return roomRepo.findByName(name)
    }

    fun createRoom(name: String, description: String?, isPrivate: Boolean = false, password: String? = null, creatorId: Int? = null): Room {
        require(name.isNotBlank()) { "Room name is required" }
        if (isPrivate) require(!password.isNullOrBlank()) { "Private rooms require a password" }
        val passwordHash = if (!password.isNullOrBlank()) PasswordUtils.hashPassword(password) else null
        return roomRepo.create(name, description, isPrivate, passwordHash, creatorId)
    }

    /** Verifies the password for a private room. Returns true if correct or room is public. */
    fun verifyRoomPassword(roomId: Int, password: String?): Boolean {
        val room = roomRepo.findById(roomId) ?: return false
        if (!room.isPrivate) return true
        if (password.isNullOrBlank()) return false
        val storedHash = roomRepo.getPasswordHash(roomId) ?: return false
        return PasswordUtils.verifyPassword(password, storedHash)
    }
}