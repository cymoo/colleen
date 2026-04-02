package service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cymoo.colleen.ws.WsConnection
import model.ChatMessage
import model.User
import model.WsEvent
import org.jooq.DSLContext
import repository.MessageRepository
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
            "${user.displayName} joined the room"
        )

        // Broadcast join event
        val joinMessage = ChatMessage.System(
            id = systemMessageId,
            content = "${user.displayName} joined the room",
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
            "${user.displayName} left the room"
        )

        // Broadcast leave event
        val leaveMessage = ChatMessage.System(
            id = systemMessageId,
            content = "${user.displayName} left the room",
            timestamp = System.currentTimeMillis()
        )

        broadcastToRoom(roomId, WsEvent.Message(leaveMessage))
        broadcastToRoom(roomId, WsEvent.UserLeft(user.id, user.username))
    }

    fun sendTextMessage(roomId: Int, user: User, content: String) {
        val messageId = messageRepo.saveTextMessage(roomId, user.id, content)

        val message = ChatMessage.Text(
            id = messageId,
            userId = user.id,
            username = user.username,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            content = content,
            timestamp = System.currentTimeMillis()
        )

        broadcastToRoom(roomId, WsEvent.Message(message))
    }

    fun sendImageMessage(roomId: Int, user: User, imageUrl: String, thumbnailUrl: String?) {
        val messageId = messageRepo.saveImageMessage(roomId, user.id, imageUrl, thumbnailUrl)

        val message = ChatMessage.Image(
            id = messageId,
            userId = user.id,
            username = user.username,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            timestamp = System.currentTimeMillis()
        )

        broadcastToRoom(roomId, WsEvent.Message(message))
    }

    fun sendFileMessage(
        roomId: Int,
        user: User,
        fileName: String,
        fileUrl: String,
        fileSize: Long,
        mimeType: String
    ) {
        val messageId = messageRepo.saveFileMessage(
            roomId, user.id, fileName, fileUrl, fileSize, mimeType
        )

        val message = ChatMessage.File(
            id = messageId,
            userId = user.id,
            username = user.username,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            fileName = fileName,
            fileUrl = fileUrl,
            fileSize = fileSize,
            mimeType = mimeType,
            timestamp = System.currentTimeMillis()
        )

        broadcastToRoom(roomId, WsEvent.Message(message))
    }

    fun getMessageHistory(roomId: Int): List<ChatMessage> {
        return messageRepo.getRecentMessages(roomId, 100)
    }

    fun getRoomUsers(roomId: Int): List<User> {
        return roomMemberRepo.getRoomMembers(roomId)
    }

    private fun broadcastToRoom(roomId: Int, event: WsEvent) {
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

    private fun serializeEvent(event: WsEvent): String {
        val payload = when (event) {
            is WsEvent.History -> mapOf(
                "type" to "history",
                "messages" to event.messages.map { objectMapper.valueToTree<JsonNode>(it) }
            )
            is WsEvent.Users -> mapOf(
                "type" to "users",
                "users" to event.users
            )
            is WsEvent.Message -> mapOf(
                "type" to "message",
                "message" to objectMapper.valueToTree<JsonNode>(event.message)  // ← 关键修复
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
        }
        return objectMapper.writeValueAsString(payload)
    }
}