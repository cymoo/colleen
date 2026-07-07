package io.github.cymoo.colleen

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Automatic post-binding validation via the [Validatable] interface
 * — review finding 4.10.
 */
class AutoValidationTest {

    data class CreateUser(val name: String = "", val email: String = "") : Validatable {
        override fun validate() = expect {
            field("name", name).required().notBlank()
            field("email", email).required().notBlank()
        }
    }

    data class Filter(val keyword: String = "") : Validatable {
        override fun validate() = expect {
            field("keyword", keyword).required().notBlank()
        }
    }

    data class PlainDto(val anything: String = "")

    class BrokenValidatable : Validatable {
        @Suppress("unused")
        val x: String = ""
        override fun validate() {
            throw IllegalStateException("not a ValidationException")
        }
    }

    // Handlers as members (kotlin-reflect limitation for local functions)
    fun createUser(req: Json<CreateUser>): String = "created ${req.value.name}"
    fun search(filter: Query<Filter>): String = "found ${filter.value.keyword}"
    fun submit(form: Form<Filter>): String = "form ${form.value.keyword}"
    fun createMany(req: Json<List<CreateUser>>): String = "batch ${req.value.size}"
    fun plain(req: Json<PlainDto>): String = "plain ok"
    fun broken(req: Json<BrokenValidatable>): String = "never"

    // ========================================================================
    // Extractor pipeline
    // ========================================================================

    @Nested
    inner class Extractors {

        @Test
        fun `invalid Json body answers 422 with structured errors before the handler runs`() {
            val app = Colleen()
            app.post("/users", ::createUser)

            val response = TestClient(app).post("/users")
                .json(mapOf("name" to "", "email" to "a@b.c"))
                .header("Accept", "application/json")
                .send()

            assertEquals(422, response.status)
            val body = response.json<Map<String, Any?>>()!!
            assertEquals("VALIDATION_FAILED", body["code"])
            @Suppress("UNCHECKED_CAST")
            val errors = body["errors"] as Map<String, List<String>>
            assertTrue("name" in errors)
        }

        @Test
        fun `valid Json body reaches the handler`() {
            val app = Colleen()
            app.post("/users", ::createUser)

            val response = TestClient(app).post("/users")
                .json(mapOf("name" to "neo", "email" to "neo@example.com"))
                .send()

            assertEquals(200, response.status)
            assertEquals("created neo", response.text())
        }

        @Test
        fun `Query DTO binding validates automatically`() {
            val app = Colleen()
            app.get("/search", ::search)

            val invalid = TestClient(app).get("/search").query("keyword", " ").send()
            assertEquals(422, invalid.status)

            val valid = TestClient(app).get("/search").query("keyword", "kotlin").send()
            assertEquals(200, valid.status)
            assertEquals("found kotlin", valid.text())
        }

        @Test
        fun `Form DTO binding validates automatically`() {
            val app = Colleen()
            app.post("/submit", ::submit)

            val invalid = TestClient(app).post("/submit")
                .form(mapOf("keyword" to " "))
                .send()
            assertEquals(422, invalid.status)

            val valid = TestClient(app).post("/submit")
                .form(mapOf("keyword" to "ok"))
                .send()
            assertEquals(200, valid.status)
        }

        @Test
        fun `DTOs not implementing Validatable are unaffected`() {
            val app = Colleen()
            app.post("/plain", ::plain)

            val response = TestClient(app).post("/plain")
                .json(mapOf("anything" to ""))
                .send()

            assertEquals(200, response.status)
        }

        @Test
        fun `top-level collections are not validated automatically (documented)`() {
            val app = Colleen()
            app.post("/users/batch", ::createMany)

            // Elements are invalid, but the top-level object is a List — the
            // documented behavior is to skip automatic validation; wrap the list
            // in a DTO when element validation is needed.
            val response = TestClient(app).post("/users/batch")
                .json(listOf(mapOf("name" to "", "email" to "")))
                .send()

            assertEquals(200, response.status)
            assertEquals("batch 1", response.text())
        }

        @Test
        fun `a validate throwing something else is a server error, not a 422`() {
            val app = Colleen()
            app.post("/broken", ::broken)

            val response = TestClient(app).post("/broken")
                .json(mapOf("x" to "v"))
                .send()

            assertEquals(500, response.status)
        }
    }

    // ========================================================================
    // Manual binding APIs
    // ========================================================================

    @Nested
    inner class ManualApis {

        @Test
        fun `ctx json triggers validation`() {
            val app = Colleen()
            app.post("/manual") { ctx ->
                val user = ctx.json<CreateUser>()!!
                "manual ${user.name}"
            }

            val invalid = TestClient(app).post("/manual")
                .json(mapOf("name" to "", "email" to ""))
                .send()
            assertEquals(422, invalid.status)

            val valid = TestClient(app).post("/manual")
                .json(mapOf("name" to "neo", "email" to "e@x.io"))
                .send()
            assertEquals(200, valid.status)
        }

        @Test
        fun `ctx queries triggers validation`() {
            val app = Colleen()
            app.get("/manual-query") { ctx ->
                val filter = ctx.queries<Filter>()!!
                "q ${filter.keyword}"
            }

            val invalid = TestClient(app).get("/manual-query").query("keyword", " ").send()
            assertEquals(422, invalid.status)
        }

        @Test
        fun `validation failures are not misclassified as 400 binding failures`() {
            val app = Colleen()
            app.post("/users", ::createUser)

            // Actual malformed JSON stays a 400...
            val malformed = TestClient(app).post("/users")
                .body("{not json")
                .header("Content-Type", "application/json")
                .send()
            assertEquals(400, malformed.status)

            // ...while a well-formed but invalid payload is a 422
            val invalid = TestClient(app).post("/users")
                .json(mapOf("name" to "", "email" to ""))
                .send()
            assertEquals(422, invalid.status)
        }
    }
}
