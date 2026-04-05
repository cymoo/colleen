package repository

import chatroom.jooq.generated.Tables.PRIVATE_MESSAGES
import chatroom.jooq.generated.Tables.USERS
import model.PrivateMessage
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class PrivateMessageRepository(private val dsl: DSLContext) {

    private val SENDER = USERS.`as`("sender")
    private val RECEIVER = USERS.`as`("receiver")

    fun saveMessage(senderId: Int, receiverId: Int, messageType: String, content: String?): Int {
        return dsl.insertInto(PRIVATE_MESSAGES)
            .set(PRIVATE_MESSAGES.SENDER_ID, senderId)
            .set(PRIVATE_MESSAGES.RECEIVER_ID, receiverId)
            .set(PRIVATE_MESSAGES.MESSAGE_TYPE, messageType)
            .set(PRIVATE_MESSAGES.CONTENT, content)
            .returningResult(PRIVATE_MESSAGES.ID)
            .fetchOne()!!
            .get(PRIVATE_MESSAGES.ID)!!
    }

    fun getConversation(userId1: Int, userId2: Int, limit: Int = 30, beforeId: Int? = null): List<PrivateMessage> {
        var query = dsl.select(
            PRIVATE_MESSAGES.ID, PRIVATE_MESSAGES.SENDER_ID, PRIVATE_MESSAGES.RECEIVER_ID,
            PRIVATE_MESSAGES.MESSAGE_TYPE, PRIVATE_MESSAGES.CONTENT, PRIVATE_MESSAGES.FILE_URL,
            PRIVATE_MESSAGES.FILE_NAME, PRIVATE_MESSAGES.FILE_SIZE, PRIVATE_MESSAGES.MIME_TYPE,
            PRIVATE_MESSAGES.THUMBNAIL_URL, PRIVATE_MESSAGES.IS_READ, PRIVATE_MESSAGES.CREATED_AT,
            SENDER.USERNAME, SENDER.AVATAR_URL,
            RECEIVER.USERNAME
        )
            .from(PRIVATE_MESSAGES)
            .join(SENDER).on(PRIVATE_MESSAGES.SENDER_ID.eq(SENDER.ID))
            .join(RECEIVER).on(PRIVATE_MESSAGES.RECEIVER_ID.eq(RECEIVER.ID))
            .where(
                PRIVATE_MESSAGES.SENDER_ID.eq(userId1).and(PRIVATE_MESSAGES.RECEIVER_ID.eq(userId2))
                    .or(PRIVATE_MESSAGES.SENDER_ID.eq(userId2).and(PRIVATE_MESSAGES.RECEIVER_ID.eq(userId1)))
            )

        if (beforeId != null) {
            query = query.and(PRIVATE_MESSAGES.ID.lt(beforeId))
        }

        val records = query.orderBy(PRIVATE_MESSAGES.CREATED_AT.desc())
            .limit(limit)
            .fetch()

        return records.reversed().map { record ->
            PrivateMessage(
                id = record.get(PRIVATE_MESSAGES.ID)!!,
                senderId = record.get(PRIVATE_MESSAGES.SENDER_ID)!!,
                senderUsername = record.get(SENDER.USERNAME) ?: "unknown",
                senderAvatarUrl = record.get(SENDER.AVATAR_URL),
                receiverId = record.get(PRIVATE_MESSAGES.RECEIVER_ID)!!,
                receiverUsername = record.get(RECEIVER.USERNAME) ?: "unknown",
                messageType = record.get(PRIVATE_MESSAGES.MESSAGE_TYPE) ?: "text",
                content = record.get(PRIVATE_MESSAGES.CONTENT),
                fileUrl = record.get(PRIVATE_MESSAGES.FILE_URL),
                fileName = record.get(PRIVATE_MESSAGES.FILE_NAME),
                fileSize = record.get(PRIVATE_MESSAGES.FILE_SIZE)?.toLong(),
                mimeType = record.get(PRIVATE_MESSAGES.MIME_TYPE),
                thumbnailUrl = record.get(PRIVATE_MESSAGES.THUMBNAIL_URL),
                isRead = (record.get(PRIVATE_MESSAGES.IS_READ) ?: 0) == 1,
                timestamp = parseTimestamp(record.get(PRIVATE_MESSAGES.CREATED_AT))
            )
        }
    }

    fun markAsRead(senderId: Int, receiverId: Int) {
        dsl.update(PRIVATE_MESSAGES)
            .set(PRIVATE_MESSAGES.IS_READ, 1)
            .where(PRIVATE_MESSAGES.SENDER_ID.eq(senderId))
            .and(PRIVATE_MESSAGES.RECEIVER_ID.eq(receiverId))
            .and(PRIVATE_MESSAGES.IS_READ.eq(0))
            .execute()
    }

    fun getUnreadCount(userId: Int): Int {
        return dsl.selectCount()
            .from(PRIVATE_MESSAGES)
            .where(PRIVATE_MESSAGES.RECEIVER_ID.eq(userId))
            .and(PRIVATE_MESSAGES.IS_READ.eq(0))
            .fetchOne(0, Int::class.java) ?: 0
    }

    fun getUnreadSenders(userId: Int): List<Map<String, Any>> {
        val unreadCount = dsl.selectCount().from(PRIVATE_MESSAGES)
            .where(PRIVATE_MESSAGES.SENDER_ID.eq(SENDER.ID))
            .and(PRIVATE_MESSAGES.RECEIVER_ID.eq(userId))
            .and(PRIVATE_MESSAGES.IS_READ.eq(0))
            .asField<Int>("unread_count")

        return dsl.select(SENDER.ID, SENDER.USERNAME, SENDER.AVATAR_URL, unreadCount)
            .from(PRIVATE_MESSAGES)
            .join(SENDER).on(PRIVATE_MESSAGES.SENDER_ID.eq(SENDER.ID))
            .where(PRIVATE_MESSAGES.RECEIVER_ID.eq(userId))
            .and(PRIVATE_MESSAGES.IS_READ.eq(0))
            .groupBy(SENDER.ID, SENDER.USERNAME, SENDER.AVATAR_URL)
            .orderBy(unreadCount.desc())
            .fetch()
            .map { r ->
                mapOf(
                    "userId" to r.get(SENDER.ID)!!,
                    "username" to (r.get(SENDER.USERNAME) ?: ""),
                    "avatarUrl" to (r.get(SENDER.AVATAR_URL) ?: ""),
                    "unreadCount" to (r.get(unreadCount) ?: 0)
                )
            }
    }

    private fun parseTimestamp(timestamp: LocalDateTime?): Long {
        return timestamp?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            ?: Instant.now().toEpochMilli()
    }
}
