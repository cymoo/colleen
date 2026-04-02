package controller

import io.github.cymoo.colleen.*
import model.*
import service.*
import io.github.cymoo.colleen.ws.WsConnection
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files

/**
 * REST API controller for rooms and files
 */
@Controller("/api")
class ApiController(
    private val roomService: RoomService,
    private val fileService: FileService
) {
    
    @Get("/rooms")
    fun getRooms(): List<RoomInfo> {
        return roomService.getAllRooms()
    }
    
    @Post("/rooms")
    fun createRoom(body: Json<CreateRoomRequest>): Room {
        val request = body.value
        return roomService.createRoom(request.name, request.description)
    }
    
    @Post("/upload/image")
    fun uploadImage(ctx: Context): UploadResponse {
        return try {
            val file = ctx.file("image") ?: throw BadRequest("No image uploaded")
            
            val fileInfo = fileService.saveImage(file)
            
            UploadResponse(
                success = true,
                url = fileInfo.fileUrl,
                thumbnail = fileInfo.thumbnailUrl,
                fileName = fileInfo.fileName,
                fileSize = fileInfo.fileSize
            )
        } catch (e: IllegalArgumentException) {
            UploadResponse(
                success = false,
                error = e.message
            )
        }
    }
    
    @Post("/upload/file")
    fun uploadFile(ctx: Context): UploadResponse {
        return try {
            val file = ctx.file("file") ?: throw BadRequest("No file uploaded")
            
            val fileInfo = fileService.saveFile(file)
            
            UploadResponse(
                success = true,
                url = fileInfo.fileUrl,
                fileName = fileInfo.fileName,
                fileSize = fileInfo.fileSize
            )
        } catch (e: IllegalArgumentException) {
            UploadResponse(
                success = false,
                error = e.message
            )
        }
    }
}

/**
 * File download controller
 */
@Controller("/uploads")
class FileController(private val fileService: FileService) {
    
    @Get("/{filename}")
    fun downloadFile(filename: Path<String>, ctx: Context) {
        val file = fileService.getFile(filename.value) ?: throw NotFound("File not found")
        
        val mimeType = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
        ctx.stream(file.inputStream(), mimeType)
    }
}

/**
 * WebSocket chat controller
 */
@Controller
class ChatController(
    private val chatService: ChatService,
    private val roomService: RoomService,
    private val objectMapper: ObjectMapper
) {
    
    @io.github.cymoo.colleen.ws.Ws("/chat/{roomId}")
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
