package repository

import chatroom.jooq.generated.Tables.MESSAGES
import chatroom.jooq.generated.Tables.USERS
import model.ChatMessage
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

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
            .set(MESSAGES.USER_ID, 0)
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
            .and(MESSAGES.MESSAGE_TYPE.ne("system"))
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

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}