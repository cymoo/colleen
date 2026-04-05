package controller
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.ws.Ws
import io.github.cymoo.colleen.ws.WsConnection
import io.github.cymoo.colleen.ws.WsUse
import model.User
import model.WsEvent
import model.WsMessagePayload
import service.ChatService
import service.RoomService
import service.SessionService
import service.UserService

/**
 * WebSocket chat controller
 */
@Controller("/chat")
class ChatController(
    private val userService: UserService,
    private val chatService: ChatService,
    private val roomService: RoomService,
    private val sessionService: SessionService,
    private val objectMapper: ObjectMapper
) {

    @WsUse
    fun validateUser(ctx: Context, next: Next) {
        val token = ctx.query("token")
        if (token.isNullOrBlank()) {
            ctx.status(401).json(mapOf("error" to "Authentication required"))
            return
        }

        val userId = sessionService.validateSession(token)
        if (userId == null) {
            ctx.status(401).json(mapOf("error" to "Invalid or expired session"))
            return
        }

        val user = userService.getUserById(userId)
        if (user == null) {
            ctx.status(401).json(mapOf("error" to "User not found"))
            return
        }

        ctx.setState("user", user)
        next()
    }

    @Ws("/{roomId}")
    fun chatRoom(conn: WsConnection) {
        val roomId = conn.pathParam("roomId")?.toIntOrNull()
            ?: run {
                conn.send("""{"type":"error","message":"Invalid room ID"}""")
                conn.close()
                return
            }

        // Mutable reference so profile updates are reflected in subsequent messages
        var currentUser = conn.getStateOrNull<User>("user")
            ?: run {
                conn.send("""{"type":"error","message":"User not authenticated"}""")
                conn.close()
                return
            }

        // Verify room exists
        val room = roomService.getRoomById(roomId)
            ?: run {
                conn.send("""{"type":"error","message":"Room not found"}""")
                conn.close()
                return
            }

        // Verify password for private rooms
        if (room.isPrivate) {
            val roomPassword = conn.query("roomPassword")
            if (!roomService.verifyRoomPassword(roomId, roomPassword)) {
                conn.send("""{"type":"error","message":"Incorrect room password"}""")
                conn.close()
                return
            }
        }

        // Join room
        chatService.joinRoom(roomId, conn, currentUser)

        // Send message history with hasMore flag
        val history = chatService.getMessageHistory(roomId)
        val hasMore = history.size >= 30
        conn.send(objectMapper.writeValueAsString(mapOf(
            "type" to "history",
            "messages" to history,
            "hasMore" to hasMore
        )))

        // Send current users
        val users = chatService.getRoomUsers(roomId)
        conn.send(objectMapper.writeValueAsString(mapOf(
            "type" to "users",
            "users" to users
        )))

        // Send initial unread DM count
        val unreadCount = chatService.getUnreadDmCount(currentUser.id)
        conn.send(chatService.serializeEvent(WsEvent.UnreadCounts(unreadCount)))

        // Handle incoming messages
        conn.onMessage { msg ->
            try {
                val payload = objectMapper.readValue(msg, WsMessagePayload::class.java)

                when (payload.type) {
                    "text" -> {
                        payload.content?.let { content ->
                            chatService.sendTextMessage(roomId, currentUser, content, payload.replyToId)
                        }
                    }
                    "image" -> {
                        payload.imageUrl?.let { imageUrl ->
                            chatService.sendImageMessage(roomId, currentUser, imageUrl, payload.thumbnailUrl, payload.replyToId)
                        }
                    }
                    "file" -> {
                        if (payload.fileName != null && payload.fileUrl != null &&
                            payload.fileSize != null && payload.mimeType != null) {
                            chatService.sendFileMessage(
                                roomId, currentUser, payload.fileName, payload.fileUrl,
                                payload.fileSize, payload.mimeType, payload.replyToId
                            )
                        }
                    }
                    "edit" -> {
                        if (payload.messageId != null && payload.content != null) {
                            chatService.editMessage(roomId, currentUser, payload.messageId, payload.content)
                        }
                    }
                    "delete" -> {
                        payload.messageId?.let { messageId ->
                            chatService.deleteMessage(roomId, currentUser, messageId)
                        }
                    }
                    "load_history" -> {
                        payload.beforeId?.let { beforeId ->
                            val (messages, hasMore) = chatService.getOlderMessages(roomId, beforeId)
                            conn.send(chatService.serializeEvent(
                                WsEvent.History(messages, hasMore)
                            ))
                        }
                    }
                    "search" -> {
                        payload.query?.let { query ->
                            if (query.isNotBlank()) {
                                val results = chatService.searchMessages(roomId, query)
                                conn.send(chatService.serializeEvent(
                                    WsEvent.SearchResults(results, query)
                                ))
                            }
                        }
                    }
                    "private_message" -> {
                        if (payload.targetUserId != null && payload.content != null) {
                            chatService.sendPrivateMessage(currentUser, payload.targetUserId, payload.content)
                        }
                    }
                    "private_history" -> {
                        payload.targetUserId?.let { targetUserId ->
                            val (messages, hasMore) = chatService.getPrivateHistory(currentUser.id, targetUserId, payload.beforeId)
                            conn.send(chatService.serializeEvent(
                                WsEvent.PrivateHistory(messages, hasMore)
                            ))
                        }
                    }
                    "set_role" -> {
                        if (payload.targetUserId != null && payload.role != null) {
                            chatService.setUserRole(roomId, currentUser, payload.targetUserId, payload.role)
                        }
                    }
                    "kick" -> {
                        payload.targetUserId?.let { targetUserId ->
                            chatService.kickUser(roomId, currentUser, targetUserId)
                        }
                    }
                    "update_profile" -> {
                        // Update currentUser so subsequent messages carry the new avatar/status
                        currentUser = chatService.updateUserProfile(
                            currentUser, payload.avatarUrl, payload.bio, payload.status
                        )
                    }
                }
            } catch (e: Exception) {
                conn.send(objectMapper.writeValueAsString(mapOf(
                    "type" to "error",
                    "message" to "Failed to process message: ${e.message}"
                )))
            }
        }

        // Handle disconnect
        conn.onClose { reason ->
            chatService.leaveRoom(roomId, currentUser, conn)
        }

        conn.onError { error ->
            println("WebSocket error for user ${currentUser.username}: ${error.message}")
        }
    }
}
