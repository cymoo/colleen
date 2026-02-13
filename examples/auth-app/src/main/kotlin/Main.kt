import io.github.cymoo.colleen.BadRequest
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.HttpException
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Unauthorized
import io.github.cymoo.colleen.ValidationException
import io.github.cymoo.colleen.expect
import org.slf4j.LoggerFactory
import io.github.cymoo.colleen.middleware.RequestLogger
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Simplified User Authentication Example
 *
 * Core features:
 * - Custom middleware (authMiddleware, requireAuth)
 * - Extractor-style handlers (handleRegister, handleLogin)
 * - DSL-style handlers (inline lambdas)
 * - Service injection (AuthService)
 */

// Domain Models
data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val email: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class Session(
    val id: String,
    val userId: String,
    val createdAt: Long = System.currentTimeMillis()
)

// Request DTOs
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String
)

data class LoginRequest(
    val username: String,
    val password: String
)

// Response DTOs
data class UserDto(
    val id: String,
    val username: String,
    val email: String,
    val createdAt: Long
)

data class LoginDto(
    val message: String,
    val sessionId: String
)

data class RegisterDto(
    val message: String,
    val user: UserDto
)

/**
 * In-memory authentication service
 */
class AuthService {
    private val users = ConcurrentHashMap<String, User>()
    private val sessions = ConcurrentHashMap<String, Session>()

    fun register(username: String, password: String, email: String): User {
        // Check if username already exists
        if (users.values.any { it.username == username }) {
            throw BadRequest("Username already exists")
        }

        // Check if email already exists
        if (users.values.any { it.email == email }) {
            throw BadRequest("Email already exists")
        }

        val user = User(
            id = UUID.randomUUID().toString(),
            username = username,
            passwordHash = hashPassword(password),
            email = email
        )

        users[user.id] = user
        return user
    }

    fun login(username: String, password: String): Session? {
        val user = users.values.find { it.username == username } ?: return null
        if (!verifyPassword(password, user.passwordHash)) return null

        val session = Session(
            id = UUID.randomUUID().toString(),
            userId = user.id
        )

        sessions[session.id] = session
        return session
    }

    fun logout(sessionId: String) = sessions.remove(sessionId)

    fun getSession(sessionId: String): Session? = sessions[sessionId]

    fun getUser(userId: String): User? = users[userId]

    fun getUserBySession(sessionId: String): User? {
        val session = getSession(sessionId) ?: return null
        return getUser(session.userId)
    }

    private fun hashPassword(password: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }

    private fun verifyPassword(password: String, hash: String) = hashPassword(password) == hash
}

/**
 * Authentication middleware - loads user from session cookie
 */
fun authMiddleware() = Middleware { ctx, next ->
    val authService = ctx.getService<AuthService>()
    val sessionId = ctx.request.cookie("session_id")

    if (sessionId != null) {
        val user = authService.getUserBySession(sessionId)
        if (user != null) {
            ctx.setState("user", user)
        }
    }
    next()
}

/**
 * Require authentication middleware
 */
fun requireAuth() = Middleware { ctx, next ->
    ctx.getStateOrNull<User?>("user") ?: throw Unauthorized("Login required")
    next()
}

fun handleRegister(
    payload: Json<RegisterRequest>,
    authService: AuthService,
): RegisterDto {
    val req = payload.value

    expect {
        field("username", req.username).minSize(3).maxSize(20)
        field("password", req.password).minSize(6)
        field("email", req.email).email()
    }

    val user = authService.register(req.username, req.password, req.email)

    return RegisterDto(
        message = "Registration successful",
        user = UserDto(user.id, user.username, user.email, user.createdAt)
    )
}

fun handleLogin(
    payload: Json<LoginRequest>,
    authService: AuthService,
    ctx: Context
): LoginDto {
    val req = payload.value

    val session = authService.login(req.username, req.password)
        ?: throw Unauthorized("Invalid username or password")

    ctx.response.cookie(
        name = "session_id",
        value = session.id,
        maxAge = 7 * 24 * 3600,
    )

    return LoginDto(
        message = "Login successful",
        sessionId = session.id
    )
}

fun main() {
    val app = Colleen()
    val logger = LoggerFactory.getLogger("app")

    // Service injection - register global service
    app.provide(AuthService())

    // Global middleware
    app.use(authMiddleware())
    app.use(RequestLogger())

    // Public API
    app.group("/api") {
        // Extractor-style handlers
        post("/register", ::handleRegister)
        post("/login", ::handleLogin)

        // DSL-style handler
        post("/logout") { ctx ->
            val sessionId = ctx.request.cookie("session_id")
            if (sessionId != null) {
                ctx.getService<AuthService>().logout(sessionId)
            }
            ctx.response.deleteCookie("session_id", "/")
            ctx.json(mapOf("message" to "Logout successful"))
        }

        get("/me") { ctx ->
            val user = ctx.getStateOrNull<User?>("user")
                ?: throw Unauthorized("Not logged in")
            UserDto(user.id, user.username, user.email, user.createdAt)
        }
    }

    // Protected routes
    app.group("/api/protected") {
        use(requireAuth())

        get("/") { ctx ->
            val user = ctx.getState<User>("user")
            mapOf("message" to "Welcome to your dashboard, ${user.username}!")
        }

        get("/profile") { ctx ->
            val user = ctx.getState<User>("user")
            ctx.header("foo", "bar").json(
                mapOf(
                    "message" to "This is a protected resource",
                    "user" to UserDto(user.id, user.username, user.email, user.createdAt)
                )
            )
        }
    }

    // Web UI
    app.get("/") { ctx ->
        val user = ctx.getStateOrNull<User?>("user")

        // @formatter:off
        ctx.html(
            """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <title>Auth System</title>
            <style>
                body { 
                    font-family: system-ui, -apple-system, sans-serif;
                    max-width: 600px;
                    margin: 50px auto;
                    padding: 20px;
                }
                .section { 
                    background: #f5f5f5;
                    padding: 20px;
                    border-radius: 8px;
                    margin: 20px 0;
                }
                input { 
                    width: 100%;
                    padding: 10px;
                    margin: 8px 0;
                    border: 1px solid #ddd;
                    border-radius: 4px;
                    box-sizing: border-box;
                }
                button { 
                    width: 100%;
                    padding: 10px;
                    margin-top: 10px;
                    border: none;
                    border-radius: 4px;
                    cursor: pointer;
                    background: #007bff;
                    color: white;
                }
                button:hover { background: #0056b3; }
                .logout { background: #dc3545; }
                .logout:hover { background: #c82333; }
                .message { 
                    padding: 10px;
                    margin: 10px 0;
                    border-radius: 4px;
                    display: none;
                }
                .success { background: #d4edda; color: #155724; }
                .error { background: #f8d7da; color: #721c24; }
            </style>
        </head>
        <body>
            <h1>🔐 Authentication System</h1>
            
            ${if (user != null) """
            <div class="section">
                <h2>Welcome, ${user.username}!</h2>
                <p>Email: ${user.email}</p>
                <button class="logout" onclick="logout()">Logout</button>
            </div>
            
            <div class="section">
                <h3>Test Protected Resource</h3>
                <button onclick="testProtected()">Access Protected Profile</button>
                <pre id="result"></pre>
            </div>
            """ else """
            <div class="section">
                <h2>Login</h2>
                <input type="text" id="loginUser" placeholder="Username">
                <input type="password" id="loginPass" placeholder="Password">
                <button onclick="login()">Login</button>
            </div>
            
            <div class="section">
                <h2>Register</h2>
                <input type="text" id="regUser" placeholder="Username (3-20 chars)">
                <input type="password" id="regPass" placeholder="Password (min 6 chars)">
                <input type="email" id="regEmail" placeholder="Email">
                <button onclick="register()">Register</button>
            </div>"""
            }
            
            <div id="msg" class="message"></div>
            
            <script>
                function showMsg(text, type = 'success') {
                    const msg = document.getElementById('msg');
                    msg.textContent = text;
                    msg.className = 'message ' + type;
                    msg.style.display = 'block';
                    setTimeout(() => msg.style.display = 'none', 3000);
                }
                
                async function register() {
                    const res = await fetch('/api/register', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            username: document.getElementById('regUser').value,
                            password: document.getElementById('regPass').value,
                            email: document.getElementById('regEmail').value
                        })
                    });
                    
                    const data = await res.json();
                    if (res.ok) {
                        showMsg('Registration successful! Please login.');
                    } else {
                        showMsg(data.details || data.error || 'Registration failed', 'error');
                    }
                }
                
                async function login() {
                    const res = await fetch('/api/login', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            username: document.getElementById('loginUser').value,
                            password: document.getElementById('loginPass').value
                        })
                    });
                    
                    if (res.ok) {
                        showMsg('Login successful!');
                        setTimeout(() => location.reload(), 1000);
                    } else {
                        showMsg('Login failed', 'error');
                    }
                }
                
                async function logout() {
                    await fetch('/api/logout', { method: 'POST' });
                    showMsg('Logged out');
                    setTimeout(() => location.reload(), 1000);
                }
                
                async function testProtected() {
                    const res = await fetch('/api/protected/profile');
                    const data = await res.json();
                    document.getElementById('result').textContent = JSON.stringify(data, null, 2);
                }
            </script>
        </body>
        </html>
        """.trimIndent()
        )
    }

    // Error handling
    app.onError<ValidationException> { e, ctx ->
        ctx.status(400).json(mapOf("error" to "Validation failed", "details" to e.message))
    }

    app.onError<HttpException> { e, ctx ->
        ctx.status(e.status).json(mapOf("error" to e.message))
    }

    app.listen(8000)
    logger.info("✅ Auth Server running on http://localhost:8000")
}