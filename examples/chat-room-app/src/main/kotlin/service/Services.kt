package service

import io.github.cymoo.colleen.FilePart
import model.*
import repository.*
import io.github.cymoo.colleen.ws.WsConnection
import org.jooq.DSLContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Chat service managing rooms, connections, and message broadcasting
 */
class ChatService(private val dsl: DSLContext, private val objectMapper: ObjectMapper) {
    
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
                "messages" to event.messages
            )
            is WsEvent.Users -> mapOf(
                "type" to "users",
                "users" to event.users
            )
            is WsEvent.Message -> mapOf(
                "type" to "message",
                "message" to event.message
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

/**
 * File management service
 */
class FileService(uploadDir: String) {
    
    private val uploadPath: Path = Paths.get(uploadDir)
    private val maxImageSize: Long = 5 * 1024 * 1024  // 5MB
    private val maxFileSize: Long = 20 * 1024 * 1024  // 20MB
    
    init {
        Files.createDirectories(uploadPath)
    }
    
    fun saveImage(file: FilePart): FileInfo {
        validateImageSize(file.size)
        validateImageType(file.contentType)
        
        val safeFilename = generateSafeFilename(file.filename)
        val targetPath = uploadPath.resolve(safeFilename)
        
        file.save(targetPath.toString())
        
        return FileInfo(
            fileName = safeFilename,
            fileUrl = "/uploads/$safeFilename",
            fileSize = file.size,
            mimeType = file.contentType ?: "application/octet-stream"
        )
    }
    
    fun saveFile(file: FilePart): FileInfo {
        validateFileSize(file.size)
        
        val safeFilename = generateSafeFilename(file.filename)
        val targetPath = uploadPath.resolve(safeFilename)
        
        file.save(targetPath.toString())
        
        return FileInfo(
            fileName = safeFilename,
            fileUrl = "/uploads/$safeFilename",
            fileSize = file.size,
            mimeType = file.contentType ?: "application/octet-stream"
        )
    }
    
    fun getFile(filename: String): File? {
        val safeFilename = sanitizeFilename(filename)
        val file = uploadPath.resolve(safeFilename).toFile()
        return if (file.exists() && file.isFile) file else null
    }
    
    private fun validateImageSize(size: Long) {
        if (size > maxImageSize) {
            throw IllegalArgumentException("Image too large. Maximum: 5MB")
        }
    }
    
    private fun validateImageType(contentType: String?) {
        val allowedTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        if (contentType !in allowedTypes) {
            throw IllegalArgumentException("Invalid image type. Allowed: JPEG, PNG, GIF, WebP")
        }
    }
    
    private fun validateFileSize(size: Long) {
        if (size > maxFileSize) {
            throw IllegalArgumentException("File too large. Maximum: 20MB")
        }
    }
    
    private fun generateSafeFilename(originalFilename: String): String {
        val timestamp = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString().substring(0, 8)
        val extension = originalFilename.substringAfterLast('.', "")
        val sanitizedName = sanitizeFilename(originalFilename.substringBeforeLast('.'))
        
        return if (extension.isNotEmpty()) {
            "${timestamp}_${uuid}_${sanitizedName}.${extension}"
        } else {
            "${timestamp}_${uuid}_${sanitizedName}"
        }
    }
    
    private fun sanitizeFilename(filename: String): String {
        return filename
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(100) // Limit filename length
    }
}

/**
 * Room management service
 */
class RoomService(private val dsl: DSLContext) {
    
    private val roomRepo = RoomRepository(dsl)
    
    fun getAllRooms(): List<RoomInfo> {
        return roomRepo.findAll().map { room ->
            RoomInfo(
                id = room.id,
                name = room.name,
                description = room.description,
                onlineUsers = roomRepo.getOnlineUserCount(room.id),
                maxUsers = room.maxUsers
            )
        }
    }
    
    fun getRoomById(id: Int): Room? {
        return roomRepo.findById(id)
    }
    
    fun getRoomByName(name: String): Room? {
        return roomRepo.findByName(name)
    }
    
    fun createRoom(name: String, description: String?): Room {
        return roomRepo.create(name, description)
    }
}

/**
 * User service
 */
class UserService(private val dsl: DSLContext) {
    
    private val userRepo = UserRepository(dsl)
    
    fun findOrCreateUser(username: String, displayName: String? = null): User {
        return userRepo.findByUsername(username)
            ?: userRepo.createUser(username, displayName ?: username)
    }
    
    fun getUserById(id: Int): User? {
        return userRepo.findById(id)
    }
}
