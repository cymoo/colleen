package model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * User model
 */
data class User(
    val id: Int,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val createdAt: Long,
    val lastSeen: Long
)

/**
 * Room model
 */
data class Room(
    val id: Int,
    val name: String,
    val description: String?,
    val createdAt: Long,
    val maxUsers: Int = 100
)

/**
 * Room with online user count
 */
data class RoomInfo(
    val id: Int,
    val name: String,
    val description: String?,
    val onlineUsers: Int,
    val maxUsers: Int
)

/**
 * Base message type with discriminator for JSON serialization
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "messageType"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ChatMessage.Text::class, name = "text"),
    JsonSubTypes.Type(value = ChatMessage.Image::class, name = "image"),
    JsonSubTypes.Type(value = ChatMessage.File::class, name = "file"),
    JsonSubTypes.Type(value = ChatMessage.System::class, name = "system")
)
sealed class ChatMessage {
    abstract val id: Int
    abstract val timestamp: Long

    data class Text(
        override val id: Int,
        val userId: Int,
        val username: String,
        val displayName: String,
        val avatarUrl: String?,
        val content: String,
        override val timestamp: Long
    ) : ChatMessage()

    data class Image(
        override val id: Int,
        val userId: Int,
        val username: String,
        val displayName: String,
        val avatarUrl: String?,
        val imageUrl: String,
        val thumbnailUrl: String?,
        override val timestamp: Long
    ) : ChatMessage()

    data class File(
        override val id: Int,
        val userId: Int,
        val username: String,
        val displayName: String,
        val avatarUrl: String?,
        val fileName: String,
        val fileUrl: String,
        val fileSize: Long,
        val mimeType: String,
        override val timestamp: Long
    ) : ChatMessage()

    data class System(
        override val id: Int,
        val content: String,
        override val timestamp: Long
    ) : ChatMessage()
}

/**
 * File information
 */
data class FileInfo(
    val fileName: String,
    val fileUrl: String,
    val fileSize: Long,
    val mimeType: String,
    val thumbnailUrl: String? = null
)

/**
 * WebSocket message payloads
 */
data class WsMessagePayload(
    val type: String,
    val content: String? = null,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val fileName: String? = null,
    val fileUrl: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null
)

/**
 * WebSocket event types sent to clients
 */
sealed class WsEvent {
    data class History(val messages: List<ChatMessage>) : WsEvent()
    data class Users(val users: List<User>) : WsEvent()
    data class Message(val message: ChatMessage) : WsEvent()
    data class UserJoined(val user: User) : WsEvent()
    data class UserLeft(val userId: Int, val username: String) : WsEvent()
    data class Error(val message: String) : WsEvent()
}

/**
 * API request/response models
 */
data class CreateRoomRequest(
    val name: String,
    val description: String?
)

data class UploadResponse(
    val success: Boolean,
    val url: String? = null,
    val thumbnail: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val error: String? = null
)
