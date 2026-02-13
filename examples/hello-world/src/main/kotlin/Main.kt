import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.RequestLogger


fun main() {
    val app = Colleen()
    app.use(RequestLogger())

    app.get("/") {
        mapOf("msg" to "hello world")
    }

    app.listen()
    println("Server running at http://localhost:8000")
}

