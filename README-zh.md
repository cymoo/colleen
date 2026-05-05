# Colleen Web 框架

[English Documentation](README.md)

Colleen 是一个基于 Undertow 构建的轻量级、类型安全 Kotlin / Java Web 框架。
它强调显式应用代码、可组合中间件、自动请求参数提取，以及 Java 21+ 上的同步 handler 编程模型。

```kotlin
fun getTodo(id: Path<Int>, service: TodoService): Todo =
    service.find(id.value) ?: throw NotFound("Todo not found")

fun createTodo(body: Json<CreateTodo>, service: TodoService): Result<Todo> =
    Result.created(service.create(body.value.title))

app.get("/todos/{id}", ::getTodo)
app.post("/todos", ::createTodo)
```

## 为什么选择 Colleen？

- **类型安全的函数式 handler**：在函数签名中直接声明 `Path<Int>`、`Query<String?>`、
  `Json<CreateTodo>` 或服务依赖。
- **默认显式**：没有 classpath scanning，没有隐藏的自动装配，也没有全局魔法。
- **可组合中间件**：类似 Koa 的洋葱模型，并保证下游异常时上游 after 逻辑仍会执行。
- **内置 OpenAPI**：函数式 handler 和 controller 可以自动生成有用的 OpenAPI 元数据，
  默认在 `/docs` 提供 Swagger UI。
- **实时能力**：WebSocket 和 SSE 是一等 API，不需要额外框架拼接。
- **Kotlin 优先，Java 友好**：Kotlin API 简洁，同时提供显式的 Java 兼容写法。

当你希望框架帮你处理路由、绑定、中间件、文档、测试和实时端点，但又希望应用结构
清楚地留在代码里时，Colleen 会很合适。

## 目录

1. [快速开始](#快速开始)
2. [一个小型 Todo API](#一个小型-todo-api)
3. [核心概念](#核心概念)
4. [API 参考](#api-参考)
5. [WebSocket 与 SSE](#websocket-与-sse)
6. [OpenAPI](#openapi)
7. [测试](#测试)
8. [Java 支持](#java-支持)
9. [配置](#配置)
10. [生产建议](#生产建议)
11. [示例](#示例)

---

## 快速开始

### 要求

- Java 21 或更高版本
- Kotlin 或 Java
- Maven 或 Gradle

### 安装

**Maven**

```xml
<dependency>
    <groupId>io.github.cymoo</groupId>
    <artifactId>colleen</artifactId>
    <version>0.4.6</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation("io.github.cymoo:colleen:0.4.6")
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

打开 <http://localhost:8000>。

---

## 一个小型 Todo API

这个示例刻意保持短小，但覆盖了 Colleen 的主要工作流：函数式 handler、类型安全参数
提取、服务注入、结构化响应、HTTP 错误和 OpenAPI。

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

试一下：

```shell
curl http://localhost:8000/todos
curl http://localhost:8000/todos/1
curl -X POST http://localhost:8000/todos \
  -H 'Content-Type: application/json' \
  -d '{"title":"Ship docs"}'
```

OpenAPI JSON 位于 `/openapi.json`；Swagger UI 位于 `/docs`。

---

## 核心概念

### Handler 风格

Colleen 支持三种 handler 风格。根据路由复杂度选择最简单合适的一种。

| 风格 | 适合场景 | 示例 |
|---|---|---|
| Lambda | 极小的内联路由 | `app.get("/") { "ok" }` |
| Function-style | 大多数应用 handler | `app.get("/todos/{id}", ::getTodo)` |
| Controller | 更大的分组 API | `app.addController(TodoController(service))` |

对于非平凡路由，推荐默认使用 function-style handler：它是普通函数，便于测试，也能向
参数绑定和 OpenAPI 暴露更完整的类型信息。

### 路由

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

路径参数使用 `{name}`，通配符使用 `{path...}`。

```kotlin
app.get("/todos/{id}", ::getTodo)
app.get("/files/{path...}") { ctx -> ctx.pathParam("path") }
app.get("/images/{name}.{ext}") { ctx -> "${ctx.pathParam("name")}.${ctx.pathParam("ext")}" }
```

路由匹配优先级是确定的：静态段、复合段、参数段、通配符段。

### Controller 风格路由

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

支持的 HTTP 注解包括 `@Get`、`@Post`、`@Put`、`@Delete` 和 `@Patch`。
当需要覆盖逻辑参数名时使用 `@Param("name")`，Java 中尤其常用。

### 中间件

中间件签名是 `(Context, Next) -> Unit`。

```kotlin
val logger = Middleware { ctx, next ->
    val start = System.currentTimeMillis()
    println("-> ${ctx.method} ${ctx.path}")

    next() // 下游异常会被捕获到 ctx.error

    val status = if (ctx.error != null) {
        (ctx.error?.cause as? HttpException)?.status ?: 500
    } else {
        ctx.response.status
    }
    println("<- $status in ${System.currentTimeMillis() - start}ms")
}

app.use(logger)
```

Colleen 使用洋葱模型：

```text
middleware1 before
middleware2 before
handler
middleware2 after
middleware1 after
```

只要某个中间件的 before 部分开始执行，它的 after 部分就会执行，即使下游抛出异常。
`next()` 不会立即向外抛异常；捕获的异常可通过 `ctx.error` 读取，并在中间件链完全
展开后重新抛出，除非被标记为 handled。

短路时，不调用 `next()` 即可：

```kotlin
app.use { ctx, next ->
    if (ctx.path == "/health") {
        ctx.text("ok")
        return@use
    }
    next()
}
```

### 依赖注入

服务必须显式注册。依赖服务的路由应在服务注册之后添加。

```kotlin
app.provide(TodoService())                         // 现有单例
app.provide { TodoService() }                      // 延迟单例
app.provide(singleton = false) { TodoService() }   // 瞬态
```

可以从 `Context` 显式解析服务：

```kotlin
app.get("/todos") { ctx ->
    ctx.getService<TodoService>().list(completed = null)
}
```

也可以把服务声明为 function/controller 参数：

```kotlin
fun listTodos(service: TodoService): List<Todo> =
    service.list(completed = null)
```

Qualifier 用于区分同类型的多个服务：

```kotlin
object Primary
object Replica

app.provide(qualifier = Primary) { primaryDataSource }
app.provide(qualifier = Replica) { replicaDataSource }

fun report(@Qualifier("Replica") ds: DataSource): DataSource = ds
```

子应用中的服务会先从当前应用解析，再向父应用查找。

### 错误处理

在 handler 或中间件中直接抛 HTTP 异常：

```kotlin
throw BadRequest("Invalid input")
throw Unauthorized("Authentication required")
throw Forbidden("Access denied")
throw NotFound("Todo not found")
throw Conflict("Already exists")
throw TooManyRequests("Rate limit exceeded")
```

按异常类型注册错误处理器：

```kotlin
app.onError<ValidationException> { e, ctx ->
    ctx.status(422).json(mapOf("error" to "validation_failed", "fields" to e.errors))
}

app.onError<HttpException> { e, ctx ->
    ctx.status(e.status).json(mapOf("code" to e.code, "message" to e.message))
}
```

如果没有匹配的自定义处理器，Colleen 会根据内容协商返回 JSON 或 HTML。
服务端错误会自动记录日志。

### 数据验证

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

验证规则默认是可选的，`.required()` 表示必填，所有字段错误会聚合到一个
`ValidationException` 中。

### 子应用

```kotlin
val api = Colleen()
api.get("/todos", ::listTodos)

val app = Colleen()
app.mount("/api", api)
```

在子应用内部，路由相对于挂载路径书写：

| 值 | `GET /api/todos` 示例 |
|---|---|
| `ctx.path` | `/todos` |
| `ctx.fullPath` | `/api/todos` |
| `ctx.pattern` | `/todos` |
| `ctx.fullPattern` | `/api/todos` |

每个子应用都有独立的路由、中间件、服务、错误处理器和配置。

### 事件

事件是同步的观察 hook，适合用于指标、追踪、日志和框架扩展。

```kotlin
app.on<Event.ResponseSent> { event ->
    println("${event.ctx.method} ${event.ctx.fullPath} " +
        "${event.ctx.response.status} ${event.total.inWholeMilliseconds}ms")
}
```

常见事件：

| 类别 | 事件 |
|---|---|
| Server 生命周期 | `ServerStarting`, `ServerStarted`, `ServerStopping`, `ServerStopped` |
| 请求生命周期 | `RequestReceived`, `ResponseReady`, `ResponseSent` |
| 执行过程 | `MiddlewareExecuting`, `MiddlewareExecuted`, `HandlerExecuting`, `HandlerExecuted`, `SubAppExecuting`, `SubAppExecuted` |
| 异常 | `ExceptionCaught`, `ExceptionHandled` |

需要改变请求流程时使用中间件和 handler；事件更适合观察和扩展。

---

## API 参考

### 参数提取器

这些类型可以声明在 function-style handler 或 controller 方法中。

| 类型 | 来源 | 必填/可选语义 |
|---|---|---|
| `Path<T>` | 路由路径段 | 必填；转换失败返回 400 |
| `Query<T>` | 查询字符串 | 由可空性和默认值控制是否必填 |
| `Form<T>` | 表单字段或表单 DTO | 与 `Query<T>` 类似，来源为表单 |
| `Json<T>` | JSON 请求体 | 使用配置的 JSON mapper 解析 |
| `Header` | HTTP 请求头 | 始终可空 |
| `Cookie` | 请求 Cookie | 始终可空 |
| `Text` | 文本请求体 | 为空时可空 |
| `Stream` | 原始请求体流 | 可空，只能读取一次 |
| `UploadedFile` | multipart 文件 | 缺失时可空 |
| `Context` | 请求上下文 | 直接注入 |
| 其他类型 | 服务容器 | 作为服务解析 |

示例：

```kotlin
fun search(
    q: Query<String?>,
    limit: Query<Int> = Query(20),
    tags: Query<List<String>>,
): List<Todo> = emptyList()

fun upload(file: UploadedFile): Map<String, Any?> =
    mapOf("filename" to file.value?.filename, "size" to file.value?.size)
```

`Query<T>` 和 `Form<T>` 规则：

| 声明 | 输入缺失时 |
|---|---|
| `Query<String>` | 400 Bad Request |
| `Query<String?>` | `null` |
| `Query<Int> = Query(1)` | 使用默认值 |
| `Query<List<T>>` | `emptyList()` |
| `Query<Map<String, String>>` | `emptyMap()` |
| `Query<Map<String, List<String>>>` | `emptyMap()` |

自定义提取器实现 `ExtractorFactory`，也可以描述其 OpenAPI 表现。

### Context API

多数 handler 只需要 `Context` 的一小部分能力。

| API | 用途 |
|---|---|
| `ctx.method`, `ctx.path`, `ctx.fullPath` | 请求方法和路径 |
| `ctx.pattern`, `ctx.fullPattern` | 路由匹配完成后的模式 |
| `ctx.pathParam("id")` | 路径参数 |
| `ctx.query("q")`, `ctx.queries()` | 查询参数 |
| `ctx.queries<T>()` | 将查询参数绑定到 DTO |
| `ctx.form("name")`, `ctx.forms<T>()` | 表单值 |
| `ctx.header("Authorization")` | 请求头 |
| `ctx.accepts("json")` | 内容协商 |
| `ctx.acceptsLang("zh-CN")` | 语言协商 |
| `ctx.text()`, `ctx.json<T>()` | 请求体解析 |
| `ctx.file("avatar")` | 上传文件 |
| `ctx.getService<T>()` | 必需服务 |
| `ctx.getServiceOrNull<T>()` | 可选服务 |
| `ctx.setState(key, value)` | 请求状态 |
| `ctx.getState<T>(key)` | 必需状态 |
| `ctx.getStateOrNull<T>(key)` | 可选状态 |

响应辅助方法：

| API | 响应 |
|---|---|
| `ctx.status(201)` | 设置状态码 |
| `ctx.header("X-Trace", id)` | 设置响应头 |
| `ctx.text("ok")` | `text/plain` |
| `ctx.html(html)` | `text/html` |
| `ctx.json(data)` | JSON |
| `ctx.json(data, stream = true)` | 流式 JSON |
| `ctx.bytes(bytes, contentType)` | 二进制响应 |
| `ctx.stream(input, contentType)` | 流式响应 |
| `ctx.sendFile(path, baseDir = "...")` | 带内容协商的文件响应 |
| `ctx.redirect("/new")` | 重定向 |
| `ctx.sse { conn -> ... }` | Server-Sent Events |

直接解析请求体、查询或表单的 API 在输入缺失或为空时返回 `null`，输入存在但格式错误时抛
`BadRequest`。

### Handler 返回值

如果 handler 返回一个值，并且响应尚未被显式写入，Colleen 会将返回值映射为 HTTP 响应。

| 返回值 | 响应 |
|---|---|
| `Unit` / Java `void` | 204 No Content |
| `String` | `text/plain` |
| `ByteArray` | `application/octet-stream` |
| `InputStream` | 流式 octet-stream |
| `Map<*, *>`, `List<*>` | JSON |
| 其他对象 | JSON |
| `Int`, `Long`, `Status` | 空 body 的状态码响应 |
| `Result<T>` | 状态码 + 响应头 + 映射后的 body |
| `ResponseBody` | 原始响应体 |
| `null` | 错误；使用 `Unit` 或 `ctx.json(null)` |

```kotlin
app.get("/todos") { listOf(Todo(1, "Try Colleen")) }
app.post("/todos") { Result.created(Todo(2, "Write docs")) }
app.delete("/todos/{id}") { Result.noContent() }
app.get("/status") { Status(204) }
```

### 内置中间件

| 中间件 | 用途 |
|---|---|
| `ServeStatic` | 静态文件服务，带安全检查和缓存 |
| `BasicAuth` | HTTP Basic 认证 |
| `Cors` | CORS 与预检请求处理 |
| `RateLimiter` | 令牌桶限流 |
| `RequestId` | 请求 ID 传递 |
| `RequestLogger` | 简单访问日志 |
| `SecurityHeaders` | 常用 HTTP 安全头 |
| `SignedCookie` | 支持密钥轮换的签名 Cookie |
| `Heartbeat` | 健康检查端点 |
| `NoCache` | 禁用客户端和代理缓存 |
| `Sunset` | RFC 8594 API 弃用头 |

```kotlin
app.use(RequestId())
app.use(RequestLogger())
app.use(Cors.permissive())
app.use(ServeStatic(root = "./public", baseUrl = "/static"))
```

---

## WebSocket 与 SSE

### WebSocket

```kotlin
app.ws("/chat/{room}") { conn ->
    val room = conn.pathParam("room")
    val name = conn.query("name") ?: "anonymous"

    conn.onMessage { msg ->
        when (msg) {
            is WsMessage.Text -> conn.send("[$room] $name: ${msg.data}")
            is WsMessage.Binary -> conn.send(msg.data)
        }
    }
}
```

WebSocket 中间件在握手阶段运行：

```kotlin
app.wsUse("/chat") { ctx, next ->
    if (ctx.header("Authorization") == null) {
        ctx.status(401).text("Unauthorized")
        return@wsUse
    }
    next()
}
```

`WsConnection` 可以访问路径参数、查询参数、握手请求头、服务，以及中间件阶段捕获的状态。

也支持 controller 风格 WebSocket：

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

单向服务器推送用 SSE；双向实时通信使用 WebSocket。

---

## OpenAPI

启用 OpenAPI 和 Swagger UI：

```kotlin
app.openApi(
    title = "Todo API",
    version = "1.0.0",
    description = "A small Colleen API"
)
```

默认值：

| 选项 | 默认值 |
|---|---|
| `path` | `/openapi.json` |
| `uiPath` | `/docs` |
| `uiHtml` | Swagger UI |

Function-style 和 controller handler 比 lambda handler 能提供更丰富的元数据，因为
Colleen 可以检查它们的函数签名。

```kotlin
@Tags("todos")
@Summary("Get a todo")
@Description("Returns one todo by id.")
@ParamDesc(name = "id", description = "Todo id")
@ResponseDesc(404, "Todo not found")
fun getTodo(id: Path<Int>, service: TodoService): Todo =
    service.find(id.value) ?: throw NotFound("Todo not found")
```

Schema 注解可以补充 DTO 信息：

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

常用注解：

| 注解 | 用途 |
|---|---|
| `@Summary` | 操作摘要 |
| `@Description` | 操作描述 |
| `@Tags` | Swagger/ReDoc 分组 |
| `@ParamDesc` | 参数说明和 required 覆盖 |
| `@ResponseDesc` | 按状态码说明响应 |
| `@Schema` | 字段级 schema 元数据 |
| `@Hidden` | 排除 handler 或 controller |

OpenAPI 会包含已挂载子应用中的路由。基于注解排除用 `@Hidden`，按路径/方法排除用 `filter`。

---

## 测试

`TestClient` 会在进程内执行请求，并走与生产环境相同的路由、中间件、参数提取、验证、
依赖注入和错误处理管线。

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

适合用 `TestClient` 做 handler 测试、中间件/安全测试，以及不绑定端口的轻量集成测试。

---

## Java 支持

Colleen 使用 Kotlin 编写，但提供 Java 友好的 API。

Java 中的主要差异是：需要显式运行时类型、显式参数名，以及用 `ch(...)` 包装方法引用。

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

需要记住的规则：

| Kotlin | Java |
|---|---|
| `ctx.json<Todo>()` | `ctx.json(Todo.class)` |
| `ctx.getService<TodoService>()` | `ctx.getService(TodoService.class)` |
| `app.onError<BadRequest> { ... }` | `app.onError(BadRequest.class, (e, ctx) -> { ... })` |
| Kotlin 函数引用 | Java 方法引用需要 `ch(...)` |
| 参数名自动保留 | 使用 `@Param("name")` 或 `-parameters` |

Java 中解析泛型 JSON 时使用 `TypeRef`：

```java
List<Todo> todos = ctx.json(TypeRef.listOf(Todo.class));
Map<String, Todo> byId = ctx.json(TypeRef.mapOf(String.class, Todo.class));
```

---

## 配置

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

需要时可以替换 JSON mapper：

```kotlin
app.config {
    jsonMapper(MyCustomJsonMapper())
}
```

---

## 生产建议

- **虚拟线程**：Java 21+ 默认启用。它让同步 handler 更适合 IO 密集场景，但上线前仍应
  基于自己的负载压测。
- **限制**：公网服务建议显式设置 `maxConcurrentRequests`、`maxRequestSize` 和 `maxFileSize`。
- **流式 JSON**：大响应可用 `ctx.json(data, stream = true)`；小响应无需启用。
- **全局中间件**：每个全局中间件都会处理每个请求。能使用前缀中间件时优先使用前缀。
- **结构化日志**：需要最终状态码、耗时、发送字节数时，优先使用 `Event.ResponseSent`。
- **静态文件**：路径包含用户输入时，使用 `sendFile(..., baseDir = "...")` 或 `ServeStatic`。

可复现的基准压测配置见 `examples/benchmark-api/README.md`。

---

## 示例

建议从这里开始：

| 想学习... | 示例 |
|---|---|
| 最小应用 | `examples/hello-world` |
| JSON CRUD API | `examples/todo-app` |
| OpenAPI 注解 | `examples/openapi` |
| 参数提取 | `examples/extractor` |
| 自定义提取器 | `examples/custom-extractor` |
| 测试 | `examples/testing` |
| 内置中间件 | `examples/middleware-showcase` |
| WebSocket | `examples/websocket` |
| SSE | `examples/sse` |
| 子应用 | `examples/sub-app` |
| 错误处理 | `examples/error-handling` |
| 事件 | `examples/event-system` |
| 文件上传 | `examples/upload-app` |
| 静态文件 | `examples/serve-static` |
| HTML 渲染 | `examples/render-html` |
| JDBC | `examples/jdbc` |
| jOOQ + SQLite | `examples/jooq-sqlite` |
| Redis 缓存 | `examples/redis` |
| 数据验证 | `examples/validator` |
| 自动重载 | `examples/auto-reload` |
| 基准压测 | `examples/benchmark-api` |
| 完整聊天室应用 | `examples/chat-room-app` |

---

## License

MIT
