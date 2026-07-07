# Colleen Web Framework

[中文文档](README-zh.md)

Colleen is a lightweight, type-safe web framework for Kotlin and Java, built on Undertow.
It is designed for explicit application code, composable middleware, automatic request
parameter extraction, and synchronous handlers on Java 21+.

```kotlin
fun getTodo(id: Path<Int>, service: TodoService): Todo =
    service.find(id.value) ?: throw NotFound("Todo not found")

fun createTodo(body: Json<CreateTodo>, service: TodoService): Result<Todo> =
    Result.created(service.create(body.value.title))

app.get("/todos/{id}", ::getTodo)
app.post("/todos", ::createTodo)
```

## Why Colleen?

- **Type-safe function handlers**: declare `Path<Int>`, `Query<String?>`, `Json<CreateTodo>`,
  or services directly in a handler signature.
- **Explicit by default**: no classpath scanning, no hidden autowiring, no global magic.
- **Composable middleware**: Koa-like onion middleware with symmetric after-logic, even when
  downstream handlers fail.
- **OpenAPI built in**: function and controller handlers can produce useful OpenAPI metadata
  automatically, with Swagger UI at `/docs`.
- **Realtime support**: WebSocket and SSE are first-class APIs, not external add-ons.
- **Kotlin first, Java friendly**: concise Kotlin APIs with explicit Java-compatible overloads.

Colleen is a good fit when you want framework help with routing, binding, middleware,
documentation, testing, and realtime endpoints, but still want application structure to stay
visible in code.

## Table of Contents

1. [Quick Start](#quick-start)
2. [A Small Todo API](#a-small-todo-api)
3. [Core Concepts](#core-concepts)
4. [API Reference](#api-reference)
5. [WebSocket and SSE](#websocket-and-sse)
6. [OpenAPI](#openapi)
7. [Testing](#testing)
8. [Java Support](#java-support)
9. [Configuration](#configuration)
10. [Production Notes](#production-notes)
11. [Examples](#examples)

---

## Quick Start

### Requirements

- Java 21 or newer
- Kotlin or Java
- Maven or Gradle

### Install

**Maven**

```xml
<dependency>
    <groupId>io.github.cymoo</groupId>
    <artifactId>colleen</artifactId>
    <version>0.4.7</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation("io.github.cymoo:colleen:0.4.7")
```

### Hello World

```kotlin
import io.github.cymoo.colleen.*

fun main() {
    val app = Colleen()

    app.get("/") { "hello world" }

    app.listen(8000)
}
```

Open <http://localhost:8000>.

---

## A Small Todo API

This example is intentionally small, but it demonstrates the main Colleen workflow:
function-style handlers, type-safe parameter extraction, service injection, structured
results, HTTP errors, and OpenAPI.

```kotlin
import io.github.cymoo.colleen.*

data class CreateTodo(val title: String)
data class Todo(val id: Int, val title: String, val completed: Boolean = false)

class TodoService {
    private val todos = linkedMapOf(
        1 to Todo(1, "Try Colleen"),
        2 to Todo(2, "Open /docs"),
    )
    private var nextId = 3

    fun list(completed: Boolean?): List<Todo> =
        todos.values.filter { completed == null || it.completed == completed }

    fun find(id: Int): Todo? = todos[id]

    fun create(title: String): Todo {
        val todo = Todo(nextId++, title)
        todos[todo.id] = todo
        return todo
    }

    fun complete(id: Int): Todo? {
        val todo = todos[id] ?: return null
        val updated = todo.copy(completed = true)
        todos[id] = updated
        return updated
    }

    fun delete(id: Int): Boolean = todos.remove(id) != null
}

fun listTodos(completed: Query<Boolean?>, service: TodoService): List<Todo> =
    service.list(completed.value)

fun getTodo(id: Path<Int>, service: TodoService): Todo =
    service.find(id.value) ?: throw NotFound("Todo not found")

fun createTodo(body: Json<CreateTodo>, service: TodoService): Result<Todo> =
    Result.created(service.create(body.value.title))

fun completeTodo(id: Path<Int>, service: TodoService): Todo =
    service.complete(id.value) ?: throw NotFound("Todo not found")

fun deleteTodo(id: Path<Int>, service: TodoService) {
    if (!service.delete(id.value)) throw NotFound("Todo not found")
}

fun main() {
    val app = Colleen()

    app.provide(TodoService())

    app.openApi(
        title = "Todo API",
        version = "1.0.0",
        description = "A small Colleen API"
    )

    app.get("/todos", ::listTodos)
    app.get("/todos/{id}", ::getTodo)
    app.post("/todos", ::createTodo)
    app.post("/todos/{id}/complete", ::completeTodo)
    app.delete("/todos/{id}", ::deleteTodo)

    app.listen(8000)
}
```

Try it:

```shell
curl http://localhost:8000/todos
curl http://localhost:8000/todos/1
curl -X POST http://localhost:8000/todos \
  -H 'Content-Type: application/json' \
  -d '{"title":"Ship docs"}'
```

OpenAPI JSON is served at `/openapi.json`; Swagger UI is served at `/docs`.

---

## Core Concepts

### Handler Styles

Colleen supports three handler styles. Use the simplest style that fits the route.

| Style | Best for | Example |
|---|---|---|
| Lambda | tiny inline routes | `app.get("/") { "ok" }` |
| Function-style | most application handlers | `app.get("/todos/{id}", ::getTodo)` |
| Controller | larger grouped APIs | `app.addController(TodoController(service))` |

Function-style handlers are the recommended default for non-trivial routes because they
are plain functions, easy to test, and expose useful type information to parameter binding
and OpenAPI.

### Routing

```kotlin
app.get("/todos") { }
app.post("/todos") { }
app.put("/todos/{id}") { }
app.delete("/todos/{id}") { }
app.patch("/todos/{id}") { }
app.head("/todos/{id}") { }
app.options("/todos") { }
app.all("/health") { }
```

Path parameters use `{name}`. Wildcards use `{path...}`.

```kotlin
app.get("/todos/{id}", ::getTodo)
app.get("/files/{path...}") { ctx -> ctx.pathParam("path") }
app.get("/images/{name}.{ext}") { ctx -> "${ctx.pathParam("name")}.${ctx.pathParam("ext")}" }
```

In complex segments, a parameter captures up to the first occurrence of the next
static delimiter, without backtracking. For example, `/files/{name}-{version}.txt`
matches `/files/foo-bar-1.txt` as `name = "foo"` and `version = "bar-1"` — and
`/{a}x{b}` does NOT match `xaxb` (`a` captures nothing before the first `x`).

Route priority is deterministic, compared segment by segment: static segments,
complex segments, parameter segments, then wildcard segments. A route that ends
exactly where the request ends beats a wildcard route matching zero extra
segments, so with both `GET /users` and `GET /users/{path...}` registered,
`GET /users` is served by the exact route.

A few path conventions to be aware of:

- **Decoding happens exactly once, in the server layer.** Route patterns are
  written in decoded form; the framework never percent-decodes again, so
  `%2F` in a request stays an opaque part of one segment and can never change
  the path structure.
- **Trailing slashes are equivalent**: `/users/` and `/users` match the same
  route (empty segments are removed during normalization).
- Requests whose decoded path contains `.` or `..` segments are rejected
  with `400 Bad Request`.

### Route Groups

Groups add a common registration-time path prefix and can carry middleware for a set
of related routes.

```kotlin
app.group("/api") {
    use(ApiKeyMiddleware())

    get("/todos", ::listTodos)          // GET /api/todos
    post("/todos", ::createTodo)        // POST /api/todos

    group("/admin") {
        use(AdminOnly())
        get("/stats") { statsService.snapshot() }  // GET /api/admin/stats
    }
}
```

Route groups are for organizing route declarations. Prefix middleware is different:
it is registered once and matched at request time for any path under the prefix.

### Controller-Style Routing

```kotlin
@Controller("/todos")
class TodoController(private val service: TodoService) {
    @Get
    fun list(completed: Query<Boolean?>): List<Todo> =
        service.list(completed.value)

    @Get("/{id}")
    fun get(id: Path<Int>): Todo =
        service.find(id.value) ?: throw NotFound("Todo not found")

    @Post
    fun create(body: Json<CreateTodo>): Result<Todo> =
        Result.created(service.create(body.value.title))
}

app.addController(TodoController(TodoService()))
```

Supported HTTP annotations: `@Get`, `@Post`, `@Put`, `@Delete`, and `@Patch`.
Use `@Param("name")` when a logical parameter name must be overridden, especially in Java.

### Middleware

Middleware has the signature `(Context, Next) -> Unit`.

```kotlin
val logger = Middleware { ctx, next ->
    val start = System.currentTimeMillis()
    println("-> ${ctx.method} ${ctx.path}")

    next() // downstream exceptions are captured in ctx.error

    val status = if (ctx.error != null) {
        (ctx.error?.cause as? HttpException)?.status ?: 500
    } else {
        ctx.response.status
    }
    println("<- $status in ${System.currentTimeMillis() - start}ms")
}

app.use(logger)
```

Colleen uses an onion model:

```text
middleware1 before
middleware2 before
handler
middleware2 after
middleware1 after
```

If the before part of a middleware ran, its after part runs even when downstream code
throws. `next()` does not throw immediately; the captured exception is available as
`ctx.error` and is rethrown after the chain unwinds unless it is marked handled.

To short-circuit, return without calling `next()`:

```kotlin
app.use { ctx, next ->
    if (ctx.path == "/health") {
        ctx.text("ok")
        return@use
    }
    next()
}
```

#### Where Middleware Can Run

| Scope | API | When it runs |
|---|---|---|
| Global | `app.use(middleware)` | every HTTP request |
| Prefix | `app.use("/api", middleware)` | paths under `/api` |
| Conditional | `app.use({ ctx -> ... }, middleware)` | when the predicate returns `true` |
| Per-route | `app.get("/todos").use(middleware).handle { ... }` | one method + path |
| Group-level | `app.group("/api") { use(middleware) }` | routes registered under the group |

```kotlin
app.use(RequestLogger())
app.use("/api", ApiKeyMiddleware())
app.use({ ctx -> ctx.accepts("json") }, JsonOnlyMiddleware())

app.get("/todos/{id}")
    .use(AuthMiddleware())
    .handle(::getTodo)
```

Use route groups to keep route declarations together. Use prefix middleware when the
same middleware should apply to paths that may be registered in different places.

### Dependency Injection

Services are explicit. Register them before routes that depend on them.

```kotlin
app.provide(TodoService())                         // existing singleton
app.provide { TodoService() }                      // lazy singleton
app.provide(singleton = false) { TodoService() }   // transient
```

Resolve services explicitly from `Context`:

```kotlin
app.get("/todos") { ctx ->
    ctx.getService<TodoService>().list(completed = null)
}
```

Or declare them as function/controller parameters:

```kotlin
fun listTodos(service: TodoService): List<Todo> =
    service.list(completed = null)
```

Qualifiers distinguish multiple services of the same type:

```kotlin
object Primary
object Replica

app.provide(qualifier = Primary) { primaryDataSource }
app.provide(qualifier = Replica) { replicaDataSource }

fun report(@Qualifier("Replica") ds: DataSource): DataSource = ds
```

Services in sub-apps are resolved from the current app first, then parents.

### Error Handling

Throw HTTP exceptions from handlers or middleware:

```kotlin
throw BadRequest("Invalid input")
throw Unauthorized("Authentication required")
throw Forbidden("Access denied")
throw NotFound("Todo not found")
throw Conflict("Already exists")
throw TooManyRequests("Rate limit exceeded")
```

Register type-based error handlers:

```kotlin
app.onError<ValidationException> { e, ctx ->
    ctx.status(422).json(mapOf("error" to "validation_failed", "fields" to e.errors))
}

app.onError<HttpException> { e, ctx ->
    ctx.status(e.status).json(mapOf("code" to e.code, "message" to e.message))
}
```

If no custom handler matches, Colleen returns JSON or HTML based on content negotiation.
Server errors are logged automatically.

### Validation

```kotlin
app.post("/todos") { ctx ->
    val body = ctx.json<CreateTodo>() ?: throw BadRequest("Missing body")

    expect {
        field("title", body.title)
            .required()
            .notBlank()
            .maxSize(100)
    }

    Result.created(ctx.getService<TodoService>().create(body.title))
}
```

Validation rules are optional by default, `.required()` makes a value mandatory, and
all field errors are collected into one `ValidationException`.

### Sub-Applications

Sub-applications let you split a larger service into isolated Colleen apps. They are
useful for API versions, admin/internal tools, feature modules, or plugin-style composition.

```kotlin
val api = Colleen()
api.get("/todos", ::listTodos)

val app = Colleen()
app.mount("/api", api)
```

Inside the sub-app, routes are written relative to the mount path:

| Value | Example for `GET /api/todos` |
|---|---|
| `ctx.path` | `/todos` |
| `ctx.fullPath` | `/api/todos` |
| `ctx.pattern` | `/todos` |
| `ctx.fullPattern` | `/api/todos` |

Each sub-app has its own routes, middleware, services, error handlers, and config.
Some request context is shared through the parent chain.

| Boundary | Behavior |
|---|---|
| Routes and middleware | isolated per app |
| Services | current app first, then parent apps |
| State | child contexts can read parent state |
| Error handlers | sub-app handlers first; parent handlers can handle propagated errors |
| Config | each app has its own config |
| Events | execution events can bubble to parent apps |

```kotlin
val root = Colleen()
root.provide(Database())

val admin = Colleen()
admin.use(AdminOnly())
admin.get("/stats") { ctx ->
    ctx.getService<Database>().stats()
}

root.mount("/admin", admin)
```

By default, unhandled exceptions in a sub-app can propagate to the parent app. Disable
that when a sub-app should own its error boundary:

```kotlin
admin.config {
    propagateExceptions = false
}
```

Restrictions: mount sub-apps before they are started, and mount each app instance only once.

### Events

Events are synchronous observation hooks. They are useful for metrics, tracing, logging,
and framework extensions.

```kotlin
app.on<Event.ResponseSent> { event ->
    println("${event.ctx.method} ${event.ctx.fullPath} " +
        "${event.ctx.response.status} ${event.total.inWholeMilliseconds}ms")
}
```

Common events:

| Category | Events |
|---|---|
| Server lifecycle | `ServerStarting`, `ServerStarted`, `ServerStopping`, `ServerStopped` |
| Request lifecycle | `RequestReceived`, `ResponseReady`, `ResponseSent` |
| Execution | `MiddlewareExecuting`, `MiddlewareExecuted`, `HandlerExecuting`, `HandlerExecuted`, `SubAppExecuting`, `SubAppExecuted` |
| Exceptions | `ExceptionCaught`, `ExceptionHandled` |

Use middleware and handlers for control flow; use events for observation and extensions.

---

## API Reference

### Parameter Extractors

Declare these in function-style handlers or controller methods.

| Type | Source | Required/optional behavior |
|---|---|---|
| `Path<T>` | route path segment | required; conversion failure returns 400 |
| `Query<T>` | query string | nullability/defaults control required behavior |
| `Form<T>` | form field or form DTO | mirrors `Query<T>` for form data |
| `Json<T>` | JSON request body | parses with configured JSON mapper |
| `Header` | HTTP header | always nullable |
| `Cookie` | request cookie | always nullable |
| `Text` | body as text | nullable when empty |
| `Stream` | raw body stream | nullable, one-time readable |
| `UploadedFile` | multipart file | nullable when missing |
| `Context` | request context | injected directly |
| any other type | service container | resolved as a service |

Examples:

```kotlin
fun search(
    q: Query<String?>,
    limit: Query<Int> = Query(20),
    tags: Query<List<String>>,
): List<Todo> = emptyList()

fun upload(file: UploadedFile): Map<String, Any?> =
    mapOf("filename" to file.value?.filename, "size" to file.value?.size)
```

Rules for `Query<T>` and `Form<T>`:

| Declaration | Missing input |
|---|---|
| `Query<String>` | 400 Bad Request |
| `Query<String?>` | `null` |
| `Query<Int> = Query(1)` | default value |
| `Query<List<T>>` | `emptyList()` |
| `Query<Map<String, String>>` | `emptyMap()` |
| `Query<Map<String, List<String>>>` | `emptyMap()` |

Custom extractors implement `ExtractorFactory` and can also describe themselves for OpenAPI.

### Custom Parameter Extractors

Custom extractors move request parsing out of handlers and turn it into a reusable,
type-safe parameter.

```kotlin
import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.openapi.*
import java.lang.reflect.Parameter

class BearerToken(value: String?) : ParamExtractor<String?>(value) {
    companion object : ExtractorFactory<BearerToken> {
        override fun build(paramName: String, param: Parameter): (Context) -> BearerToken {
            return { ctx ->
                val token = ctx.header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                BearerToken(token)
            }
        }

        override fun describeOpenApi(paramName: String, param: Parameter) = OpenApiParamSpec(
            parameters = listOf(
                OpenApiParameter(
                    name = "Authorization",
                    location = "header",
                    schema = mapOf("type" to "string"),
                    description = "Bearer token. Format: `Bearer <token>`",
                )
            )
        )
    }

    fun require(): String =
        value ?: throw Unauthorized("Bearer token is required")
}

fun me(token: BearerToken, service: UserService): UserProfile =
    service.profile(token.require())
```

`build` performs extraction, and `describeOpenApi` lets the extractor appear in
generated API docs. Add `missingMessage` when the framework, rather than handler
code like `require()`, should report missing required values for your extractor.

### Context API

Most handlers only need a small subset of `Context`.

| API | Purpose |
|---|---|
| `ctx.method`, `ctx.path`, `ctx.fullPath` | request method and path |
| `ctx.pattern`, `ctx.fullPattern` | matched route pattern after routing |
| `ctx.pathParam("id")` | path parameter |
| `ctx.query("q")`, `ctx.queries()` | query parameters |
| `ctx.queries<T>()` | bind query parameters to a DTO |
| `ctx.form("name")`, `ctx.forms<T>()` | form values |
| `ctx.header("Authorization")` | request header |
| `ctx.accepts("json")` | content negotiation |
| `ctx.acceptsLang("zh-CN")` | language negotiation |
| `ctx.text()`, `ctx.json<T>()` | body parsing |
| `ctx.file("avatar")` | uploaded file |
| `ctx.getService<T>()` | required service |
| `ctx.getServiceOrNull<T>()` | optional service |
| `ctx.setState(key, value)` | request state |
| `ctx.getState<T>(key)` | required state |
| `ctx.getStateOrNull<T>(key)` | optional state |

Response helpers:

| API | Response |
|---|---|
| `ctx.status(201)` | set status |
| `ctx.header("X-Trace", id)` | set header |
| `ctx.text("ok")` | `text/plain` |
| `ctx.html(html)` | `text/html` |
| `ctx.json(data)` | JSON |
| `ctx.json(data, stream = true)` | streaming JSON |
| `ctx.bytes(bytes, contentType)` | binary body |
| `ctx.stream(input, contentType)` | streaming body |
| `ctx.sendFile(path, baseDir = "...")` | file response with negotiation |
| `ctx.redirect("/new")` | redirect |
| `ctx.sse { conn -> ... }` | Server-Sent Events |

Direct body parsing APIs return `null` for absent or empty input and throw `BadRequest`
for malformed input.

### Handler Return Values

If a handler returns a value and the response has not already been written, Colleen maps
the value to an HTTP response.

| Return value | Response |
|---|---|
| `Unit` / Java `void` | 204 No Content |
| `String` | `text/plain` |
| `ByteArray` | `application/octet-stream` |
| `InputStream` | streaming octet-stream |
| `Map<*, *>`, `List<*>` | JSON |
| any other object | JSON |
| `Int`, `Long`, `Status` | status code with empty body |
| `Result<T>` | status + headers + mapped body |
| `ResponseBody` | raw response body |
| `null` | error; use `Unit` or `ctx.json(null)` |

```kotlin
app.get("/todos") { listOf(Todo(1, "Try Colleen")) }
app.post("/todos") { Result.created(Todo(2, "Write docs")) }
app.delete("/todos/{id}") { Result.noContent() }
app.get("/status") { Status(204) }
```

### Built-in Middleware

| Middleware | Purpose |
|---|---|
| `ServeStatic` | static files with security checks and caching |
| `BasicAuth` | HTTP Basic authentication |
| `Cors` | CORS and preflight handling |
| `RateLimiter` | token-bucket rate limiting |
| `RequestId` | request ID propagation |
| `RequestLogger` | simple access logging |
| `SecurityHeaders` | common HTTP security headers |
| `SignedCookie` | signed cookies with key rotation |
| `Heartbeat` | health-check endpoint |
| `NoCache` | disable client/proxy caching |
| `Sunset` | RFC 8594 API deprecation headers |

```kotlin
app.use(RequestId())
app.use(RequestLogger())
app.use(Cors.permissive())
app.use(ServeStatic(root = "./public", baseUrl = "/static"))
```

---

## WebSocket and SSE

### WebSocket

```kotlin
app.ws("/chat/{room}") { conn ->
    val room = conn.pathParam("room")
    val name = conn.query("name") ?: "anonymous"

    conn.onMessage { text ->
        conn.send("[$room] $name: $text")
    }

    conn.onBinary { bytes ->
        conn.send(bytes)
    }
}
```

WebSocket middleware runs during the handshake:

```kotlin
app.wsUse("/chat") { ctx, next ->
    if (ctx.header("Authorization") == null) {
        ctx.status(401).text("Unauthorized")
        return@wsUse
    }
    next()
}
```

`WsConnection` can access path params, query params, handshake headers, services, and
state captured during middleware.

Two timing details worth knowing:

- **HTTP middleware does not see WebSocket handshakes.** Upgrade requests are
  routed before the HTTP middleware chain is collected, so global `use(...)`
  middleware (logging, rate limiting, ...) never runs for them — only the
  `wsUse(...)` chain does. Apply WS-specific concerns with `wsUse`.
- **Path parameters are available earlier for WS.** For HTTP routes they are
  injected right before the handler; for WS routes they are injected before
  the `wsUse` middleware chain, so WS middleware can already read
  `ctx.pathParam(...)`.

Controller-style WebSocket routes are also supported:

```kotlin
@Controller("/notifications")
class NotificationController {
    @Ws("/live")
    fun live(conn: WsConnection) {
        conn.onMessage { msg -> conn.send("ack") }
    }
}
```

### Server-Sent Events

```kotlin
app.get("/events") { ctx ->
    ctx.sse { conn ->
        conn.keepAlive(15)
        conn.onClose { reason -> println("closed: $reason") }
        conn.send("hello")
    }
}
```

Use SSE for one-way server push. Use WebSocket for bidirectional realtime communication.

---

## OpenAPI

Enable OpenAPI and Swagger UI:

```kotlin
app.openApi(
    title = "Todo API",
    version = "1.0.0",
    description = "A small Colleen API"
)
```

Defaults:

| Option | Default |
|---|---|
| `path` | `/openapi.json` |
| `uiPath` | `/docs` |
| `uiHtml` | Swagger UI |

Function-style and controller handlers provide richer metadata than lambda handlers
because Colleen can inspect their signatures.

```kotlin
@Tags("todos")
@Summary("Get a todo")
@Description("Returns one todo by id.")
@ParamDesc(name = "id", description = "Todo id")
@ResponseDesc(404, "Todo not found")
fun getTodo(id: Path<Int>, service: TodoService): Todo =
    service.find(id.value) ?: throw NotFound("Todo not found")
```

Schema annotations enrich DTOs:

```kotlin
data class Todo(
    @Schema(description = "Todo id", example = "1")
    val id: Int,
    @Schema(description = "Task title", example = "Ship docs")
    val title: String,
    @Schema(hidden = true)
    val internalVersion: Int = 0,
)
```

Common annotations:

| Annotation | Purpose |
|---|---|
| `@Summary` | operation summary |
| `@Description` | operation description |
| `@Tags` | Swagger/ReDoc grouping |
| `@ParamDesc` | parameter description and required override |
| `@ResponseDesc` | response description by status |
| `@Schema` | field-level schema metadata |
| `@Hidden` | exclude a handler or controller |

OpenAPI includes mounted sub-app routes. Use `@Hidden` for annotation-based exclusion
and `filter` for path/method-based exclusion.

---

## Testing

`TestClient` runs requests in-process through the same routing, middleware, extraction,
validation, DI, and error-handling pipeline as production.

```kotlin
val app = Colleen()
app.provide(TodoService())
app.post("/todos", ::createTodo)

val client = TestClient(app)

val response = client.post("/todos")
    .json(mapOf("title" to "Write tests"))
    .send()

response.assertStatus(201)

val todo = response.json<Todo>()!!
check(todo.title == "Write tests")
```

Use `TestClient` for handler tests, middleware/security tests, and lightweight integration
tests without binding a network port.

---

## Java Support

Colleen is written in Kotlin but exposes Java-friendly APIs.

The main differences in Java are explicit runtime types, explicit parameter names, and
method-reference wrapping.

```java
import io.github.cymoo.colleen.*;

import static io.github.cymoo.colleen.lambda.ch;

class App {
    static Todo getTodo(@Param("id") Path<Integer> id, TodoService service) {
        var todo = service.find(id.value);
        if (todo == null) throw new NotFound("Todo not found");
        return todo;
    }

    public static void main(String[] args) {
        var app = new Colleen();
        app.provide(TodoService.class, new TodoService());
        app.get("/todos/{id}", ch(App::getTodo));
        app.listen(8000);
    }
}
```

Rules to remember:

| Kotlin | Java |
|---|---|
| `ctx.json<Todo>()` | `ctx.json(Todo.class)` |
| `ctx.getService<TodoService>()` | `ctx.getService(TodoService.class)` |
| `app.onError<BadRequest> { ... }` | `app.onError(BadRequest.class, (e, ctx) -> { ... })` |
| Kotlin function reference | Java method reference wrapped with `ch(...)` |
| parameter names preserved | use `@Param("name")` or `-parameters` |

For generic JSON in Java, use `TypeRef`:

```java
List<Todo> todos = ctx.json(TypeRef.listOf(Todo.class));
Map<String, Todo> byId = ctx.json(TypeRef.mapOf(String.class, Todo.class));
```

---

## Configuration

```kotlin
app.config {
    server {
        host = "127.0.0.1"
        port = 8000
        useVirtualThreads = true
        maxThreads = Runtime.getRuntime().availableProcessors() * 8
        maxConcurrentRequests = 0
        maxRequestSize = 30 * 1024 * 1024
        maxFileSize = 10 * 1024 * 1024
        fileSizeThreshold = 256 * 1024
        shutdownTimeout = 30_000
        idleTimeout = 30_000
        trustedProxyCount = 0
    }

    ws {
        idleTimeoutMs = 300_000
        maxMessageSizeBytes = 64 * 1024
        pingIntervalMs = 30_000
        pingTimeoutMs = 10_000
        maxConnections = 0
    }

    json {
        pretty = false
        includeNulls = false
        failOnUnknownProperties = true
        failOnNullForPrimitives = true
        failOnEmptyBeans = false
        acceptSingleValueAsArray = false
        writeDatesAsTimestamps = false
        dateFormat = null
        writeEnumsUsingToString = false
        readEnumsUsingToString = false
    }

    propagateExceptions = true
}
```

Replace the JSON mapper when needed:

```kotlin
app.config {
    jsonMapper(MyCustomJsonMapper())
}
```

---

## Production Notes

- **Virtual threads**: enabled by default on Java 21+. They make synchronous handlers
  practical for IO-heavy workloads, but benchmark your own workload before production.
- **Limits**: set explicit `maxConcurrentRequests`, `maxRequestSize`, and `maxFileSize`
  for public services.
- **Streaming JSON**: use `ctx.json(data, stream = true)` for large payloads; avoid it
  for small responses.
- **Global middleware**: every global middleware runs for every request. Prefer prefix
  middleware when possible.
- **Structured logging**: prefer `Event.ResponseSent` when logs need final status,
  duration, and bytes sent.
- **Static files**: use `sendFile(..., baseDir = "...")` or `ServeStatic` when paths
  include user input.
- **TLS / HTTP/2**: Colleen intentionally ships plain HTTP only. Terminate TLS (and
  HTTP/2) at a reverse proxy such as Nginx or Caddy, and set
  `server { trustedProxyCount = 1 }` (the number of proxy layers you control) so
  `Request.ip`, `isSecure`, and HSTS work from `X-Forwarded-For` /
  `X-Forwarded-Proto`. With the default `trustedProxyCount = 0` these headers are
  ignored — they are client-supplied and trivially forged.

See `examples/benchmark-api/README.md` for reproducible benchmark profiles.

---

## Examples

Start here:

### Start Here

| Example | What it shows |
|---|---|
| [hello-world](examples/hello-world) | The smallest runnable Colleen app. |
| [todo-app](examples/todo-app) | JSON CRUD API with validation and CORS. |
| [testing](examples/testing) | In-process requests with `TestClient`. |
| [openapi](examples/openapi) | OpenAPI annotations and Swagger UI. |

### Core APIs

| Example | What it shows |
|---|---|
| [extractor](examples/extractor) | Built-in path, query, form, JSON, header, cookie, and file extraction. |
| [custom-extractor](examples/custom-extractor) | Domain-specific extractors such as bearer tokens and pagination. |
| [validator](examples/validator) | Validation DSL and aggregated field errors. |
| [middleware-showcase](examples/middleware-showcase) | Built-in middleware and common middleware patterns. |
| [auth-app](examples/auth-app) | Authentication with custom middleware and service injection. |
| [error-handling](examples/error-handling) | Global error handlers and sub-app error propagation. |
| [sub-app](examples/sub-app) | Modular applications with mounted sub-apps. |
| [event-system](examples/event-system) | Lifecycle and request/response events. |

### Realtime and Files

| Example | What it shows |
|---|---|
| [websocket](examples/websocket) | WebSocket routes, middleware, and controller-style handlers. |
| [sse](examples/sse) | Server-Sent Events with keep-alive and close handling. |
| [upload-app](examples/upload-app) | Multipart upload and file download. |
| [serve-static](examples/serve-static) | Static file serving with cache and security controls. |
| [render-html](examples/render-html) | Pebble templates for HTML rendering. |

### Integrations and Operations

| Example | What it shows |
|---|---|
| [jdbc](examples/jdbc) | JDBC integration with SQLite and batch execution. |
| [jooq-sqlite](examples/jooq-sqlite) | jOOQ code generation and type-safe SQLite queries. |
| [redis](examples/redis) | Redis-backed response caching middleware. |
| [auto-reload](examples/auto-reload) | Development-time auto reload workflow. |
| [benchmark-api](examples/benchmark-api) | Reproducible benchmark profiles and load scenarios. |

---

## License

MIT
