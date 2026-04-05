package repository

import chatroom.jooq.generated.Tables.MESSAGES
import chatroom.jooq.generated.Tables.USERS
import model.ChatMessage
import model.ReplyInfo
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class MessageRepository(private val dsl: DSLContext) {

    fun saveTextMessage(roomId: Int, userId: Int, content: String, replyToId: Int? = null): Int {
        var step = dsl.insertInto(MESSAGES)
            .set(MESSAGES.ROOM_ID, roomId)
            .set(MESSAGES.USER_ID, userId)
            .set(MESSAGES.MESSAGE_TYPE, "text")
            .set(MESSAGES.CONTENT, content)

        if (replyToId != null) {
            step = step.set(MESSAGES.REPLY_TO_ID, replyToId)
        }

        return step.returningResult(MESSAGES.ID)
            .fetchOne()!!
            .get(MESSAGES.ID)!!
    }

    fun saveImageMessage(roomId: Int, userId: Int, imageUrl: String, thumbnailUrl: String?, replyToId: Int? = null): Int {
        var step = dsl.insertInto(MESSAGES)
            .set(MESSAGES.ROOM_ID, roomId)
            .set(MESSAGES.USER_ID, userId)
            .set(MESSAGES.MESSAGE_TYPE, "image")
            .set(MESSAGES.FILE_URL, imageUrl)
            .set(MESSAGES.THUMBNAIL_URL, thumbnailUrl)

        if (replyToId != null) {
            step = step.set(MESSAGES.REPLY_TO_ID, replyToId)
        }

        return step.returningResult(MESSAGES.ID)
            .fetchOne()!!
            .get(MESSAGES.ID)!!
    }

    fun saveFileMessage(
        roomId: Int,
        userId: Int,
        fileName: String,
        fileUrl: String,
        fileSize: Long,
        mimeType: String,
        replyToId: Int? = null
    ): Int {
        var step = dsl.insertInto(MESSAGES)
            .set(MESSAGES.ROOM_ID, roomId)
            .set(MESSAGES.USER_ID, userId)
            .set(MESSAGES.MESSAGE_TYPE, "file")
            .set(MESSAGES.FILE_NAME, fileName)
            .set(MESSAGES.FILE_URL, fileUrl)
            .set(MESSAGES.FILE_SIZE, fileSize.toInt())
            .set(MESSAGES.MIME_TYPE, mimeType)

        if (replyToId != null) {
            step = step.set(MESSAGES.REPLY_TO_ID, replyToId)
        }

        return step.returningResult(MESSAGES.ID)
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

    fun updateMessage(messageId: Int, userId: Int, content: String): Boolean {
        val updated = dsl.update(MESSAGES)
            .set(MESSAGES.CONTENT, content)
            .set(MESSAGES.EDITED_AT, LocalDateTime.now(ZoneOffset.UTC))
            .where(MESSAGES.ID.eq(messageId))
            .and(MESSAGES.USER_ID.eq(userId))
            .and(MESSAGES.MESSAGE_TYPE.eq("text"))
            .execute()
        return updated > 0
    }

    fun softDeleteMessage(messageId: Int, userId: Int): Boolean {
        val updated = dsl.update(MESSAGES)
            .set(MESSAGES.IS_DELETED, 1)
            .where(MESSAGES.ID.eq(messageId))
            .and(MESSAGES.USER_ID.eq(userId))
            .execute()
        return updated > 0
    }

    fun adminDeleteMessage(messageId: Int): Boolean {
        val updated = dsl.update(MESSAGES)
            .set(MESSAGES.IS_DELETED, 1)
            .where(MESSAGES.ID.eq(messageId))
            .execute()
        return updated > 0
    }

    fun getRecentMessages(roomId: Int, limit: Int = 30): List<ChatMessage> {
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
            MESSAGES.EDITED_AT,
            MESSAGES.IS_DELETED,
            MESSAGES.REPLY_TO_ID,
            USERS.USERNAME,
            USERS.AVATAR_URL
        )
            .from(MESSAGES)
            .leftJoin(USERS).on(MESSAGES.USER_ID.eq(USERS.ID))
            .where(MESSAGES.ROOM_ID.eq(roomId))
            .and(MESSAGES.MESSAGE_TYPE.ne("system"))
            .and(MESSAGES.IS_DELETED.eq(0).or(MESSAGES.IS_DELETED.isNull))
            .orderBy(MESSAGES.CREATED_AT.desc())
            .limit(limit)
            .fetch()

        return records.reversed().map { record ->
            val replyToId = record.get(MESSAGES.REPLY_TO_ID)
            val replyInfo = if (replyToId != null) getReplyInfo(replyToId) else null
            mapRecordToMessage(record, 0, replyInfo)
        }
    }

    fun getMessagesBefore(roomId: Int, beforeId: Int, limit: Int = 30): List<ChatMessage> {
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
            MESSAGES.EDITED_AT,
            MESSAGES.IS_DELETED,
            MESSAGES.REPLY_TO_ID,
            USERS.USERNAME,
            USERS.AVATAR_URL
        )
            .from(MESSAGES)
            .leftJoin(USERS).on(MESSAGES.USER_ID.eq(USERS.ID))
            .where(MESSAGES.ROOM_ID.eq(roomId))
            .and(MESSAGES.MESSAGE_TYPE.ne("system"))
            .and(MESSAGES.IS_DELETED.eq(0).or(MESSAGES.IS_DELETED.isNull))
            .and(MESSAGES.ID.lt(beforeId))
            .orderBy(MESSAGES.CREATED_AT.desc())
            .limit(limit)
            .fetch()

        return records.reversed().map { record ->
            val replyToId = record.get(MESSAGES.REPLY_TO_ID)
            val replyInfo = if (replyToId != null) getReplyInfo(replyToId) else null
            mapRecordToMessage(record, 0, replyInfo)
        }
    }

    fun searchMessages(roomId: Int, query: String, limit: Int = 50): List<ChatMessage> {
        // Escape SQL LIKE special characters
        val escapedQuery = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

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
            MESSAGES.EDITED_AT,
            MESSAGES.IS_DELETED,
            MESSAGES.REPLY_TO_ID,
            USERS.USERNAME,
            USERS.AVATAR_URL
        )
            .from(MESSAGES)
            .leftJoin(USERS).on(MESSAGES.USER_ID.eq(USERS.ID))
            .where(MESSAGES.ROOM_ID.eq(roomId))
            .and(MESSAGES.MESSAGE_TYPE.ne("system"))
            .and(MESSAGES.IS_DELETED.eq(0).or(MESSAGES.IS_DELETED.isNull))
            .and(MESSAGES.CONTENT.likeIgnoreCase("%$escapedQuery%"))
            .orderBy(MESSAGES.CREATED_AT.desc())
            .limit(limit)
            .fetch()

        return records.reversed().map { record ->
            mapRecordToMessage(record, 0, null)
        }
    }

    fun getReplyInfo(messageId: Int): ReplyInfo? {
        val record = dsl.select(
            MESSAGES.ID,
            MESSAGES.MESSAGE_TYPE,
            MESSAGES.CONTENT,
            MESSAGES.FILE_NAME,
            USERS.USERNAME
        )
            .from(MESSAGES)
            .leftJoin(USERS).on(MESSAGES.USER_ID.eq(USERS.ID))
            .where(MESSAGES.ID.eq(messageId))
            .fetchOne() ?: return null

        val msgType = record.get(MESSAGES.MESSAGE_TYPE) ?: "text"
        val content = when (msgType) {
            "text" -> record.get(MESSAGES.CONTENT) ?: ""
            "image" -> "[Image]"
            "file" -> record.get(MESSAGES.FILE_NAME) ?: "[File]"
            else -> ""
        }

        return ReplyInfo(
            id = record.get(MESSAGES.ID)!!,
            username = record.get(USERS.USERNAME) ?: "unknown",
            content = if (content.length > 100) content.take(100) + "..." else content,
            messageType = msgType
        )
    }

    private fun mapRecordToMessage(
        record: org.jooq.Record,
        isDeleted: Int,
        replyInfo: ReplyInfo?
    ): ChatMessage {
        val messageType = record.get(MESSAGES.MESSAGE_TYPE)!!
        val messageId = record.get(MESSAGES.ID)!!
        val timestamp = parseTimestamp(record.get(MESSAGES.CREATED_AT))
        val userId = record.get(MESSAGES.USER_ID)!!
        val username = record.get(USERS.USERNAME) ?: "system"
        val avatarUrl = record.get(USERS.AVATAR_URL)
        val editedAt = record.get(MESSAGES.EDITED_AT)?.let { parseTimestamp(it) }

        return when (messageType) {
            "text" -> ChatMessage.Text(
                id = messageId,
                userId = userId,
                username = username,
                avatarUrl = avatarUrl,
                content = record.get(MESSAGES.CONTENT) ?: "",
                editedAt = editedAt,
                timestamp = timestamp,
                replyTo = replyInfo
            )
            "image" -> ChatMessage.Image(
                id = messageId,
                userId = userId,
                username = username,
                avatarUrl = avatarUrl,
                imageUrl = record.get(MESSAGES.FILE_URL) ?: "",
                thumbnailUrl = record.get(MESSAGES.THUMBNAIL_URL),
                timestamp = timestamp,
                replyTo = replyInfo
            )
            "file" -> ChatMessage.File(
                id = messageId,
                userId = userId,
                username = username,
                avatarUrl = avatarUrl,
                fileName = record.get(MESSAGES.FILE_NAME) ?: "",
                fileUrl = record.get(MESSAGES.FILE_URL) ?: "",
                fileSize = record.get(MESSAGES.FILE_SIZE)?.toLong() ?: 0L,
                mimeType = record.get(MESSAGES.MIME_TYPE) ?: "application/octet-stream",
                timestamp = timestamp,
                replyTo = replyInfo
            )
            "system" -> ChatMessage.System(
                id = messageId,
                content = record.get(MESSAGES.CONTENT) ?: "",
                timestamp = timestamp,
                replyTo = null
            )
            else -> throw IllegalStateException("Unknown message type: $messageType")
        }
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}