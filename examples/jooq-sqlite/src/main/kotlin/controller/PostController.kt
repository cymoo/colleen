package controller

import Post
import PostWithAuthor
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Delete
import io.github.cymoo.colleen.Get
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.NotFound
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.Put
import io.github.cymoo.colleen.Query
import io.github.cymoo.colleen.Result
import io.github.cymoo.colleen.ServerError
import io.github.cymoo.colleen.expect
import repository.PostRepository
import io.github.cymoo.colleen.Post as POST

@Controller("/posts")
class PostController(private val postRepo: PostRepository) {

    data class CreatePostRequest(
        val userId: Int,
        val title: String,
        val content: String? = null,
        val published: Boolean = false
    )

    data class UpdatePostRequest(
        val title: String? = null,
        val content: String? = null,
        val published: Boolean? = null
    )

    @Get("/")
    fun list(published: Query<Boolean?>): List<Post> {
        return if (published.value == true) {
            postRepo.findAllPublished()
        } else {
            postRepo.findAll()
        }
    }

    @Get("/:id")
    fun getOne(id: Path<Int>): PostWithAuthor {
        return postRepo.findByIdWithAuthor(id.value) ?: throw NotFound("Post not found")
    }

    @Get("/users/:userId")
    fun getUserPosts(userId: Path<Int>): List<Post> {
        return postRepo.findByUserId(userId.value)
    }

    @POST("/")
    fun create(req: Json<CreatePostRequest>): Result<Post> {
        val data = req.value

        expect {
            field("title", data.title).notBlank()
        }

        val post = postRepo.create(data.userId, data.title, data.content, data.published)
            ?: throw ServerError("Failed to create post")

        return Result.created(post)
    }

    @Put("/:id")
    fun update(id: Path<Int>, req: Json<UpdatePostRequest>) {
        val data = req.value

        val updated = postRepo.update(id.value, data.title, data.content, data.published)

        if (updated == 0) {
            throw NotFound("Post not found")
        }
    }

    @Delete("/:id")
    fun delete(id: Path<Int>) {
        val deleted = postRepo.delete(id.value)

        if (deleted == 0) {
            throw NotFound("Post not found")
        }
    }
}