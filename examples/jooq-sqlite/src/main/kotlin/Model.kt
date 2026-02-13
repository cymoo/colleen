import com.fasterxml.jackson.annotation.JsonUnwrapped
import java.time.LocalDateTime

data class User(
    val id: Int? = null,
    val username: String,
    val email: String,
    val createdAt: LocalDateTime? = null
)

data class Post(
    val id: Int? = null,
    val userId: Int,
    val title: String,
    val content: String? = null,
    val published: Boolean = false,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

data class PostWithAuthor(
    @field:JsonUnwrapped
    val post: Post,

    val author: User
)