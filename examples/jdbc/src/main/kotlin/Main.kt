import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Delete
import io.github.cymoo.colleen.Get
import io.github.cymoo.colleen.NotFound
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.middleware.RequestLogger
import java.time.LocalDateTime

/**
 * Blog Post API Demo
 *
 * Features demonstrated:
 * - SQLite in-memory database with JdbcClient
 * - RESTful API design (GET list, GET detail, DELETE)
 * - Named parameter queries
 * - Automatic JSON serialization
 */

data class Post(
    val id: Long,
    val title: String,
    val content: String,
    val author: String,
    val createdAt: LocalDateTime
)

@Controller("/posts")
class PostController(val db: JdbcClient) {

    @Get("/")
    fun list() =
        db.queryMaps("SELECT * FROM posts ORDER BY created_at DESC")

    // Option 1: Directly return result as Map<String, Any?>
    // This is the simplest way when you just need raw database data
    @Get("/{id}")
    fun getOne(id: Path<Int>): Map<String, Any?> {
        return db.queryMap("SELECT * FROM posts WHERE id = ?", listOf(id.value))
            ?: throw NotFound("Post Not Found")
    }

    // Option 2: Manually map the result to a data class
    @Get("/v1/{id}")
    fun getOne1(id: Path<Int>): Post {
        val post = db.queryOne(
            "SELECT * FROM posts WHERE id = :id",
            mapOf("id" to id.value)
        ) {
            Post(
                id = it.getLong("id"),
                title = it.getString("title"),
                content = it.getString("content"),
                author = it.getString("author"),
                createdAt = it.getLocalDateTimeOrNull("created_at")!!
            )
        }

        return post ?: throw NotFound("Post Not Found")
    }

    @Delete("/{id}")
    fun delete(id: Path<Int>): Map<String, Any?> {
        val affected = db.update(
            "DELETE FROM posts WHERE id = :id",
            mapOf("id" to id.value)
        )

        if (affected == 0) throw NotFound("Post Not Found")

        return mapOf("success" to true, "message" to "Post deleted")
    }
}


fun main() {
    val db = JdbcClient.forSQLite("file::memory:?cache=shared")

    initDatabase(db)

    val app = Colleen()

    app.config {
        json {
            pretty = true
        }
    }

    app.use(RequestLogger())

    app.addController(PostController(db))

    app.get("/") { ctx ->
        ctx.redirect("/posts")
    }

    app.listen()
    println("Server running on http://localhost:8000")
    println("Try:")
    println("  GET    http://localhost:8000/posts")
    println("  GET    http://localhost:8000/posts/1")
    println("  DELETE http://localhost:8000/posts/1")
}

/**
 * Initialize SQLite in-memory database with sample data
 */
fun initDatabase(db: JdbcClient) {
    // Create posts table
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS posts(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            author TEXT NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    """
    )

    // Insert sample data
    val posts = listOf(
        mapOf(
            "title" to "Getting Started with Kotlin",
            "content" to "Kotlin is a modern programming language that makes developers happier. It's concise, safe, and fully interoperable with Java.",
            "author" to "Alice"
        ),
        mapOf(
            "title" to "Building REST APIs",
            "content" to "RESTful APIs are the backbone of modern web applications. Learn how to design and implement clean, scalable APIs.",
            "author" to "Bob"
        )
    )

    db.batchUpdate(
        "INSERT INTO posts (title, content, author) VALUES (:title, :content, :author)",
        posts
    )

    println("Database initialized with ${posts.size} sample posts")
}