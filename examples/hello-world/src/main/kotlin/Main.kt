/**
 * Colleen Framework - Comprehensive Example
 *
 * This file demonstrates all core features of the Colleen web framework:
 *   - Routing (lambda, function, controller styles)
 *   - Parameter extraction (path, query, form, JSON, header, cookie, file)
 *   - Data validation
 *   - Middleware (global, prefix-based, route-level)
 *   - Dependency injection
 *   - Error handling
 *   - Event system
 *   - Sub-applications
 *   - SSE (Server-Sent Events)
 *   - Testing with TestClient
 */

import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.Post as POST
import io.github.cymoo.colleen.middleware.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.reflect.Parameter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

val logger: Logger = LoggerFactory.getLogger("Main")

// =============================================================================
// Domain Models
// =============================================================================

data class User(
    val id: Int,
    val name: String,
    val email: String,
    val role: String = "user",
)

data class Post(
    val id: Int,
    val authorId: Int,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
)

data class Comment(
    val id: Int,
    val postId: Int,
    val authorId: Int,
    val body: String,
)

// Request / response DTOs
data class CreateUserRequest(val name: String, val email: String, val password: String)
data class UpdateUserRequest(val name: String?, val email: String?)
data class CreatePostRequest(val title: String, val content: String, val tags: List<String> = emptyList())
data class CreateCommentRequest(val body: String)
data class SearchParams(val q: String, val limit: Int = 10, val offset: Int = 0)
data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val token: String, val userId: Int)
data class UploadResult(val filename: String, val size: Long, val contentType: String?)

// =============================================================================
// Services
// =============================================================================

class UserService {
    private val store = ConcurrentHashMap<Int, User>()
    private val nextId = AtomicInteger(1)

    init {
        // Seed data
        create("Alice", "alice@example.com")
        create("Bob", "bob@example.com")
        val admin = create("Admin", "admin@example.com")
        store[admin.id] = admin.copy(role = "admin")
    }

    fun findAll(): List<User> = store.values.toList()

    fun findById(id: Int): User = store[id] ?: throw NotFound("User $id not found")

    fun findByEmail(email: String): User? = store.values.find { it.email == email }

    fun create(name: String, email: String): User {
        if (store.values.any { it.email == email }) throw Conflict("Email already in use")
        val user = User(nextId.getAndIncrement(), name, email)
        store[user.id] = user
        return user
    }

    fun update(id: Int, name: String?, email: String?): User {
        val existing = findById(id)
        val updated = existing.copy(
            name = name ?: existing.name,
            email = email ?: existing.email,
        )
        store[id] = updated
        return updated
    }

    fun delete(id: Int) {
        findById(id)   // throws 404 if missing
        store.remove(id)
    }

    fun search(q: String, limit: Int, offset: Int): List<User> =
        store.values
            .filter { it.name.contains(q, ignoreCase = true) || it.email.contains(q, ignoreCase = true) }
            .drop(offset)
            .take(limit)
}

class PostService {
    private val store = ConcurrentHashMap<Int, Post>()
    private val nextId = AtomicInteger(1)

    init {
        create(1, "Hello Colleen", "First post about the Colleen framework.", listOf("kotlin", "web"))
        create(2, "Dependency Injection", "How DI works in Colleen.", listOf("di", "kotlin"))
    }

    fun findAll(tags: List<String> = emptyList()): List<Post> {
        val all = store.values.toList()
        return if (tags.isEmpty()) all else all.filter { post -> tags.any { it in post.tags } }
    }

    fun findById(id: Int): Post = store[id] ?: throw NotFound("Post $id not found")

    fun create(authorId: Int, title: String, content: String, tags: List<String> = emptyList()): Post {
        val post = Post(nextId.getAndIncrement(), authorId, title, content, tags)
        store[post.id] = post
        return post
    }

    fun delete(id: Int) {
        findById(id)
        store.remove(id)
    }
}

class CommentService {
    private val store = ConcurrentHashMap<Int, Comment>()
    private val nextId = AtomicInteger(1)

    fun findByPost(postId: Int): List<Comment> = store.values.filter { it.postId == postId }

    fun create(postId: Int, authorId: Int, body: String): Comment {
        val comment = Comment(nextId.getAndIncrement(), postId, authorId, body)
        store[comment.id] = comment
        return comment
    }
}

/** Simple in-memory token store — not for production use. */
class AuthService {
    // token → userId
    private val tokens = ConcurrentHashMap<String, Int>()

    fun login(user: User): String {
        val token = UUID.randomUUID().toString()
        tokens[token] = user.id
        return token
    }

    fun verify(token: String): Int =
        tokens[token] ?: throw Unauthorized("Invalid or expired token")

    fun logout(token: String) {
        tokens.remove(token)
    }
}

/** Simple metrics collector. */
class MetricsService {
    val requestCount = AtomicInteger(0)
    val errorCount = AtomicInteger(0)

    fun snapshot() = mapOf(
        "requests" to requestCount.get(),
        "errors" to errorCount.get(),
    )
}

// =============================================================================
// Custom Parameter Extractor — BearerToken
// =============================================================================

/**
 * Extracts a Bearer token from the Authorization header.
 *
 * Usage in a handler:
 *   fun myHandler(token: BearerToken, authService: AuthService): ...
 */
class BearerToken(value: String?) : ParamExtractor<String?>(value) {

    companion object : ExtractorFactory<BearerToken> {
        private const val PREFIX = "Bearer "

        override fun build(paramName: String, param: Parameter): (Context) -> BearerToken {
            return { ctx ->
                val raw = ctx.header("Authorization")
                val token = raw
                    ?.takeIf { it.startsWith(PREFIX, ignoreCase = true) }
                    ?.substring(PREFIX.length)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                BearerToken(token)
            }
        }
    }

    /** Returns the token value or throws 401 if absent. */
    fun require(): String = value ?: throw Unauthorized("Bearer token is required")
}

// =============================================================================
// Middleware
// =============================================================================

/**
 * Authentication middleware.
 * Resolves the current user from the bearer token and stores it in context state.
 */
class AuthMiddleware(private val requiredRole: String = "user") : Middleware {
    override fun invoke(ctx: Context, next: Next) {
        val raw = ctx.header("Authorization")
            ?.removePrefix("Bearer ")
            ?: throw Unauthorized("Missing Authorization header")

        val authService = ctx.getService<AuthService>()
        val userService = ctx.getService<UserService>()

        val userId = authService.verify(raw)
        val user = userService.findById(userId)

        if (requiredRole == "admin" && user.role != "admin") {
            throw Forbidden("Admin access required")
        }

        ctx.setState("currentUser", user)
        next()
    }
}

/**
 * Metrics middleware — counts total requests and errors.
 */
val metricsMiddleware: Middleware = { ctx, next ->
    val metrics = ctx.getService<MetricsService>()
    metrics.requestCount.incrementAndGet()
    next()
    if (ctx.error != null) metrics.errorCount.incrementAndGet()
}

// =============================================================================
// Function-style Handlers
// =============================================================================

// ----- Auth -----

fun login(body: Json<LoginRequest>, userService: UserService, authService: AuthService): LoginResponse {
    val req = body.value
    val user = userService.findByEmail(req.email) ?: throw Unauthorized("Invalid credentials")
    // Password check omitted for brevity
    val token = authService.login(user)
    return LoginResponse(token, user.id)
}

fun logout(token: BearerToken, authService: AuthService): Status {
    authService.logout(token.require())
    return Status(204)
}

// ----- Users -----

fun listUsers(userService: UserService): List<User> = userService.findAll()

fun getUser(id: Path<Int>, userService: UserService): User = userService.findById(id.value)

fun createUser(body: Json<CreateUserRequest>, userService: UserService): Result<User> {
    val req = body.value

    expect {
        field("name", req.name).required().notBlank().minSize(2).maxSize(50)
        field("email", req.email).required().email()
        field("password", req.password).required().minSize(8)
            .matches(Regex("^(?=.*[A-Z])(?=.*[0-9]).*$"))
            .message("Password must contain at least one uppercase letter and one digit")
    }

    val user = userService.create(req.name, req.email)
    return Result.created(user)
}

fun updateUser(id: Path<Int>, body: Json<UpdateUserRequest>, userService: UserService): User {
    val req = body.value

    expect {
        field("name", req.name).maxSize(50)
        field("email", req.email).email()
    }

    return userService.update(id.value, req.name, req.email)
}

fun deleteUser(id: Path<Int>, userService: UserService): Status {
    userService.delete(id.value)
    return Status(204)
}

fun searchUsers(params: Query<SearchParams>, userService: UserService): List<User> {
    val p = params.value
    return userService.search(p.q, p.limit, p.offset)
}

// ----- Posts -----

fun listPosts(tags: Query<List<String>>, postService: PostService): List<Post> =
    postService.findAll(tags.value)

fun getPost(id: Path<Int>, postService: PostService): Post = postService.findById(id.value)

fun createPost(body: Json<CreatePostRequest>, ctx: Context, postService: PostService): Result<Post> {
    val currentUser = ctx.getState<User>("currentUser")
    val req = body.value

    expect {
        field("title", req.title).required().notBlank().maxSize(200)
        field("content", req.content).required().notBlank()
    }

    val post = postService.create(currentUser.id, req.title, req.content, req.tags)
    return Result.created(post)
}

fun deletePost(id: Path<Int>, ctx: Context, postService: PostService): Status {
    val currentUser = ctx.getState<User>("currentUser")
    val post = postService.findById(id.value)
    if (post.authorId != currentUser.id && currentUser.role != "admin") {
        throw Forbidden("You can only delete your own posts")
    }
    postService.delete(id.value)
    return Status(204)
}

// ----- Comments -----

fun listComments(postId: Path<Int>, commentService: CommentService): List<Comment> =
    commentService.findByPost(postId.value)

fun createComment(
    postId: Path<Int>,
    body: Json<CreateCommentRequest>,
    ctx: Context,
    postService: PostService,
    commentService: CommentService,
): Result<Comment> {
    val currentUser = ctx.getState<User>("currentUser")
    postService.findById(postId.value)   // verify post exists
    val req = body.value

    expect {
        field("body", req.body).required().notBlank().maxSize(2000)
    }

    val comment = commentService.create(postId.value, currentUser.id, req.body)
    return Result.created(comment)
}

// ----- File upload -----

fun uploadAvatar(file: UploadedFile): UploadResult {
    val item = file.value ?: throw BadRequest("No file uploaded")
    if ((item.size) > 2 * 1024 * 1024) throw BadRequest("File exceeds 2 MB limit")
    return UploadResult(item.name, item.size, item.contentType)
}

// =============================================================================
// Controller-style Handlers — Admin
// =============================================================================

@Controller("/admin")
class AdminController(private val userService: UserService, private val metricsService: MetricsService) {

    /** List all users (admin only). */
    @Get("/users")
    fun listUsers(): List<User> = userService.findAll()

    /** Promote a user to admin (admin only). */
    @POST("/users/{id}/promote")
    fun promoteUser(id: Path<Int>): User {
        val user = userService.findById(id.value)
        // In a real app, this would persist the role change.
        return user.copy(role = "admin")
    }

    /** Application metrics (admin only). */
    @Get("/metrics")
    fun metrics(): Map<String, Any> = metricsService.snapshot()
}

// =============================================================================
// Sub-application — API v1
// =============================================================================

fun buildApiV1App(
    userService: UserService,
    postService: PostService,
    commentService: CommentService,
    authService: AuthService,
): Colleen {
    val api = Colleen()

    // Services available within this sub-app
    api.provide(userService)
    api.provide(postService)
    api.provide(commentService)
    api.provide(authService)

    // Sub-app level middleware: CORS + request ID for every /api/v1 request
    api.use(Cors.permissive())
    api.use(RequestId())

    // ---- Auth endpoints (no auth required) ----
    api.post("/auth/login", ::login)
    api.post("/auth/logout", ::logout)

    // ---- User endpoints ----
    api.get("/users", ::listUsers)
    api.get("/users/search", ::searchUsers)
    api.get("/users/{id}", ::getUser)

    // Write operations require authentication
    api.post("/users", ::createUser)

    api.put("/users/{id}")
        .use(AuthMiddleware())
        .handle(::updateUser)

    api.delete("/users/{id}")
        .use(AuthMiddleware())
        .handle(::deleteUser)

    // ---- Post endpoints ----
    api.get("/posts", ::listPosts)
    api.get("/posts/{id}", ::getPost)

    api.post("/posts/{id}")
        .use(AuthMiddleware())
        .handle(::createPost)

    api.delete("/posts/{id}")
        .use(AuthMiddleware())
        .handle(::deletePost)

    // ---- Comment endpoints ----
    api.get("/posts/{postId}/comments", ::listComments)

    api.post("/posts/{postId}/comments")
        .use(AuthMiddleware())
        .handle(::createComment)

    // ---- File upload ----
    api.post("/users/avatar")
        .use(AuthMiddleware())
        .handle(::uploadAvatar)

    // ---- SSE — live notifications ----
    api.get("/notifications") { ctx ->
        val token = ctx.header("Authorization")?.removePrefix("Bearer ")
            ?: throw Unauthorized("Missing token")
        authService.verify(token)  // ensure the client is authenticated

        ctx.sse { conn ->
            conn.keepAlive(15)

            conn.onClose { reason ->
                logger.info("[SSE] client disconnected: $reason")
            }

            // Emit a few demo events and then close the stream
            repeat(5) { i ->
                conn.send("""{"event":"ping","seq":$i}""")
                Thread.sleep(1_000)
            }
        }
    }

    // ---- Sub-app-level error handling ----
    api.config.propagateExceptions = false

    api.onError<ValidationException> { e, ctx ->
        ctx.status(422).json(
            mapOf("error" to "Validation failed", "fields" to e.errors),
        )
    }

    api.onError<HttpException> { e, ctx ->
        ctx.status(e.status).json(
            mapOf("error" to e.code, "message" to e.message),
        )
    }

    return api
}

// =============================================================================
// Sub-application — Admin
// =============================================================================

fun buildAdminApp(
    userService: UserService,
    authService: AuthService,
    metricsService: MetricsService
): Colleen {
    val admin = Colleen()

    admin.provide(userService)
    admin.provide(metricsService)
    admin.provide(authService)

    // All admin routes require an admin-level token
    admin.use(AuthMiddleware(requiredRole = "admin"))

    admin.addController(AdminController(userService, metricsService))

    return admin
}

// =============================================================================
// Application Entry Point
// =============================================================================

fun main() {
    // ---- Shared services (registered on the root app) ----
    val userService = UserService()
    val postService = PostService()
    val commentService = CommentService()
    val authService = AuthService()
    val metricsService = MetricsService()

    // ---- Root application ----
    val app = Colleen()

    // Root-level services (inherited by all sub-apps via DI resolution chain)
    app.provide(metricsService)
    // Note: domain services are provided by sub-apps to keep boundaries clear

    // ---- Global middleware ----
    app.use(SecurityHeaders())
    app.use(RequestLogger())
    app.use(metricsMiddleware)
    app.use(Heartbeat(endpoint = "/health"))
    app.use(RateLimiter(capacity = 200, refillRate = 50.0))

    // ---- Event system ----

    // Log every registered route at startup
    app.on<Event.RouteRegistered> { event ->
        logger.info("[Route] ${event.node.method} ${event.node.path}")
    }

    // Structured access log when the response is fully sent
    app.on<Event.ResponseSent> { event ->
        val ctx = event.ctx
        logger.info(
            "[Access] ${ctx.method} ${ctx.fullPath} → ${ctx.response.status} " +
                    "(${event.total.inWholeMilliseconds}ms, ${event.bytesSent}B)"
        )
    }

    // Track errors globally
    app.on<Event.ExceptionCaught> { event ->
        if (event.exception !is HttpException) {
            logger.error("[Error] Unhandled exception: ${event.exception.message}")
        }
    }

    // ---- Root-level routes ----

    // Simple lambda-style handler
    app.get("/") {
        mapOf(
            "name" to "Colleen Demo API",
            "version" to "1.0.0",
            "endpoints" to listOf("/api/v1", "/admin"),
        )
    }

    // Demonstrates raw query-param map binding
    app.get("/echo") { ctx ->
        val headers = mapOf(
            "method" to ctx.method,
            "path" to ctx.path,
            "query" to ctx.queries(),
        )
        headers
    }

    // Demonstrates reading a signed cookie
    app.use(SignedCookie(secret = "super-secret-key-change-in-prod-must-at-least-32-bytes"))

    app.get("/session") { ctx ->
        val userId = ctx.getSignedCookie("session_user")
        mapOf("session_user" to (userId ?: "none"))
    }

    app.post("/session") { ctx ->
        val userId = ctx.query("userId") ?: throw BadRequest("userId query param is required")
        ctx.signedCookie(name = "session_user", value = userId)
        mapOf("ok" to true)
    }

    // ---- Mount sub-applications ----
    val apiV1 = buildApiV1App(userService, postService, commentService, authService)
    val adminApp = buildAdminApp(userService, authService, metricsService)

    app.mount("/api/v1", apiV1)
    app.mount("/", adminApp)

    // ---- Root-level error handling (catch-all) ----
    app.onError<Exception> { e, ctx ->
        val (status, message) = if (e is HttpException) {
            e.status to e.message
        } else {
            500 to "internal server error"
        }
        if (status >= 500) {
            logger.error("[Unhandled] ${e::class.simpleName}: ${e.message}")
        }
        ctx.status(status).json(mapOf("error" to message))
    }

    // ---- Application configuration ----
    app.config {
        server {
            host = "0.0.0.0"
            port = 8080
            useVirtualThreads = true
            maxConcurrentRequests = 500
            maxRequestSize = 30 * 1024 * 1024   // 30 MB
            maxFileSize = 10 * 1024 * 1024       // 10 MB per file
            idleTimeout = 60_000
            shutdownTimeout = 30_000
        }
        json {
            pretty = false
            failOnUnknownProperties = true
            failOnNullForPrimitives = true
        }
    }

    // ---- Start server ----
    app.listen()
    logger.info("Server running → http://localhost:8080")

    runTests()
}

// =============================================================================
// Tests
// =============================================================================

fun runTests() {
    logger.info("\n========== Running TestClient suite ==========\n")

    val userService = UserService()
    val postService = PostService()
    val commentService = CommentService()
    val authService = AuthService()
    val metricsService = MetricsService()

    // Build a minimal version of the app for testing
    fun buildTestApp(): Colleen {
        val app = Colleen()
        app.provide(metricsService)
        app.use(metricsMiddleware)

        val api = buildApiV1App(userService, postService, commentService, authService)
        app.mount("/api/v1", api)

        val admin = buildAdminApp(userService, authService, metricsService)
        app.mount("/", admin)

        return app
    }

    val client = TestClient(buildTestApp())

    // ---- Helper: obtain a valid token ----
    fun loginAs(email: String): String {
        val resp = client.post("/api/v1/auth/login")
            .json(mapOf("email" to email, "password" to "irrelevant"))
            .send()
        resp.assertStatus(200)
        return resp.json<LoginResponse>()!!.token
    }

    // ------------------------------------------------------------------
    // 1. Public endpoints
    // ------------------------------------------------------------------
    logger.info("[Test] GET /api/v1/users — list users (public)")
    client.get("/api/v1/users").send().assertStatus(200)
    logger.info("  PASS")

    logger.info("[Test] GET /api/v1/posts — list posts (public)")
    client.get("/api/v1/posts").send().assertStatus(200)
    logger.info("  PASS")

    // ------------------------------------------------------------------
    // 2. Authentication
    // ------------------------------------------------------------------
    logger.info("[Test] POST /api/v1/auth/login — valid credentials")
    val tokenResp = client.post("/api/v1/auth/login")
        .json(mapOf("email" to "alice@example.com", "password" to "any"))
        .send()
    tokenResp.assertStatus(200)
    val aliceToken = tokenResp.json<LoginResponse>()!!.token
    check(aliceToken.isNotBlank()) { "Token must not be blank" }
    logger.info("  PASS (token=$aliceToken)")

    logger.info("[Test] POST /api/v1/auth/login — unknown email → 401")
    client.post("/api/v1/auth/login")
        .json(mapOf("email" to "nobody@example.com", "password" to "x"))
        .send()
        .assertStatus(401)
    logger.info("  PASS")

    // ------------------------------------------------------------------
    // 3. Create user with validation
    // ------------------------------------------------------------------
    logger.info("[Test] POST /api/v1/users — valid payload")
    val createResp = client.post("/api/v1/users")
        .json(mapOf("name" to "Charlie", "email" to "charlie@example.com", "password" to "Secret1234"))
        .send()
    createResp.assertStatus(201)
    val charlie = createResp.json<User>()!!
    check(charlie.name == "Charlie")
    logger.info("  PASS (id=${charlie.id})")

    logger.info("[Test] POST /api/v1/users — duplicate email → 409")
    client.post("/api/v1/users")
        .json(mapOf("name" to "Charlie2", "email" to "charlie@example.com", "password" to "Secret1234"))
        .send()
        .assertStatus(409)
    logger.info("  PASS")

    logger.info("[Test] POST /api/v1/users — weak password → 422")
    client.post("/api/v1/users")
        .json(mapOf("name" to "Dave", "email" to "dave@example.com", "password" to "short"))
        .send()
        .assertStatus(422)
    logger.info("  PASS")

    // ------------------------------------------------------------------
    // 4. Authenticated update
    // ------------------------------------------------------------------
    logger.info("[Test] PUT /api/v1/users/{id} — authenticated update")
    client.put("/api/v1/users/${charlie.id}")
        .header("Authorization", "Bearer $aliceToken")
        .json(mapOf("name" to "Charles"))
        .send()
        .assertStatus(200)
    logger.info("  PASS")

    logger.info("[Test] PUT /api/v1/users/{id} — unauthenticated → 401")
    client.put("/api/v1/users/${charlie.id}")
        .json(mapOf("name" to "Charles"))
        .send()
        .assertStatus(401)
    logger.info("  PASS")

    // ------------------------------------------------------------------
    // 5. Search with query params
    // ------------------------------------------------------------------
    logger.info("[Test] GET /api/v1/users/search?q=alice")
    val searchResp = client.get("/api/v1/users/search")
        .query("q", "alice")
        .query("limit", "5")
        .send()
    searchResp.assertStatus(200)
    val results = searchResp.json<List<User>>()!!
    check(results.isNotEmpty()) { "Search must return at least one result" }
    logger.info("  PASS (found ${results.size} user(s))")

    // ------------------------------------------------------------------
    // 6. Post and comment flow
    // ------------------------------------------------------------------
    logger.info("[Test] POST /api/v1/posts/{id} — create post (authenticated)")
    val postResp = client.post("/api/v1/posts/0")   // id in path unused; authorId comes from token
        .header("Authorization", "Bearer $aliceToken")
        .json(mapOf("title" to "Test Post", "content" to "Hello world", "tags" to listOf("test")))
        .send()
    postResp.assertStatus(201)
    val post = postResp.json<Post>()!!
    logger.info("  PASS (postId=${post.id})")

    logger.info("[Test] GET /api/v1/posts — filter by tag")
    val tagResp = client.get("/api/v1/posts")
        .query("tags", "test")
        .send()
    tagResp.assertStatus(200)
    logger.info("  PASS")

    logger.info("[Test] POST /api/v1/posts/{postId}/comments — create comment")
    val commentResp = client.post("/api/v1/posts/${post.id}/comments")
        .header("Authorization", "Bearer $aliceToken")
        .json(mapOf("body" to "Great post!"))
        .send()
    commentResp.assertStatus(201)
    logger.info("  PASS")

    // ------------------------------------------------------------------
    // 7. File upload
    // ------------------------------------------------------------------
    logger.info("[Test] POST /api/v1/users/avatar — multipart upload")
    val uploadResp = client.post("/api/v1/users/avatar")
        .header("Authorization", "Bearer $aliceToken")
        .file("file", "avatar.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png")
        .send()
    uploadResp.assertStatus(200)
    logger.info("  PASS")

    // ------------------------------------------------------------------
    // 8. Admin routes
    // ------------------------------------------------------------------
    val adminToken = loginAs("admin@example.com")

    logger.info("[Test] GET /admin/users — admin access")
    client.get("/admin/users")
        .header("Authorization", "Bearer $adminToken")
        .send()
        .assertStatus(200)
    logger.info("  PASS")

    logger.info("[Test] GET /admin/users — non-admin access → 403")
    client.get("/admin/users")
        .header("Authorization", "Bearer $aliceToken")
        .send()
        .assertStatus(403)
    logger.info("  PASS")

    logger.info("[Test] GET /admin/metrics — returns counters")
    val metricsResp = client.get("/admin/metrics")
        .header("Authorization", "Bearer $adminToken")
        .send()
    metricsResp.assertStatus(200)
    logger.info("  PASS (metrics=${metricsResp.json<Map<String, Any>>()})")

    // ------------------------------------------------------------------
    // 9. 404 for unknown routes
    // ------------------------------------------------------------------
    logger.info("[Test] GET /api/v1/does-not-exist → 404")
    client.get("/api/v1/does-not-exist").send().assertClientError()
    logger.info("  PASS")

    logger.info("\n========== All tests passed ✔ ==========\n")
}