package controller

import io.github.cymoo.colleen.*
import model.*
import service.FileService
import service.RoomService
import service.SessionService
import service.UserService

/**
 * REST API controller for rooms, files, and user profiles
 */
@Controller("/api")
class ApiController(
    private val roomService: RoomService,
    private val fileService: FileService,
    private val userService: UserService,
    private val sessionService: SessionService
) {

    @Get("/rooms")
    fun getRooms(): List<RoomInfo> {
        return roomService.getAllRooms()
    }

    @Post("/rooms")
    fun createRoom(ctx: Context, body: Json<CreateRoomRequest>): Room {
        val userId = ctx.requireAuth()
        val request = body.value
        if (request.name.isBlank()) throw BadRequest("Room name is required")
        if (request.isPrivate && request.password.isNullOrBlank()) throw BadRequest("Private rooms require a password")
        return roomService.createRoom(request.name, request.description, request.isPrivate, request.password, userId)
    }

    @Post("/upload/image")
    fun uploadImage(ctx: Context, image: UploadedFile): UploadResponse {
        ctx.requireAuth()
        return try {
            val file = image.value ?: throw BadRequest("No image uploaded")
            val fileInfo = fileService.saveImage(file)
            UploadResponse(success = true, url = fileInfo.fileUrl, thumbnail = fileInfo.thumbnailUrl, fileName = fileInfo.fileName, fileSize = fileInfo.fileSize)
        } catch (e: IllegalArgumentException) {
            UploadResponse(success = false, error = e.message)
        }
    }

    @Post("/upload/file")
    fun uploadFile(ctx: Context, file: UploadedFile): UploadResponse {
        ctx.requireAuth()
        return try {
            val f = file.value ?: throw BadRequest("No file uploaded")
            val fileInfo = fileService.saveFile(f)
            UploadResponse(success = true, url = fileInfo.fileUrl, fileName = fileInfo.fileName, fileSize = fileInfo.fileSize)
        } catch (e: IllegalArgumentException) {
            UploadResponse(success = false, error = e.message)
        }
    }

    @Get("/users/{userId}")
    fun getUser(userId: Path<Int>): User {
        return userService.getUserById(userId.value) ?: throw NotFound("User not found")
    }

    @Patch("/users/me")
    fun updateMyProfile(ctx: Context, body: Json<UpdateProfileRequest>): User {
        val userId = ctx.requireAuth()
        val req = body.value
        return userService.updateProfile(userId, req.displayName, req.avatarUrl, req.bio, req.status)
            ?: throw NotFound("User not found")
    }

    private fun Context.requireAuth(): Int {
        val auth = header("Authorization") ?: throw Unauthorized("Authentication required")
        val token = if (auth.startsWith("Bearer ")) auth.removePrefix("Bearer ").trim() else throw Unauthorized("Invalid authorization header")
        return sessionService.validateSession(token) ?: throw Unauthorized("Invalid or expired session")
    }
}

