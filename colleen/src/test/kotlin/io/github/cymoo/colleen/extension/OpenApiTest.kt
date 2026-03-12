package io.github.cymoo.colleen.extension

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.util.http.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for the OpenAPI 3.0.3 spec generation extension.
 *
 * Strategy
 * --------
 * All tests drive the system through its public API: register routes on a [Colleen]
 * instance, call [enableOpenApi], then invoke the spec-generation handler directly
 * to obtain the spec [Map].  This exercises the full pipeline (route collection →
 * operation building → schema generation) without starting an HTTP server.
 *
 * Helper
 * ------
 * [spec] is a small inline helper that wires [enableOpenApi] onto a fresh [Colleen]
 * instance, then reaches into the registered spec-route handler to produce the map.
 * Tests never touch private internals directly — if a refactor breaks the contract
 * the tests will catch it.
 */

// ============================================================================
// Test fixtures — DTOs, enums, handler functions
// ============================================================================

enum class Role { ADMIN, VIEWER }

data class Address(
    val street: String?,
    val city: String?,
)

data class UserDto(
    @Schema(description = "Unique user ID", example = "42")
    val id: Int,
    @Schema(description = "Login name", example = "alice")
    val username: String?,
    val role: Role?,
    val address: Address?,
)

data class CreateUserRequest(
    val username: String?,
    val age: Int,           // primitive → required in schema
)

data class Pagination(
    val page: Int,
    val size: Int,
)

// -- top-level handler functions used as KFunction references -----------------

fun handlerNoParams(ctx: Context) {}

@Summary("Get user by ID")
@Description("Returns a single user. Returns 404 when not found.")
@Tags("users")
@ParamDesc(name = "id", description = "Unique user identifier")
@ResponseDesc(200, "User found")
@ResponseDesc(404, "User not found")
fun getUser(id: Path<Int>): UserDto = error("stub")

@ParamDesc(name = "q", description = "Search keyword")
@ParamDesc(name = "active", description = "Filter by active status")
fun listUsers(
    q: Query<String?>,
    active: Query<Boolean> = Query(true),
): List<UserDto> = error("stub")

fun createUser(body: Json<CreateUserRequest>): UserDto = error("stub")

fun submitForm(data: Form<CreateUserRequest>) {}

fun uploadFile(avatar: UploadedFile) {}

fun sendText(body: Text) {}

fun streamBody(body: Stream) {}

fun getHeader(authorization: Header) {}

fun getCookie(session: Cookie) {}

fun queryWithDto(filter: Query<Pagination>): List<UserDto> = error("stub")

fun queryWithList(ids: Query<List<Int>>): List<UserDto> = error("stub")

fun queryWithMap(meta: Query<Map<String, String>>): Map<String, String> = error("stub")

fun queryWithMultiValueMap(tags: Query<Map<String, List<String>>>): Map<String, Any> = error("stub")

fun returnsString(ctx: Context): String = error("stub")

fun returnsUnit(ctx: Context) {}

// -- DTOs for testing @Schema features (name, hidden, type, format) -----------

data class AliasedDto(
    @Schema(name = "q")
    val keyword: String?,
    val page: Int,
)

data class HiddenFieldDto(
    val visible: String?,
    @Schema(hidden = true)
    val secret: String?,
)

data class TypeOverrideDto(
    @Schema(type = "string", format = "date-time")
    val startTime: java.time.LocalDateTime?,
    @Schema(type = "string")
    val customField: Any?,
)

data class ShortDto(
    val shortVal: Short,
    val byteVal: Byte,
)

data class TemporalDto(
    val date: java.time.LocalDate?,
    val dateTime: java.time.LocalDateTime?,
    val instant: java.time.Instant?,
    val uuid: java.util.UUID?,
    val bigDecimal: java.math.BigDecimal?,
    val bigInteger: java.math.BigInteger?,
)

// -- handler functions for new features ---------------------------------------

@Hidden
@Suppress("UNUSED_PARAMETER")
fun hiddenHandler(id: Path<Int>): String = error("stub")

fun createAliased(body: Json<AliasedDto>): String = error("stub")

fun createHiddenField(body: Json<HiddenFieldDto>): String = error("stub")

fun createTypeOverride(body: Json<TypeOverrideDto>): String = error("stub")

fun queryShort(v: Query<Short>): String = error("stub")

fun queryByte(v: Query<Byte>): String = error("stub")

fun queryTemporal(body: Json<TemporalDto>): TemporalDto = error("stub")

fun queryShortDto(body: Json<ShortDto>): ShortDto = error("stub")

// ============================================================================
// Test helpers
// ============================================================================

private fun createTestContext(
    app: Colleen,
    method: String = "GET",
    path: String = "/",
    headers: Map<String, String> = emptyMap()
): Context {
    val headersObj = Headers()
    headers.forEach { (k, v) -> headersObj[k] = v }

    val request = Request(
        method = method,
        path = path,
        headers = headersObj
    )
    return Context(request = request, app=app)
}

/**
 * Builds a [Colleen] app, applies [setup] to register routes, then installs
 * [enableOpenApi] and returns the generated spec map.
 *
 * The spec handler is invoked synchronously via a minimal fake [Context] that
 * captures the returned value — no network I/O required.
 */
@Suppress("UNCHECKED_CAST")
private fun spec(
    title: String = "Test API",
    version: String = "0.0.1",
    description: String? = null,
    filter: ((path: String, method: String) -> Boolean)? = null,
    setup: Colleen.() -> Unit,
): Map<String, Any> {
    val app = Colleen()
    app.setup()
    app.enableOpenApi(title = title, version = version, description = description, filter = filter)

    // The spec route is always registered last at "/openapi.json".
    // We locate it and invoke its handler directly to get the map.
    val specRoute = app.router.routes.last { it.path == "/openapi.json" }
    val ctx = createTestContext(app)
    return specRoute.handler(ctx) as Map<String, Any>
}

/** Convenience: navigate into the spec path hierarchy. */
@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.path(vararg keys: String): Any? =
    keys.fold(this as Any?) { acc, key -> (acc as? Map<String, Any>)?.get(key) }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.operation(path: String, method: String): Map<String, Any> =
    (this.path("paths", path, method) as? Map<String, Any>)
        ?: error("No operation found for $method $path")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.parameters(path: String, method: String): List<Map<String, Any>> =
    operation(path, method)["parameters"] as? List<Map<String, Any>> ?: emptyList()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.requestBody(path: String, method: String): Map<String, Any>? =
    operation(path, method)["requestBody"] as? Map<String, Any>

// ============================================================================
// Test: info block
// ============================================================================

class OpenApiInfoTest {

    @Test
    fun `spec contains correct openapi version`() {
        val s = spec {}
        assertEquals("3.0.3", s["openapi"])
    }

    @Test
    fun `info block reflects title and version`() {
        val s = spec(title = "My Service", version = "2.1.0") {}
        val info = s["info"] as Map<*, *>
        assertEquals("My Service", info["title"])
        assertEquals("2.1.0", info["version"])
    }

    @Test
    fun `info block includes description when provided`() {
        val s = spec(description = "A test service") {}
        assertEquals("A test service", s.path("info", "description"))
    }

    @Test
    fun `info block omits description when not provided`() {
        val s = spec {}
        assertFalse((s["info"] as Map<*, *>).containsKey("description"))
    }
}

// ============================================================================
// Test: route collection
// ============================================================================

class RouteCollectionTest {

    @Test
    fun `registered route appears in paths`() {
        val s = spec { get("/hello") { "hi" } }
        assertNotNull(s.path("paths", "/hello", "get"))
    }

    @Test
    fun `multiple routes on different paths are all collected`() {
        val s = spec {
            get("/users", ::listUsers)
            post("/users", ::createUser)
            get("/users/{id}", ::getUser)
        }
        val paths = s["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/users"))
        assertTrue(paths.containsKey("/users/{id}"))
    }

    @Test
    fun `wildcard method expands to all standard HTTP methods`() {
        val s = spec { all("/resource") { } }
        val item = s.path("paths", "/resource") as Map<*, *>
        listOf("get", "post", "put", "delete", "patch", "head", "options")
            .forEach { method -> assertTrue(item.containsKey(method), "Missing method: $method") }
    }

    @Test
    fun `spec and swagger-ui routes are excluded from paths`() {
        val s = spec { get("/users", ::listUsers) }
        val paths = s["paths"] as Map<*, *>
        assertFalse(paths.containsKey("/openapi.json"))
        assertFalse(paths.containsKey("/swagger-ui"))
    }

    @Test
    fun `routes from mounted sub-app appear under mount prefix`() {
        val s = spec {
            val sub = Colleen()
            sub.get("/profile", ::getUser)
            mount("/v1", sub)
        }
        assertNotNull(s.path("paths", "/v1/profile", "get"))
    }

    @Test
    fun `deeply nested mount prefixes are combined correctly`() {
        val s = spec {
            val inner = Colleen()
            inner.get("/item", ::getUser)

            val outer = Colleen()
            outer.mount("/api", inner)

            mount("/v2", outer)
        }
        assertNotNull(s.path("paths", "/v2/api/item", "get"))
    }
}

// ============================================================================
// Test: lambda handlers
// ============================================================================

class LambdaHandlerTest {

    @Test
    fun `lambda handler produces minimal operation with only responses`() {
        val s = spec { get("/ping") { "pong" } }
        val op = s.operation("/ping", "get")
        // Lambda handlers cannot carry annotations, so only "responses" is expected.
        assertFalse(op.containsKey("parameters"))
        assertFalse(op.containsKey("requestBody"))
        assertFalse(op.containsKey("summary"))
        assertNotNull(op["responses"])
    }
}

// ============================================================================
// Test: KFunction — operation metadata annotations
// ============================================================================

class KFunctionMetadataTest {

    @Test
    fun `operationId is ClassName_methodName for top-level function`() {
        val s = spec { get("/users/{id}", ::getUser) }
        // Top-level Kotlin functions compile into a synthetic class whose simple name
        // ends with "Kt"; we assert that the method name segment is correct.
        val opId = s.operation("/users/{id}", "get")["operationId"] as String
        assertTrue(opId.endsWith("_getUser"), "Expected operationId ending in '_getUser', got: $opId")
    }

    @Test
    fun `@Summary is reflected in operation summary field`() {
        val s = spec { get("/users/{id}", ::getUser) }
        assertEquals("Get user by ID", s.operation("/users/{id}", "get")["summary"])
    }

    @Test
    fun `@Description is reflected in operation description field`() {
        val s = spec { get("/users/{id}", ::getUser) }
        assertEquals(
            "Returns a single user. Returns 404 when not found.",
            s.operation("/users/{id}", "get")["description"]
        )
    }

    @Test
    fun `@Tags on method are included in operation tags`() {
        val s = spec { get("/users/{id}", ::getUser) }
        @Suppress("UNCHECKED_CAST")
        val tags = s.operation("/users/{id}", "get")["tags"] as List<String>
        assertTrue(tags.contains("users"))
    }

    @Test
    fun `operation without annotations omits summary, description and tags`() {
        val s = spec { get("/users", ::listUsers) }
        val op = s.operation("/users", "get")
        assertFalse(op.containsKey("summary"))
        assertFalse(op.containsKey("description"))
        assertFalse(op.containsKey("tags"))
    }
}

// ============================================================================
// Test: KFunction — path parameters
// ============================================================================

class PathParameterTest {

    @Test
    fun `Path param is emitted as a required path parameter`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val param = s.parameters("/users/{id}", "get").single { it["name"] == "id" }
        assertEquals("path", param["in"])
        assertEquals(true, param["required"])
    }

    @Test
    fun `Path param schema reflects the generic type`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val schema = s.parameters("/users/{id}", "get")
            .single { it["name"] == "id" }["schema"] as Map<*, *>
        assertEquals("integer", schema["type"])
        assertEquals("int32", schema["format"])
    }

    @Test
    fun `@ParamDesc description is attached to the path parameter`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val param = s.parameters("/users/{id}", "get").single { it["name"] == "id" }
        assertEquals("Unique user identifier", param["description"])
    }
}

// ============================================================================
// Test: KFunction — query parameters
// ============================================================================

class QueryParameterTest {

    @Test
    fun `nullable Query param is not required`() {
        val s = spec { get("/users", ::listUsers) }
        val param = s.parameters("/users", "get").single { it["name"] == "q" }
        assertEquals("query", param["in"])
        assertEquals(false, param["required"])
    }

    @Test
    fun `Query param with default value is not required`() {
        val s = spec { get("/users", ::listUsers) }
        val param = s.parameters("/users", "get").single { it["name"] == "active" }
        assertEquals(false, param["required"])
    }

    @Test
    fun `@ParamDesc descriptions are attached to correct query params`() {
        val s = spec { get("/users", ::listUsers) }
        val params = s.parameters("/users", "get").associateBy { it["name"] }
        assertEquals("Search keyword", params["q"]?.get("description"))
        assertEquals("Filter by active status", params["active"]?.get("description"))
    }

    @Test
    fun `Query of Boolean generates boolean schema`() {
        val s = spec { get("/users", ::listUsers) }
        val schema = s.parameters("/users", "get")
            .single { it["name"] == "active" }["schema"] as Map<*, *>
        assertEquals("boolean", schema["type"])
    }

    @Test
    fun `Query of DTO generates object schema (not expanded)`() {
        val s = spec { get("/items", ::queryWithDto) }
        val schema = s.parameters("/items", "get")
            .single { it["name"] == "filter" }["schema"] as Map<*, *>
        assertEquals("object", schema["type"])
        assertNotNull(schema["properties"])
    }

    @Test
    fun `Query of List generates array schema with correct item type`() {
        val s = spec { get("/items", ::queryWithList) }
        val schema = s.parameters("/items", "get")
            .single { it["name"] == "ids" }["schema"] as Map<*, *>
        assertEquals("array", schema["type"])
        val items = schema["items"] as Map<*, *>
        assertEquals("integer", items["type"])
    }

    @Test
    fun `Query of Map generates object schema with additionalProperties`() {
        val s = spec { get("/items", ::queryWithMap) }
        val schema = s.parameters("/items", "get")
            .single { it["name"] == "meta" }["schema"] as Map<*, *>
        assertEquals("object", schema["type"])
        val addlProps = schema["additionalProperties"] as Map<*, *>
        assertEquals("string", addlProps["type"])
    }

    @Test
    fun `Query of Map with List values uses array additionalProperties`() {
        val s = spec { get("/items", ::queryWithMultiValueMap) }
        val schema = s.parameters("/items", "get")
            .single { it["name"] == "tags" }["schema"] as Map<*, *>
        val addlProps = schema["additionalProperties"] as Map<*, *>
        assertEquals("array", addlProps["type"])
    }
}

// ============================================================================
// Test: KFunction — header and cookie parameters
// ============================================================================

class HeaderAndCookieParameterTest {

    @Test
    fun `Header param is emitted in header location with string schema`() {
        val s = spec { get("/secure", ::getHeader) }
        val param = s.parameters("/secure", "get").single { it["name"] == "authorization" }
        assertEquals("header", param["in"])
        assertEquals("string", (param["schema"] as Map<*, *>)["type"])
    }

    @Test
    fun `Cookie param is emitted in cookie location with string schema`() {
        val s = spec { get("/session", ::getCookie) }
        val param = s.parameters("/session", "get").single { it["name"] == "session" }
        assertEquals("cookie", param["in"])
        assertEquals("string", (param["schema"] as Map<*, *>)["type"])
    }
}

// ============================================================================
// Test: KFunction — request bodies
// ============================================================================

class RequestBodyTest {

    @Test
    fun `Json body produces application-json requestBody`() {
        val s = spec { post("/users", ::createUser) }
        val rb = s.requestBody("/users", "post")!!
        assertEquals(true, rb["required"])
        assertNotNull(rb.path("content", "application/json", "schema"))
    }

    @Test
    fun `Json body schema reflects the DTO type`() {
        val s = spec { post("/users", ::createUser) }
        val schema = s.requestBody("/users", "post")!!
            .path("content", "application/json", "schema") as Map<*, *>
        assertEquals("object", schema["type"])
    }

    @Test
    fun `Form body produces application-x-www-form-urlencoded requestBody`() {
        val s = spec { post("/submit", ::submitForm) }
        val rb = s.requestBody("/submit", "post")!!
        assertNotNull(rb.path("content", "application/x-www-form-urlencoded", "schema"))
    }

    @Test
    fun `Text body produces text-plain requestBody`() {
        val s = spec { post("/text", ::sendText) }
        val rb = s.requestBody("/text", "post")!!
        assertNotNull(rb.path("content", "text/plain", "schema"))
        assertEquals("string", rb.path("content", "text/plain", "schema", "type"))
    }

    @Test
    fun `Stream body produces application-octet-stream requestBody`() {
        val s = spec { post("/upload", ::streamBody) }
        val rb = s.requestBody("/upload", "post")!!
        assertNotNull(rb.path("content", "application/octet-stream", "schema"))
        assertEquals("binary", rb.path("content", "application/octet-stream", "schema", "format"))
    }

    @Test
    fun `UploadedFile produces multipart-form-data requestBody with binary property`() {
        val s = spec { post("/avatar", ::uploadFile) }
        val rb = s.requestBody("/avatar", "post")!!
        @Suppress("UNCHECKED_CAST")
        val props = rb.path("content", "multipart/form-data", "schema", "properties") as Map<String, Any>
        val fileField = props["avatar"] as Map<*, *>
        assertEquals("binary", fileField["format"])
    }
}

// ============================================================================
// Test: KFunction — response building
// ============================================================================

class ResponseBuildingTest {

    @Test
    fun `default 200 response is always present`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val responses = s.operation("/users/{id}", "get")["responses"] as Map<*, *>
        assertTrue(responses.containsKey("200"))
    }

    @Test
    fun `@ResponseDesc overrides the 200 response description`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val desc = s.path("paths", "/users/{id}", "get", "responses", "200", "description")
        assertEquals("User found", desc)
    }

    @Test
    fun `@ResponseDesc for non-200 status adds extra response entry`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val responses = s.operation("/users/{id}", "get")["responses"] as Map<*, *>
        assertTrue(responses.containsKey("404"))
        assertEquals("User not found", (responses["404"] as Map<*, *>)["description"])
    }

    @Test
    fun `Unit return type produces response without content`() {
        val s = spec { post("/submit", ::submitForm) }
        val response = s.path("paths", "/submit", "post", "responses", "200") as Map<*, *>
        assertFalse(response.containsKey("content"))
    }

    @Test
    fun `String return type produces text-plain response content`() {
        val s = spec { get("/text", ::returnsString) }
        assertNotNull(s.path("paths", "/text", "get", "responses", "200", "content", "text/plain"))
    }

    @Test
    fun `DTO return type produces application-json response content`() {
        val s = spec { get("/users/{id}", ::getUser) }
        assertNotNull(
            s.path("paths", "/users/{id}", "get", "responses", "200", "content", "application/json")
        )
    }
}

// ============================================================================
// Test: Schema generation — scalars and primitives
// ============================================================================

class ScalarSchemaTest {

    @Test
    fun `Int produces integer int32 schema`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val schema = s.parameters("/users/{id}", "get")
            .single { it["name"] == "id" }["schema"] as Map<*, *>
        assertEquals("integer", schema["type"])
        assertEquals("int32", schema["format"])
    }

    @Test
    fun `Boolean produces boolean schema`() {
        val s = spec { get("/users", ::listUsers) }
        val schema = s.parameters("/users", "get")
            .single { it["name"] == "active" }["schema"] as Map<*, *>
        assertEquals("boolean", schema["type"])
    }

    @Test
    fun `String query param produces string schema`() {
        val s = spec { get("/users", ::listUsers) }
        val schema = s.parameters("/users", "get")
            .single { it["name"] == "q" }["schema"] as Map<*, *>
        assertEquals("string", schema["type"])
    }
}

// ============================================================================
// Test: Schema generation — enums
// ============================================================================

class EnumSchemaTest {

    // A handler whose DTO contains an enum field provides an indirect test.
    // We also define a direct Query<Role> handler here.

    fun queryByRole(role: Query<Role>): List<UserDto> = error("stub")

    @Test
    fun `enum type produces string schema with enum values`() {
        val s = spec { get("/users", ::queryByRole) }
        val schema = s.parameters("/users", "get")
            .single { it["name"] == "role" }["schema"] as Map<*, *>
        assertEquals("string", schema["type"])
        @Suppress("UNCHECKED_CAST")
        val values = schema["enum"] as List<String>
        assertEquals(listOf("ADMIN", "VIEWER"), values)
    }

    @Test
    fun `enum field inside DTO schema is rendered correctly`() {
        val s = spec { get("/users/{id}", ::getUser) }
        @Suppress("UNCHECKED_CAST")
        val props = s.path(
            "paths", "/users/{id}", "get", "responses", "200",
            "content", "application/json", "schema", "properties"
        ) as Map<String, Any>
        val roleSchema = props["role"] as Map<*, *>
        assertEquals("string", roleSchema["type"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((roleSchema["enum"] as List<String>).containsAll(listOf("ADMIN", "VIEWER")))
    }
}

// ============================================================================
// Test: Schema generation — objects and nesting
// ============================================================================

class ObjectSchemaTest {

    @Test
    fun `DTO fields are reflected into properties`() {
        val s = spec { post("/users", ::createUser) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/users", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        assertTrue(props.containsKey("username"))
        assertTrue(props.containsKey("age"))
    }

    @Test
    fun `primitive field appears in required array`() {
        val s = spec { post("/users", ::createUser) }
        @Suppress("UNCHECKED_CAST")
        val required = s.requestBody("/users", "post")!!
            .path("content", "application/json", "schema", "required") as List<String>
        assertTrue(required.contains("age"))
    }

    @Test
    fun `non-primitive field is marked nullable`() {
        val s = spec { post("/users", ::createUser) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/users", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        val usernameSchema = props["username"] as Map<*, *>
        assertEquals(true, usernameSchema["nullable"])
    }

    @Test
    fun `primitive field is not marked nullable`() {
        val s = spec { post("/users", ::createUser) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/users", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        val ageSchema = props["age"] as Map<*, *>
        assertFalse(ageSchema.containsKey("nullable"))
    }

    @Test
    fun `nested DTO is recursively expanded up to MAX_DEPTH`() {
        val s = spec { get("/users/{id}", ::getUser) }
        @Suppress("UNCHECKED_CAST")
        val props = s.path(
            "paths", "/users/{id}", "get", "responses", "200",
            "content", "application/json", "schema", "properties"
        ) as Map<String, Any>
        val addressSchema = props["address"] as Map<*, *>
        // Address is a non-primitive field; it should expand into an object schema.
        assertEquals("object", addressSchema["type"])
        val addrProps = addressSchema["properties"] as Map<*, *>
        assertTrue(addrProps.containsKey("street"))
        assertTrue(addrProps.containsKey("city"))
    }

    @Test
    fun `@Schema description and example are applied to the field schema`() {
        val s = spec { get("/users/{id}", ::getUser) }
        @Suppress("UNCHECKED_CAST")
        val props = s.path(
            "paths", "/users/{id}", "get", "responses", "200",
            "content", "application/json", "schema", "properties"
        ) as Map<String, Any>
        val idSchema = props["id"] as Map<*, *>
        assertEquals("Unique user ID", idSchema["description"])
        assertEquals("42", idSchema["example"])
    }
}

// ============================================================================
// Test: Schema generation — collections and maps
// ============================================================================

class CollectionSchemaTest {

    @Test
    fun `List return type generates array schema`() {
        val s = spec { get("/users", ::listUsers) }
        val schema = s.path(
            "paths", "/users", "get", "responses", "200",
            "content", "application/json", "schema"
        ) as Map<*, *>
        // The raw return type List::class is known; generic element type is erased at runtime.
        assertEquals("array", schema["type"])
    }

    @Test
    fun `List of DTO generates array schema without items due to type erasure`() {
        val s = spec { get("/users", ::listUsers) }
        val schema = s.path(
            "paths", "/users", "get", "responses", "200",
            "content", "application/json", "schema"
        ) as Map<*, *>
        // items is absent because the generic element type UserDto is erased at runtime
        assertFalse(schema.containsKey("items"))
    }

    @Test
    fun `Map return type generates object schema without additionalProperties due to type erasure`() {
        val s = spec { get("/meta", ::queryWithMap) }
        val schema = s.path(
            "paths", "/meta", "get", "responses", "200",
            "content", "application/json", "schema"
        ) as Map<*, *>
        assertEquals("object", schema["type"])
        // additionalProperties is absent because Map's value type is erased at runtime
        assertFalse(schema.containsKey("additionalProperties"))
    }
}

// ============================================================================
// Test: Tags merging (class-level + method-level)
// ============================================================================

class TagsMergingTest {

    @Tags("orders")
    @Controller("/orders")
    class OrderController {
        @Tags("internal")
        @Get("/{id}")
        fun getOrder(id: Path<Int>): String = error("stub")

        @Get("/")
        fun listOrders(): List<String> = error("stub")
    }

    @Test
    fun `class-level and method-level tags are merged and deduplicated`() {
        val app = Colleen()
        app.addController(OrderController())
        app.enableOpenApi()
        val specRoute = app.router.routes.last { it.path == "/openapi.json" }
        @Suppress("UNCHECKED_CAST")
        val s = specRoute.handler(createTestContext(app)) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val tags = s.operation("/orders/{id}", "get")["tags"] as List<String>
        assertTrue(tags.contains("orders"))
        assertTrue(tags.contains("internal"))
    }

    @Test
    fun `class-level tags appear on method without own @Tags`() {
        val app = Colleen()
        app.addController(OrderController())
        app.enableOpenApi()
        val specRoute = app.router.routes.last { it.path == "/openapi.json" }
        @Suppress("UNCHECKED_CAST")
        val s = specRoute.handler(createTestContext(app)) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val tags = s.operation("/orders", "get")["tags"] as List<String>  // "/orders/" → "/orders"
        assertTrue(tags.contains("orders"))
        assertFalse(tags.contains("internal"))
    }
}

// ============================================================================
// Test: operationId uniqueness for overloaded methods
// ============================================================================

class OperationIdTest {

    @Test
    fun `operationId for non-overloaded KFunction is ClassName_methodName`() {
        val s = spec { get("/users/{id}", ::getUser) }
        val opId = s.operation("/users/{id}", "get")["operationId"] as String
        assertTrue(opId.endsWith("_getUser"))
        // Must not contain a parameter-type suffix when there are no overloads.
        assertFalse(opId.endsWith("_getUser_"))
    }

    inner class OverloadedController {
        @Get("/a")
        fun handle1(id: Path<Int>): String = error("stub")

        @Get("/b")
        fun handle2(name: Path<String>): String = error("stub")
    }

    @Test
    fun `operationIds for overloaded methods include parameter type suffix`() {
        val app = Colleen()
        app.addController(OverloadedController())
        app.enableOpenApi()
        val specRoute = app.router.routes.last { it.path == "/openapi.json" }
        @Suppress("UNCHECKED_CAST")
        val s = specRoute.handler(createTestContext(app)) as Map<String, Any>
        val paths = s["paths"] as Map<*, *>

        val opIdA = ((paths["/a"] as Map<*, *>)["get"] as Map<*, *>)["operationId"] as String
        val opIdB = ((paths["/b"] as Map<*, *>)["get"] as Map<*, *>)["operationId"] as String
        assertNotEquals(opIdA, opIdB)
    }
}

// ============================================================================
// Test: Context parameter is silently ignored
// ============================================================================

class ContextParameterTest {

    @Test
    fun `Context parameter does not appear in OpenAPI parameters`() {
        val s = spec { get("/ping") { ctx: Context -> "pong" } }
        // Lambda handler — no parameters expected either way.
        assertTrue(s.operation("/ping", "get")["parameters"] == null ||
                (s.operation("/ping", "get")["parameters"] as List<*>).isEmpty())
    }

    @Test
    fun `Context parameter mixed with other params is excluded`() {
        val s = spec { get("/text", ::returnsString) }
        // returnsString takes Context; it must not surface as a parameter.
        val params = s.operation("/text", "get")["parameters"]
        assertTrue(params == null || (params as List<*>).isEmpty())
    }
}

// ============================================================================
// Test: filter parameter for enableOpenApi
// ============================================================================

class FilterTest {

    @Test
    fun `filter excludes routes by path`() {
        val s = spec(filter = { path, _ -> path != "/internal" }) {
            get("/public", ::listUsers)
            get("/internal", ::getUser)
        }
        val paths = s["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/public"))
        assertFalse(paths.containsKey("/internal"))
    }

    @Test
    fun `filter excludes routes by method`() {
        val s = spec(filter = { _, method -> method != "delete" }) {
            get("/users", ::listUsers)
            delete("/users/{id}", ::getUser)
        }
        val paths = s["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/users"))
        assertFalse(paths.containsKey("/users/{id}"))
    }

    @Test
    fun `filter can exclude individual methods from wildcard route`() {
        val s = spec(filter = { _, method -> method != "delete" && method != "patch" }) {
            all("/resource") { "ok" }
        }
        val item = s.path("paths", "/resource") as Map<*, *>
        assertTrue(item.containsKey("get"))
        assertTrue(item.containsKey("post"))
        assertFalse(item.containsKey("delete"))
        assertFalse(item.containsKey("patch"))
    }

    @Test
    fun `no filter includes all routes`() {
        val s = spec {
            get("/a", ::listUsers)
            get("/b", ::getUser)
        }
        val paths = s["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/a"))
        assertTrue(paths.containsKey("/b"))
    }
}

// ============================================================================
// Test: @Hidden annotation
// ============================================================================

class HiddenAnnotationTest {

    @Test
    fun `@Hidden on function excludes route from spec`() {
        val s = spec {
            get("/visible", ::listUsers)
            get("/hidden", ::hiddenHandler)
        }
        val paths = s["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/visible"))
        assertFalse(paths.containsKey("/hidden"))
    }

    @Test
    fun `@Hidden on controller class excludes all its routes`() {
        @Hidden
        @Controller("/internal")
        class HiddenController {
            @Get("/metrics")
            fun metrics(): String = error("stub")

            @Get("/health")
            fun health(): String = error("stub")
        }

        val app = Colleen()
        app.addController(HiddenController())
        app.get("/public", ::listUsers)
        app.enableOpenApi()
        val specRoute = app.router.routes.last { it.path == "/openapi.json" }
        @Suppress("UNCHECKED_CAST")
        val s = specRoute.handler(createTestContext(app)) as Map<String, Any>

        val paths = s["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/public"))
        assertFalse(paths.containsKey("/internal/metrics"))
        assertFalse(paths.containsKey("/internal/health"))
    }

    @Test
    fun `@Hidden on method within controller hides only that method`() {
        @Controller("/api")
        class PartiallyHiddenController {
            @Get("/visible")
            fun visible(): String = error("stub")

            @Hidden
            @Get("/hidden")
            fun hidden(): String = error("stub")
        }

        val app = Colleen()
        app.addController(PartiallyHiddenController())
        app.enableOpenApi()
        val specRoute = app.router.routes.last { it.path == "/openapi.json" }
        @Suppress("UNCHECKED_CAST")
        val s = specRoute.handler(createTestContext(app)) as Map<String, Any>

        val paths = s["paths"] as Map<*, *>
        assertTrue(paths.containsKey("/api/visible"))
        assertFalse(paths.containsKey("/api/hidden"))
    }
}

// ============================================================================
// Test: @Schema name (alias) support
// ============================================================================

class SchemaAliasTest {

    @Test
    fun `@Schema name overrides field name in schema properties`() {
        val s = spec { post("/aliased", ::createAliased) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/aliased", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        assertTrue(props.containsKey("q"), "Aliased field 'q' should be present")
        assertFalse(props.containsKey("keyword"), "Original field name 'keyword' should not be present")
    }

    @Test
    fun `non-aliased fields still use original name`() {
        val s = spec { post("/aliased", ::createAliased) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/aliased", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        assertTrue(props.containsKey("page"))
    }
}

// ============================================================================
// Test: @Schema hidden field support
// ============================================================================

class SchemaHiddenFieldTest {

    @Test
    fun `@Schema hidden field is excluded from schema`() {
        val s = spec { post("/hidden-field", ::createHiddenField) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/hidden-field", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        assertTrue(props.containsKey("visible"))
        assertFalse(props.containsKey("secret"), "Hidden field 'secret' should not be in schema")
    }
}

// ============================================================================
// Test: @Schema type/format override
// ============================================================================

class SchemaTypeOverrideTest {

    @Test
    fun `@Schema type overrides inferred type`() {
        val s = spec { post("/type-override", ::createTypeOverride) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/type-override", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        val startTimeSchema = props["startTime"] as Map<*, *>
        assertEquals("string", startTimeSchema["type"])
        assertEquals("date-time", startTimeSchema["format"])
    }

    @Test
    fun `@Schema type without format produces only type`() {
        val s = spec { post("/type-override", ::createTypeOverride) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/type-override", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        val customSchema = props["customField"] as Map<*, *>
        assertEquals("string", customSchema["type"])
        assertFalse(customSchema.containsKey("format"))
    }
}

// ============================================================================
// Test: Additional type support in typeToSchema
// ============================================================================

class AdditionalTypeSchemaTest {

    @Test
    fun `Short produces integer int32 schema`() {
        val s = spec { get("/short", ::queryShort) }
        val schema = s.parameters("/short", "get")
            .single { it["name"] == "v" }["schema"] as Map<*, *>
        assertEquals("integer", schema["type"])
        assertEquals("int32", schema["format"])
    }

    @Test
    fun `Byte produces integer int32 schema`() {
        val s = spec { get("/byte", ::queryByte) }
        val schema = s.parameters("/byte", "get")
            .single { it["name"] == "v" }["schema"] as Map<*, *>
        assertEquals("integer", schema["type"])
        assertEquals("int32", schema["format"])
    }

    @Test
    fun `ShortDto fields produce correct schema in object`() {
        val s = spec { post("/short-dto", ::queryShortDto) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/short-dto", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>
        val shortSchema = props["shortVal"] as Map<*, *>
        assertEquals("integer", shortSchema["type"])
        val byteSchema = props["byteVal"] as Map<*, *>
        assertEquals("integer", byteSchema["type"])
    }

    @Test
    fun `TemporalDto fields produce correct schemas`() {
        val s = spec { post("/temporal", ::queryTemporal) }
        @Suppress("UNCHECKED_CAST")
        val props = s.requestBody("/temporal", "post")!!
            .path("content", "application/json", "schema", "properties") as Map<String, Any>

        val dateSchema = props["date"] as Map<*, *>
        assertEquals("string", dateSchema["type"])
        assertEquals("date", dateSchema["format"])

        val dateTimeSchema = props["dateTime"] as Map<*, *>
        assertEquals("string", dateTimeSchema["type"])
        assertEquals("date-time", dateTimeSchema["format"])

        val instantSchema = props["instant"] as Map<*, *>
        assertEquals("string", instantSchema["type"])
        assertEquals("date-time", instantSchema["format"])

        val uuidSchema = props["uuid"] as Map<*, *>
        assertEquals("string", uuidSchema["type"])
        assertEquals("uuid", uuidSchema["format"])

        val bigDecimalSchema = props["bigDecimal"] as Map<*, *>
        assertEquals("number", bigDecimalSchema["type"])

        val bigIntegerSchema = props["bigInteger"] as Map<*, *>
        assertEquals("integer", bigIntegerSchema["type"])
    }

    @Test
    fun `TemporalDto response schema also produces correct types`() {
        val s = spec { post("/temporal", ::queryTemporal) }
        @Suppress("UNCHECKED_CAST")
        val props = s.path(
            "paths", "/temporal", "post", "responses", "200",
            "content", "application/json", "schema", "properties"
        ) as Map<String, Any>

        val dateSchema = props["date"] as Map<*, *>
        assertEquals("string", dateSchema["type"])
        assertEquals("date", dateSchema["format"])
    }
}