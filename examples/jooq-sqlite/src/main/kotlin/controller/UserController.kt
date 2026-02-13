package controller

import User
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Delete
import io.github.cymoo.colleen.Get
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.NotFound
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.Result
import io.github.cymoo.colleen.ServerError
import io.github.cymoo.colleen.expect
import repository.UserRepository
import io.github.cymoo.colleen.Post as POST

@Controller("/users")
class UserController(private val userRepo: UserRepository) {

    data class CreateUserRequest(val username: String, val email: String)

    @Get("/")
    fun list(): List<User> {
        return userRepo.findAll()
    }

    @Get("/:id")
    fun getOne(id: Path<Int>): User {
        return userRepo.findById(id.value) ?: throw NotFound("User not found")
    }

    @POST("/")
    fun create(req: Json<CreateUserRequest>): Result<User> {
        val data = req.value

        expect {
            field("username", data.username).notBlank().minSize(3)
            field("email", data.email).notBlank().email()
        }

        val user = userRepo.create(data.username, data.email) ?: throw ServerError("Failed to create user")
        return Result.created(user)
    }

    @Delete("/:id")
    fun delete(id: Path<Int>) {
        val deleted = userRepo.delete(id.value)

        if (deleted == 0) {
            throw NotFound("User not found")
        }
    }
}