package com.example.app.controller

import com.example.app.model.CreateUserRequest
import com.example.app.model.UpdateUserRequest
import com.example.app.model.User
import com.example.app.service.UserService
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Delete
import io.github.cymoo.colleen.Get
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.Result
import io.github.cymoo.colleen.expect
import io.github.cymoo.colleen.openapi.ParamDesc
import io.github.cymoo.colleen.openapi.ResponseDesc
import io.github.cymoo.colleen.openapi.Summary
import io.github.cymoo.colleen.openapi.Tags
import io.github.cymoo.colleen.Post as POST
import io.github.cymoo.colleen.Put as PUT

@Tags("users")
@Controller("/users")
class UserController(private val users: UserService) {
    @Get("/")
    @Summary("List users")
    @ResponseDesc(200, "Users returned")
    fun list(): List<User> = users.list()

    @Get("/{id}")
    @Summary("Get a user")
    @ParamDesc("id", "User ID")
    @ResponseDesc(200, "User found")
    @ResponseDesc(404, "User not found")
    fun get(id: Path<Int>): User = users.get(id.value)

    @POST("/")
    @Summary("Create a user")
    @ResponseDesc(201, "User created")
    @ResponseDesc(409, "Username or email already exists")
    fun create(body: Json<CreateUserRequest>): Result<User> {
        val request = body.value
        validateCreate(request)
        return Result.created(users.create(request))
    }

    @PUT("/{id}")
    @Summary("Update a user")
    @ParamDesc("id", "User ID")
    @ResponseDesc(200, "User updated")
    @ResponseDesc(404, "User not found")
    @ResponseDesc(409, "Username or email already exists")
    fun update(id: Path<Int>, body: Json<UpdateUserRequest>): User {
        val request = body.value
        validateUpdate(request)
        return users.update(id.value, request)
    }

    @Delete("/{id}")
    @Summary("Delete a user")
    @ParamDesc("id", "User ID")
    @ResponseDesc(204, "User deleted")
    @ResponseDesc(404, "User not found")
    fun delete(id: Path<Int>) {
        users.delete(id.value)
    }

    private fun validateCreate(request: CreateUserRequest) {
        expect {
            field("username", request.username).notBlank().minSize(3).maxSize(64)
            field("email", request.email).notBlank().email().maxSize(255)
        }
    }

    private fun validateUpdate(request: UpdateUserRequest) {
        expect {
            request.username?.let { field("username", it).notBlank().minSize(3).maxSize(64) }
            request.email?.let { field("email", it).notBlank().email().maxSize(255) }
        }
    }
}
