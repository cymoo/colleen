package io.github.cymoo.colleen

import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtractorTest {

    private lateinit var app: Colleen

    data class UserDto(val name: String, val age: Int)
    data class QueryDto(val page: Int, val size: Int)

    @BeforeAll
    fun setup() {
        app = Colleen()
    }

    // ============================================
    // Context extract
    // ============================================
    fun handler1(ctx: Context): String = "path: ${ctx.path}"

    @Test
    fun `should extract Context parameter directly`() {
        val handler = cx(::handler1)
        val ctx = createContext(path = "/test")
        val result = handler(ctx)

        assertEquals("path: /test", result)
    }

    fun handler2(ctx: Context, ctx2: Context): String =
        "${ctx.path} - ${ctx2.path}"

    @Test
    fun `should handle multiple Context parameters`() {
        val handler = cx(::handler2)
        val ctx = createContext(path = "/multi")
        val result = handler(ctx) as String

        assertEquals("/multi - /multi", result)
    }

    // ============================================
    // Path extract
    // ============================================

    fun handler3(@Param("id") id: Path<String>) = "id: ${id.value}"

    @Test
    fun `should extract Path String parameter`() {
        val ctx = createContext().apply { setPathParam("id", "123") }
        val result = cx(::handler3)(ctx) as String

        assertEquals("id: 123", result)
    }

    fun handler4(@Param("id") id: Path<Int>) = id.value * 2

    @Test
    fun `should extract Path Int parameter`() {
        val ctx = createContext().apply { setPathParam("id", "42") }
        val result = cx(::handler4)(ctx) as Int

        assertEquals(84, result)
    }

    fun handler5(@Param("id") id: Path<Long>) = id.value

    @Test
    fun `should extract Path Long parameter`() {
        val ctx = createContext().apply { setPathParam("id", "9999999999") }
        val result = cx(::handler5)(ctx) as Long

        assertEquals(9999999999L, result)
    }

    fun handler6(@Param("id") id: Path<Int>) = id.value

    @Test
    fun `should throw when Path parameter is missing`() {
        val ctx = createContext()

        assertThrows<IllegalArgumentException> {
            cx(::handler6)(ctx)
        }
    }

    fun handler7(@Param("id") id: Path<Int>) = id.value

    @Test
    fun `should throw when Path parameter conversion fails`() {

        val ctx = createContext().apply { setPathParam("id", "not-a-number") }

        assertThrows<TypeConversionFailed> {
            cx(::handler7)(ctx)
        }
    }

    fun handler8(id: Path<Int>) = id.value

    @Test
    fun `should preserve parameter name in kotlin`() {
        val ctx = createContext().apply { setPathParam("id", "42") }
        val result = cx(::handler8)(ctx)
        assertEquals(42, result)
    }

    // ============================================
    // Query extract
    // ============================================

    fun handler9(@Param("name") name: Query<String>) = "Hello ${name.value}"

    @Test
    fun `should extract Query String parameter`() {
        val ctx = createContext(queryString = "name=Alice")
        val result = cx(::handler9)(ctx) as String

        assertEquals("Hello Alice", result)
    }

    fun handler10(@Param("page") page: Query<Int>) = page.value

    @Test
    fun `should extract Query Int parameter`() {
        val ctx = createContext(queryString = "page=5")
        val result = cx(::handler10)(ctx) as Int

        assertEquals(5, result)
    }

    fun handler11(@Param("page") page: Query<Int?>) = page.value ?: -1

    @Test
    fun `should extract nullable Query Int parameter when present`() {
        val ctx = createContext(queryString = "page=10")
        val result = cx(::handler11)(ctx) as Int

        assertEquals(10, result)
    }

    fun handler12(@Param("page") page: Query<Int?>) = page.value ?: -1

    @Test
    fun `should extract nullable Query Int parameter when absent`() {
        val ctx = createContext(queryString = "")
        val result = cx(::handler12)(ctx) as Int

        assertEquals(-1, result)
    }

    fun handler13(@Param("page") page: Query<Int>) = page.value

    @Test
    fun `should throw when required Query parameter is missing`() {
        val ctx = createContext(queryString = "")

        assertThrows<BadRequest> { cx(::handler13)(ctx) }
    }

    fun handler14(@Param("active") active: Query<Boolean>) = active.value

    @Test
    fun `should extract Query Boolean parameter - true variants`() {
        listOf("true", "True", "TRUE", "1", "yes", "Yes", "y", "Y").forEach { value ->
            val ctx = createContext(queryString = "active=$value")
            val result = cx(::handler14)(ctx) as Boolean
            assertTrue(result, "Failed for value: $value")
        }
    }

    fun handler15(@Param("active") active: Query<Boolean>) = active.value

    @Test
    fun `should extract Query Boolean parameter - false variants`() {
        listOf("false", "False", "0", "no", "n", "anything").forEach { value ->
            val ctx = createContext(queryString = "active=$value")
            val result = cx(::handler15)(ctx) as Boolean
            assertFalse(result, "Failed for value: $value")
        }
    }

    fun handler16(@Param("tags") tags: Query<List<String>>) = tags.value.size

    @Test
    fun `should extract Query List of Strings`() {
        val ctx = createContext(queryString = "tags=java&tags=kotlin&tags=spring")
        val result = cx(::handler16)(ctx) as Int

        assertEquals(3, result)
    }

    fun handler17(@Param("ids") ids: Query<List<Int>>) = ids.value.sum()

    @Test
    fun `should extract Query List of Ints`() {
        val ctx = createContext(queryString = "ids=1&ids=2&ids=3")
        val result = cx(::handler17)(ctx) as Int

        assertEquals(6, result)
    }

    fun handler18(params: Query<Map<String, List<String>>>) =
        params.value.keys.size

    @Test
    fun `should extract Query Map of all parameters`() {
        val ctx = createContext(queryString = "a=1&b=2&c=3")
        val result = cx(::handler18)(ctx) as Int

        assertEquals(3, result)
    }

    fun handler19(dto: Query<QueryDto>) = dto.value.page + dto.value.size

    @Test
    fun `should extract Query as DTO`() {
        val ctx = createContext(queryString = "page=1&size=20")
        val result = cx(::handler19)(ctx) as Int

        assertEquals(21, result)
    }

    fun handler20(@Param("message") msg: Query<String>) = msg.value

    @Test
    fun `should handle URL encoded Query parameters`() {
        val ctx = createContext(queryString = "message=hello%20world")
        val result = cx(::handler20)(ctx) as String

        assertEquals("hello world", result)
    }

    // ============================================
    // Form extract
    // ============================================

    fun handler21(@Param("username") name: Form<String>) = name.value

    @Test
    fun `should extract Form String parameter`() {
        val ctx = createContext(
            body = "username=john",
            contentType = "application/x-www-form-urlencoded"
        )
        val result = cx(::handler21)(ctx) as String

        assertEquals("john", result)
    }

    fun handler22(@Param("age") age: Form<Int>) = age.value

    @Test
    fun `should extract Form Int parameter`() {
        val ctx = createContext(
            body = "age=25",
            contentType = "application/x-www-form-urlencoded"
        )
        val result = cx(::handler22)(ctx) as Int

        assertEquals(25, result)
    }

    fun handler23(@Param("age") age: Form<Int?>) = age.value ?: 0

    @Test
    fun `should extract nullable Form parameter when absent`() {
        val ctx = createContext(
            body = "name=test",
            contentType = "application/x-www-form-urlencoded"
        )
        val result = cx(::handler23)(ctx) as Int

        assertEquals(0, result)
    }

    fun handler24(@Param("colors") colors: Form<List<String>>) = colors.value

    @Test
    fun `should extract Form List of Strings`() {
        val ctx = createContext(
            body = "colors=red&colors=blue&colors=green",
            contentType = "application/x-www-form-urlencoded"
        )
        val result = cx(::handler24)(ctx) as List<*>

        assertEquals(listOf("red", "blue", "green"), result)
    }

    fun handler25(data: Form<Map<String, List<String>>>) = data.value.size

    @Test
    fun `should extract Form as Map`() {
        val ctx = createContext(
            body = "field1=val1&field2=val2",
            contentType = "application/x-www-form-urlencoded"
        )
        val result = cx(::handler25)(ctx) as Int

        assertEquals(2, result)
    }

    fun handler26(@Param("name") name: Form<String?>) = name.value ?: "empty"

    @Test
    fun `should return empty when Content-Type is not form`() {

        val ctx = createContext(
            body = "name=test",
            contentType = "application/json"
        )
        val result = cx(::handler26)(ctx) as String

        assertEquals("empty", result)
    }

    // ============================================
    // JSON extract
    // ============================================

    fun handler27(user: Json<UserDto>) = user.value.name

    @Test
    fun `should extract JSON as DTO`() {

        val json = """{"name":"Alice","age":30}"""
        val ctx = createContext(body = json)
        val result = cx(::handler27)(ctx) as String

        assertEquals("Alice", result)
    }

    fun handler28(user: Json<UserDto>) = user.value

    @Test
    fun `should throw when JSON parsing fails`() {

        val ctx = createContext(body = "invalid json")

        assertThrows<BadRequest> {
            cx(::handler28)(ctx)
        }
    }

    data class ComplexDto1(val users: List<UserDto>, val count: Int)

    fun handler29(data: Json<ComplexDto1>) = data.value.users.size

    @Test
    fun `should handle complex JSON structures`() {
        val json = """{"users":[{"name":"A","age":20},{"name":"B","age":25}],"count":2}"""
        val ctx = createContext(body = json)
        val result = cx(::handler29)(ctx) as Int

        assertEquals(2, result)
    }

    // ============================================
    // Header extract
    // ============================================

    fun handler30(@Param("Authorization") auth: Header) = auth.value

    @Test
    fun `should extract Header parameter`() {
        val ctx = createContext(headers = mapOf("Authorization" to "Bearer token123"))
        val result = cx(::handler30)(ctx) as String

        assertEquals("Bearer token123", result)
    }

    fun handler31(@Param("X-Custom") header: Header = Header("foobar")) = header.value

    @Test
    fun `should return empty string when Header is missing`() {
        val ctx = createContext()
        val result = cx(::handler31)(ctx) as String

        assertEquals("foobar", result)
    }

    fun handler31a(@Param("X-Custom") header: Header) = header.value

    @Test
    fun `should get null when Header is missing`() {
        val ctx = createContext()
        val result = cx(::handler31a)(ctx)
        assertNull(result)
    }

    fun handler32(@Param("content-type") ct: Header) = ct.value

    @Test
    fun `should extract case-insensitive Header`() {
        val ctx = createContext(headers = mapOf("Content-Type" to "application/json"))
        val result = cx(::handler32)(ctx) as String

        assertEquals("application/json", result)
    }

    // ============================================
    // Cookie extract
    // ============================================

    fun handler33(@Param("session") session: Cookie) = session.value

    @Test
    fun `should extract Cookie parameter`() {
        val ctx = createContext(headers = mapOf("cookie" to "session=abc123"))
        val result = cx(::handler33)(ctx) as String

        assertEquals("abc123", result)
    }

    fun handler34(@Param("token") token: Cookie = Cookie("foobar")) = token.value

    @Test
    fun `should use default value when Cookie is missing`() {
        val ctx = createContext()
        val result = cx(::handler34)(ctx) as String

        assertEquals("foobar", result)
    }

    fun handler34a(@Param("token") token: Cookie) = token.value

    @Test
    fun `should get null when Cookie is missing`() {
        val ctx = createContext()

        val result = cx(::handler34a)(ctx)
        assertNull(result)
    }

    fun handler35(@Param("user") user: Cookie) = user.value

    @Test
    fun `should extract Cookie from multiple cookies`() {

        val ctx = createContext(
            headers = mapOf("cookie" to "session=xyz; user=john; theme=dark")
        )
        val result = cx(::handler35)(ctx) as String

        assertEquals("john", result)
    }

    // ============================================
    // Text extract
    // ============================================

    fun handler36(text: Text) = text.value

    @Test
    fun `should extract Text body`() {
        val ctx = createContext(body = "Plain text content")
        val result = cx(::handler36)(ctx) as String

        assertEquals("Plain text content", result)
    }

    fun handler37(text: Text = Text("foobar")) = text.value

    @Test
    fun `should use default value when body is null`() {
        val ctx = createContext(body = null)
        val result = cx(::handler37)(ctx) as String

        assertEquals("foobar", result)
    }

    fun handler37a(text: Text) = text.value

    @Test
    fun `should get null when body is null`() {
        val ctx = createContext(body = null)
        val result = cx(::handler37a)(ctx)
        assertNull(result)
    }

    // ============================================
    // Stream extract
    // ============================================

    fun handler38(stream: Stream): Int {
        return stream.value.use { it!!.available() }
    }

    @Test
    fun `should extract Stream parameter`() {

        val data = "test data".toByteArray()
        val ctx = createContext(stream = ByteArrayInputStream(data))
        val result = cx(::handler38)(ctx) as Int

        assertEquals(data.size, result)
    }

    fun handler39(stream: Stream) = stream.value

    @Test
    fun `should get null when Stream is missing`() {
        val ctx = createContext(stream = null)
        val result = cx(::handler39)(ctx)
        assertNull(result)
    }

    // ============================================
    // UploadedFile extract
    // ============================================

    fun handler40(@Param("avatar") file: UploadedFile) = file.value?.filename

    @Test
    fun `should extract UploadedFile with explicit name`() {
        val data = buildFakeMultipart("avatar", "avatar.jpg", "image/jpeg", byteArrayOf())
        val ctx = createContext(
            contentType = "multipart/form-data", stream = data.inputStream(), multipart = listOf(
                FileItem(
                    name = "avatar",
                    filename = "avatar.jpg",
                    contentType = "image/jpeg",
                    size = 0,
                    inputStream = byteArrayOf().inputStream()
                )
            )
        )
        val result = cx(::handler40)(ctx)
        assertEquals("avatar.jpg", result)
    }

    fun handler41(file: UploadedFile) = file.value?.contentType

    @Test
    fun `should extract UploadedFile with default name 'file'`() {
        val data = buildFakeMultipart("file", "doc.pdf", "application/pdf", byteArrayOf())
        val ctx = createContext(
            contentType = "multipart/form-data", stream = data.inputStream(), multipart = listOf(
                FileItem(
                    name = "file",
                    filename = "doc.pdf",
                    contentType = "application/pdf",
                    size = 0,
                    inputStream = byteArrayOf().inputStream()
                )
            )
        )
        val result = cx(::handler41)(ctx)
        assertEquals("application/pdf", result)
    }

    fun handler42(@Param("document") file: UploadedFile) = file.value

    @Test
    fun `should get null when UploadedFile is missing`() {

        val ctx = createContext()

        val result = cx(::handler42)(ctx)
        assertNull(result)
    }

    // ============================================
    // Service inject and extract
    // ============================================

    class TestService(val value: String = "injected")

    fun handler43(service: TestService) = service.value

    @Test
    fun `should inject service from container`() {

        app.serviceContainer.registerInstance(TestService())
        val ctx = createContext()
        val result = cx(::handler43)(ctx) as String

        assertEquals("injected", result)
    }

    class MissingService

    fun handler44(service: MissingService?) = service ?: "not found"

    @Test
    fun `should get null when service not found`() {

        val ctx = createContext()
        cx(::handler44)(ctx)
    }

    fun handler44a(service: MissingService) = service

    @Test
    fun `should throw when service not found`() {

        val ctx = createContext()
        assertThrows<IllegalArgumentException> {
            cx(::handler44a)(ctx)
        }
    }

    fun handler44b(service: MissingService = MissingService()): String = service.javaClass.simpleName

    @Test
    fun `should use default value when service not found`() {

        val ctx = createContext()
        val result = cx(::handler44b)(ctx)
        assertEquals("MissingService", result)

    }

    // ============================================
    // Qualifier-based Service injection
    // ============================================

    object Primary
    object Replica

    class QualifiedService(val value: String)

    // --- object qualifier ---

    fun handlerWithPrimaryQualifier(@Qualifier("Primary") svc: QualifiedService) = svc.value
    fun handlerWithReplicaQualifier(@Qualifier("Replica") svc: QualifiedService) = svc.value

    @Test
    fun `should inject service with object qualifier (exact case)`() {
        app.provide(qualifier = Primary) { QualifiedService("primary-value") }
        app.provide(qualifier = Replica) { QualifiedService("replica-value") }

        val ctx = createContext()
        assertEquals("primary-value", cx(::handlerWithPrimaryQualifier)(ctx))
        assertEquals("replica-value", cx(::handlerWithReplicaQualifier)(ctx))
    }

    fun handlerWithLowercaseQualifier(@Qualifier("primary") svc: QualifiedService) = svc.value

    @Test
    fun `should inject service with object qualifier (case-insensitive)`() {
        app.provide(qualifier = Primary) { QualifiedService("primary-value") }

        val ctx = createContext()
        assertEquals("primary-value", cx(::handlerWithLowercaseQualifier)(ctx))
    }

    // --- string qualifier ---

    fun handlerWithStringQualifier(@Qualifier("db-main") svc: QualifiedService) = svc.value

    @Test
    fun `should inject service with string qualifier`() {
        app.provide(qualifier = "db-main") { QualifiedService("string-qualified") }

        val ctx = createContext()
        assertEquals("string-qualified", cx(::handlerWithStringQualifier)(ctx))
    }

    // --- qualifier not matched ---

    object Unregistered

    fun handlerWithUnregisteredQualifierNullable(@Qualifier("Unregistered") svc: QualifiedService?) = svc

    @Test
    fun `should get null when qualified service not found and parameter is nullable`() {
        val ctx = createContext()
        assertNull(cx(::handlerWithUnregisteredQualifierNullable)(ctx))
    }

    fun handlerWithUnregisteredQualifierNonNull(@Qualifier("Unregistered") svc: QualifiedService) = svc

    @Test
    fun `should throw when qualified service not found and parameter is non-null`() {
        val ctx = createContext()
        assertThrows<IllegalArgumentException> {
            cx(::handlerWithUnregisteredQualifierNonNull)(ctx)
        }
    }

    fun handlerWithUnregisteredQualifierDefault(
        @Qualifier("Unregistered") svc: QualifiedService = QualifiedService("default"),
    ) = svc.value

    @Test
    fun `should use default value when qualified service not found`() {
        val ctx = createContext()
        assertEquals("default", cx(::handlerWithUnregisteredQualifierDefault)(ctx))
    }

    // --- same type, multiple qualifiers are isolated ---

    fun handlerWithBothQualifiers(
        @Qualifier("Primary") primary: QualifiedService,
        @Qualifier("Replica") replica: QualifiedService,
    ) = "${primary.value}:${replica.value}"

    @Test
    fun `should resolve different instances for different qualifiers of same type`() {
        app.provide(qualifier = Primary) { QualifiedService("primary-value") }
        app.provide(qualifier = Replica) { QualifiedService("replica-value") }

        val ctx = createContext()
        assertEquals("primary-value:replica-value", cx(::handlerWithBothQualifiers)(ctx))
    }

    fun handlerNoQualifier(svc: QualifiedService) = svc.value

    @Test
    fun `should not resolve qualified service when no qualifier annotation present`() {
        // only qualified registrations exist — unqualified lookup must fail
        app.provide(qualifier = Primary) { QualifiedService("primary-value") }

        val ctx = createContext()
        assertThrows<IllegalArgumentException> {
            cx(::handlerNoQualifier)(ctx)
        }
    }

    // --- qualifier resolution in parent-child app hierarchy ---

    fun handlerResolvesFromParent(@Qualifier("Primary") svc: QualifiedService) = svc.value

    @Test
    fun `should resolve qualified service from parent app when not found in child`() {
        val parent = Colleen()
        parent.provide(qualifier = Primary) { QualifiedService("parent-primary") }

        val child = Colleen()
        parent.mount("/child", child)

        child.get("/test", ::handlerResolvesFromParent)

        val testClient = TestClient(parent)
        val rv = testClient.get("/child/test").send()
        assertEquals("parent-primary", rv.text())
    }

    @Test
    fun `should prefer child app qualified service over parent`() {
        val parent = Colleen()
        parent.provide(qualifier = Primary) { QualifiedService("parent-primary") }

        val child = Colleen()
        child.provide(qualifier = Primary) { QualifiedService("child-primary") }
        parent.mount("/child", child)

        child.get("/test", ::handlerResolvesFromParent)

        val testClient = TestClient(parent)
        val rv = testClient.get("/child/test").send()
        assertEquals("child-primary", rv.text())
    }

    // ============================================
    // Mixed params extract
    // ============================================

    fun handler45(
        ctx: Context,
        @Param("id") id: Path<Int>,
        @Param("format") format: Query<String?>,
        user: Json<UserDto>
    ): String {
        return "${ctx.path}:${id.value}:${format.value}:${user.value.name}"
    }

    @Test
    fun `should handle mixed parameter types`() {

        val ctx = createContext(
            path = "/users/123",
            queryString = "format=json",
            body = """{"name":"Bob","age":28}"""
        ).apply {
            setPathParam("id", "123")
        }

        val result = cx(::handler45)(ctx) as String
        assertEquals("/users/123:123:json:Bob", result)
    }

    fun handler46(
        @Param("page") page: Query<Int>,
        @Param("size") size: Query<Int>,
        @Param("sort") sort: Query<String?>
    ): String {
        return "page=${page.value},size=${size.value},sort=${sort.value ?: "default"}"
    }

    @Test
    fun `should handle multiple Query parameters`() {

        val ctx = createContext(queryString = "page=2&size=50")
        val result = cx(::handler46)(ctx) as String

        assertEquals("page=2,size=50,sort=default", result)
    }

    // ============================================
    // Edge cases and error handling
    // ============================================

    fun handler47(@Param("test") param: Query<String?>) = param.value ?: "empty"

    @Test
    fun `should handle empty query string`() {

        val ctx = createContext(queryString = "")
        val result = cx(::handler47)(ctx) as String

        assertEquals("empty", result)
    }

    fun handler48(@Param("name") name: Path<String>) = name.value

    @Test
    fun `should handle special characters in Path parameter`() {

        val ctx = createContext().apply {
            setPathParam("name", "hello-world_123")
        }
        val result = cx(::handler48)(ctx) as String

        assertEquals("hello-world_123", result)
    }

    fun handler49(@Param("id") id: Path<Int>) = id.value

    @Test
    fun `should throw descriptive error for invalid Int conversion`() {

        val ctx = createContext().apply { setPathParam("id", "12.34") }

        val exception = assertThrows<TypeConversionFailed> {
            cx(::handler49)(ctx)
        }
        assertTrue(exception.message!!.contains("12.34"))
    }

    fun handler50(
        @Param("price") price: Query<Double>,
        @Param("rate") rate: Query<Float>
    ) = Pair(price.value, rate.value)

    @Test
    fun `should handle Float and Double conversions`() {

        val ctx = createContext(queryString = "price=99.99&rate=0.15")
        val result = cx(::handler50)(ctx) as Pair<*, *>

        assertEquals(99.99, result.first)
        assertEquals(0.15f, result.second)
    }

    fun handler51(@Param("timestamp") ts: Query<Long>) = ts.value

    @Test
    fun `should handle Long conversions for large numbers`() {

        val ctx = createContext(queryString = "timestamp=1234567890123")
        val result = cx(::handler51)(ctx) as Long

        assertEquals(1234567890123L, result)
    }

    // ============================================
    // Helper Methods
    // ============================================

    private fun createContext(
        path: String = "/test",
        method: String = "GET",
        queryString: String = "",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        stream: InputStream? = null,
        contentType: String? = null,
        multipart: List<Part> = emptyList(),
    ): Context {
        val headerMap = Headers()
        headers.forEach { (k, v) -> headerMap[k] = v }
        contentType?.let { headerMap["Content-Type"] = it }

        val inputStream = stream ?: body?.byteInputStream()

        val request = Request(
            method = method,
            path = path,
            queryString = queryString,
            headers = headerMap,
            stream = inputStream,
            multipartSupplier = { multipart },
        )

        return Context(
            request = request,
            response = Response(),
            app = app
        )
    }
}

// ============================================
// Additional Edge Case Tests
// ============================================

class ExtractorEdgeCaseTest {

    fun handler1(@Param("data") data: Query<String>) = data.value.length

    @Test
    fun `should handle very long strings`() {

        val longString = "x".repeat(10000)
        val ctx = createMinimalContext(queryString = "data=$longString")
        val result = cx(::handler1)(ctx) as Int

        assertEquals(10000, result)
    }

    fun handler2(
        @Param("temp") temp: Query<Int>,
        @Param("balance") balance: Query<Double>
    ) = Pair(temp.value, balance.value)

    @Test
    fun `should handle negative numbers`() {

        val ctx = createMinimalContext(queryString = "temp=-15&balance=-99.99")
        val result = cx(::handler2)(ctx) as Pair<*, *>

        assertEquals(-15, result.first)
        assertEquals(-99.99, result.second)
    }

    fun handler3(@Param("count") count: Query<Int>) = count.value

    @Test
    fun `should handle zero values`() {

        val ctx = createMinimalContext(queryString = "count=0")
        val result = cx(::handler3)(ctx) as Int

        assertEquals(0, result)
    }

    fun handler4(@Param("tags") tags: Query<List<String>>) = tags.value.isEmpty()

    @Test
    fun `should handle empty List parameters`() {

        val ctx = createMinimalContext(queryString = "other=value")
        val result = cx(::handler4)(ctx) as Boolean

        assertTrue(result)
    }

    private fun createMinimalContext(queryString: String = ""): Context {
        val request = Request(
            method = "GET",
            path = "/",
            queryString = queryString,
            headers = Headers()
        )

        val mockApp = Colleen()

        return Context(request, Response(), mockApp)
    }
}

fun buildFakeMultipart(
    name: String,
    filename: String,
    contentType: String,
    content: ByteArray,
    boundary: String = "----TEST-BD",
): ByteArray {
    val crlf = "\r\n"

    val header = buildString {
        append("--$boundary").append(crlf)
        append("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"").append(crlf)
        append("Content-Type: $contentType").append(crlf)
        append(crlf)
    }.toByteArray()

    val footer = buildString {
        append(crlf)
        append("--$boundary--").append(crlf)
    }.toByteArray()

    return header + content + footer
}

