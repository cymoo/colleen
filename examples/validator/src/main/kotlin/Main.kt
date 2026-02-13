import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.expect
import io.github.cymoo.colleen.middleware.RequestLogger

/**
 * Validation Web Example
 *
 * Features demonstrated:
 * - Validate query string parameters
 * - Optional vs required fields
 * - Custom messages
 * - Interactive HTML demo
 */
fun main() {
    data class User(
        val name: String,
        val email: String,
        val age: Int? = null,
    )

    val app = Colleen()

    app.use(RequestLogger())

    app.get("/check") { ctx ->

        val user = ctx.queries<User>()

        expect {

            field("username", user?.name)
                .required()
                .minSize(3)
                .maxSize(20)
                .alphanumeric()

            field("email", user?.email)
                .required()
                .email()

            field("age", user?.age)
                .min(18)
                .message("Minimum age required: 18.")
                .max(120)
        }

        ctx.html("<h1>Validation Success</h1>")
    }

    app.listen(8000)
    println("✅ Validation demo running at http://localhost:8000")

    // Home page with demo links
    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Validation Demo</title>
                <style>
                    body { font-family: Arial; margin: 40px; }
                    .ok { color: green; }
                    .error { color: red; }
                    a { display: block; margin: 10px 0; }
                </style>
            </head>
            <body>
                <h1>🔍 Validation Demo</h1>

                <h2>Try these examples:</h2>

                <p>
                    Valid example: 
                    <a href="/check?name=tom&email=tom@example.com&age=20">
                        name = tom, email = tom@example.com, age = 20
                    </a>
                </p>
                
                <p>
                    Invalid example: 
                    <a href="/check?name=ab&email=bad-email&age=-1">
                        name = ab, email = bad-email, age = -1
                    </a>
                </p>
                
                <p>
                    Malformed example: 
                    <a href="/check?name=&email=&age=abc">
                        name = , email = , age = -1
                    </a>
                </p>
            </body>
            </html>
        """.trimIndent()
        )
    }
}
