package repository

import model.PrivateMessage
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class PrivateMessageRepository(private val dsl: DSLContext) {

    private val PM = DSL.table("private_messages")
    private val PM_ID = DSL.field("private_messages.id", Int::class.java)
    private val PM_SENDER_ID = DSL.field("private_messages.sender_id", Int::class.java)
    private val PM_RECEIVER_ID = DSL.field("private_messages.receiver_id", Int::class.java)
    private val PM_MESSAGE_TYPE = DSL.field("private_messages.message_type", String::class.java)
    private val PM_CONTENT = DSL.field("private_messages.content", String::class.java)
    private val PM_FILE_URL = DSL.field("private_messages.file_url", String::class.java)
    private val PM_FILE_NAME = DSL.field("private_messages.file_name", String::class.java)
    private val PM_FILE_SIZE = DSL.field("private_messages.file_size", Int::class.java)
    private val PM_MIME_TYPE = DSL.field("private_messages.mime_type", String::class.java)
    private val PM_THUMBNAIL_URL = DSL.field("private_messages.thumbnail_url", String::class.java)
    private val PM_IS_READ = DSL.field("private_messages.is_read", Int::class.java)
    private val PM_CREATED_AT = DSL.field("private_messages.created_at", LocalDateTime::class.java)

    private val SENDER = DSL.table("users").`as`("sender")
    private val SENDER_USERNAME = DSL.field("sender.username", String::class.java)
    private val SENDER_DISPLAY_NAME = DSL.field("sender.display_name", String::class.java)
    private val SENDER_AVATAR_URL = DSL.field("sender.avatar_url", String::class.java)
    private val SENDER_ID_FK = DSL.field("sender.id", Int::class.java)

    private val RECEIVER = DSL.table("users").`as`("receiver")
    private val RECEIVER_USERNAME = DSL.field("receiver.username", String::class.java)
    private val RECEIVER_DISPLAY_NAME = DSL.field("receiver.display_name", String::class.java)
    private val RECEIVER_ID_FK = DSL.field("receiver.id", Int::class.java)

    fun saveMessage(senderId: Int, receiverId: Int, messageType: String, content: String?): Int {
        return dsl.insertInto(PM)
            .set(PM_SENDER_ID, senderId)
            .set(PM_RECEIVER_ID, receiverId)
            .set(PM_MESSAGE_TYPE, messageType)
            .set(PM_CONTENT, content)
            .returningResult(PM_ID)
            .fetchOne()!!
            .get(PM_ID)!!
    }

    fun getConversation(userId1: Int, userId2: Int, limit: Int = 30, beforeId: Int? = null): List<PrivateMessage> {
        var query = dsl.select(
            PM_ID, PM_SENDER_ID, PM_RECEIVER_ID, PM_MESSAGE_TYPE,
            PM_CONTENT, PM_FILE_URL, PM_FILE_NAME, PM_FILE_SIZE,
            PM_MIME_TYPE, PM_THUMBNAIL_URL, PM_IS_READ, PM_CREATED_AT,
            SENDER_USERNAME, SENDER_DISPLAY_NAME, SENDER_AVATAR_URL,
            RECEIVER_USERNAME, RECEIVER_DISPLAY_NAME
        )
            .from(PM)
            .join(SENDER).on(PM_SENDER_ID.eq(SENDER_ID_FK))
            .join(RECEIVER).on(PM_RECEIVER_ID.eq(RECEIVER_ID_FK))
            .where(
                PM_SENDER_ID.eq(userId1).and(PM_RECEIVER_ID.eq(userId2))
                    .or(PM_SENDER_ID.eq(userId2).and(PM_RECEIVER_ID.eq(userId1)))
            )

        if (beforeId != null) {
            query = query.and(PM_ID.lt(beforeId))
        }

        val records = query.orderBy(PM_CREATED_AT.desc())
            .limit(limit)
            .fetch()

        return records.reversed().map { record ->
            PrivateMessage(
                id = record.get(PM_ID)!!,
                senderId = record.get(PM_SENDER_ID)!!,
                senderUsername = record.get(SENDER_USERNAME) ?: "unknown",
                senderDisplayName = record.get(SENDER_DISPLAY_NAME) ?: "Unknown",
                senderAvatarUrl = record.get(SENDER_AVATAR_URL),
                receiverId = record.get(PM_RECEIVER_ID)!!,
                receiverUsername = record.get(RECEIVER_USERNAME) ?: "unknown",
                receiverDisplayName = record.get(RECEIVER_DISPLAY_NAME) ?: "Unknown",
                messageType = record.get(PM_MESSAGE_TYPE) ?: "text",
                content = record.get(PM_CONTENT),
                fileUrl = record.get(PM_FILE_URL),
                fileName = record.get(PM_FILE_NAME),
                fileSize = record.get(PM_FILE_SIZE)?.toLong(),
                mimeType = record.get(PM_MIME_TYPE),
                thumbnailUrl = record.get(PM_THUMBNAIL_URL),
                isRead = (record.get(PM_IS_READ) ?: 0) == 1,
                timestamp = parseTimestamp(record.get(PM_CREATED_AT))
            )
        }
    }

    fun markAsRead(senderId: Int, receiverId: Int) {
        dsl.update(PM)
            .set(PM_IS_READ, 1)
            .where(PM_SENDER_ID.eq(senderId))
            .and(PM_RECEIVER_ID.eq(receiverId))
            .and(PM_IS_READ.eq(0))
            .execute()
    }

    fun getUnreadCount(userId: Int): Int {
        return dsl.selectCount()
            .from(PM)
            .where(PM_RECEIVER_ID.eq(userId))
            .and(PM_IS_READ.eq(0))
            .fetchOne(0, Int::class.java) ?: 0
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}
