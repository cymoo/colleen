# Colleen Web 框架

[English Documentation](README.md)

## 目录

1. [简介](#简介)
2. [快速开始](#快速开始)
3. [示例](#示例)
4. [路由](#路由)
5. [参数提取](#参数提取)
6. [请求处理](#请求处理)
7. [数据验证](#数据验证)
8. [中间件](#中间件)
9. [依赖注入](#依赖注入)
10. [错误处理](#错误处理)
11. [事件系统](#事件系统)
12. [子应用](#子应用)
13. [测试](#测试)
14. [Java 支持](#java-支持)
15. [应用配置](#应用配置)
16. [生产建议](#生产建议)

---

## 简介

Colleen 是一个轻量级、类型安全的 Kotlin / Java Web 框架。

主要特点：

- 显式配置
- 可组合的中间件
- 自动参数提取
- 清晰直接的依赖注入
- 基于虚拟线程的同步请求处理模型

设计理念：

- 显式优于隐式
- 同步优于异步
- 类型安全不可或缺
- 清晰本身就是 feature
- 魔法是负债

---

## 快速开始

### 安装

**Maven**

```xml
<dependency>
    <groupId>io.github.cymoo</groupId>
    <artifactId>colleen</artifactId>
    <version>0.2.3</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation("io.github.cymoo:colleen:0.2.3")
```

### Hello World

```kotlin
fun main() {
    val app = Colleen()
    app.get("/") { "hello world" }
    app.listen(8000)
}
```

### Kotlin 简明示例

```kotlin
import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.middleware.*

// ---------- Function-style handlers ----------

fun getUser(id: Path<Int>, service: UserService): User =
    service.find(id.value)

fun createUser(body: Json<CreateUser>, service: UserService): User =
    service.create(body.value.name)

// ---------- Controller-style Handlers ----------

@Controller("/api")
class ApiController {

    @Get("/ping")
    fun ping(): String = "pong"

    @Get("/users/{id}")
    fun get(id: Path<Int>, service: UserService): User =
        service.find(id.value)
}

// ---------- 应用入口 ----------

fun main() {
    val app = Colleen()

    // 注册服务
    app.provide(UserService())

    // 注册中间件
    app.use(RequestLogger())
    app.use(Cors())

    // Lambda-style
    app.get("/") { "Hello World" }

    // Function-style
    app.get("/users/{id}", ::getUser)
    app.post("/users", ::createUser)

    // Controller-style
    app.addController(ApiController())

    app.listen(8000)
    println("→ http://localhost:8000")
}

// ---------- 数据模型 ----------

data class CreateUser(val name: String)
data class User(val id: Int, val name: String)

// ---------- 服务层 ----------

class UserService {

    private val users = mutableMapOf(
        1 to User(1, "Alice"),
        2 to User(2, "Bob")
    )

    private var nextId = 3

    fun create(name: String): User {
        val user = User(nextId++, name)
        users[user.id] = user
        return user
    }

    fun find(id: Int): User =
        users[id] ?: throw NotFound("User not found")
}
```

### Java 简明示例

```java
import io.github.cymoo.colleen.*;
import io.github.cymoo.colleen.middleware.*;

import static io.github.cymoo.colleen.lambda.ch;

import java.util.*;

class MyApp {

    // ----- Function-style handlers -----

    static User getUser(@Param("id") Path<Integer> id, UserService service) {
        return service.find(id.value);
    }

    static User createUser(Json<CreateUser> body, UserService service) {
        return service.create(body.value.name());
    }

    // ----- Controller-style Handlers -----

    @Controller("/api")
    static class ApiController {

        @Get("/ping")
        public String ping() {
            return "pong";
        }

        @Get("/users/{id}")
        public User get(@Param("id") Path<Integer> id, UserService service) {
            return service.find(id.value);
        }
    }

    // ----- 应用入口 -----

    static void main() {

        var app = new Colleen();

        app.provide(UserService.class, new UserService());

        app.use(new RequestLogger());

        // Lambda-style
        app.get("/", ctx -> "Hello World");

        // Function-style
        app.get("/users/{id}", ch(MyApp::getUser));
        app.post("/users", ch(MyApp::createUser));

        // Controller-style
        app.addController(new ApiController());

        app.listen(8000);
        System.out.println("→ http://localhost:8000");
    }

    // ----- 数据模型 -----

    record CreateUser(String name) {
    }

    record User(int id, String name) {
    }

    // ----- 服务层 -----

    static class UserService {
        private final Map<Integer, User> users = new HashMap<>();
        private int next = 3;

        UserService() {
            users.put(1, new User(1, "Alice"));
            users.put(2, new User(2, "Bob"));
        }

        User create(String name) {
            var user = new User(next++, name);
            users.put(user.id(), user);
            return user;
        }

        User find(int id) {
            var user = users.get(id);
            if (user == null) throw new NotFound("User not found");
            return user;
        }
    }
}
```

---

## 示例

Colleen 提供了一组完整示例，覆盖常见功能与集成方式：

- **[todo-app](examples/todo-app/src/main/kotlin/Main.kt)** - RESTful TODO API，包含 JSON 处理、参数验证与 CORS
- **[auth-app](examples/auth-app/src/main/kotlin/Main.kt)** - 用户认证示例，包含自定义中间件与服务注入
- **[upload-app](examples/upload-app/src/main/kotlin/Main.kt)** - 文件上传与下载，包含大小校验与静态资源管理等
- **[extractor](examples/extractor/src/main/kotlin/Main.kt)** - 参数提取全景示例（路径、查询、表单、JSON、Header、Cookie、文件）
- **[custom-extractor](examples/custom-extractor/src/main/kotlin/Main.kt)** - 自定义领域参数提取器（BearerToken、Pagination、DateRange）
- **[jooq-sqlite](examples/jooq-sqlite/src/main/kotlin)** - JOOQ 集成示例，包含代码生成、连接池与类型安全查询
- **[render-html](examples/render-html/src/main/kotlin/Main.kt)** - Pebble 模板引擎集成，用于动态 HTML 渲染
- **[serve-static](examples/serve-static/src/main/kotlin/Main.kt)** - 静态文件服务，支持缓存控制、自动索引与安全策略
- **[error-handling](examples/error-handling/src/main/kotlin/Main.kt)** - 全局异常处理、子应用异常传播与自定义错误中间件
- **[event-system](examples/event-system/src/main/kotlin/Main.kt)** - 生命周期事件、请求/响应追踪与执行耗时统计
- **[jdbc](examples/jdbc/src/main/kotlin/Main.kt)** - JDBC 集成示例，包含 SQLite、批量执行与命名参数查询
- **[redis](examples/redis/src/main/kotlin/Main.kt)** - Redis 集成示例，包含可配置 TTL 的响应缓存中间件
- **[signed-cookie](examples/signed-cookie/src/main/kotlin/Main.kt)** - 加密签名 Cookie，支持密钥轮换
- **[sse](examples/sse/src/main/kotlin/Main.kt)** - Server-Sent Events 实现实时推送，包含保活与连接生命周期管理
- **[sub-app](examples/sub-app/src/main/kotlin/Main.kt)** - 子应用架构示例，支持独立中间件、服务与错误处理
- **[testing](examples/testing/src/main/kotlin/Main.kt)** - TestClient 使用示例
- **[validator](examples/validator/src/main/kotlin/Main.kt)** - Validator 使用示例

---

## 路由

### 基础路由注册

Colleen 为常见 HTTP 方法提供了便捷 API：

```kotlin
app.get("/users") { }
app.post("/users") { }
app.put("/users/{id}") { }
app.delete("/users/{id}") { }
app.patch("/users/{id}") { }
app.head("/users/{id}") { }
app.options("/users") { }
// 匹配所有方法
app.all("/health") { }
```

### 路径参数

路径参数使用花括号语法定义 `{name}`：

```kotlin
app.get("/users/{id}") { ctx ->
    val id = ctx.pathParam("id")
    // ...
}
```

**路径段类型：**

1. **静态段** - 精确匹配：`/users`
2. **参数段** - 匹配任意单段值：`/users/{id}`
3. **通配符段** - 匹配剩余所有路径：`/files/{path...}`
4. **复合段** - 静态与参数混合：`/files/{name}.{ext}`

**示例：**

```kotlin
app.get("/users/{id}")

app.get("/posts/{year}/{month}/{slug}")

app.get("/files/{path...}") { ctx ->
    val path = ctx.pathParam("path")  // "a/b/c.txt"
}

app.get("/images/{filename}.{ext}") { ctx ->
    val filename = ctx.pathParam("filename")
    val ext = ctx.pathParam("ext")
}

app.get("/users/{id}-profile") { ctx ->
    val id = ctx.pathParam("id")
}
```

**路径匹配优先级：**

当多个路由可能匹配时，按照以下优先级从高到低进行选择：

1. 静态段
2. 复合段
3. 参数段
4. 通配符段

```kotlin
app.get("/users/admin")           // 静态（最高）
app.get("/users/{id}-profile")    // 复合
app.get("/users/{id}")            // 参数
app.get("/users/{path...}")       // 通配符（最低）

// 请求：GET /users/admin
// 匹配：/users/admin（静态具有最高优先级）

// 请求：GET /users/123-profile
// 匹配：/users/{id}-profile（复合）

// 请求：GET /users/123
// 匹配：/users/{id}
```

### 为路由附加中间件

可以为单个路由链式添加一个或多个中间件：

```kotlin
app.get("/users/{id}")
    .use(AuthMiddleware())
    .use(RateLimitMiddleware())
    .handle { ctx ->
        val id = ctx.pathParam("id")!!.toInt()
        userService.findById(id)
    }
```

### 路由分组

通过分组为一组路由添加统一前缀和中间件：

```kotlin
app.group("/api/v1") {
    use(ApiKeyMiddleware())

    get("/users") { ctx ->
        userService.findAll()
    }

    post("/users") { ctx ->
        val user = ctx.json<CreateUserRequest>()
        userService.create(user!!)
    }

    group("/admin") {
        use(AdminAuthMiddleware())

        get("/stats") { ctx ->
            adminService.getStats()
        }
    }
}
```

### 函数式路由

可以直接使用普通函数作为路由 handler，Colleen 会根据函数参数类型自动完成请求数据绑定：

```kotlin
fun update(id: Path<Int>, req: Json<UpdateUserRequest>): User {
    // ...
}

app.post("/users/{id}", ::update)
```

**为什么选择函数式路由？**

- 轻量直接
    - 不需要类或注解，只需定义函数。
- 类型安全
    - 请求数据自动解析，类型清晰。
- 易于测试
    - Handler 是普通的 Kotlin 函数，可独立单测。
- 适合小型 API 或偏函数式风格的代码库

### Controller 风格路由

通过注解标注的类来定义路由，更适合结构化的大型 API：

```kotlin
@Controller("/users")
class UserController(val userService: UserService) {

    @Get("/{id}")
    fun getUser(id: Path<Int>): User {
        return userService.findById(id.value)
    }

    @Post
    fun createUser(user: Json<CreateUserRequest>): User {
        return userService.create(user.value)
    }

    @Put("/{id}")
    fun updateUser(id: Path<Int>, user: Json<UpdateUserRequest>): User {
        return userService.update(id.value, user.value)
    }

    @Delete("/{id}")
    fun deleteUser(id: Path<Int>) {
        userService.delete(id.value)
    }
}

app.addController(UserController(UserService()))
```

**为什么使用 controller 风格？**

- 结构清晰
    - 相关端点集中在同一类中，便于维护。
- 声明式路由
    - 使用注解定义接口，表达直观。
- 职责分离
    - Controller 处理 HTTP 语义，服务层负责业务逻辑。
- 易于迁移
    - 对 Spring 等注解式框架用户更友好。

**支持的注解：**

- `@Controller(path)` - 标记 Controller 类
- `@Get(path)`、`@Post(path)`、`@Put(path)`、`@Delete(path)`、`@Patch(path)` - HTTP 方法
- `@Use` - 为方法附加中间件
- `@Param(name)` - 指定参数名称（Java 中通常需要，Kotlin 一般可自动保留参数名）

---

## 参数提取

Colleen 提供一套类型安全的参数提取机制，可以从不同来源自动解析并转换请求数据。

你只需要在函数签名中声明参数类型，框架会完成剩下的绑定工作。

### 内置提取器类型

| 类型             | 来源       | 示例                   |
|----------------|----------|----------------------|
| `Path<T>`      | 路径参数     | `id: Path<Int>`      |
| `Header`       | HTTP 请求头 | `token: Header`      |
| `Cookie`       | Cookie   | `session: Cookie`    |
| `Query<T>`     | 查询参数     | `q: Query<String>`   |
| `Form<T>`      | 表单字段     | `name: Form<String>` |
| `Json<T>`      | JSON 请求体 | `user: Json<User>`   |
| `Text`         | 原始文本请求体  | `body: Text`         |
| `Stream`       | 原始输入流    | `stream: Stream`     |
| `UploadedFile` | 上传文件     | `file: UploadedFile` |

### 基于函数的自动绑定

Colleen 会根据函数签名自动完成参数提取：

```kotlin
fun getUser(id: Path<Int>): User {
    return userService.findById(id.value)
}

fun searchUsers(
    query: Query<String>,
    limit: Query<Int?>,
    offset: Query<Int?>
): SearchResult {
    return userService.search(
        query.value,
        limit.value ?: 10,
        offset.value ?: 0
    )
}

fun createUser(user: Json<CreateUserRequest>): User {
    return userService.create(user.value)
}

app.get("/users/{id}", ::getUser)
app.get("/users/search", ::searchUsers)
app.post("/users", ::createUser)
```

函数参数就是你的接口定义。

### 路径参数

```kotlin
fun getUser(id: Path<Int>): User {
    return userService.findById(id.value)
}

app.get("/users/{id}", ::getUser)
```

`Path<T>` 会自动完成类型转换。如果转换失败，将返回 400。

### 查询参数

查询参数支持多种绑定形式。

**单值**

```kotlin
// ?q=keyword
fun search(q: Query<String>): List<User> {
    return searchService.searchByKeyword(q.value)
}
```

**多值列表**

```kotlin
// ?tags=kotlin&tags=backend
fun filter(tags: Query<List<String>>): List<Item> {
    return itemService.findByTags(tags.value)
}
```

**Map（单值）**

```kotlin
// ?role=admin&status=active
fun search(filters: Query<Map<String, String>>): List<User> {
    val role = filters.value["role"]
    val status = filters.value["status"]

    return userService.search(role = role, status = status)
}
```

**Map（多值）**

```kotlin
// ?tags=kotlin&tags=backend&role=admin
fun search(filters: Query<Map<String, List<String>>>): List<User> {
    val tags = filters.value["tags"] ?: emptyList()

    return userService.searchByTags(tags)
}
```

**自定义 DTO**

```kotlin
// ?q=neo&limit=10
data class SearchParams(
    val q: String,
    val limit: Int = 10,
    val offset: Int = 0
)

fun search(params: Query<SearchParams>): List<User> {
    return searchService.search(
        keyword = params.value.q,
        limit = params.value.limit,
        offset = params.value.offset
    )
}
```

框架会根据字段名自动完成参数映射与类型转换。

### 表单参数

`Form<T>` 的工作方式与 `Query<T>` 相同，只是数据来源为表单。

支持：

- application/x-www-form-urlencoded
- multipart/form-data

> 文件上传不属于 `Form<T>`，而是使用 `UploadedFile`。

```kotlin
fun createUser(form: Form<UserForm>): User {
    return userService.create(form.value)
}
```

### JSON 请求体

```kotlin
data class CreateUserRequest(
    val name: String,
    val email: String,
    val age: Int
)

fun createUser(user: Json<CreateUserRequest>): User {
    return userService.create(user.value)
}
```

`Json<T>` 会自动进行反序列化与类型校验。

### 请求头

请求头始终是可空的（HTTP 本身允许缺失）。

```kotlin
fun authenticate(token: Header): User {
    val tokenValue = token.value ?: throw Unauthorized()
    return authService.verify(tokenValue)
}
```

### Cookie

与 Header 一样，Cookie 也始终是可空的。

```kotlin
fun getSession(session: Cookie): SessionData {
    val sessionId = session.value ?: throw Unauthorized()
    return sessionService.get(sessionId)
}
```

### 文件上传

通过 `multipart/form-data` 支持文件上传。
每个 `UploadedFile` 对应一个文件字段。

```kotlin
fun uploadAvatar(file: UploadedFile): UploadResult {
    val fileItem = file.value ?: throw BadRequest("No file uploaded")

    return UploadResult(
        filename = fileItem.name,
        size = fileItem.size,
        contentType = fileItem.contentType
    )
}
```

### 原始请求体

当你需要完全控制请求负载时，可以直接使用原始数据。

适用于：

- Webhook 验签
- 原始 JSON 签名验证
- 大文件流式处理

```kotlin
// 文本
fun webhook(body: Text): Response {
    val payload = body.value ?: throw BadRequest("Missing request body")
    webhookService.process(payload)
    return Response(status = "received")
}

// 流
fun uploadLargeFile(stream: Stream): UploadResult {
    val inputStream = stream.value ?: throw BadRequest("Missing request body")
    return storageService.store(inputStream)
}
```

### 可空、必需与默认值

Colleen 明确区分三种情况：

```kotlin
fun handler(
    q: Query<String?>,                  // 可空
    tag: Query<String>,                 // 必需，缺失时抛 400
    page: Query<Int> = Query(1),        // 默认值
)
```

**规则**

- 可空参数（T?）
    - 可以缺失
    - 缺失时为 `null`

- 必须参数（T）
    - 必须存在
    - 缺失 → 400 Bad Request

- 可选参数（默认值）
    - 缺失时使用默认值
    - 不会报错

**JSON 中的相同规则**

```kotlin
data class SearchRequest(
    val q: String?,      // 可选
    val page: Int = 1,   // 默认
    val pageSize: Int,   // 必需
)
```

```json
{
  "q": "kotlin"
}
```

结果：

- `q` → `"kotlin"`
- `page` → `1`
- `pageSize` → 缺失 → 400 Bad Request

**集合类型的特殊规则**

为了减少空判断，集合类型在缺失时采用统一的默认行为。

支持的集合类型包括：

- `Query<List<T>>`
- `Query<Map<String, String>>`
- `Query<Map<String, List<String>>>`

（上述类型均包括可空形式，如 `List<T>?`。）

当参数在请求中 **完全缺失** 时：

- `List` → `emptyList()`
- `Map` → `emptyMap()`

这一行为与是否声明为可空类型无关，也不会因为缺失而触发 400。

```kotlin
fun filter(
    tags: Query<List<String>?>,
    filters: Query<Map<String, List<String>>>
)
```

在上述示例中：

- 未提供 `tags` → `emptyList()`
- 未提供 `filters` → `emptyMap()`

这种设计确保集合参数永远可安全遍历，无需额外的 `null` 判断。

### 自定义参数提取器

除了内置提取器，还可以实现自己的提取逻辑。

这适合：

- 身份认证
- 请求上下文封装
- 自定义 Header 解析
- 领域级参数对象

实现 `ExtractorFactory` 即可：

```kotlin
class BearerToken(value: String?) : ParamExtractor<String?>(value) {
    companion object : ExtractorFactory<BearerToken> {
        private const val BEARER_PREFIX = "Bearer "

        override fun build(paramName: String, param: Parameter): (Context) -> BearerToken {
            return { ctx ->
                val authHeader = ctx.header("Authorization")
                val token = authHeader
                    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                    ?.substring(BEARER_PREFIX.length)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                BearerToken(token)
            }
        }
    }

    /**
     * 验证令牌是否存在，如果缺失则抛出 Unauthorized
     */
    fun require(): String {
        return value ?: throw Unauthorized("Bearer token is required")
    }
}
```

使用方式：

```kotlin
fun getProfile(token: BearerToken): Map<String, Any> {
    val validToken = token.require()
    // ...
}
```

自定义提取器的主要目标：
> 将请求解析逻辑从 handler 中抽离，变为可复用、可测试的组件。

### 请求绑定错误

当发生以下问题时：

- JSON 反序列化失败
- 类型转换错误
- 必需字段缺失
- 请求结构不匹配

Colleen 默认抛出 `BadRequest`，并附带简洁稳定的错误信息，例如：

- `Missing required field: name`
- `Invalid type for field 'age': expected Int`
- `Invalid type for element 2 of field 'tags': expected String`
- `Unknown field: extra`
- `Malformed request body`
- `Request body does not match expected structure`

如果需要自定义响应格式，可以在全局异常 handler 中拦截底层异常并重新包装。

---

## 请求处理

Colleen 提供了一个简洁且富有表现力的请求处理模型，其核心是 Context 对象。

### Context 对象

Context 是请求处理的核心接口，封装了：

- HTTP 请求
- HTTP 响应
- State
- Service

> 以下示例展示了 Context API 的主要能力。
> 实际开发中，大多数 handler 只会使用其中的一小部分功能。

```kotlin
app.get("/example") { ctx ->
    // 请求属性
    val method = ctx.method
    val path = ctx.path
    val fullPath = ctx.fullPath
    val pattern = ctx.pattern
    val fullPattern = ctx.fullPattern

    // 路径参数
    val id = ctx.pathParam("id")

    // 查询参数
    val query = ctx.query("q")
    val allQueries = ctx.queries()
    val searchParams = ctx.queries<SearchParams>()

    // 请求头
    val authHeader = ctx.header("Authorization")
    val acceptsJson = ctx.accepts("json")

    // 表单数据
    val name = ctx.form("name")
    val allForms = ctx.forms()
    val formData = ctx.forms<UserForm>()

    // 请求体
    val text = ctx.text()
    val user = ctx.json<User>()

    // 文件上传
    val file = ctx.file("avatar")

    // 服务获取
    val userService = ctx.getService<UserService>()

    // 状态管理
    ctx.setState("userId", 123)
    val userId = ctx.getState<Int>("userId")

    // 响应
    ctx.status(200)
        .header("foo", "bar")
        .json(mapOf("message" to "success"))
}
```

**路由 Pattern**

- `pattern` 当前应用匹配到的路由模式，如 `/users/{id}`
- `fullPattern` 包含挂载前缀在内的完整路由模式，如 `/api/users/{id}`

> 注意
>
> `pattern` 与 `fullPattern` 只有在路由匹配完成后才会被填充。
> 若在中间件中访问，必须在 `next()` 返回之后再读取。

**请求解析语义**

当直接使用 `Context` 提供的 JSON、查询参数或表单解析 API 时，
Colleen 采用一致的语义规则：

- 当输入缺失或为空时，解析方法返回 `null`
- 当输入存在但格式不合法时，抛出 `BadRequest` 异常

这种设计使 handler 能够明确区分：可选输入和无效输入。

Colleen 为主要请求解析 API 提供了 Java 兼容重载，以便在 Java 项目中使用。

### 响应生成

可以通过两种方式生成响应：

- 返回一个值，由 Colleen 自动转换为 HTTP 响应

- 使用 `ctx` 显式写入响应

如果 handler 显式写入响应（例如调用 `ctx.json()`），则返回值将被忽略。

#### 自动结果转换

Colleen 会根据返回值类型自动生成适当的 HTTP 响应：

```kotlin
// String → text/plain
app.get("/hello") { "Hello, World!" }

// Map/List → application/json
app.get("/data") { mapOf("message" to "success") }

// 自定义对象 → application/json
app.get("/user") { User(id = 1, name = "Alice") }

// Unit → 204 No Content
app.post("/log") {
    logger.info("Logged")
}

// Status(Int) → 指定 HTTP 状态码
app.get("/status") { Status(204) }

// ByteArray → application/octet-stream
app.get("/file") { fileBytes }

// InputStream → 流式响应
app.get("/stream") { FileInputStream(file) }
```

#### 结构化结果类型

当需要对 HTTP 响应进行显式、声明式控制时，可以使用 `Result<T>`。

```kotlin
app.get("/user") { Result.ok(user) }

app.post("/user") { Result.created(newUser) }

app.delete("/user") { Result.noContent() }

app.get("/custom") { Result.of(418, user).header("X-Custom", "value") }
```

`Result<T>` 适用于需要精确控制状态码、响应体与响应头的场景。

#### JSON 响应

```kotlin
app.get("/users") { ctx ->
    val users = userService.findAll()
    ctx.json(users)
}

// 对大型数据进行流式 JSON 输出
app.get("/large-dataset") { ctx ->
    val data = dataService.getLargeDataset()
    ctx.json(data, stream = true)
}
```

#### HTML 响应

```kotlin
app.get("/") { ctx ->
    ctx.html(
        """
        <!DOCTYPE html>
        <html>
            <head><title>Welcome</title></head>
            <body><h1>Hello, World!</h1></body>
        </html>
    """.trimIndent()
    )
}
```

#### 文本响应

```kotlin
app.get("/health") { ctx ->
    ctx.text("OK")
}
```

#### 二进制响应

```kotlin
app.get("/download") { ctx ->
    val bytes = fileService.readFile("report.pdf")
    ctx.bytes(bytes, "application/pdf")
}
```

#### 重定向

```kotlin
app.get("/old-path") { ctx ->
    ctx.redirect("/new-path")
}

// 使用自定义状态码
app.get("/moved") { ctx ->
    ctx.redirect("/new-location", 301)
}
```

#### 流式响应

```kotlin
app.get("/download/{filename}") { ctx ->
    val filename = ctx.pathParam("filename")!!
    val stream = fileService.getFileStream(filename)
    ctx.stream(stream)
}
```

#### Server-Sent Events (SSE)

```kotlin
app.get("/events") { ctx ->
    ctx.sse { conn ->
        // 防止空闲超时
        conn.keepAlive(15)

        // 处理连接关闭
        conn.onClose { reason ->
            println("Connection closed: $reason")
        }

        // 发送事件
        repeat(10) { idx ->
            conn.send("message: $idx")
            sleep(1_000)
        }
    }
}
```

> SSE 连接会保持打开状态，直到 handler 执行完成或客户端主动断开连接。

---

## 数据验证

Colleen 提供了一套声明式、可组合的验证 API，具备清晰的空值语义与错误聚合机制。

验证规则在整个验证块执行结束后统一评估。

### 基本用法

```kotlin
app.post("/users") { ctx ->
    val user = ctx.json<CreateUserRequest>() ?: throw BadRequest()

    expect {
        field("name", user.name)
            .required()
            .notBlank()
            .minSize(3)
            .maxSize(50)

        field("email", user.email)
            .notBlank()
            .email()

        field("age", user.age)
            .between(18, 100)

        field("password", user.password)
            .required()
            .minSize(8)
            .matches(Regex("^(?=.*[A-Z])(?=.*[0-9]).*$"))
            .message("密码必须包含大写字母和数字")
    }

    userService.create(user)
}
```

### 验证语义

- 所有字段默认均为可选
- 使用 `.required()` 明确声明字段为必填
- 验证规则仅在字段值存在时才会执行
- 所有字段的错误会被统一收集并一次性返回
- 可通过 `.message()` 自定义错误信息

这种设计使得：

- 可选字段只有在提供值时才参与校验
- 必填字段需要显式声明，语义更加清晰

### 自定义错误消息

Colleen 为内置验证规则提供了合理的默认错误消息。

当你需要自定义错误消息时，可以使用 `message(...)` 修饰符，用于**覆盖紧邻的上一条验证规则所产生的错误信息**。

#### `message(String)`

覆盖**前一个验证规则**的错误提示。

- 仅在该规则校验失败时生效
- 若校验通过，则不会产生任何影响

```kotlin
field("username", user.username)
    .notBlank()
    .message("用户名不能为空")
```

如果 `notBlank()` 校验通过，`message()` 不会产生作用。

#### `message { value -> ... }`

根据当前字段值动态生成错误提示：

```kotlin
field("age", user.age)
    .min(18)
    .message { v -> "年龄 $v 太小" }
```

这种方式适用于需要根据实际输入生成定制化提示的场景。

### 分组验证

支持嵌套分组，用于校验结构化数据。
分组名称会自动体现在错误字段路径中。

```kotlin
expect {
    group("user") {
        field("name", user.name).required()
        field("email", user.email).email()
    }
}
```

对应的错误字段路径：

```text
user.name
user.email
```

### 错误处理

当验证失败时，会抛出 `ValidationException`，其中包含**所有字段的错误信息**。

```kotlin
app.onError<ValidationException> { e, ctx ->
    ctx.status(422).json(
        mapOf(
            "error" to "验证失败",
            "fields" to e.errors
        )
    )
}
```

### 非抛出式验证

如果你不希望通过异常控制流程，可以使用 `validate {}`：

```kotlin
val result = validate {
    field("username", user.name).required().minSize(3)
}

if (result.isFailure()) {
    println(result.errors())
}
```

这种方式更适合：

- 表单校验场景
- 业务内部校验
- 需要手动处理错误的流程控制

---

## 中间件

Colleen 的中间件采用类似 Koa 的洋葱模型，同时提供更强的执行保障：

> 一旦中间件的“前置逻辑”开始执行，其对应的“后置逻辑”一定会执行 —— 即使过程中发生异常。

这种设计使中间件行为可预测、可组合。

### 对称执行保证

在许多框架中，如果下游抛出异常，上游中间件的清理逻辑可能无法执行。

Colleen 明确保证**对称执行**：

- 如果中间件在调用 `next()` 之前执行代码
- 那么其 `next()` 之后的逻辑一定会执行
- 即使异常来自：
    - 下游中间件
    - 或路由 handler 本身

```kotlin
val middleware: Middleware = { ctx, next ->
    println("Before")

    next()  // 即使下游抛出异常

    println("After")   // 仍然保证执行
}
```

这一模型带来的好处包括：

- 可靠的资源释放
- 精确的性能统计
- 一致的日志与链路追踪
- 安全的事务收尾逻辑

### 异常处理模型

Colleen 将异常捕获与异常传播分离，确保执行流程清晰可控。

执行过程如下：

1. 中间件或 handler 抛出的异常会被捕获，而不是立即向外抛出
2. 捕获的异常沿中间件链向上传播
3. 每个中间件都可以：
   - 通过 `ctx.error` 访问当前异常
   - 显式设置 `ctx.error.handled = true`，表示该异常已被处理
4. 当整个中间件链执行完成后：
   - 如果异常仍未被标记为已处理，则会统一重新抛出

这种机制确保：

- 后置逻辑始终执行
- 异常不会被意外吞掉
- 错误处理集中且一致

> 编写中间件时，无需显式使用 `try / finally` 来保证清理逻辑。

### 基本中间件示例

```kotlin
val logger: Middleware = { ctx, next ->
    val start = System.currentTimeMillis()
    println("→ ${ctx.method} ${ctx.path}")

    next()  // 将控制权转移给下一个 middleware / handler

    val duration = System.currentTimeMillis() - start
    var status = ctx.response.status
    if (ctx.error != null) {
        status = (ctx.error?.cause as? HttpException)?.status ?: 500
    }
    println("← $status (${duration}ms)")
}

app.use(logger)
```

### 全局中间件

应用于所有请求，按照注册顺序执行：

```kotlin
app.use(Cors())
app.use(RequestLogger())
```

### 基于路径前缀

仅当请求路径匹配指定前缀时执行：

```kotlin
app.use("/api", ApiKeyMiddleware())

app.use("/admin", AdminAuthMiddleware())
```

### 条件中间件

通过自定义逻辑决定是否执行：

```kotlin
app.use({ ctx -> ctx.accepts("json") }, JsonOnlyMiddleware())

app.use({ ctx -> ctx.header("X-Internal") != null }, InternalMiddleware())
```

### 路由级中间件

绑定到特定 HTTP 方法和路径：

```kotlin
app.get("/users")
    .use(CacheMiddleware())
    .use(ValidationMiddleware())
    .handle { }
```

### 执行顺序

中间件采用线性、确定性的洋葱模型：

```kotlin
app.use(middleware1)
app.use(middleware2)

app.get("/") { ctx ->
    "response"
}
```

执行流程：

```
middleware1 → 前置
middleware2 → 前置
handler
middleware2 → 后置
middleware1 → 后置
```

执行顺序始终确定且对称。

### 短路（提前返回）

中间件可以选择**不**调用 `next()`。

当未调用 `next()` 时，中间件链会立即停止，当前中间件将成为该请求的最终处理器。

这在以下场景中非常有用：

- 健康检查
- 认证失败
- 限流
- 请求过滤
- 缓存响应

例如：

```kotlin
app.use { ctx, next ->
  if (ctx.path == "/ping") {
    ctx.text("pong")
    return@use
  }

  next()
}
```

如果路径为 `/ping`，则会直接发送响应，后续的中间件或 route handler 都不会执行。

否则，请求流程将正常继续。

**执行保证**

短路并不会破坏对称执行模型：

- 如果未调用 `next()`，下游逻辑将不会执行
- 上游中间件仍会按预期执行其 after 阶段的逻辑

### 编写参数化中间件

参数化中间件可以将行为封装为可复用组件，通过配置进行复用，而不是在代码中硬编码逻辑。

例如，根据角色控制访问权限：

```kotlin
class AuthMiddleware(private val requiredRole: String) : Middleware {
    override fun invoke(ctx: Context, next: Next) {
        val token = ctx.header("Authorization")
            ?.removePrefix("Bearer ")
            ?: throw Unauthorized("Missing token")

        val user = authService.verify(token)

        if (user.role != requiredRole) {
            throw Forbidden("Insufficient permissions")
        }

        ctx.setState("user", user)

        next()
    }
}

app.use("/admin", AuthMiddleware(requiredRole = "admin"))
```

通过构造参数注入配置，使同一个中间件可以在不同路径或不同权限级别下复用。

### 内置中间件

Colleen 提供一组常用的内置中间件。

#### ServeStatic

静态文件服务，支持缓存控制、安全检查与内容协商。

```kotlin
app.use(ServeStatic(root = "./public", baseUrl = "/static"))
```

#### BasicAuth

HTTP Basic 认证，使用恒定时间比较以防止时序攻击。

```kotlin
app.use(BasicAuth(credentials = mapOf("admin" to "secret123")))
```

#### Cors

CORS 支持，包括凭证与预检缓存控制。

```kotlin
app.use(Cors.permissive())  // 允许所有来源
app.use(Cors.forOrigin("https://example.com", allowCredentials = true))
```

#### RateLimiter

无锁令牌桶限流算法，支持自动清理。

```kotlin
app.use(RateLimiter(capacity = 100, refillRate = 10.0))
```

#### RequestId

为每个请求添加唯一标识，便于日志追踪。

```kotlin
app.use(RequestId())
```

#### RequestLogger

以简洁格式记录请求日志。

```kotlin
// 示例输出：
// 127.0.0.1 - - [2025-12-07 15:30:45] "GET /api/users" 200 - 2ms
app.use(RequestLogger())
```

#### SecurityHeaders

添加常见 HTTP 安全头（如 X-Frame-Options、CSP、HSTS 等）。

```kotlin
app.use(SecurityHeaders())
```

#### SignedCookie

支持签名 Cookie，并支持密钥轮换。

```kotlin
app.use(SignedCookie(secret = "your-secret-key"))

// 设置签名 Cookie
app.get("/login") { ctx ->
    val username = ctx.query("username") ?: "guest"
    ctx.signedCookie(name = "session", value = username)
}

// 读取签名 Cookie
app.get("/profile") { ctx ->
    val username = ctx.getSignedCookie("session")
    // ...
}
```

#### Heartbeat

提供简单的健康检查端点。

```kotlin
app.use(Heartbeat(endpoint = "/health"))
```

#### NoCache

禁用客户端和代理缓存。

```kotlin
app.use(NoCache())
```

#### Sunset

支持 RFC 8594 标准的 API 弃用头。

```kotlin
app.use(
    Sunset(
        sunsetAt = Instant.parse("2025-12-31T00:00:00Z"),
        links = listOf("<https://api.example.com/v2>; rel=\"successor-version\"")
    )
)
```

---

## 依赖注入

Colleen 内置一个轻量级依赖注入（DI）容器，用于管理应用级服务。

设计目标：

* 显式、可预测
* 不依赖基于反射的构造函数注入
* 与子应用（Sub-Application）完全兼容

整个 DI 系统强调类型驱动、显式注册和分层解析。

### 注册服务

服务注册在应用实例上，并存储在其服务容器中。

#### 单例（Singleton）

单例服务按需创建（延迟初始化），并在后续解析中复用同一实例：

```kotlin
// 注册现有实例
app.provide(UserService())

// 注册工厂（延迟创建单例）
app.provide { UserService() }
```

#### 瞬态（Transient）

瞬态服务在每次解析时都会创建新实例：

```kotlin
app.provide(singleton = false) { UserService() }
```

适用于：

* 无状态对象
* 轻量级服务
* 需要实例隔离的场景

#### 使用 Qualifier 区分服务实例

当需要注册同一类型的多个实例时，可以使用 qualifier 进行区分。

推荐使用 Kotlin 的 `object` 作为 qualifier，具有以下优势：

* 编译期类型安全
* 支持 IDE 重构
* 避免字符串错误

对于简单场景，也可以使用字符串作为 qualifier。

```kotlin
object Primary
object Replica

app.provide(qualifier = Primary) { HikariDataSource(primaryConfig) }
app.provide(qualifier = Replica) { HikariDataSource(replicaConfig) }

// 带 qualifier 的瞬态服务
app.provide(qualifier = Primary, singleton = false) { HikariDataSource(primaryConfig) }

// 带 qualifier 的现有实例
app.provide(HikariDataSource(primaryConfig), qualifier = Primary)

// 使用字符串 qualifier
app.provide(qualifier = "primary") { HikariDataSource(primaryConfig) }
```

### 注入服务

服务可以通过两种方式获取：

* 从请求上下文显式获取
* 作为 handler 参数自动解析

#### 在 handler 中显式获取

```kotlin
app.get("/users") { ctx ->
    val userService = ctx.getService<UserService>()
    userService.findAll()
}

// 使用 qualifier
app.get("/report") { ctx ->
    val primary = ctx.getService<DataSource>(Primary)
    val replica = ctx.getService<DataSource>(Replica)
    replica.query("SELECT ...")
}
```

#### 作为 handler 函数参数

Handler 函数可以声明多种类型的参数，Colleen 会自动解析。

参数解析规则：

* `Context` 类型
  → 注入当前请求上下文

* `ParamExtractor` 类型
  → 从请求中提取数据（路径参数、Query、Header、Body 等）

* 其他类型
  → 视为服务，从 DI 容器中解析

```kotlin
fun getUsers(userService: UserService): List<User> {
    return userService.findAll()
}

app.get("/users", ::getUsers)
```

如果需要注入带 qualifier 的服务，可以使用 `@Qualifier` 注解，并指定 qualifier 名称。

当 qualifier 使用 Kotlin `object` 注册时，使用其类名（不区分大小写）：

```kotlin
object Primary
object Replica

app.provide(qualifier = Primary) { HikariDataSource(primaryConfig) }
app.provide(qualifier = Replica) { HikariDataSource(replicaConfig) }

fun getReport(
    @Qualifier("Primary") primary: DataSource,
    @Qualifier("Replica") replica: DataSource,
): String {
    return replica.query("SELECT ...")
}

app.get("/report", ::getReport)
```

如果 qualifier 使用字符串注册，则直接使用该字符串：

```kotlin
app.provide(qualifier = "primary") { HikariDataSource(primaryConfig) }

fun getReport(@Qualifier("primary") ds: DataSource): String {
    return ds.query("SELECT ...")
}
```

#### 在 Controller 中使用

Controller 是普通的 Kotlin 或 Java 类，其依赖通过构造函数显式提供：

```kotlin
@Controller("/users")
class UserController(
    private val userService: UserService,
) {
    @Get
    fun list(auditService: AuditService): List<User> {
        auditService.record("list users")
        return userService.findAll()
    }

    @Get("/{id}")
    fun get(id: Path<Int>): User {
        return userService.findById(id.value)
    }
}

// 注册 controller
app.addController(UserController(UserService()))
```

Controller 方法的参数解析规则与普通 handler 完全一致：

* `Context` → 注入请求上下文
* `ParamExtractor` → 从请求提取数据
* 其他类型 → 作为服务从 DI 容器解析
* `@Qualifier` → 用于解析指定实例

### 服务解析机制

服务解析采用分层查找策略：

1. 当前应用的服务容器
2. 父应用的服务容器
3. 祖先应用的服务容器（递归向上）

```kotlin
val mainApp = Colleen()
mainApp.provide { DatabaseService() }

val apiApp = Colleen()
apiApp.provide { UserService() }

mainApp.mount("/api", apiApp)
```

在 `apiApp` 中：

* `UserService` 从 `apiApp` 容器解析
* `DatabaseService` 从 `mainApp` 容器解析

这种分层机制带来的好处：

* 基础设施服务可以在顶层统一提供
* 子应用可以定义自己的领域服务
* 模块之间职责清晰，互不干扰
* 支持构建结构清晰的模块化应用

---

## 错误处理

Colleen 提供了一套**结构化、基于类型的错误处理模型**。

在 Colleen 中：

- 错误以异常的形式表示
- 异常沿中间件链传播
- 最终由用户注册的异常处理器或默认策略转换为 HTTP 响应

### 抛出 HTTP 错误

Handler 或中间件可以直接抛出 HTTP 异常，从而立即终止当前处理流程：

```kotlin
throw BadRequest("无效输入")
throw Unauthorized("需要身份验证")
throw Forbidden("访问被拒绝")
throw NotFound("未找到用户")
throw Conflict("电子邮件已存在")
throw ValidationException(errors)
throw TooManyRequests("超过速率限制")
// 自定义状态或代码
throw HttpException(500, "内部错误", cause)
```

每个 `HttpException` 包含：

- HTTP 状态码
- 面向用户的错误消息
- 稳定、机器可读的错误代码

如果抛出的是非 `HttpException`，默认会被视为 `500 Internal Server Error`（除非显式注册了对应处理器）。

### 注册错误处理器

可以基于异常类型注册错误处理器：

```kotlin
app.onError<BadRequest> { e, ctx ->
    ctx.status(400).json(
        mapOf(
            "error" to "Bad Request",
            "message" to e.message
        )
    )
}

app.onError<ValidationException> { e, ctx ->
    ctx.status(422).json(
        mapOf(
            "error" to "Validation Failed",
            "fields" to e.errors
        )
    )
}

app.onError<HttpException> { e, ctx ->
    ctx.status(e.status).json(
        mapOf(
            "error" to e.code,
            "message" to e.message
        )
    )
}

app.onError<Exception> { e, ctx ->
    logger.error("Unexpected error", e)
    ctx.status(500).json(
        mapOf("error" to "Internal Server Error")
    )
}
```

注意：

* 错误处理器**按异常类型匹配**
* 而不是按状态码匹配

### 处理器解析顺序

当异常发生时，Colleen 会沿异常类继承链向上查找，并选择**最具体的匹配处理器**。

```kotlin
class CustomException : IllegalArgumentException()

app.onError<CustomException> { /* 优先匹配 */ }
app.onError<IllegalArgumentException> { /* 后备匹配 */ }
app.onError<Exception> { /* 最终兜底 */ }
```

解析规则遵循标准的类型层级优先策略，行为确定且可预测。

### 默认错误处理

如果没有匹配的自定义处理器，Colleen 会使用内置默认策略：

* 对于 `HttpException`
    * 使用异常自身的状态码、错误代码和消息
* 对于其他异常
    * 返回 `500 Internal Server Error`
* 所有 `5xx` 错误会自动记录日志

默认处理器支持内容协商：

* `Accept: application/json` → 返回 JSON 错误响应
* `Accept: text/html` → 返回简单的 HTML 错误页面

**JSON 示例**

```json
{
  "status": 404,
  "code": "NOT_FOUND",
  "message": "未找到用户"
}
```

**HTML 示例**

```html
<h1>404 Not Found</h1>
<p>未找到用户</p>
```

### 中间件中的错误状态

当请求处理过程中抛出异常时，它不会立即向外抛出，而是暂存于请求上下文中：

```kotlin
val errorState = ctx.error

if (errorState != null) {
    val cause = errorState.cause
    val handled = errorState.handled
}
```

如果希望在中间件中“消化”错误，可以将其标记为已处理：

```kotlin
ctx.error?.handled = true
```

被标记为 `handled` 的异常在中间件链结束后不会重新抛出。

这使得以下高级场景成为可能：

* 错误恢复
* 错误转换（例如映射为不同异常类型）
* 自定义日志或审计中间件
* 柔性失败策略

---

## 事件系统

Colleen 提供了一个轻量**同步的事件系统**，用于观察和扩展框架的生命周期与请求执行过程。

事件的定位是**观察机制（hook）**，而不是控制流工具。  
它用于“监听发生了什么”，而不是“改变接下来做什么”。

### 设计原则

- **同步、有序**
    - 监听器在事件触发时立即执行，并按照注册顺序运行。
- **分层与冒泡**
    - 子应用发出的事件默认会向父应用冒泡。
- **默认安全**
    - 监听器中的异常会被捕获并记录，不会影响请求处理流程。
- **作用域明确**
    - 部分事件仅在当前应用内生效（如请求生命周期事件）。

### 订阅事件

```kotlin
app.on<Event.RouteRegistered> { event ->
    logger.info("Route registered: ${event.node.method} ${event.node.path}")
}
```

事件监听是**类型安全**的，回调函数接收具体的事件类型实例。

### 事件类型

#### Server 生命周期事件

* `Event.ServerStarting` / `Event.ServerStarted`
* `Event.ServerStopping` / `Event.ServerStopped`

适用于：初始化或清理全局资源

#### 结构化事件

结构化事件描述的是**应用如何被构建**，而不是请求如何流动。

它们在组件注册或应用挂载时触发，可被父应用观察。

结构化事件包括：

* `Event.MiddlewareRegistered` — 注册中间件节点时触发
* `Event.RouteRegistered` — 注册路由时触发
* `Event.ControllerRegistered` — 注册 controller 实例时触发
* `Event.SubAppMounted` — 挂载子应用时触发

典型用途：

* 生成 OpenAPI / Swagger 文档
* 路由与中间件内省
* 可视化应用结构
* 启动阶段验证与诊断

**示例：收集路由信息用于 OpenAPI**

```kotlin
val routes = mutableListOf<RouteNode>()

app.on<Event.RouteRegistered> { event ->
    routes += event.node
}
```

默认情况下，结构化事件会向父应用冒泡，因此父应用可以完整观察所有已挂载子应用的结构。

> 结构化事件在“配置阶段”触发，而不是在请求期间。
> 它们语义稳定，适合构建工具与插件。

#### 请求生命周期事件

Colleen 在请求处理过程中提供了清晰的生命周期节点：

* `Event.RequestReceived` — 请求刚被接收，**尚未**进行路由匹配和执行中间件链，此时可以修改请求对象
* `Event.ResponseReady` — Handler 执行完毕，响应生成，但尚未发送
* `Event.ResponseSent` — 响应已完全发送，此时可以获取发送的子节数等

**示例：访问日志**

```kotlin
app.on<Event.ResponseSent> { event ->
    val ctx = event.ctx
    logger.info(
        "${ctx.method} ${ctx.path} ${ctx.response.status} " +
                "${event.bytesSent} bytes in ${event.total}"
    )
}
```

#### 执行事件（Execution Events）

执行事件用于描述当前执行单元及其耗时情况：

* `Event.MiddlewareExecuting` / `Event.MiddlewareExecuted`
* `Event.HandlerExecuting` / `Event.HandlerExecuted`
* `Event.SubAppExecuting` / `Event.SubAppExecuted`

适用于：

* 性能分析
* 链路追踪
* 细粒度指标采集

#### 异常事件（Exception Events）

`Event.ExceptionCaught` / `Event.ExceptionHandled`

### 事件冒泡

子应用发出的事件会自动向上传播：

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

mainApp.on<Event.HandlerExecuted> {
    // 接收来自 mainApp 和 apiApp 的事件
}

mainApp.mount("/api", apiApp)
```

**停止传播**

如果需要阻止事件继续冒泡：

```kotlin
app.on<Event.HandlerExecuted> { event ->
    event.stopPropagation()
}
```

### 事件来源

每个事件都包含 `source` 属性，指明是哪个应用实例发出的：

```kotlin
app.on<Event.RouteRegistered> { event ->
    logger.info("Emitted by app mounted at ${event.source.mountPath}")
}
```

这对于多子应用架构下的结构分析与调试尤为重要。

### 典型用途：框架扩展

事件系统非常适合实现**可插拔的框架扩展**，而无需修改请求处理管道。

例如，实现 HTTP method override 功能（简化版）：

```kotlin
fun Colleen.enableHttpMethodOverride() {
    on<Event.RequestReceived> { event ->
        val overridden = event.request.headers["x-http-method-override"]
            ?.uppercase()
            ?: return@on

        event.request = event.request.copy(method = overridden)
    }
}

app.enableHttpMethodOverride()
```

这种扩展方式具备：

* 隔离性强
* 可组合
* 对路由与中间件透明

### 何时使用事件

适合使用事件系统的场景包括：

* 观察或记录框架行为
* 构建插件或工具（如 OpenAPI 生成器）
* 添加横切关注点（日志、追踪、指标）
* 在不改变控制流的前提下扩展能力

如果需要改变请求处理逻辑或控制流程，应优先使用**中间件**。

---

## 子应用

子应用允许你将大型应用拆分为更小、相互隔离的模块。

每个子应用都拥有独立的路由、中间件、服务和配置，同时仍然参与统一的请求生命周期。

它们适合用于：

- API 版本管理
- 按功能拆分模块
- 管理后台或内部系统
- 边界清晰的大型系统架构

### 挂载子应用

子应用可以挂载到父应用的指定路径下。

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

// 配置子应用
apiApp.config {
    json {
        pretty = true
    }
}

// 注册子应用服务
apiApp.provide { UserService() }

// 定义子应用路由
apiApp.get("/users") { ctx ->
    val userService = ctx.getService<UserService>()
    userService.findAll()
}

// 挂载子应用
mainApp.mount("/api", apiApp)

// 请求：GET /api/users
// 实际由 apiApp 处理
```

挂载完成后，所有以 `/api` 开头的请求都会交由 `apiApp` 处理。

### 路径重写机制

当请求进入子应用时，Colleen 会对路径进行重写，使子应用能够像运行在根路径下一样定义路由。

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

apiApp.get("/users/{id}") { ctx ->
    // ctx.path = "/users/1"
    // ctx.fullPath = "/api/users/1"
    // ctx.pattern = "/users/{id}"
    // ctx.fullPattern = "/api/users/{id}"
}

mainApp.mount("/api", apiApp)
```

这样可以保证：

- 子应用内部的路由定义简洁清晰
- 在需要时仍可访问完整的路径信息

### 上下文层级结构

子应用会创建一个**子请求上下文**， 并继承父应用中的状态数据。

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

// 父应用中间件
mainApp.use { ctx, next ->
    ctx.setState("requestId", UUID.randomUUID().toString())
    next()
}

// 子应用 handler
apiApp.get("/users") { ctx ->
    val requestId = ctx.getState<String>("requestId")  // 可访问
}

mainApp.mount("/api", apiApp)
```

状态会沿应用树自上而下传递，非常适合处理横切关注点，例如：

- 请求 ID
- 认证上下文
- 链路追踪信息

### 服务解析规则

服务采用分层解析机制：
优先从当前子应用查找，找不到时回退到父应用。

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

// 根应用共享服务
mainApp.provide { DatabaseService() }

// 子应用专属服务
apiApp.provide { UserService() }

mainApp.mount("/api", apiApp)

// 在 apiApp 的 handler 中：
apiApp.get("/users") { ctx ->
    val userService = ctx.getService<UserService>()      // 来自 apiApp
    val dbService = ctx.getService<DatabaseService>()    // 来自 mainApp
}
```

这种机制带来：

- 子应用内部的局部覆盖能力
- 根级别统一的基础设施服务
- 清晰明确的职责边界

### 异常传播

默认情况下，子应用中抛出的异常会向上传播至父应用。

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

mainApp.onError<Exception> { e, ctx ->
    logger.error("Error in ${ctx.app.mountPath}", e)
    ctx.status(500).json(mapOf("error" to "Internal error"))
}

apiApp.get("/users") {
    throw RuntimeException("Something went wrong")
}

mainApp.mount("/api", apiApp)
```

这使得在根应用中统一定义**全局错误处理逻辑**变得非常简单。

**禁用异常传播：**

```kotlin
apiApp.config {
    propagateExceptions = false
}
```

禁用后，异常将只由子应用自身的异常处理器处理，不会再向父应用传播。

### 挂载路径信息

每个应用都清楚自己在应用树中的位置。

```kotlin
val mainApp = Colleen()
val subApp = Colleen()
val nestedApp = Colleen()

mainApp.mount("/api", subApp)
subApp.mount("/v1", nestedApp)

// 在 nestedApp 中：
nestedApp.mountPath        // "/v1"
nestedApp.fullMountPath    // "/api/v1"
nestedApp.parent           // subApp
```

这对于调试、日志记录和插件开发非常有帮助。

### 挂载多个子应用

一个主应用可以挂载多个相互独立的子应用。

```kotlin
val mainApp = Colleen()

val apiApp = Colleen()
val adminApp = Colleen()
val docsApp = Colleen()

mainApp.mount("/api", apiApp)
mainApp.mount("/admin", adminApp)
mainApp.mount("/docs", docsApp)
```

### 同一路径挂载多个子应用

多个子应用可以挂载到同一路径。

它们会按照挂载顺序依次匹配 ——  **第一个命中的路由将被执行。**

```kotlin
app.mount("/api", prodApi)
app.mount("/api", debugApi)
```

这种方式适用于：

- 分层增强（如认证、限流）
- 在不修改现有子应用的情况下扩展功能
- 渐进式迁移或灰度发布

挂载顺序至关重要。

### 使用限制

**只能在启动前挂载：**

```kotlin
val subApp = Colleen()
subApp.listen(9000)  // 已启动

val mainApp = Colleen()
mainApp.mount("/api", subApp)  // ❌ 错误：应用已运行
```

**每个应用只能被挂载一次：**

```kotlin
val mainApp = Colleen()
val subApp = Colleen()

mainApp.mount("/api", subApp)
mainApp.mount("/v2", subApp)  // ❌ 错误：应用已被挂载
```

---

## 测试

Colleen 内置了 `TestClient`，用于在同一进程内测试应用，无需真正启动 HTTP 服务器。

与外部 HTTP 客户端不同，`TestClient` 会通过与生产环境完全一致的执行管道处理请求 ——  
包括路由、中间件、参数解析、验证、依赖注入以及错误处理。

因此，测试既快速、可预测，又足够贴近真实运行环境。

### 可执行示例

下面是一个完整可运行的示例程序，演示常见测试场景：
身份验证、参数校验、查询参数以及文件上传。

```kotlin
import io.github.cymoo.colleen.*

// ---------------------------------------------------------------------
// 应用程序定义
// ---------------------------------------------------------------------

data class CreateUser(val email: String)
data class User(val id: Int, val email: String)

val authMiddleware = Middleware { ctx, next ->
    val token = ctx.header("Authorization")
    if (token != "Bearer valid-token") {
        throw Unauthorized()
    }
    next()
}

fun createApp(): Colleen {
    val app = Colleen()

    app.use("/users", authMiddleware)

    app.post("/users") { ctx ->
        val req = ctx.json<CreateUser>() ?: throw BadRequest()

        expect {
            field("email", req.email).email()
        }

        ctx.status(201).json(
            User(id = 1, email = req.email)
        )
    }

    app.post("/users/upload") { ctx ->
        val avatar = ctx.file("avatar") ?: throw BadRequest("缺少文件")
        mapOf(
            "name" to avatar.name,
            "size" to avatar.size
        )
    }

    return app
}

// ---------------------------------------------------------------------
// 测试示例
// ---------------------------------------------------------------------

fun runTestClientExample() {
    val client = TestClient(createApp())

    // JSON 请求 + 身份验证
    val response = client.post("/users")
        .header("Authorization", "Bearer valid-token")
        .json(mapOf("email" to "alice@example.com"))
        .send()

    response.assertStatus(201)

    val user = response.json<User>()!!
    check(user.id == 1)
    check(user.email == "alice@example.com")

    // Multipart 文件上传
    client.post("/users/upload")
        .header("Authorization", "Bearer valid-token")
        .file("avatar", "avatar.png", byteArrayOf(1, 2, 3), "image/png")
        .send()
        .assertStatus(200)

    // 查询参数（路由未定义）
    client.get("/users")
        .query("page", "1")
        .query("size", "10")
        .send()
        .assertClientError()

    println("All TestClient checks passed ✔")
}

fun main() {
    runTestClientExample()
}
```

### 何时使用 TestClient

`TestClient` 适用于以下场景：

- **Handler 级测试**
    - 验证请求解析、参数校验规则和响应结构。
- **中间件与安全测试**
    - 验证认证、授权以及各种横切逻辑。
- **轻量级集成测试**
    - 在不启动真实服务器的前提下，覆盖多层调用链。

由于测试在进程内执行， 通常比基于网络套接字的 HTTP 测试快几个数量级。

---

## Java 支持

虽然 Colleen 使用 Kotlin 编写，但是 Java 同样能够流畅地使用它。

路由、中间件、事件系统、依赖注入以及参数提取等核心能力，都可以通过清晰、显式且对 Java 友好的 API 完整使用。

Kotlin 与 Java API 的核心差异主要体现在：

- 泛型类型信息（reified vs. type erasure）
- 反射能力
- 方法引用的可解析性

Colleen 已尽量在底层屏蔽差异，但在 Java 中仍需要一些显式写法。

### 1. 显式运行时类型

Kotlin 支持具体化泛型（reified generics），Java 中没有类似的特性。

因此在 Java 中，必须显式传入 `Class<T>`。

**Kotlin**：

```kotlin
app.on<Event.RequestReceived> { /* ... */ }
app.onError<BadRequest> { e, ctx -> /* ... */ }
app.provide { UserService() }

val service = ctx.getService<UserService>()
val user = ctx.json<User>()
```

**Java**：

```java
app.on(Event.RequestReceived.class, event -> { /* ... */ });
app.onError(BadRequest.class, (e, ctx) -> { /* ... */ });
app.provide(UserService.class, new UserService());

UserService service = ctx.getService(UserService.class);
User user = ctx.json(User.class);
```

### 2. 显式参数名称

Java 默认不会保留方法参数名（除非使用 `-parameters` 编译选项）。

因此，在 Java route handler 中必须使用 `@Param` 显式声明逻辑参数名称。

**Kotlin（无需注解）**：

```kotlin
fun getUser(id: Path<Int>) = userService.findById(id.value)

app.get("/users/{id}", ::getUser)
```

**Java（需要 `@Param`）**：

```java
public User getUser(@Param("id") Path<Integer> id) {
    return userService.findById(id.value);
}

app.get("/users/{id}", ch(this::getUser));
```

> `@Param` 用于声明路径参数、查询参数、表单字段或请求头的逻辑名称。

### 3. 包装方法引用：`ch()`

Java 方法引用无法像 Kotlin 函数那样直接被框架解析。

因此需要使用 `ch()` 进行包装，将方法引用转换为可执行 `Handler`。

```java
import io.github.cymoo.colleen.Colleen;

import static io.github.cymoo.colleen.lambda.ch;

class MyApp {
    static void main() {
        var app = new Colleen();

        app.get("/", ch(MyApp::hello));

        app.listen(8000);
    }

    static String hello() {
        return "hello";
    }
}
```

在底层，`ch()` 会：

- 通过 JVM 的 `SerializedLambda` 解析真实方法
- 提取捕获的实例（如果存在）
- 应用与 Kotlin route handler 相同的参数提取规则

你只需要记住一条规则：

> 任何作为 route handler 的 Java 方法引用，都必须用 `ch()` 包装。

### 4. 泛型 JSON 解析与 `TypeRef`

Java 在运行时会擦除泛型类型信息。

当解析泛型 JSON 数据时，需要使用 `TypeRef` 保留类型结构：

```java
List<User> users = ctx.json(TypeRef.listOf(User.class));

Map<String, User> userMap = ctx.json(TypeRef.mapOf(String.class, User.class));
```

对于深层嵌套泛型：

```java
var data = ctx.json(new TypeRef<Map<String, List<User>>>() {});
```

对于非泛型类型，优先使用 `Class<T>`：

```java
User user = ctx.json(User.class);
```

---

## 应用配置

以下示例展示了 Colleen 的默认配置。

### Server 配置

```kotlin
app.config {
    server {
        host = "127.0.0.1"
        port = 8000

        // 线程模型
        useVirtualThreads = true
        maxThreads = <cpuCount> * 8       // 仅在禁用虚拟线程时生效

        // 并发控制
        maxConcurrentRequests = 0          // 0 = 无限制

        // 请求大小限制
        maxRequestSize = 30 * 1024 * 1024  // 30MB
        maxFileSize = 10 * 1024 * 1024     // 每个文件 10MB
        fileSizeThreshold = 256 * 1024      // 超过 256KB 后写入磁盘缓冲

        // 超时设置（毫秒）
        shutdownTimeout = 30_000
        idleTimeout = 30_000
        readTimeout = 30_000
        writeTimeout = 30_000
    }
}
```

### JSON 配置

```kotlin
app.config {
    json {
        pretty = false
        includeNulls = false

        failOnUnknownProperties = true
        failOnNullForPrimitives = true
        failOnEmptyBeans = false

        acceptSingleValueAsArray = false

        writeDatesAsTimestamps = false
        dateFormat = null                  // null = ISO-8601

        writeEnumsUsingToString = false
        readEnumsUsingToString = false
    }
}
```

#### 常见选项说明

- **pretty**  
  是否格式化输出 JSON。

- **includeNulls**  
  是否在响应中包含值为 `null` 的字段。

- **failOnUnknownProperties**  
  请求体包含未知字段时是否报错。

- **failOnNullForPrimitives**  
  基本类型字段为 `null` 时是否报错。

- **acceptSingleValueAsArray**  
  是否允许将单个值自动视为数组。

- **时间与枚举配置**  
  控制日期格式及枚举序列化方式。

#### 自定义 JSON 映射器

如果默认的 Jackson 实现无法满足需求，可以替换为自定义映射器：

```kotlin
app.config {
    jsonMapper(MyCustomJsonMapper())
}
```

自定义实现必须实现 `JsonMapper` 接口。

### 应用程序配置

```kotlin
app.config {
    // 为 true 时，子应用抛出的异常会向父应用传播
    propagateExceptions = true
}
```

---

## 生产建议

本节汇总了一些生产环境中的常见注意事项。

### 虚拟线程

Colleen 默认启用 Java 21 的虚拟线程（Virtual Threads）。

虚拟线程在 IO 密集型场景下可以显著提升并发能力，以极低的线程开销支持大量并发任务。

在特定情况下，虚拟线程可能发生 *pinning* —— 即虚拟线程在阻塞期间暂时占用其载体线程（carrier thread），
导致该平台线程无法被复用。

在早期实现中，以下情况可能触发 pinning：

- 进入 `synchronized` 代码块
- 执行本地方法或 JNI 调用

发生 pinning 时，载体线程会在阻塞期间保持占用状态，在高并发负载下会大幅降低整体并行度。

较新的 JDK 版本（包括 Java 25）对虚拟线程实现进行了重要改进。尤其是在常见的 `synchronized` 场景下，已不存在早期版本中的 pinning 限制。

如果应用包含大量 CPU 密集型任务，或依赖存在本地阻塞调用的第三方库，建议进行充分的基准测试。
在部分负载模型下，平台线程可能提供更稳定、可预测的延迟表现。

在高并发生产环境中，建议优先使用较新的 JDK 版本。

### JSON 流式传输

对于大型 JSON 响应，可以启用流式输出，而不是一次性全部缓冲：

```kotlin
app.get("/large") { ctx ->
    ctx.json(getLargeData(), stream = true)
}
```

流式输出可以：

- 降低内存占用
- 改善大体积响应的首字节延迟

但需要注意：

- 流式过程中抛出的异常可能导致客户端收到部分响应
- 最好在大数据量场景下使用，小型响应无需启用流式

### 中间件开销

每增加一个中间件，请求处理链中就多一个执行步骤。

优先使用**带前缀的中间件**，减少不必要的全局开销：

```kotlin
// 对所有请求生效
app.use(LoggingMiddleware())

// 仅对 /api/* 生效
app.use("/api", LoggingMiddleware())
```

避免在全局中间件中放置复杂或耗时逻辑，除非确有必要。

### 请求与文件大小限制

在生产环境中，应合理配置请求与上传限制：

```kotlin
app.config {
    server {
        maxRequestSize = 50 * 1024 * 1024
        maxFileSize = 20 * 1024 * 1024
        maxConcurrentRequests = 500
    }
}
```

限制过宽可能导致：

- 内存压力
- 磁盘耗尽
- 资源耗尽攻击风险

根据业务需求进行平衡配置。

### 超时策略

根据部署环境（例如反向代理、负载均衡器）合理调整超时参数：

```kotlin
app.config {
    server {
        idleTimeout = 60_000
        readTimeout = 30_000
        writeTimeout = 30_000
        shutdownTimeout = 30_000
    }
}
```

- 超时过长可能导致资源被长时间占用
- 超时过短可能会误伤合法的慢速客户端

建议结合实际流量模型进行调优。

### 结构化日志

Colleen 会发出多种生命周期事件，可用于实现结构化日志或其他指标采集：

```kotlin
app.on<Event.ResponseSent> { event ->
    logger.info(
        "request method={} path={} status={} duration_ms={} bytes={}",
        event.ctx.method,
        event.ctx.fullPath,
        event.ctx.response.status,
        event.total.inWholeMilliseconds,
        event.bytesSent
    )
}
```

这种方式具有两个优势：

- 日志记录与请求真实完成时刻保持一致
- 无需将日志逻辑耦合到中间件中

推荐在生产环境中基于事件系统统一实现日志与监控集成。

---

## License

MIT