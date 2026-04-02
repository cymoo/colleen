package service

import model.Room
import model.RoomInfo
import org.jooq.DSLContext
import repository.RoomRepository

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

    fun createRoom(name: String, description: String?): Room {
        return roomRepo.create(name, description)
    }
}