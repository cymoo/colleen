import io.github.cymoo.colleen.Colleen

fun main() {
    val app = Colleen()

    app.get("/") {
        "hello world!"
    }

    app.listen()
    println("Server running at http://127.0.0.1:8000")
}