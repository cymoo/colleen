import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.middleware.RequestLogger

object Primary
object Replica

class MyService {

}

fun main() {
    val app = Colleen()
    app.provide(MyService(), "foo")
    app.provide(MyService())
    app.provide {MyService()}
    app.provide(qualifier = Primary) { MyService() }
    app.provide(qualifier = Replica) { MyService() }

    app.use(RequestLogger())

    app.get("/") {
        mapOf("msg" to "hello world")
    }

    app.listen()
    println("Server running at http://localhost:8000")
}

