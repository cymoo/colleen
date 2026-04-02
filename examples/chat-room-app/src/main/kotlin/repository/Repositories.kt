package repository

import chatroom.jooq.generated.Tables.*
import chatroom.jooq.generated.tables.records.UsersRecord
import chatroom.jooq.generated.tables.records.RoomsRecord
import chatroom.jooq.generated.tables.records.MessagesRecord
import model.*
import org.jooq.DSLContext
import java.time.Instant

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
        val now = Instant.now().toEpochMilli()
        
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
            .set(USERS.LAST_SEEN, Instant.now().toString())
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

    private fun parseTimestamp(timestamp: String?): Long {
        return timestamp?.let { Instant.parse(it).toEpochMilli() } ?: Instant.now().toEpochMilli()
    }
}

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

    fun create(name: String, description: String?): Room {
        val record = dsl.insertInto(ROOMS)
            .set(ROOMS.NAME, name)
            .set(ROOMS.DESCRIPTION, description)
            .returningResult(ROOMS.fields().toList())
            .fetchOne()!!

        return record.toModel()
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
            createdAt = parseTimestamp(this.createdAt),
            maxUsers = this.maxUsers ?: 100
        )
    }

    private fun parseTimestamp(timestamp: String?): Long {
        return timestamp?.let { Instant.parse(it).toEpochMilli() } ?: Instant.now().toEpochMilli()
    }
}

class MessageRepository(private val dsl: DSLContext) {

    fun saveTextMessage(roomId: Int, userId: Int, content: String): Int {
        return dsl.insertInto(MESSAGES)
            .set(MESSAGES.ROOM_ID, roomId)
            .set(MESSAGES.USER_ID, userId)
            .set(MESSAGES.MESSAGE_TYPE, "text")
            .set(MESSAGES.CONTENT, content)
            .returningResult(MESSAGES.ID)
            .fetchOne()!!
            .get(MESSAGES.ID)!!
    }

    fun saveImageMessage(roomId: Int, userId: Int, imageUrl: String, thumbnailUrl: String?): Int {
        return dsl.insertInto(MESSAGES)
            .set(MESSAGES.ROOM_ID, roomId)
            .set(MESSAGES.USER_ID, userId)
            .set(MESSAGES.MESSAGE_TYPE, "image")
            .set(MESSAGES.FILE_URL, imageUrl)
            .set(MESSAGES.THUMBNAIL_URL, thumbnailUrl)
            .returningResult(MESSAGES.ID)
            .fetchOne()!!
            .get(MESSAGES.ID)!!
    }

    fun saveFileMessage(
        roomId: Int,
        userId: Int,
        fileName: String,
        fileUrl: String,
        fileSize: Long,
        mimeType: String
    ): Int {
        return dsl.insertInto(MESSAGES)
            .set(MESSAGES.ROOM_ID, roomId)
            .set(MESSAGES.USER_ID, userId)
            .set(MESSAGES.MESSAGE_TYPE, "file")
            .set(MESSAGES.FILE_NAME, fileName)
            .set(MESSAGES.FILE_URL, fileUrl)
            .set(MESSAGES.FILE_SIZE, fileSize.toInt())
            .set(MESSAGES.MIME_TYPE, mimeType)
            .returningResult(MESSAGES.ID)
            .fetchOne()!!
            .get(MESSAGES.ID)!!
    }

    fun saveSystemMessage(roomId: Int, content: String): Int {
        return dsl.insertInto(MESSAGES)
            .set(MESSAGES.ROOM_ID, roomId)
            .set(MESSAGES.USER_ID, 0) // System user
            .set(MESSAGES.MESSAGE_TYPE, "system")
            .set(MESSAGES.CONTENT, content)
            .returningResult(MESSAGES.ID)
            .fetchOne()!!
            .get(MESSAGES.ID)!!
    }

    fun getRecentMessages(roomId: Int, limit: Int = 100): List<ChatMessage> {
        val records = dsl.select(
            MESSAGES.ID,
            MESSAGES.ROOM_ID,
            MESSAGES.USER_ID,
            MESSAGES.MESSAGE_TYPE,
            MESSAGES.CONTENT,
            MESSAGES.FILE_URL,
            MESSAGES.FILE_NAME,
            MESSAGES.FILE_SIZE,
            MESSAGES.MIME_TYPE,
            MESSAGES.THUMBNAIL_URL,
            MESSAGES.CREATED_AT,
            USERS.USERNAME,
            USERS.DISPLAY_NAME,
            USERS.AVATAR_URL
        )
            .from(MESSAGES)
            .leftJoin(USERS).on(MESSAGES.USER_ID.eq(USERS.ID))
            .where(MESSAGES.ROOM_ID.eq(roomId))
            .orderBy(MESSAGES.CREATED_AT.desc())
            .limit(limit)
            .fetch()

        return records.reversed().map { record ->
            val messageType = record.get(MESSAGES.MESSAGE_TYPE)!!
            val messageId = record.get(MESSAGES.ID)!!
            val timestamp = parseTimestamp(record.get(MESSAGES.CREATED_AT))
            val userId = record.get(MESSAGES.USER_ID)!!
            val username = record.get(USERS.USERNAME) ?: "system"
            val displayName = record.get(USERS.DISPLAY_NAME) ?: "System"
            val avatarUrl = record.get(USERS.AVATAR_URL)

            when (messageType) {
                "text" -> ChatMessage.Text(
                    id = messageId,
                    userId = userId,
                    username = username,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    content = record.get(MESSAGES.CONTENT) ?: "",
                    timestamp = timestamp
                )
                "image" -> ChatMessage.Image(
                    id = messageId,
                    userId = userId,
                    username = username,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    imageUrl = record.get(MESSAGES.FILE_URL) ?: "",
                    thumbnailUrl = record.get(MESSAGES.THUMBNAIL_URL),
                    timestamp = timestamp
                )
                "file" -> ChatMessage.File(
                    id = messageId,
                    userId = userId,
                    username = username,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    fileName = record.get(MESSAGES.FILE_NAME) ?: "",
                    fileUrl = record.get(MESSAGES.FILE_URL) ?: "",
                    fileSize = record.get(MESSAGES.FILE_SIZE)?.toLong() ?: 0L,
                    mimeType = record.get(MESSAGES.MIME_TYPE) ?: "application/octet-stream",
                    timestamp = timestamp
                )
                "system" -> ChatMessage.System(
                    id = messageId,
                    content = record.get(MESSAGES.CONTENT) ?: "",
                    timestamp = timestamp
                )
                else -> throw IllegalStateException("Unknown message type: $messageType")
            }
        }
    }

    private fun parseTimestamp(timestamp: String?): Long {
        return timestamp?.let { Instant.parse(it).toEpochMilli() } ?: Instant.now().toEpochMilli()
    }
}

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
            .fetch()
            .map { record ->
                User(
                    id = record.get(USERS.ID)!!,
                    username = record.get(USERS.USERNAME)!!,
                    displayName = record.get(USERS.DISPLAY_NAME)!!,
                    avatarUrl = record.get(USERS.AVATAR_URL),
                    createdAt = parseTimestamp(record.get(USERS.CREATED_AT)),
                    lastSeen = parseTimestamp(record.get(USERS.LAST_SEEN))
                )
            }
    }

    private fun parseTimestamp(timestamp: String?): Long {
        return timestamp?.let { Instant.parse(it).toEpochMilli() } ?: Instant.now().toEpochMilli()
    }
}
