package service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cymoo.colleen.ws.WsConnection
import model.*
import org.jooq.DSLContext
import repository.MessageRepository
import repository.PrivateMessageRepository
import repository.RoomMemberRepository
import repository.UserRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Chat service managing rooms, connections, and message broadcasting
 */
class ChatService(dsl: DSLContext, private val objectMapper: ObjectMapper) {

    private val messageRepo = MessageRepository(dsl)
    private val roomMemberRepo = RoomMemberRepository(dsl)
    private val userRepo = UserRepository(dsl)
    private val privateMessageRepo = PrivateMessageRepository(dsl)

    // Room ID -> Set of WS connections
    private val roomConnections = ConcurrentHashMap<Int, CopyOnWriteArraySet<WsConnection>>()

    // User ID -> Connection
    private val userConnections = ConcurrentHashMap<Int, WsConnection>()

    // Broadcast executor for async message sending
    private val broadcastExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "chat-broadcast"
        }
    }

    fun joinRoom(roomId: Int, connection: WsConnection, user: User) {
        // Add to connection tracking
        val connections = roomConnections.computeIfAbsent(roomId) { CopyOnWriteArraySet() }
        connections.add(connection)
        userConnections[user.id] = connection

        // Add to database
        roomMemberRepo.addMember(roomId, user.id)
        userRepo.updateLastSeen(user.id)

        // Save system message
        val systemMessageId = messageRepo.saveSystemMessage(
            roomId,
            "${user.username} joined the room"
        )

        // Broadcast join event
        val joinMessage = ChatMessage.System(
            id = systemMessageId,
            content = "${user.username} joined the room",
            timestamp = System.currentTimeMillis()
        )

        broadcastToRoom(roomId, WsEvent.Message(joinMessage))
        broadcastToRoom(roomId, WsEvent.UserJoined(user))
    }

    fun leaveRoom(roomId: Int, user: User, connection: WsConnection) {
        // Remove from tracking
        roomConnections[roomId]?.remove(connection)
        userConnections.remove(user.id)

        // Remove from database
        roomMemberRepo.removeMember(roomId, user.id)

        // Clean up empty room connections
        if (roomConnections[roomId]?.isEmpty() == true) {
            roomConnections.remove(roomId)
        }

        // Save system message
        val systemMessageId = messageRepo.saveSystemMessage(
            roomId,
            "${user.username} left the room"
        )

        // Broadcast leave event
        val leaveMessage = ChatMessage.System(
            id = systemMessageId,
            content = "${user.username} left the room",
            timestamp = System.currentTimeMillis()
        )

        broadcastToRoom(roomId, WsEvent.Message(leaveMessage))
        broadcastToRoom(roomId, WsEvent.UserLeft(user.id, user.username))
    }

    fun sendTextMessage(roomId: Int, user: User, content: String, replyToId: Int? = null) {
        val messageId = messageRepo.saveTextMessage(roomId, user.id, content, replyToId)

        val replyInfo = if (replyToId != null) getReplyInfoById(replyToId) else null

        val message = ChatMessage.Text(
            id = messageId,
            userId = user.id,
            username = user.username,
            avatarUrl = user.avatarUrl,
            content = content,
            timestamp = System.currentTimeMillis(),
            replyTo = replyInfo
        )

        broadcastToRoom(roomId, WsEvent.Message(message))

        // Parse mentions
        parseMentions(content, roomId, messageId, user.username)
    }

    fun sendImageMessage(roomId: Int, user: User, imageUrl: String, thumbnailUrl: String?, replyToId: Int? = null) {
        val messageId = messageRepo.saveImageMessage(roomId, user.id, imageUrl, thumbnailUrl, replyToId)

        val replyInfo = if (replyToId != null) getReplyInfoById(replyToId) else null

        val message = ChatMessage.Image(
            id = messageId,
            userId = user.id,
            username = user.username,
            avatarUrl = user.avatarUrl,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            timestamp = System.currentTimeMillis(),
            replyTo = replyInfo
        )

        broadcastToRoom(roomId, WsEvent.Message(message))
    }

    fun sendFileMessage(
        roomId: Int,
        user: User,
        fileName: String,
        fileUrl: String,
        fileSize: Long,
        mimeType: String,
        replyToId: Int? = null
    ) {
        val messageId = messageRepo.saveFileMessage(
            roomId, user.id, fileName, fileUrl, fileSize, mimeType, replyToId
        )

        val replyInfo = if (replyToId != null) getReplyInfoById(replyToId) else null

        val message = ChatMessage.File(
            id = messageId,
            userId = user.id,
            username = user.username,
            avatarUrl = user.avatarUrl,
            fileName = fileName,
            fileUrl = fileUrl,
            fileSize = fileSize,
            mimeType = mimeType,
            timestamp = System.currentTimeMillis(),
            replyTo = replyInfo
        )

        broadcastToRoom(roomId, WsEvent.Message(message))
    }

    fun editMessage(roomId: Int, user: User, messageId: Int, newContent: String) {
        if (messageRepo.updateMessage(messageId, user.id, newContent)) {
            val editedAt = System.currentTimeMillis()
            broadcastToRoom(roomId, WsEvent.MessageEdited(messageId, newContent, editedAt))
        }
    }

    fun deleteMessage(roomId: Int, user: User, messageId: Int) {
        // Check if user is admin/moderator or message owner
        val role = roomMemberRepo.getMemberRole(roomId, user.id)
        val deleted = if (role in listOf("owner", "admin", "moderator")) {
            messageRepo.adminDeleteMessage(messageId)
        } else {
            messageRepo.softDeleteMessage(messageId, user.id)
        }

        if (deleted) {
            broadcastToRoom(roomId, WsEvent.MessageDeleted(messageId))
        }
    }

    fun getMessageHistory(roomId: Int): List<ChatMessage> {
        return messageRepo.getRecentMessages(roomId, 30)
    }

    fun getOlderMessages(roomId: Int, beforeId: Int, limit: Int = 30): Pair<List<ChatMessage>, Boolean> {
        val messages = messageRepo.getMessagesBefore(roomId, beforeId, limit + 1)
        val hasMore = messages.size > limit
        return Pair(if (hasMore) messages.drop(1) else messages, hasMore)
    }

    fun searchMessages(roomId: Int, query: String): List<ChatMessage> {
        return messageRepo.searchMessages(roomId, query)
    }

    fun getRoomUsers(roomId: Int): List<User> {
        return roomMemberRepo.getRoomMembers(roomId)
    }

    // Private messaging
    fun sendPrivateMessage(sender: User, receiverId: Int, content: String) {
        val messageId = privateMessageRepo.saveMessage(sender.id, receiverId, "text", content)
        val receiver = userRepo.findById(receiverId) ?: return

        val pm = PrivateMessage(
            id = messageId,
            senderId = sender.id,
            senderUsername = sender.username,
            senderAvatarUrl = sender.avatarUrl,
            receiverId = receiverId,
            receiverUsername = receiver.username,
            messageType = "text",
            content = content,
            timestamp = System.currentTimeMillis()
        )

        // Send to receiver
        val receiverConn = userConnections[receiverId]
        if (receiverConn != null) {
            runCatching { receiverConn.send(serializeEvent(WsEvent.PrivateMessageEvent(pm))) }
            // Receiver is online — push updated unread count immediately
            runCatching {
                receiverConn.send(serializeEvent(WsEvent.UnreadCounts(privateMessageRepo.getUnreadCount(receiverId))))
            }
        }
        // If offline — unread count will be picked up on next connect

        // Send to sender (confirmation)
        val senderConn = userConnections[sender.id]
        if (senderConn != null) {
            runCatching { senderConn.send(serializeEvent(WsEvent.PrivateMessageEvent(pm))) }
        }
    }

    fun getPrivateHistory(userId1: Int, userId2: Int, beforeId: Int? = null): Pair<List<PrivateMessage>, Boolean> {
        val messages = privateMessageRepo.getConversation(userId1, userId2, 31, beforeId)
        val hasMore = messages.size > 30
        // Mark messages from userId2 to userId1 as read
        privateMessageRepo.markAsRead(userId2, userId1)
        // Notify sender of updated unread count
        sendToUser(userId1, WsEvent.UnreadCounts(privateMessageRepo.getUnreadCount(userId1)))
        return Pair(if (hasMore) messages.drop(1) else messages, hasMore)
    }

    fun getUnreadDmCount(userId: Int): Int = privateMessageRepo.getUnreadCount(userId)

    fun getUnreadDmSenders(userId: Int): List<Map<String, Any>> = privateMessageRepo.getUnreadSenders(userId)

    // Room permissions
    fun setUserRole(roomId: Int, actorUser: User, targetUserId: Int, role: String) {
        val actorRole = roomMemberRepo.getMemberRole(roomId, actorUser.id) ?: return
        if (actorRole !in listOf("owner", "admin")) return
        if (role !in listOf("admin", "moderator", "member")) return

        roomMemberRepo.updateMemberRole(roomId, targetUserId, role)
        broadcastToRoom(roomId, WsEvent.RoleChanged(targetUserId, role))
    }

    fun kickUser(roomId: Int, actorUser: User, targetUserId: Int, reason: String = "Kicked by admin") {
        val actorRole = roomMemberRepo.getMemberRole(roomId, actorUser.id) ?: return
        if (actorRole !in listOf("owner", "admin", "moderator")) return

        val targetConn = userConnections[targetUserId]
        if (targetConn != null) {
            runCatching {
                targetConn.send(serializeEvent(WsEvent.Kicked(reason)))
                targetConn.close()
            }
        }
    }

    // User profiles — returns the refreshed user
    fun updateUserProfile(user: User, avatarUrl: String?, bio: String?, status: String?): User {
        userRepo.updateProfile(user.id, avatarUrl, bio, status)
        val updatedUser = userRepo.findById(user.id) ?: return user

        // Broadcast to all rooms the user is in
        for ((roomId, connections) in roomConnections) {
            if (connections.any { userConnections[user.id] == it }) {
                broadcastToRoom(roomId, WsEvent.UserUpdated(updatedUser))
            }
        }
        return updatedUser
    }

    private fun parseMentions(content: String, roomId: Int, messageId: Int, mentionedBy: String) {
        val mentionPattern = Regex("@(\\w+)")
        val mentions = mentionPattern.findAll(content).map { it.groupValues[1] }.toList()

        if (mentions.isEmpty()) return

        val roomUsers = getRoomUsers(roomId)
        mentions.forEach { username ->
            val mentionedUser = roomUsers.find { it.username == username }
            if (mentionedUser != null) {
                val conn = userConnections[mentionedUser.id]
                if (conn != null) {
                    runCatching {
                        conn.send(serializeEvent(WsEvent.Mention(messageId, mentionedBy, content)))
                    }
                }
            }
        }
    }

    private fun getReplyInfoById(messageId: Int): ReplyInfo? {
        return messageRepo.getReplyInfo(messageId)
    }

    fun broadcastToRoom(roomId: Int, event: WsEvent) {
        val connections = roomConnections[roomId] ?: return
        val eventJson = serializeEvent(event)

        // Take snapshot and broadcast asynchronously
        val snapshot = connections.toList()
        broadcastExecutor.execute {
            snapshot.forEach { conn ->
                runCatching { conn.send(eventJson) }
            }
        }
    }

    fun sendToUser(userId: Int, event: WsEvent) {
        val conn = userConnections[userId] ?: return
        runCatching { conn.send(serializeEvent(event)) }
    }

    fun serializeEvent(event: WsEvent): String {
        val payload = when (event) {
            is WsEvent.History -> mapOf(
                "type" to "history",
                "messages" to event.messages.map { objectMapper.valueToTree<JsonNode>(it) },
                "hasMore" to event.hasMore
            )
            is WsEvent.Users -> mapOf(
                "type" to "users",
                "users" to event.users
            )
            is WsEvent.Message -> mapOf(
                "type" to "message",
                "message" to objectMapper.valueToTree<JsonNode>(event.message)
            )
            is WsEvent.UserJoined -> mapOf(
                "type" to "user_joined",
                "user" to event.user
            )
            is WsEvent.UserLeft -> mapOf(
                "type" to "user_left",
                "userId" to event.userId,
                "username" to event.username
            )
            is WsEvent.Error -> mapOf(
                "type" to "error",
                "message" to event.message
            )
            is WsEvent.MessageEdited -> mapOf(
                "type" to "message_edited",
                "messageId" to event.messageId,
                "content" to event.content,
                "editedAt" to event.editedAt
            )
            is WsEvent.MessageDeleted -> mapOf(
                "type" to "message_deleted",
                "messageId" to event.messageId
            )
            is WsEvent.PrivateMessageEvent -> mapOf(
                "type" to "private_message",
                "message" to event.message
            )
            is WsEvent.PrivateHistory -> mapOf(
                "type" to "private_history",
                "messages" to event.messages,
                "hasMore" to event.hasMore
            )
            is WsEvent.Mention -> mapOf(
                "type" to "mention",
                "messageId" to event.messageId,
                "mentionedBy" to event.mentionedBy,
                "content" to event.content
            )
            is WsEvent.UserUpdated -> mapOf(
                "type" to "user_updated",
                "user" to event.user
            )
            is WsEvent.Kicked -> mapOf(
                "type" to "kicked",
                "reason" to event.reason
            )
            is WsEvent.RoleChanged -> mapOf(
                "type" to "role_changed",
                "userId" to event.userId,
                "role" to event.role
            )
            is WsEvent.SearchResults -> mapOf(
                "type" to "search_results",
                "messages" to event.messages.map { objectMapper.valueToTree<JsonNode>(it) },
                "query" to event.query
            )
            is WsEvent.UnreadCounts -> mapOf(
                "type" to "unread_counts",
                "unreadDms" to event.unreadDms
            )
        }
        return objectMapper.writeValueAsString(payload)
    }
}