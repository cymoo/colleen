# Copilot Instructions for Colleen

Colleen is a lightweight, type-safe web framework for Kotlin and Java, built on Undertow. It emphasizes explicit configuration, composable middleware, automatic parameter extraction, and synchronous request handling on virtual threads (Java 21+).

## Build and Test

Requires Java 21+.

```shell
# Run all tests (framework module)
mvn -pl colleen test

# Run a single test class
mvn -pl colleen test -Dtest=RouterTest

# Run a single test method
mvn -pl colleen test -Dtest="ColleenTest#fullMountPath should return empty for root app"

# Build without running tests
mvn -pl colleen -DskipTests package
```

## Project Layout

Maven multi-module project:
- `colleen/` — framework core (source, tests, the only publishable artifact)
- `examples/` — standalone example apps demonstrating features (todo-app, auth-app, websocket, etc.)

Source roots:
- `colleen/src/main/kotlin/io/github/cymoo/colleen/` — framework source
- `colleen/src/test/kotlin/io/github/cymoo/colleen/` — tests (JUnit 5)

## Architecture

### Request Flow

UndertowServer → `Colleen.handleRequest` → `Router.handleRequest` → middleware chain → route handler → `Response.materialize` → UndertowServer writes response.

Key source files:
- `Colleen.kt` — app class; coordinates routing, middleware, services, lifecycle
- `Router.kt` — route matching, middleware orchestration, dispatch
- `Context.kt` — per-request hub: request/response access, path params, service injection, request-scoped state
- `Response.kt` — response builder with materialization pipeline (`ResponseBody` → `RawResponseBody`)
- `Request.kt` — HTTP request wrapper (one-time readable body stream, lazy parsing)
- `server/undertow/UndertowServer.kt` — Undertow adapter, virtual thread dispatch, graceful shutdown

### Three Handler Styles

**Lambda** — minimal, receives `Context`, no auto-extraction:
```kotlin
app.get("/") { ctx -> "hello" }
```

**Function reference** — automatic parameter extraction from function signature:
```kotlin
fun getUser(id: Path<Int>, service: UserService): User = service.find(id.value)
app.get("/users/{id}", ::getUser)
```

**Controller** — annotated class with grouped routes and middleware:
```kotlin
@Controller("/users")
class UserController {
    @Get("/{id}")
    fun get(id: Path<Int>): User { ... }
}
app.addController(UserController())
```

Function references and controller methods use reflection at **route registration time** (not per-request) to build extraction lambdas. This is handled by `Extractor.kt` and `Scanner.kt`.

### Parameter Extraction Types

Handler parameters are automatically resolved from HTTP requests:

| Type | Source | Notes |
|------|--------|-------|
| `Path<T>` | URL path segment | Generic; `Path<Int>`, `Path<UUID>`, etc. |
| `Query<T>` | Query string | Generic; `Query<String?>` for optional |
| `Form<T>` | Form field | Generic; `Form<String>` |
| `Json<T>` | Request body JSON | Generic; `Json<CreateUser>` |
| `Header` | HTTP header | Not generic; wraps `String?` |
| `Cookie` | Cookie value | Not generic; wraps `String?` |
| `Text` | Request body as string | Not generic; wraps `String?` |
| `Stream` | Request body as InputStream | Not generic; wraps `InputStream?` |
| `UploadedFile` | Uploaded file | Not generic; wraps `FilePart?` |
| `Body` | Raw request body bytes | Direct type |
| `Context` | Request context | Injected directly |
| Any other type | Service container | Auto-resolved via DI |

Nullability controls optionality for generic extractors: `Query<String>` is required, `Query<String?>` is optional.

### Handler Return Value Mapping

Handled by `Response.applyHandlerResult`:

| Return type | Response |
|-------------|----------|
| `Unit` | 204 No Content |
| `String` | text/plain |
| `ByteArray` | application/octet-stream |
| `InputStream` | application/octet-stream |
| `Map`, `List` | JSON |
| `Result<T>` | Structured response with status + headers + body |
| `Int` / `Status` | HTTP status code only |
| Any other object | JSON (fallback) |
| `null` | **Forbidden** — throws at runtime; use `Unit` for no-content |

### Middleware

Middleware signature: `(Context, Next) -> Unit`. Colleen provides a **Koa-like onion model with a symmetric execution guarantee**: code before `next()` runs in the request phase; code after `next()` always runs in the response phase — even if an exception was thrown downstream. Exceptions are captured by the framework (not thrown through `next()`), stored in `ctx.error`, and re-raised after the full chain unwinds. Middleware authors **do not need `try-finally`** for cleanup.

```kotlin
val logger: Middleware = { ctx, next ->
    val start = System.currentTimeMillis()

    next()  // never throws — exceptions captured in ctx.error

    val duration = System.currentTimeMillis() - start
    val status = if (ctx.error != null)
        (ctx.error?.cause as? HttpException)?.status ?: 500
    else
        ctx.response.status
    println("← $status (${duration}ms)")
}
app.use(logger)
```

Execution is deterministic and symmetric:
```
middleware1 → before
middleware2 → before
  handler
middleware2 → after   ← always runs
middleware1 → after   ← always runs
```

To short-circuit (e.g., auth failure), return without calling `next()` — the rest of the chain is skipped.

Built-in middleware in `middleware/` package: `Cors`, `BasicAuth`, `RateLimiter`, `RequestId`, `SecurityHeaders`, `SignedCookie`, `NoCache`, `RequestLogger`, `ServeStatic`, `HeartBeat`, `Sunset`.

### Dependency Injection

Explicit service registration via `ServiceContainer`. No classpath scanning.

```kotlin
app.provide(UserService())                          // singleton by type
app.provide(primaryDs, "primary")                   // with string qualifier
app.provide<Database> { Database.connect(config) }  // factory (singleton by default)
```

Services are resolved by type from handler function parameters. Qualifiers can be strings or object singletons.

### Error Handling

All HTTP errors extend `HttpException(status, message, code)`. Typed subclasses for common statuses: `BadRequest`, `NotFound`, `Unauthorized`, `Forbidden`, `TooManyRequests`, etc.

- `ValidationException` carries structured `errors: Map<String, List<String>>`
- `BindingFailure` is a sealed class for data binding issues (missing field, type mismatch, malformed JSON, etc.)
- Custom error handlers: `app.onError<ExceptionType> { e, ctx -> ... }`
- Default error response: HTML if client accepts it, otherwise JSON `{ status, code, message }`

### Sub-Applications

```kotlin
val api = Colleen()
app.mount("/api", api)
```

Sub-apps have independent middleware, error handlers, and services. Context chains via `parentContext`. Service resolution walks up the parent chain. Exceptions propagate to parent by default (`config.propagateExceptions = true`).

### WebSocket

```kotlin
app.ws("/chat/{room}") { conn ->
    conn.onMessage { msg -> conn.send("echo: $msg") }
    conn.onBinary { data -> /* handle binary */ }
    conn.onClose { reason -> /* cleanup */ }
}
```

WS routes support middleware (`wsUse`), path params, state management, and controller-style handlers via `@Ws` and `@WsUse` annotations. Configuration in `WsConfig` (idle timeout, max message size, ping interval, max connections).

### SSE

```kotlin
app.get("/events") { ctx ->
    ctx.sse { conn ->
        conn.send("hello")
        conn.onClose { /* cleanup */ }
    }
}
```

### Testing

`TestClient` runs requests in-process — no HTTP server needed:

```kotlin
val client = TestClient(app)
val res = client.get("/users/1").header("Authorization", "Bearer token").send()
assertEquals(200, res.status)
```

### Events

Lifecycle hooks via typed event bus: `ServerStarting`, `ServerStarted`, `ServerStopping`, `ServerStopped`, `RequestReceived`, `ResponseReady`, `ResponseSent`.

### OpenAPI

Enable with `app.openApi()`. Annotate handlers with `@Summary`, `@Description`, `@Tags`, `@ParamDesc`, `@ResponseDesc`. Function-reference and controller handlers produce rich metadata; lambda handlers produce minimal docs.

## Key Conventions

- **Explicit over implicit** — no classpath scanning, no auto-wiring, no magic
- **Synchronous on virtual threads** — handlers run synchronously on Java 21 virtual threads
- **One-time body reads** — request body stream consumed on first access; parsed results are cached
- **Reflection at registration time** — parameter extraction lambdas built once when routes are registered
- **Null returns forbidden** — returning null from a handler throws at runtime; use `Unit` for no-content
- **Path params use `{id}` syntax** — `:id` is also supported but `{id}` is preferred; wildcards as `{path...}`
- **Route priority** — static > complex > param > wildcard segments
- **Java handlers need `@Param`** — Kotlin preserves parameter names; Java requires `@Param("name")` or the `-parameters` compiler flag
- **Java function references use `ch()`** — wrap Java method references with `ch(MyClass::myMethod)` from `JavaLambdaCompat`

## Best Practices

- **Prefer function references and controllers over lambdas** for non-trivial handlers — they get automatic parameter extraction, richer OpenAPI docs, and are easier to test in isolation.
- **Register services before adding routes** — `provide()` calls must precede any `get()`/`post()`/`addController()` that depend on them.
- **Body is consumed once** — do not read the request body (JSON, form, text, stream) more than once per request. Parsed results are cached, but `Stream` and raw `Body` are not.
- **Don't return `null`** — returning `null` from a handler throws at runtime. Return `Unit` for 204 No Content.
- **Set headers before `next()` for guaranteed inclusion on early-return paths** — if a middleware short-circuits (returns without calling `next()`), any code after `next()` never runs; set headers or state beforehand if needed in both paths.
- **Keep middleware stateless or thread-safe** — a single middleware instance is shared across all virtual-thread requests concurrently. Avoid mutable instance fields unless they are thread-safe.
- **Use `TestClient` for handler tests** — it runs in-process without a real server and exercises the full middleware chain.
- **Use sub-apps for modular boundaries** — mount independent `Colleen()` instances under a path prefix to isolate middleware, error handlers, and services for distinct API sections.
- **Middleware order is execution order** — `app.use()` calls are applied in registration order; earlier middleware wraps later ones and the handler.

## Git Commit Messages

After completing a task, commit the changes once the code is working and all relevant tests pass — unless the user explicitly asks not to commit.

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <short summary>

[optional body — explain *what* and *why*, not *how*]

[optional footer(s)]
```

**Types**: `feat`, `fix`, `refactor`, `perf`, `style`, `test`, `docs`, `chore`, `ci`, `build`

**Rules**:
- Subject line: imperative mood, no period, ≤ 72 chars
- Scope: the module/layer being changed (optional but encouraged)
- Body: wrap at 72 chars; use bullet points for multiple changes
- Breaking changes: add `BREAKING CHANGE:` footer or append `!` after type

**Examples**:

```
feat(rooms): add room archiving support
```

```
fix(auth): prevent token reuse after logout

Sessions were not invalidated in Redis on explicit logout, allowing
reuse of the old Bearer token until the 7-day TTL expired.
```

```
refactor(middleware): restructure timing and cleanup patterns

- Clarify that post-next() code is skipped on exception
- Add try-finally guidance for guaranteed cleanup
```

```
feat(api)!: require Authorization header for all /api routes

BREAKING CHANGE: previously some read-only endpoints were unauthenticated.
```
