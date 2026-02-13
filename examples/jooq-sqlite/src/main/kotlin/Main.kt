import config.DatabaseConfig
import controller.PostController
import controller.UserController
import repository.PostRepository
import repository.UserRepository
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.Cors
import io.github.cymoo.colleen.middleware.RequestLogger

fun main() {
    val app = Colleen()

    app.use(RequestLogger())
    app.use(Cors())

    app.get("/") {
        "hello world"
    }

    val dsl = DatabaseConfig.createDSLContext()
    val userController = UserController(UserRepository(dsl))
    val postController = PostController(PostRepository(dsl))

    app.addController(postController)
    app.addController(userController)

    app.listen()
    println("Server running on http://localhost:8000")
}