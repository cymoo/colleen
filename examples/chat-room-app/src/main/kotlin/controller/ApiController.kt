package controller

import io.github.cymoo.colleen.*
import model.*
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
    fun uploadImage(image: UploadedFile): UploadResponse {
        return try {
            val file = image.value ?: throw BadRequest("No image uploaded")

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
    fun uploadFile(file: UploadedFile): UploadResponse {
        return try {
            val file = file.value ?: throw BadRequest("No file uploaded")

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
    fun getUser(userId: Path<Int>): User {
        val user = userService?.getUserById(userId.value) ?: throw NotFound("User not found")
        return user
    }
}

