import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.Query
import io.github.cymoo.colleen.UploadedFile
import java.time.Instant

data class BenchQuery(
    val q: String? = null,
    val page: Int? = 1
)

data class BenchPayload(
    val userId: Long,
    val name: String,
    val tags: List<String> = emptyList()
)

fun main() {
    val app = Colleen()

    app.get("/text") { "ok" }

    app.get("/json") {
        mapOf(
            "ok" to true,
            "framework" to "colleen",
            "ts" to Instant.now().toEpochMilli()
        )
    }

    app.get("/json-stream") { ctx ->
        val payload = (1..2000).map { i ->
            mapOf("id" to i, "name" to "item-$i", "active" to (i % 2 == 0))
        }
        ctx.json(payload, stream = true)
    }

    app.post("/upload", ::upload)
    app.post("/extract/auto", ::extractAuto)

    app.listen(7070)
}

fun upload(file: UploadedFile): Map<String, Any?> {
    val f = file.value
    return mapOf(
        "uploaded" to (f != null),
        "name" to f?.name,
        "size" to f?.size,
        "contentType" to f?.contentType
    )
}

fun extractAuto(query: Query<BenchQuery>, body: Json<BenchPayload>): Map<String, Any?> {
    return mapOf(
        "query" to query.value,
        "body" to body.value,
        "tagCount" to body.value.tags.size
    )
}
