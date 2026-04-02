package controller
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.ws.Ws
import io.github.cymoo.colleen.ws.WsConnection
import io.github.cymoo.colleen.ws.WsUse
import model.User
import model.WsMessagePayload
import service.ChatService
import service.RoomService
import service.UserService

/**
 * WebSocket chat controller
 */
@Controller("/chat")
class ChatController(
    private val userService: UserService,
    private val chatService: ChatService,
    private val roomService: RoomService,
    private val objectMapper: ObjectMapper
) {

    @WsUse
    fun validateUser(ctx: Context, next: Next) {
        val username = ctx.query("username")
        val displayName = ctx.query("displayName")

        if (username.isNullOrBlank()) {
            ctx.status(400).json(mapOf("error" to "Username is required"))
            return
        }

        // Find or create user
        val user = userService.findOrCreateUser(username, displayName)

        // Store user in context state for WS handler
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

        val user = conn.getStateOrNull<User>("user")
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

        // Join room
        chatService.joinRoom(roomId, conn, user)

        // Send message history
        val history = chatService.getMessageHistory(roomId)
        conn.send(objectMapper.writeValueAsString(mapOf(
            "type" to "history",
            "messages" to history
        )))

        // Send current users
        val users = chatService.getRoomUsers(roomId)
        conn.send(objectMapper.writeValueAsString(mapOf(
            "type" to "users",
            "users" to users
        )))

        // Handle incoming messages
        conn.onMessage { msg ->
            try {
                val payload = objectMapper.readValue(msg, WsMessagePayload::class.java)

                when (payload.type) {
                    "text" -> {
                        payload.content?.let { content ->
                            chatService.sendTextMessage(roomId, user, content)
                        }
                    }
                    "image" -> {
                        payload.imageUrl?.let { imageUrl ->
                            chatService.sendImageMessage(roomId, user, imageUrl, payload.thumbnailUrl)
                        }
                    }
                    "file" -> {
                        if (payload.fileName != null && payload.fileUrl != null &&
                            payload.fileSize != null && payload.mimeType != null) {
                            chatService.sendFileMessage(
                                roomId, user, payload.fileName, payload.fileUrl,
                                payload.fileSize, payload.mimeType
                            )
                        }
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
            chatService.leaveRoom(roomId, user, conn)
        }

        conn.onError { error ->
            println("WebSocket error for user ${user.username}: ${error.message}")
        }
    }
}
