# Colleen

A lightweight, type-safe web framework for Kotlin and Java.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cymoo/colleen?color=blue)](https://central.sonatype.com/artifact/io.github.cymoo/colleen)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/projects/jdk/21/)

[中文文档](README-zh.md)

```kotlin
fun main() {
    val app = Colleen()
    app.get("/") { "hello world" }
    app.listen(8000)
}
```

## Overview

Colleen focuses on a predictable programming model for HTTP APIs and real-time services on Java 21+.

- **Type-safe parameter extraction** — Path, query, form, JSON, headers, and cookies are extracted as typed parameters
- **Symmetric middleware execution** — Post-processing still runs when exceptions occur, without manual `try/finally`
- **Built-in WebSocket and SSE** — Unified support for real-time handlers, middleware, and route parameters
- **OpenAPI generation** — Serve Swagger UI from route definitions, without extra annotations
- **Declarative validation** — Fluent DSL with field-level error aggregation
- **Explicit dependency injection** — Register services directly with `app.provide(...)`
- **Built-in middleware** — CORS, rate limiting, auth, security headers, signed cookies, and more
- **Java compatibility** — Works with Java records, lambdas, and method references
- **Sub-app architecture** — Mount independent apps with isolated middleware, services, and error handling
- **In-process testing** — TestClient for fast, network-free tests

### Core API Examples

**Type-safe parameters** — automatically extracted from the request:

```kotlin
fun getUser(id: Path<Int>, active: Query<Boolean?>): User = ...
app.get("/users/{id}", ::getUser)
// Missing required parameter → 400 Bad Request; nullable parameter → null if absent
```

**Middleware execution guarantees** — cleanup logic runs even if the handler throws:

```kotlin
val timing: Middleware = { ctx, next ->
    val start = System.nanoTime()
    next()  // after-logic always runs
    ctx.header("X-Response-Time", "${(System.nanoTime() - start) / 1_000_000}ms")
}
```

**WebSocket** — with path params, query params, and middleware:

```kotlin
app.ws("/chat/{room}") { conn ->
    val room = conn.pathParam("room")
    conn.onTextMessage { msg -> conn.send("[$room] $msg") }
}
```

**Validation** — declarative, with full error aggregation:

```kotlin
expect {
    field("email", user.email).required().email()
    field("age", user.age).between(18, 120)
    field("password", user.password).required().minSize(8)
        .matches(Regex(".*[A-Z].*")).message("Must contain uppercase")
}
```

**OpenAPI** — one line for Swagger UI:

```kotlin
app.openApi()  // → Swagger UI at http://localhost:8000/docs
```

### Design Principles

| | Principle | In practice |
|---|-----------|-------------|
| 1 | **Explicit > Implicit** | No hidden config or classpath scanning |
| 2 | **Synchronous > Asynchronous** | Virtual threads provide concurrency without callback chains |
| 3 | **Type safety by default** | Catch issues at compile time |
| 4 | **Readable code paths** | Behavior stays traceable in application code |
| 5 | **Minimal framework magic** | Operational behavior is easier to debug and test |

---

## Table of Contents

**Getting Started** — [Quick Start](#quick-start) · [Examples](#examples)

**Core** — [Routing](#routing) · [Parameter Extraction](#parameter-extraction) · [Request Handling](#request-handling)

**Features** — [WebSocket](#websocket) · [Validation](#validation) · [Middleware](#middleware) · [Dependency Injection](#dependency-injection) · [Error Handling](#error-handling) · [Events System](#events-system) · [Sub-Applications](#sub-applications) · [OpenAPI Documentation](#openapi-documentation)

**More** — [Testing](#testing) · [Java Support](#java-support) · [Configuration](#configuration) · [Production Notes](#production-notes)

---

## Quick Start

### Installation

**Maven**

```xml
<dependency>
    <groupId>io.github.cymoo</groupId>
    <artifactId>colleen</artifactId>
    <version>0.4.1</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation("io.github.cymoo:colleen:0.4.1")
```

### Hello World

```kotlin
fun main() {
    val app = Colleen()
    app.get("/") { "hello world" }
    app.listen(8000)
}
```

### Kotlin Compact Example

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

// ---------- Application ----------

fun main() {
    val app = Colleen()
  
    // Expose OpenAPI docs (Swagger UI): http://localhost:8000/docs
    app.openApi()

    // Provide service
    app.provide(UserService())

    // Register middleware
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

// ---------- Data ----------

data class CreateUser(val name: String)
data class User(val id: Int, val name: String)

// ---------- Service ----------

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

### Java Compact Example

```java
import io.github.cymoo.colleen.*;
import io.github.cymoo.colleen.middleware.*;

import static io.github.cymoo.colleen.lambda.ch;

import java.util.*;

class MyApp {

    // ----- Function-style -----

    static User getUser(@Param("id") Path<Integer> id, UserService service) {
        return service.find(id.value);
    }

    static User createUser(Json<CreateUser> body, UserService service) {
        return service.create(body.value.name());
    }

    // ----- Controller-style -----

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

    // ----- Application -----

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

    // ----- Data -----

    record CreateUser(String name) {
    }

    record User(int id, String name) {
    }

    // ----- Service -----

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

## Examples

Colleen comes with comprehensive examples demonstrating various features and integration patterns:

- **[todo-app](examples/todo-app/src/main/kotlin/Main.kt)** - RESTful TODO API with JSON handling, validation, and CORS
- **[openapi](examples/openapi/src/main/kotlin/Main.kt)** - API documentation with OpenAPI support and interactive Swagger UI
- **[auth-app](examples/auth-app/src/main/kotlin/Main.kt)** - User authentication with custom middleware and service injection
- **[upload-app](examples/upload-app/src/main/kotlin/Main.kt)** - File upload/download with size validation and static file management
- **[extractor](examples/extractor/src/main/kotlin/Main.kt)** - Comprehensive parameter extraction examples (path,
  query, form, JSON, headers, cookies, files)
- **[custom-extractor](examples/custom-extractor/src/main/kotlin/Main.kt)** - Domain-specific parameter extractors (
  BearerToken, Pagination, DateRange)
- **[jooq-sqlite](examples/jooq-sqlite/src/main/kotlin)** - JOOQ integration with code generation, connection
  pooling, and type-safe queries
- **[render-html](examples/render-html/src/main/kotlin/Main.kt)** - Pebble template engine integration for dynamic HTML
  rendering
- **[serve-static](examples/serve-static/src/main/kotlin/Main.kt)** - Static file serving with cache control,
  auto-indexing, and security features
- **[error-handling](examples/error-handling/src/main/kotlin/Main.kt)** - Global error handlers, sub-app error
  propagation, and custom error middleware
- **[event-system](examples/event-system/src/main/kotlin/Main.kt)** - Application lifecycle events, request/response
  tracing, and execution timing
- **[jdbc](examples/jdbc/src/main/kotlin/Main.kt)** - JDBC integration with SQLite database, batch execution, and named
  parameter queries
- **[redis](examples/redis/src/main/kotlin/Main.kt)** - Redis integration with HTTP response caching middleware and
  configurable TTL
- **[middleware-showcase](examples/middleware-showcase)** - Usage examples of various built-in middleware
- **[sse](examples/sse/src/main/kotlin/Main.kt)** - Server-Sent Events for real-time server push with keep-alive and
  connection lifecycle handling
- **[websocket](examples/websocket/src/main/kotlin/Main.kt)** - WebSocket support with echo, chat rooms, middleware
  authentication, and controller-style handlers
- **[sub-app](examples/sub-app/src/main/kotlin/Main.kt)** - Modular application architecture with independent
  middleware, error handling, and service
- **[testing](examples/testing/src/main/kotlin/Main.kt)** - TestClient usage for application testing
- **[validator](examples/validator/src/main/kotlin/Main.kt)** - Validator usage 


---

## Routing

### Basic Route Registration

Colleen provides convenience methods for common HTTP methods:

```kotlin
app.get("/users") { }
app.post("/users") { }
app.put("/users/{id}") { }
app.delete("/users/{id}") { }
app.patch("/users/{id}") { }
app.head("/users/{id}") { }
app.options("/users") { }
// Match all methods
app.all("/health") { }
```

### Path Parameters

Path parameters are defined using curly braces `{name}`.

```kotlin
app.get("/users/{id}") { ctx ->
    val id = ctx.pathParam("id")
    // ...
}
```

**Path Segment Types:**

1. **Static segments** - Match exact text: `/users`
2. **Parameter segments** - Match any value: `/users/{id}`
3. **Wildcard segments** - Match remaining path: `/files/{path...}`
4. **Complex segments** - Mix static and parameters: `/files/{name}.{ext}`

**Examples:**

```kotlin
// Simple parameter
app.get("/users/{id}")

// Multiple parameters
app.get("/posts/{year}/{month}/{slug}")

// Wildcard (matches rest of path)
app.get("/files/{path...}") { ctx ->
    val path = ctx.pathParam("path")  // "a/b/c.txt"
}

// Complex segment
app.get("/images/{filename}.{ext}") { ctx ->
    val filename = ctx.pathParam("filename")
    val ext = ctx.pathParam("ext")
}

app.get("/users/{id}-profile") { ctx ->
    val id = ctx.pathParam("id")
}
```

**Path Matching Priority:**

Routes are matched in priority order (higher priority first):

1. Static segments (highest)
2. Complex segments
3. Parameter segments
4. Wildcard segments (lowest)

```kotlin
// Given these routes:
app.get("/users/admin")           // Priority 3 (static)
app.get("/users/{id}-profile")    // Priority 2 (complex)
app.get("/users/{id}")            // Priority 1 (parameter)
app.get("/users/{path...}")       // Priority 0 (wildcard)

// Request: GET /users/admin
// Matches: /users/admin (static has the highest priority)

// Request: GET /users/123-profile
// Matches: /users/{id}-profile (complex)

// Request: GET /users/123
// Matches: /users/{id} (parameter)
```

### Routes with Middleware

Define a route and attach one or more middleware to it:

```kotlin
app.get("/users/{id}")
    .use(AuthMiddleware())
    .use(RateLimitMiddleware())
    .handle { ctx ->
        val id = ctx.pathParam("id")!!.toInt()
        userService.findById(id)
    }
```

### Route Groups

Group related routes under a common path prefix:

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

### Function-based Routing

Define routes using plain functions.

Colleen automatically binds request data based on function parameters.

```kotlin
fun update(id: Path<Int>, req: Json<UpdateUserRequest>): User {
    // ...
}

app.post("/users/{id}", ::update)
```

**Why use function-based routing?**

- Minimal and lightweight
    - No classes or annotations required — just functions.

- Strongly typed parameters
    - Request data are type-safe and automatically parsed.

- Easy to test
    - Handler functions are plain Kotlin functions and can be tested easily.

- Great for small APIs and functional-style codebases
    - Ideal when you prefer simplicity over structure.

### Controller-Style Routing

Define routes using annotated controller classes.

This style groups related endpoints into a single class and provides a clear, structured way to build larger APIs.

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

**Why use controller-style routing?**

- Clear structure for larger APIs
    - Related endpoints are grouped together, making the codebase easier to navigate.

- Annotation-driven and declarative
    - Routes are defined declaratively with annotations, similar to Spring MVC.

- Good separation of concerns
    - Controllers focus on HTTP concerns, while services handle business logic.

- Familiar to many backend developers
    - Especially comfortable for users coming from Spring or other annotation-based frameworks.

**Supported Annotations:**

- `@Controller(path)` - Marks a class as a controller
- `@Get(path)`, `@Post(path)`, `@Put(path)`, `@Delete(path)`, `@Patch(path)` - HTTP method annotations
- `@Use` - Attach middleware to a handler method
- `@Param(name)` - Override parameter name for extraction
    - Usually not required in Kotlin (parameter names are preserved), but required in Java.

---

## Parameter Extraction

Colleen provides powerful type-safe parameter extractors that automatically extract and convert request data from
various sources.

### Extractor Types

| Type           | Description      | Example              |
|----------------|------------------|----------------------|
| `Path<T>`      | Path parameter   | `id: Path<Int>`      |
| `Header`       | HTTP header      | `token: Header`      |
| `Cookie`       | Cookie value     | `session: Cookie`    |
| `Query<T>`     | Query parameter  | `q: Query<String>`   |
| `Form<T>`      | Form parameter   | `name: Form<String>` |
| `Json<T>`      | JSON body        | `user: Json<User>`   |
| `Text`         | Raw text body    | `body: Text`         |
| `Stream`       | Raw input stream | `stream: Stream`     |
| `UploadedFile` | Uploaded file    | `file: UploadedFile` |

### Function-Based Handlers

Colleen can automatically extract parameters from function signatures:

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

### Path Parameters

```kotlin
fun getUser(id: Path<Int>): User {
    return userService.findById(id.value)
}

app.get("/users/{id}", ::getUser)
```

`Path<T>` automatically performs type conversion. If the conversion fails, a 400 response is returned.

### Query Parameters

Query parameters support multiple shapes:

**Single value**

```kotlin
// ?q=keyword
fun search(q: Query<String>): List<User> {
    return searchService.searchByKeyword(q.value)
}
```

**List of values**

```kotlin
// ?tags=kotlin&tags=backend
fun filter(tags: Query<List<String>>): List<Item> {
    return itemService.findByTags(tags.value)
}
```

**Map (single values)**

```kotlin
// ?role=admin&status=active
fun search(filters: Query<Map<String, String>>): List<User> {
    val role = filters.value["role"]
    val status = filters.value["status"]

    return userService.search(role = role, status = status)
}
```

**Map (multiple values)**

```kotlin
// ?tags=kotlin&tags=backend&role=admin
fun search(filters: Query<Map<String, List<String>>>): List<User> {
    val tags = filters.value["tags"] ?: emptyList()

    return userService.searchByTags(tags)
}
```

**Custom DTO**

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

### Form Parameters

`Form<T>` work identically to `Query<T>`, but extract values from form data.

Supports:

- `application/x-www-form-urlencoded`
- `multipart/form-data`

> File uploads do not belong to `Form<T>`; they use `UploadedFile` instead.

```kotlin
fun createUser(form: Form<UserForm>): User {
    return userService.create(form.value)
}
```

### JSON Body

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

`Json<T>` automatically performs deserialization and type validation.

### Headers

Header values are always nullable, since a header may be missing from the request.

```kotlin
fun authenticate(token: Header): User {
    // Header values are always nullable
    val tokenValue = token.value ?: throw Unauthorized()
    return authService.verify(tokenValue)
}
```

### Cookies

Cookies are extracted from the request and are always nullable.

```kotlin
fun getSession(session: Cookie): SessionData {
    val sessionId = session.value ?: throw Unauthorized()
    return sessionService.get(sessionId)
}
```

### File Uploads

File uploads are supported via `multipart/form-data`.
Each `UploadedFile` represents a single file field.

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

### Raw Body

When you need full control over the request payload, you can access the raw data directly.

Suitable for:

- Webhook signature verification
- Raw JSON signature validation
- Streaming large file processing

```kotlin
// Text body
fun webhook(body: Text): Response {
    val payload = body.value ?: throw BadRequest("Missing request body")
    webhookService.process(payload)
    return Response(status = "received")
}

// Stream body
fun uploadLargeFile(stream: Stream): UploadResult {
    val inputStream = stream.value ?: throw BadRequest("Missing request body")
    return storageService.store(inputStream)
}
```

### Nullable, Required, and Optional Parameters

Colleen distinguishes between **nullable** and **optional** parameters.

These concepts apply consistently across query parameters, JSON bodies, and other bindings.

```kotlin
fun handler(
    q: Query<String?>,                  // missing -> null
    tag: Query<String>,                 // missing -> error (400 Bad Request)
    page: Query<Int> = Query(1),        // missing -> default (1)
)
```

**Rules**

- Nullable parameters (T?)
    - The parameter may be missing.
    - If it is not present in the request, the value is null.

- Non-null parameters (T)
    - The parameter is required.
    - Missing values result in a 400 Bad Request.

- Optional parameters (default values)
    - If a parameter has a default value, it is optional.
    - When missing, the default value is used.

**The same rules apply to JSON request bodies.**

```kotlin
data class SearchRequest(
    val q: String?,      // optional, may be null
    val page: Int = 1,   // optional, default value
    val pageSize: Int,   // required
)
```

```json
{
  "q": "kotlin"
}
```

Result:

- `q` → `"kotlin"`
- `page` → `1` (default value)
- `pageSize` → missing → 400 Bad Request

**Special Rules for Collection Types**

To reduce unnecessary null checks, collection types follow a unified default behavior when missing.

Currently, the supported collection types are:

- `Query<List<T>>`
- `Query<Map<String, String>>`
- `Query<Map<String, List<String>>>`

(Nullable variants, such as `List<T>?`, are also covered.)

When the parameter is **completely absent** from the request:

- `List` → `emptyList()`
- `Map` → `emptyMap()`

This behavior is independent of nullability and will never trigger a `400 Bad Request` due to absence.

```kotlin
fun filter(
    tags: Query<List<String>?>,
    filters: Query<Map<String, List<String>>>
)
````

In the example above:

* If `tags` is not provided → `emptyList()`
* If `filters` is not provided → `emptyMap()`

This design guarantees that collection parameters are always safe to iterate, eliminating unnecessary `null` checks.

### Custom Parameter Extractors

Custom parameter extractors let you encapsulate request parsing logic
and expose it as a strongly typed handler parameter.

This is useful for cross-cutting concerns such as authentication,
request context, or custom headers.

Create custom extractors by implementing `ExtractorFactory`:

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

        // Describe how this extractor appears in the OpenAPI spec.
        // Return null (the default) to exclude it from the spec.
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

    /**
     * Validates that token is present, throws Unauthorized if missing
     */
    fun require(): String {
        return value ?: throw Unauthorized("Bearer token is required")
    }
}
```

Usage:

```kotlin
fun getProfile(token: BearerToken): Map<String, Any> {
    // Require token to be present
    val validToken = token.require()
    // ...
}
```

When using `app.openApi()`, the `Authorization` header will automatically appear in the generated spec.

> Custom extractors are designed to move request-specific logic
> out of handlers and into reusable, testable components.

### Request Binding Errors

The following issues may occur during request binding:

- JSON deserialization failure
- Type conversion errors
- Missing required fields
- Request structure mismatch

By default, Colleen throws `BadRequest` 
along with clear and stable error messages, such as:

- `Missing required field: name`
- `Invalid type for field 'age': expected Int`
- `Invalid type for element 2 of field 'tags': expected String`
- `Unknown field: extra`
- `Malformed request body`
- `Request body does not match expected structure`

If you need a custom response format, you can intercept the underlying exceptions
in a global exception handler and wrap them accordingly.

---

## Request Handling

Colleen provides a concise and expressive request handling model centered around the `Context` object.

### Context Object

The `Context` object is the primary interface for request handling.

It encapsulates:

- HTTP request
- HTTP response
- State
- Service

> NOTE
>
> The example below demonstrates the main surface of the `Context` API.
> In practice, most handlers only use a small subset.

```kotlin
app.get("/example") { ctx ->
    // Request properties
    val method = ctx.method
    val path = ctx.path
    val fullPath = ctx.fullPath
    val pattern = ctx.pattern
    val fullPattern = ctx.fullPattern

    // Path parameters
    val id = ctx.pathParam("id")

    // Query parameters
    val query = ctx.query("q")
    val allQueries = ctx.queries()
    val searchParams = ctx.queries<SearchParams>()

    // Headers
    val authHeader = ctx.header("Authorization")
    val acceptsJson = ctx.accepts("json")

    // Form data
    val name = ctx.form("name")
    val allForms = ctx.forms()
    val formData = ctx.forms<UserForm>()

    // Request body
    val text = ctx.text()
    val user = ctx.json<User>()

    // File uploads
    val file = ctx.file("avatar")

    // Service injection
    val userService = ctx.getService<UserService>()

    // State management
    ctx.setState("userId", 123)
    val userId = ctx.getState<Int>("userId")

    // Response
    ctx.status(200)
        .header("foo", "bar")
        .json(mapOf("message" to "success"))
}
```

**Route Pattern Information**

- `pattern` represents the route pattern matched at the current application level, e.g., `/users/{id}`
- `fullPattern` represents the fully qualified route pattern including mounted prefixes,  e.g., `/api/users/{id}`

> Important
>
> `pattern` and `fullPattern` are populated only after route matching completes.
> When accessed in middleware, they must be read after `next()` returns.

**Request Parsing Semantics**

When using the `Context` JSON, query, or form parsing APIs directly,
Colleen applies consistent semantics:

- If the input is absent or empty, parsing methods return null
- If the input is present but malformed, a BadRequest exception is thrown

This allows handlers to distinguish between optional input and invalid input explicitly.

Colleen provides Java-compatible overloads for all major request parsing APIs.

### Response Generation

Handlers may either:

- Return a value, which Colleen converts into an HTTP response automatically

- Write directly to the response using `ctx`

If a handler explicitly writes to the response (e.g. `ctx.json()`),
the returned value is ignored.

#### Automatic Handler Result Processing

Colleen automatically converts handler return values into appropriate HTTP responses:

```kotlin
// String → text/plain
app.get("/hello") { "Hello, World!" }

// Map/List → application/json
app.get("/data") { mapOf("message" to "success") }

// Custom object → application/json
app.get("/user") { User(id = 1, name = "Alice") }

// Unit → 204 No Content
app.post("/log") {
    logger.info("Logged")
}

// Status(Int) → HTTP status code
app.get("/status") { Status(204) }

// ByteArray → application/octet-stream
app.get("/file") { fileBytes }

// InputStream → streaming response
app.get("/stream") { FileInputStream(file) }
```

#### Structured Result Type

Use `Result<T>` when you want explicit, declarative control over the HTTP response.

```kotlin
app.get("/user") { Result.ok(user) }

app.post("/user") { Result.created(newUser) }

app.delete("/user") { Result.noContent() }

app.get("/custom") { Result.of(418, user).header("X-Custom", "value") }
```

#### JSON Responses

```kotlin
app.get("/users") { ctx ->
    val users = userService.findAll()
    ctx.json(users)
}

// Streaming JSON for large payloads
app.get("/large-dataset") { ctx ->
    val data = dataService.getLargeDataset()
    ctx.json(data, stream = true)
}
```

#### HTML Responses

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

#### Text Responses

```kotlin
app.get("/health") { ctx ->
    ctx.text("OK")
}
```

#### Binary Responses

```kotlin
app.get("/download") { ctx ->
    val bytes = fileService.readFile("report.pdf")
    ctx.bytes(bytes, "application/pdf")
}
```

#### Redirects

```kotlin
app.get("/old-path") { ctx ->
    ctx.redirect("/new-path")
}

// With custom status code
app.get("/moved") { ctx ->
    ctx.redirect("/new-location", 301)
}
```

#### Stream Responses

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
        // Prevent idle timeout
        conn.keepAlive(15)

        // Handle connection close
        conn.onClose { reason ->
            println("Connection closed: $reason")
        }

        // Send events
        repeat(10) { idx ->
            conn.send("message: $idx")
            sleep(1_000)
        }
    }
}
```

> The SSE connection remains open until the handler completes or the client disconnects.

---

## WebSocket

Colleen provides built-in WebSocket support with a callback-based API for bidirectional
real-time communication.

### Basic WebSocket Route

```kotlin
app.ws("/echo") { conn ->
    conn.onTextMessage { msg ->
        conn.send(msg)
    }
    conn.onClose { reason ->
        println("Closed: $reason")
    }
    conn.onError { error ->
        println("Error: ${error.message}")
    }
}
```

### Path Parameters, Query Parameters, and Headers

WebSocket routes support the same path patterns as HTTP routes.
Query parameters from the handshake URL and HTTP headers from the upgrade request
are also accessible.

```kotlin
// ws://localhost:8000/chat/general?name=Alice
// Headers: Authorization: Bearer token123
app.ws("/chat/{room}") { conn ->
    val room = conn.pathParam("room")     // "general"
    val name = conn.query("name")         // "Alice"
    val auth = conn.header("Authorization") // "Bearer token123"
    conn.onTextMessage { msg ->
        conn.send("[$room] $name: $msg")
    }
}
```

### WebSocket Middleware

WebSocket middleware runs during the handshake phase, before the connection is established.
It uses the same `Middleware` signature and can inspect HTTP headers, cookies, and query parameters.

```kotlin
// Global WS middleware
app.wsUse { ctx, next ->
    val token = ctx.header("Authorization")
    if (token != null) next()
    else ctx.status(401).text("Unauthorized")
}

// Prefix-scoped WS middleware
app.wsUse("/admin") { ctx, next ->
    if (isAdmin(ctx)) next()
    else ctx.status(403).text("Forbidden")
}
```

### State and Service Access

WebSocket connections can access application services registered via `provide()` and
request-scoped state set by WS middleware during the handshake phase.

```kotlin
app.provide(ChatService())

app.wsUse { ctx, next ->
    val userId = authenticate(ctx.header("Authorization"))
    ctx.setState("userId", userId)
    next()
}

app.ws("/chat/{room}") { conn ->
    val chatService = conn.getService<ChatService>()
    val userId = conn.getState<Int>("userId")
    val room = conn.pathParam("room")

    conn.onTextMessage { msg ->
        chatService.broadcast(room!!, userId, msg)
    }
}
```

Available methods on `WsConnection`:

| Method | Description |
|---|---|
| `pathParam(key)` | Path parameter from the route pattern |
| `query(key)` | First query parameter value |
| `queryList(key)` | All query parameter values |
| `header(key)` | HTTP header from the upgrade request |
| `headerValues(key)` | All values of a multi-valued header |
| `getService<T>()` | Required service (throws if not found) |
| `getServiceOrNull<T>()` | Optional service (returns null) |
| `getServices<T>()` | All instances of a type |
| `getState<T>(key)` | Required state (throws if not found) |
| `getStateOrNull<T>(key)` | Optional state (returns null) |
| `setState(key, value)` | Set or update state |
| `hasState(key)` | Check if state exists |

> Service resolution walks up the parent app chain, so services registered
> on a parent app are accessible from WebSocket routes in mounted sub-apps.

### WebSocket in Sub-Applications

WebSocket routes in mounted sub-applications work automatically.
The upgrade request is delegated to the sub-app just like normal HTTP requests.

```kotlin
val chatApp = Colleen()
chatApp.ws("/room/{id}") { conn ->
    conn.onMessage { msg -> /* ... */ }
}

app.mount("/chat", chatApp)
// Connect to: ws://localhost:8000/chat/room/42
```

### Controller-Style WebSocket

```kotlin
@Controller("/notifications")
class NotificationController {
    @Ws("/live")
    fun live(conn: WsConnection) {
        conn.onMessage { msg -> /* ... */ }
    }
}
```

> The WebSocket connection stays open until either side closes it. Messages are dispatched via
> `onMessage` callbacks, and `onClose` / `onError` handle lifecycle events.

---

## Validation

Colleen provides a **declarative, composable validation API** designed for
request data validation with **clear null semantics** and **full error aggregation**.

Validation rules are defined using a fluent DSL and are evaluated
**after the validation block completes**.

### Basic Usage

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
            .message("Password must contain an uppercase letter and a number")
    }

    userService.create(user)
}
```

### Validation Semantics

* All fields are **optional by default**
* Use `.required()` to enforce non-null values
* Validation rules are applied **only when a value is present**
* All validation errors are collected and reported together
* Error messages can be customized using .message()

This allows optional fields to be validated **only when provided**,
while required fields are explicitly enforced.

### Custom Error Messages

Colleen validators come with **sensible default error messages**.

When you do want full control over error messages, you can use the `message(...)`
modifier to **override the error message produced by the previous validation step**.

#### `message(String)`

Overrides the error message of the **immediately preceding validation rule**.

- Only takes effect **if that validation failed**
- Has no effect if the validation passed

```kotlin
field("username", user.username)
    .notBlank()
    .message("Username cannot be empty")
```

If notBlank() passes, the message() call is ignored.

#### `message { value -> ... }`

Generates a custom error message dynamically based on the current field value.

```kotlin
field("age", user.age)
    .min(18)
    .message { v -> "Age $v is too young" }
```

### Grouped Validation

Nested validation groups are supported for structured data
and produce hierarchical field names in error output:

```kotlin
expect {
    group("user") {
        field("name", user.name).required()
        field("email", user.email).email()
    }
}
```

Resulting error keys:

```text
user.name
user.email
```

### Error Handling

If validation fails, a single `ValidationException` is thrown containing **all field errors**:

```kotlin
app.onError<ValidationException> { e, ctx ->
    ctx.status(422).json(
        mapOf(
            "error" to "Validation failed",
            "fields" to e.errors
        )
    )
}
```

### Non-Throwing Validation

For cases where exceptions are not desired, use `validate {}`:

```kotlin
val result = validate {
    field("username", user.name).required().minSize(3)
}

if (result.isFailure()) {
    println(result.errors())
}
```

---

## Middleware

Middleware in Colleen provides a Koa-like onion model, while offering a stronger execution guarantee:
once a middleware’s “before” logic runs, its corresponding “after” logic is guaranteed to run as well — even when
exceptions occur.

This makes middleware behavior predictable, composable, and safe for production use.

### Symmetric Execution Guarantee

In many frameworks, an exception thrown downstream may prevent upstream middleware from completing its cleanup logic.

Colleen explicitly guarantees symmetric execution:

- If a middleware executes code before calling `next()`
- Its after logic will always execute
- Even if an exception is thrown in:
    - a downstream middleware
    - or the route handler itself

```kotlin
val middleware: Middleware = { ctx, next ->
    println("Before")

    next()  // Does NOT throw

    println("After")   // Always executes
}
```

This model enables:

- reliable resource cleanup
- accurate metrics collection
- consistent logging and tracing
- safe transactional logic

### Exception Handling Model

Colleen separates exception capture from exception propagation.

**At a glance**:

1. Exceptions thrown during middleware or handler execution are captured, not immediately thrown
2. Captured exceptions propagate upward through the middleware chain
3. Each middleware can:
   - access the exception via `ctx.error`
   - explicitly set `ctx.error.handled = true` to indicate that the exception has been handled
4. After the entire chain completes:
   - if the exception is still unhandled, it is rethrown in a unified manner

This design ensures **after-logic always runs**, without hiding failures.

> Middleware authors do not need `try / finally` blocks to guarantee cleanup.

### Basic Middleware Example

```kotlin
val logger: Middleware = { ctx, next ->
    val start = System.currentTimeMillis()
    println("→ ${ctx.method} ${ctx.path}")

    next()  // Transfer control to next middleware/handler

    val duration = System.currentTimeMillis() - start
    var status = ctx.response.status
    if (ctx.error != null) {
        status = (ctx.error?.cause as? HttpException)?.status ?: 500
    }
    println("← $status (${duration}ms)")
}

app.use(logger)
```

### Global Middleware

Applied to all incoming requests, in registration order:

```kotlin
app.use(Cors())
app.use(RequestLogger())
```

### Prefix-Based Middleware

Executed only when the request path matches the prefix:

```kotlin
app.use("/api", ApiKeyMiddleware())

app.use("/admin", AdminAuthMiddleware())
```

### Conditional Middleware

Middleware can be guarded by custom predicates:

```kotlin
app.use({ ctx -> ctx.accepts("json") }, JsonOnlyMiddleware())

app.use({ ctx -> ctx.header("X-Internal") != null }, InternalMiddleware())
```

### Per-Route Middleware

Middleware bound to a specific HTTP method and path:

```kotlin
app.get("/users")
    .use(CacheMiddleware())
    .use(ValidationMiddleware())
    .handle { }
```

### Middleware Execution Order

Middleware executes in a linear, predictable onion model:

```kotlin
app.use(middleware1)
app.use(middleware2)

app.get("/") { ctx ->
    "response"
}
```

Execution flow:

```
middleware1 → before
middleware2 → before
handler
middleware2 → after
middleware1 → after
```

The execution order is deterministic and symmetric.

### Short-Circuiting (Early Return)

Middleware may choose **not** to call `next()`.

When `next()` is not invoked, the middleware chain stops immediately,
and the current middleware becomes the final handler for the request.

This is useful for:

- health checks
- authentication failures
- rate limiting
- request filtering
- cached responses

For example:

```kotlin
app.use { ctx, next ->
    if (ctx.path == "/ping") {
        ctx.text("pong")
        return@use
    }

    next()
}
```

If the path is `/ping`, the response is sent directly
and no downstream middleware or route handler executes.

Otherwise, control continues normally.

**Execution Guarantee**

Short-circuiting does **not** break the symmetric execution model:

- If `next()` is not called, downstream logic will not run.
- Upstream middleware will still execute their after-logic as expected.

### Writing Parameterized Middleware

Parameterized middleware allows you to **encapsulate reusable behavior** with configuration, instead of hard-coding
logic inside the middleware itself.

In the example below, the required role is provided via the constructor, making the middleware easy to reuse for
different routes or permission levels:

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

### Built-in Middleware

Colleen ships with a set of production-ready middleware:

#### ServeStatic

Serves static files with caching, security checks, and content negotiation.

```kotlin
app.use(ServeStatic(root = "./public", baseUrl = "/static"))
```

#### BasicAuth

HTTP Basic Authentication with constant-time comparison.

```kotlin
app.use(BasicAuth(credentials = mapOf("admin" to "secret123")))
```

#### Cors

CORS support, including credentials and preflight caching.

```kotlin
app.use(Cors.permissive())  // Allow all origins
app.use(Cors.forOrigin("https://example.com", allowCredentials = true))
```

#### RateLimiter

Lock-free token bucket rate limiting with automatic cleanup.

```kotlin
app.use(RateLimiter(capacity = 100, refillRate = 10.0))
```

#### RequestId

Adds a unique request identifier for tracing.

```kotlin
app.use(RequestId())
```

#### RequestLogger

Logs HTTP requests in simple format.

```kotlin
// example output:
// 127.0.0.1 - - [2025-12-07 15:30:45] "GET /api/users" 200 - 2ms
app.use(RequestLogger())
```

#### SecurityHeaders

Applies common HTTP security headers (X-Frame-Options, CSP, HSTS, etc.).

```kotlin
app.use(SecurityHeaders())
```

#### SignedCookie

Cryptographically signed cookies with key rotation.

```kotlin
app.use(SignedCookie(secret = "your-secret-key"))

// Set a signed cookie
app.get("/login") { ctx ->
    val username = ctx.query("username") ?: "guest"
    ctx.signedCookie(name = "session", value = username)
}

// Read a signed cookie
app.get("/profile") { ctx ->
    val username = ctx.getSignedCookie("session")
    // ...
}
```

#### Heartbeat

Simple health check endpoint.

```kotlin
app.use(Heartbeat(endpoint = "/health"))
```

#### NoCache

Disables client and proxy caching.

```kotlin
app.use(NoCache())
```

#### Sunset

API deprecation headers per RFC 8594.

```kotlin
app.use(
    Sunset(
        sunsetAt = Instant.parse("2025-12-31T00:00:00Z"),
        links = listOf("<https://api.example.com/v2>; rel=\"successor-version\"")
    )
)
```

---

## Dependency Injection

Colleen provides a lightweight dependency injection (DI) container for managing application services.

The DI system is designed to be:

- Explicit and predictable
- Free of reflection-based constructor injection
- Fully compatible with mounted sub-applications

### Registering Services

Services are registered on the application instance and stored in its service container.

#### Singleton Services

Singleton services are created lazily and reused for all resolutions:

```kotlin
// Register an existing instance
app.provide(UserService())

// Register a factory (lazy singleton)
app.provide { UserService() }
```

#### Transient Services

Transient services are created every time they are retrieved:

```kotlin
app.provide(singleton = false) { UserService() }
```

#### Qualifier-based Registration

When multiple instances of the same type are needed, distinguish them with a qualifier.
The recommended approach is to use a Kotlin `object` as the qualifier — it is compile-time
safe and IDE refactor-friendly. A plain `String` also works for simpler cases.

```kotlin
object Primary
object Replica

app.provide(qualifier = Primary) { HikariDataSource(primaryConfig) }
app.provide(qualifier = Replica) { HikariDataSource(replicaConfig) }

// Transient with qualifier
app.provide(qualifier = Primary, singleton = false) { HikariDataSource(primaryConfig) }

// Pre-built instance with qualifier
app.provide(HikariDataSource(primaryConfig), qualifier = Primary)

// String qualifier
app.provide(qualifier = "primary") { HikariDataSource(primaryConfig) }
```

### Injecting Services

Services can be retrieved explicitly from the request context or resolved as handler parameters.

#### In Handlers (Explicit)

```kotlin
app.get("/users") { ctx ->
    val userService = ctx.getService<UserService>()
    userService.findAll()
}

// With qualifier
app.get("/report") { ctx ->
  val primary = ctx.getService<DataSource>(Primary)
  val replica = ctx.getService<DataSource>(Replica)
    replica.query("SELECT ...")
}
```

#### As Handler Function Parameters

Handler functions may declare parameters of different categories.

Colleen resolves parameters using the following rules:

- `Context` parameters receive the current request context
- `ParamExtractor` parameters are resolved from the request (path, query, headers, body, etc.)
- All other parameter types are treated as services and resolved from the dependency injection container

```kotlin
fun getUsers(userService: UserService): List<User> {
    return userService.findAll()
}

app.get("/users", ::getUsers)
```

To inject a qualified service as a handler parameter, annotate it with `@Qualifier` and pass
the qualifier's name. When the qualifier was registered as a Kotlin `object`, use its class
simple name (case-insensitive):

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

If the qualifier was registered as a plain `String`, use that string directly:

```kotlin
app.provide(qualifier = "primary") { HikariDataSource(primaryConfig) }

fun getReport(@Qualifier("primary") ds: DataSource): String {
    return ds.query("SELECT ...")
}
```

#### In Controllers

Controllers are regular Kotlin or Java classes whose dependencies are supplied explicitly at construction time.

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
    fun get(id: Path<Int>): User = userService.findById(id.value)
}

// Register controller
app.addController(UserController(UserService()))
```

Controller handler methods follow the same parameter resolution rules as handler functions:
non-`Context` and non-`ParamExtractor` parameters are resolved as services, and `@Qualifier`
works the same way.

### Service Resolution

Services are resolved using a hierarchical lookup strategy:

1. Current application's service container
2. Parent application's container
3. Ancestor containers, recursively

```kotlin
val mainApp = Colleen()
mainApp.provide { DatabaseService() }

val apiApp = Colleen()
apiApp.provide { UserService() }

mainApp.mount("/api", apiApp)
```

Within `apiApp` handlers:

- `UserService` is resolved from `apiApp`
- `DatabaseService` is resolved from `mainApp`

This enables modular applications with shared infrastructure services and isolated domain logic.

---

## Error Handling

Colleen provides a structured, type-based error handling model built around exceptions and a predictable handler
resolution strategy.

Errors are represented as exceptions, propagated through the middleware chain, and finally converted into HTTP responses
by either user-defined or default handlers.

### Throwing HTTP Errors

Handlers and middleware may throw HTTP exceptions directly to short-circuit request processing:

```kotlin
throw BadRequest("Invalid input")
throw Unauthorized("Authentication required")
throw Forbidden("Access denied")
throw NotFound("User not found")
throw Conflict("Email already exists")
throw ValidationException(errors)
throw TooManyRequests("Rate limit exceeded")
// Custom status or code
throw HttpException(500, "Internal error", cause)
```

Each `HttpException` carries:

- an HTTP status code
- a human-readable message
- a stable, machine-readable error code

Non-HTTP exceptions are treated as `500 Internal Server Error` unless explicitly handled.

### Registering Error Handlers

Applications can register error handlers by exception type:

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

Handlers are matched **by type**, not by status code.

### Handler Resolution Order

When an exception occurs, Colleen walks up the exception class hierarchy and selects the most specific matching handler:

```kotlin
class CustomException : IllegalArgumentException()

app.onError<CustomException> { /* matched first */ }
app.onError<IllegalArgumentException> { /* fallback */ }
app.onError<Exception> { /* last resort */ }
```

### Default Error Handling

If no custom handler matches, Colleen applies a default strategy:

- `HttpException`
    - Uses the exception’s status, message, and code
- Other exceptions
    - Return `500 Internal Server Error`
- Server errors (`5xx`) are logged automatically

The default handler performs content negotiation.

- `Accept: application/json` → JSON error payload
- `Accept: text/html` → minimal HTML error page

**JSON response (example):**

```json
{
  "status": 404,
  "code": "NOT_FOUND",
  "message": "User was not found"
}
```

**HTML response (example):**

```html
<h1>404 Not Found</h1>
<p>User was not found</p>
```

### Error State in Middleware

When an exception is thrown during request processing, it is captured in the request context:

```kotlin
val errorState = ctx.error
if (errorState != null) {
    val cause = errorState.cause
    val handled = errorState.handled
}

// Mark error as handled to prevent re-throwing
ctx.error?.handled = true
```

Handled errors are not rethrown at the end of the middleware chain,
enabling advanced use cases such as error recovery, transformation, or logging middleware.

---

## Events System

Colleen exposes a **synchronous, in-process event system** for observing and extending framework
lifecycle and request execution behavior.

Events are designed as **observation hooks**, not a secondary control flow.

Design Principles

- Synchronous & ordered
    - Listeners run immediately, in registration order.
- Hierarchical & bubbling
    - Events emitted by a sub-application bubble to its parent by default.
- Safe by default
    - Listener exceptions are caught and logged and do not affect request handling.
- Explicit scope
    - Some events bubble (e.g. routing, execution), others are local-only (e.g. request lifecycle).

### Subscribing to Events

```kotlin
app.on<Event.RequestReceived> { 
    logger.info("→ ${it.request.method} ${it.request.path}")
}
```

Listeners are type-safe and receive the concrete event instance.

### Event Type

#### Server Lifecycle Events

* `Event.ServerStarting` / `Event.ServerStarted`
* `Event.ServerStopping` / `Event.ServerStopped`

Typical use cases:
- Initializing global resources (e.g., database connections, caches, thread pools)
- Registering background tasks
- Releasing resources gracefully during shutdown

#### Request Lifecycle Events

Colleen provides clearly defined lifecycle hooks during request processing:

- `Event.RequestReceived`
  - The request has just been received.
  - Routing and the middleware chain have **not** yet been executed.
  - At this stage, the request object can still be modified.
- `Event.ResponseReady`
  - The handler has completed execution and the response has been generated.
  - But it has not yet been sent.
- `Event.ResponseSent`
  - The response has been fully sent.
  - At this point, metrics such as bytes sent can be accessed.

**Example: Access logging**

```kotlin
app.on<Event.ResponseSent> { event ->
    val ctx = event.ctx
    logger.info(
        "${ctx.method} ${ctx.path} ${ctx.response.status} " + 
        "${event.bytesSent} bytes in ${event.total}"
    )
}
```

#### Execution Events

Execution events describe what is currently running and how long it took:

- `Event.MiddlewareExecuting` / `Event.MiddlewareExecuted`
- `Event.HandlerExecuting` / `Event.HandlerExecuted`
- `Event.SubAppExecuting` / `Event.SubAppExecuted`

These events are useful for:

- profiling
- tracing
- fine-grained metrics

#### Exception Events

`Event.ExceptionCaught` / `Event.ExceptionHandled`

### Event Bubbling

Events emitted by a mounted sub-application automatically propagate upward.

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

mainApp.on<Event.HandlerExecuted> {
    // Receives event from both mainApp and apiApp
}

mainApp.mount("/api", apiApp)
```

**Stopping propagation**

```kotlin
app.on<Event.HandlerExecuted> { event ->
    event.stopPropagation()
}
```

### Event Source

Every event carries a `source` reference indicating **which application emitted it**:

```kotlin
app.on<Event.RouteRegistered> { event ->
    logger.info("Emitted by app mounted at ${event.source.mountPath}")
}
```

### Typical Use Case: Framework Extension

The event system is commonly used to implement **opt-in framework extensions** without touching the request pipeline.

Example: HTTP method override (simplified):

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

This pattern keeps extensions:

- isolated
- composable
- transparent to routing and middleware

### When to Use Events

Use the event system when you need to:

- observe or record framework behavior
- attach cross-cutting concerns
- extend the framework without affecting control flow

For request control and business logic, prefer middleware and handlers.

---

## Sub-Applications

Sub-applications let you compose large applications from smaller, isolated modules.

Each sub-app has its own routes, middleware, services, and configuration,
while still participating in a shared request lifecycle.

They are ideal for:

- API versioning
- Feature-based modularization
- Admin panels or internal tools
- Large applications with clear boundaries

### Mounting a Sub-Application

A sub-app is mounted at a specific path on a parent application.

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

// Configure sub-app
apiApp.config {
    json {
        pretty = true
    }
}

// Register sub-app services
apiApp.provide { UserService() }

// Define sub-app routes
apiApp.get("/users") { ctx ->
    val userService = ctx.getService<UserService>()
    userService.findAll()
}

// Mount sub-app
mainApp.mount("/api", apiApp)

// Request: GET /api/users
// Handled by apiApp
```

Once mounted, all requests under `/api` are delegated to `apiApp`.

### Path Rewriting

When a request is routed into a sub-app, Colleen rewrites the request path
so that the sub-app operates as if it were a root application.

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

This ensures:

- Route definitions remain clean and independent
- The full routing context is still available when needed

### Context Hierarchy

Sub-applications create **child request contexts** that inherit state from their parents.

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

// Parent app middleware
mainApp.use { ctx, next ->
    ctx.setState("requestId", UUID.randomUUID().toString())
    next()
}

// Sub-app handler
apiApp.get("/users") { ctx ->
    val requestId = ctx.getState<String>("requestId")  // ✅ Available
}

mainApp.mount("/api", apiApp)
```

State flows downward through the application tree, enabling cross-cutting concerns such as:

- Request IDs
- Authentication context
- Tracing information

### Service Resolution

Services are resolved hierarchically, starting from the sub-app and falling back to its parents.

```kotlin
val mainApp = Colleen()
val apiApp = Colleen()

// Shared service
mainApp.provide { DatabaseService() }

// Sub-app specific service
apiApp.provide { UserService() }

mainApp.mount("/api", apiApp)

// In apiApp handlers:
apiApp.get("/users") { ctx ->
    val userService = ctx.getService<UserService>()      // ✅ From apiApp
    val dbService = ctx.getService<DatabaseService>()    // ✅ From mainApp
}
```

This allows:

- Local overrides in sub-apps
- Shared infrastructure services at the root
- Clear ownership boundaries

### Exception Propagation

By default, exceptions thrown inside a sub-app propagate to the parent application.

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

This makes it easy to define **global error handling** at the root.

**Disable propagation:**

```kotlin
apiApp.config {
    propagateExceptions = false
}
```

When disabled, exceptions are handled **only by the sub-app’s error handlers**.

### Mount Path Information

Each application is aware of its position in the application tree.

```kotlin
val mainApp = Colleen()
val subApp = Colleen()
val nestedApp = Colleen()

mainApp.mount("/api", subApp)
subApp.mount("/v1", nestedApp)

// In nestedApp:
nestedApp.mountPath        // "/v1"
nestedApp.fullMountPath    // "/api/v1"
nestedApp.parent           // subApp
```

### Multiple Sub-Applications

A single application can mount multiple independent sub-apps.

```kotlin
val mainApp = Colleen()

val apiApp = Colleen()
val adminApp = Colleen()
val docsApp = Colleen()

mainApp.mount("/api", apiApp)
mainApp.mount("/admin", adminApp)
mainApp.mount("/docs", docsApp)
```

### Multiple Sub-Apps on the Same Path

Multiple sub-applications can be mounted on the same path.

They are evaluated in mount order — the **first matching route wins**.

```kotlin
app.mount("/api", prodApi)
app.mount("/api", debugApi)
```

This is useful for layering concerns (e.g. auth, rate limiting) or extending an API
without modifying existing sub-apps.

Mount order is significant.

### Restrictions

**An application can only be mounted before it starts.**

```kotlin
val subApp = Colleen()
subApp.listen(9000)  // Start server

val mainApp = Colleen()
mainApp.mount("/api", subApp)  // ❌ Error: app is already running
```

**An application can only be mounted once.**

```kotlin
val mainApp = Colleen()
val subApp = Colleen()

mainApp.mount("/api", subApp)
mainApp.mount("/v2", subApp)  // ❌ Error: app already mounted
```

---

## OpenAPI Documentation

Colleen can generate OpenAPI specs directly from registered routes.

### 1) `openApi` usage

```kotlin
import io.github.cymoo.colleen.*

fun main() {
    val app = Colleen()

    app.openApi(
        title = "Todo API",
        version = "1.0.0",
        description = "OpenAPI example"
    )

    app.listen(8000)
    // Spec: http://localhost:8000/openapi.json
    // Docs: http://localhost:8000/docs
}
```

Common options:

- `path`: OpenAPI JSON endpoint (default `/openapi.json`)
- `uiPath`: docs UI endpoint (default `/docs`, set `null` to disable UI page)
- `filter`: include/exclude operations by `(path, method) -> Boolean`
- `uiHtml`: customize docs UI HTML
  - default: Swagger UI
  - ReDoc example: `app.openApi(uiHtml = ::redocHtml)`

### 2) Automatic inference from function signatures

For function and controller handlers, Colleen infers OpenAPI information directly from signatures:

- Parameters:
  - `Path<T>` → path parameter
  - `Query<T>` → query parameter
  - `Header<T>` / `Cookie<T>` → header/cookie parameter
  - `Json<T>` / `Form<T>` / `Text` / `Stream` / `UploadedFile` → requestBody
- Required/nullable:
  - Kotlin nullability and default values are used to infer required fields
  - `OptionalBool` in annotations can override inferred behavior
- Responses:
  - return type becomes the `200` schema automatically
  - `Result<T>` is documented as `T`
  - `Unit` / `Void` / `Response` are treated as no response body
- Handler kind:
  - function / method handlers produce rich metadata
  - lambda handlers only contribute minimal operation metadata

### 3) Common annotations and usage

All annotations are optional.

The OpenAPI extension works out of the box with sensible defaults; 
add annotations only when you want to enrich or customize the generated spec.

```kotlin
@Tags("todos")
class TodoController {

    @Summary("Get one todo")
    @Description("Returns a todo by id")
    @ParamDesc(name = "id", description = "Todo ID")
    @ResponseDesc(404, "Todo not found")
    fun getOne(id: Path<Long>): Todo = TODO()
}

data class Todo(
    @Schema(description = "Todo id", example = "1")
    val id: Long,
    @Schema(description = "Task title", example = "Buy milk")
    val title: String,
    @Schema(hidden = true)
    val internalVersion: Int = 0,
)

@Hidden
fun internalHealth(): String = "ok"
```

Most-used annotations:

- `@Summary`, `@Description`: operation summary/description
- `@Tags`: grouping in Swagger/ReDoc UI (supports class + method merge)
- `@ParamDesc`: parameter description and optional required override
- `@ResponseDesc`: response description by HTTP status code
- `@Schema`: field-level schema metadata (`description`, `example`, `name`, `hidden`, `type`, `format`, `required`)
- `@Hidden`: exclude a function or whole controller from OpenAPI

### 4) Practical notes

- OpenAPI includes routes from mounted sub-applications.
- Use `@Hidden` for annotation-based exclusion, and `filter` for programmatic exclusion.
- See complete runnable example: `examples/openapi/src/main/kotlin/Main.kt`.
- If you need a custom docs page style, pass your own `uiHtml(specPath)` renderer.

---

## Testing

Colleen provides a built-in `TestClient` for testing applications
in-process, without starting an HTTP server.

Unlike external HTTP clients, TestClient executes requests through the
same pipeline used in production: routing, middleware, validation,
dependency injection, and error handling.

This makes tests fast, deterministic, and expressive.

### Executable Example

The example below is a fully runnable program demonstrating common
testing scenarios, including authentication, validation, query
parameters, and file uploads.

```kotlin
// import io.github.cymoo.colleen.*

// ---------------------------------------------------------------------
// Application setup
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
        val avatar = ctx.file("avatar") ?: throw BadRequest("missing file")
        mapOf(
            "name" to avatar.name,
            "size" to avatar.size
        )
    }

    return app
}

// ---------------------------------------------------------------------
// Executable test example
// ---------------------------------------------------------------------

fun runTestClientExample() {
    val client = TestClient(createApp())

    // JSON request + authentication
    val response = client.post("/users")
        .header("Authorization", "Bearer valid-token")
        .json(mapOf("email" to "alice@example.com"))
        .send()

    response.assertStatus(201)

    val user = response.json<User>()!!
    check(user.id == 1)
    check(user.email == "alice@example.com")

    // Multipart file upload
    client.post("/users/upload")
        .header("Authorization", "Bearer valid-token")
        .file("avatar", "avatar.png", byteArrayOf(1, 2, 3), "image/png")
        .send()
        .assertStatus(200)

    // Query parameters (route not defined)
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

### When to Use TestClient

TestClient is suitable for:

- **Handler-level testing**
    - Validate request parsing, validation rules, and response shapes.
- **Middleware and security testing**
    - Verify authentication, authorization, and cross-cutting behavior.
- **Integration-style testing**
    - Exercise multiple layers without starting an actual server.

Because tests run in-process, they are typically orders of magnitude
faster than socket-based HTTP tests.

---

## Java Support

Although Colleen is written in Kotlin, it is designed to be a **first-class Java framework** as well.

All core features—routing, middleware, events, DI, and parameter extraction—are fully usable from Java via explicit,
Java-friendly APIs.

Kotlin and Java API differ mainly in **type information, reflection, and method references**.

Colleen handles most of this automatically, but a few explicit APIs are required on the Java side.

### 1. Explicit Runtime Types

Kotlin uses reified generics. Java does not.

Java APIs therefore require an explicit `Class<T>`.

**Kotlin**:

```kotlin
app.on<Event.RequestReceived> { /* ... */ }
app.onError<BadRequest> { e, ctx -> /* ... */ }
app.provide { UserService() }

val service = ctx.getService<UserService>()
val user = ctx.json<User>()
```

**Java**

```java
app.on(Event.RequestReceived.class, event -> { /* ... */ });
app.onError(BadRequest.class, (e, ctx) -> { /* ... */ });
app.provide(UserService.class, new UserService());

UserService service = ctx.getService(UserService.class);
User user = ctx.json(User.class);
```

### 2. Explicit Parameter Names

Java does not preserve method parameter names by default.

Handler parameters must therefore be annotated with `@Param`.

**Kotlin (no annotation needed)**:

```kotlin
fun getUser(id: Path<Int>) = userService.findById(id.value)

app.get("/users/{id}", ::getUser)
```

**Java (annotation required)**:

```java
public User getUser(@Param("id") Path<Integer> id) {
    return userService.findById(id.value);
}

app.get("/users/{id}", ch(this::getUser));
```

> `@Param` defines the logical parameter name used for path, query, form or header extraction.

### 3. Wrapping Method References (`ch()`)

Java method references cannot be inspected directly like Kotlin functions.

Use `ch()` to convert them into `Handler`:

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

Under the hood, `ch()`:

- Resolves the actual method using JVM `SerializedLambda`
- Extracts the captured instance (if any)
- Applies the same parameter extraction rules as Kotlin handlers

You only need to remember **one thing**:

> Any Java method reference used as a route handler must be wrapped with `ch()`.

### 4.Generic JSON Parsing with `TypeRef`

Java erases generic type information at runtime.

Use `TypeRef` when parsing generic JSON payloads.

```java
List<User> users = ctx.json(TypeRef.listOf(User.class));

Map<String, User> userMap = ctx.json(TypeRef.mapOf(String.class, User.class));
```

For deeply nested generics:

```java
var data = ctx.json(new TypeRef<Map<String, List<User>>>() {});
```

For non-generic types, prefer `Class<T>`:

```java
User user = ctx.json(User.class);
```

---

## Configuration

The configuration below shows the default settings.

### Server Configuration

```kotlin
app.config {
    server {
        host = "127.0.0.1"
        port = 8000

        // Threading
        useVirtualThreads = true
        maxThreads = <cpuCount> * 8       // Used only when virtual threads are disabled

        // Concurrency
        maxConcurrentRequests = 0          // 0 = unlimited (set an explicit cap in production)

        // Request limits
        maxRequestSize = 30 * 1024 * 1024  // 30MB
        maxFileSize = 10 * 1024 * 1024     // 10MB per file
        fileSizeThreshold = 256 * 1024     // 256KB before buffering to disk

        // Timeouts (milliseconds)
        shutdownTimeout = 30_000
        idleTimeout = 30_000
    }
}
```

### WebSocket Configuration

```kotlin
app.config {
    ws {
        idleTimeoutMs = 300_000           // 5 min, set to 0 for no timeout
        maxMessageSizeBytes = 64 * 1024   // 64 KB
        pingIntervalMs = 30_000           // 30 seconds, set to 0 to disable ping/pong heartbeat
        pingTimeoutMs = 10_000            // 10 seconds, only effective when pingIntervalMs > 0
        maxConnections = 0                // 0 = unlimited (set an explicit cap in production)
    }
}
```

### JSON Configuration

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

#### Custom JSON Mapper

Replace the default Jackson-based mapper:

```kotlin
app.config {
    jsonMapper(MyCustomJsonMapper())
}
```

Your mapper must implement `JsonMapper`.

### Application Configuration

```kotlin
app.config {
    // If true, exceptions from mounted sub-apps bubble to parent
    propagateExceptions = true
}
```

---

## Production Notes

### Virtual Threads

Colleen enables virtual threads by default (Java 21+), and JDK 25 is recommended for production/high-concurrency workloads.

Virtual threads greatly improve scalability for IO-bound workloads
by allowing a large number of concurrent tasks with minimal thread overhead.

However, certain operations may cause *pinning*,
where a virtual thread temporarily occupies its carrier (platform) thread
and prevents it from being reused. Historically, this could occur when:

- Entering `synchronized` blocks (prior to recent JVM improvements)
- Executing native or JNI calls

When pinning happens, the carrier thread remains tied to the virtual thread
during the blocking period,which can reduce effective parallelism under high load.

Recent JDK releases (especially Java 25) have significantly improved virtual thread implementation.
In particular, common cases such as `synchronized` blocks no longer show the same pinning behavior seen on older runtimes.

If your application performs heavy CPU-bound work
or relies on libraries with blocking native calls, benchmark carefully.
In some workloads, platform threads may provide more predictable latency characteristics.

For high-concurrency production environments, use JDK 25 whenever possible.
If you must stay on JDK 21, run stress tests for your own workload before production rollout.
For reproducible benchmark profiles (including high-concurrency request plans), see:
`examples/benchmark-api/README.md`.

### JSON Streaming

Large JSON responses can be streamed instead of fully buffered:

```kotlin
app.get("/large") { ctx ->
    ctx.json(getLargeData(), stream = true)
}
```

Streaming reduces memory usage and improves latency for large payloads.

Keep in mind:

- Exceptions thrown during streaming may result in partially written responses.
- Streaming is most beneficial for large datasets — avoid unnecessary use for small payloads.

### Middleware Cost

Each middleware adds an extra step in the request pipeline.

Prefer prefix-based middleware when possible:

```kotlin
// Runs for every request
app.use(LoggingMiddleware())

// Runs only for /api/*
app.use("/api", LoggingMiddleware())
```

Avoid placing heavy logic in global middleware unless required.

### Request & File Limits

Set appropriate limits for production environments:

```kotlin
app.config {
    server {
        maxRequestSize = 50 * 1024 * 1024
        maxFileSize = 20 * 1024 * 1024
        maxConcurrentRequests = 500
    }
}
```

Overly permissive limits may expose the server to resource exhaustion.

### Structured Logging

Colleen emits lifecycle events that can be used for structured logging or other metrics:

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

This approach keeps logging outside middleware and aligned with actual request completion.

---

## License

MIT
