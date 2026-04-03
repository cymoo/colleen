package controller

import io.github.cymoo.colleen.*
import model.CreateRoomRequest
import model.Room
import model.RoomInfo
import model.UploadResponse
import service.FileService
import service.RoomService
import service.UserService

/**
 * REST API controller for rooms, files, and user profiles
 */
@Controller("/api")
class ApiController(
    private val roomService: RoomService,
    private val fileService: FileService,
    private val userService: UserService? = null
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

    @Get("/users/{userId}")
    fun getUser(userId: Path<Int>): Any {
        val user = userService?.getUserById(userId.value) ?: throw NotFound("User not found")
        return user
    }
}

