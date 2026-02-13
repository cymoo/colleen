import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Json
import io.github.cymoo.colleen.NotFound
import io.github.cymoo.colleen.Path
import io.github.cymoo.colleen.Query
import io.github.cymoo.colleen.Result
import io.github.cymoo.colleen.expect
import io.github.cymoo.colleen.middleware.Cors
import io.github.cymoo.colleen.middleware.RequestLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Restful TODO Application Example
 *
 * Features demonstrated:
 * - RESTful API design
 * - JSON request and response handling
 * - Path parameter extraction
 * - Query parameter processing
 * - Data validation
 * - CORS middleware integration
 */

// ============================================================================
// Models
// ============================================================================

data class Todo(
    val id: Long,
    val title: String,
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class CreateTodoRequest(
    val title: String
)

data class UpdateTodoRequest(
    val title: String?,
    val completed: Boolean?
)

// ============================================================================
// Function-style handlers
// ============================================================================

fun getAllTodos(completed: Query<Boolean?>, todoService: TodoService): List<Todo> {
    val todos = todoService.getAll()

    val completed = completed.value
    return if (completed != null) {
        todos.filter { it.completed == completed }
    } else {
        todos
    }
}

fun getOneTodo(id: Path<Long>, todoService: TodoService): Todo {
    return todoService.getById(id.value)
        ?: throw NotFound("Todo not found")
}

fun createTodo(req: Json<CreateTodoRequest>, todoService: TodoService): Result<Todo> {
    val payload = req.value

    // Validate request data
    expect {
        field("title", payload.title).minSize(1).maxSize(200)
    }

    val todo = todoService.create(payload.title)
    return Result.created(todo)
}

fun updateTodo(id: Path<Long>, req: Json<UpdateTodoRequest>, todoService: TodoService): Todo {
    val payload = req.value

    expect {
        field("title", payload.title).minSize(1).maxSize(200)
    }

    return todoService.update(id.value, payload.title, payload.completed)
        ?: throw NotFound("Todo not found")
}

fun deleteAllTodos(todoService: TodoService) {
    todoService.clear()
}

fun deleteOneTodo(id: Path<Long>, todoService: TodoService) {
    val deleted = todoService.delete(id.value)
    if (!deleted) {
        throw NotFound("Todo not found")
    }
}

/**
 * Application entry point
 */
fun main() {
    val app = Colleen()

    app.config.json {
        pretty = true
    }

    // Register TodoService as a global service
    app.provide(TodoService())

    // Enable CORS and request logging
    app.use(Cors())
    app.use(RequestLogger())

    // Route group
    app.group("/api/todos") {

        // GET /api/todos - Get all todos (optional filter by completion status)
        get("/", ::getAllTodos)

        // GET /api/todos/{id} - Get single todo by ID
        get("/{id}", ::getOneTodo)

        // POST /api/todos - Create new todo
        post("/", ::createTodo)

        // PUT /api/todos/{id} - Update existing todo
        put("/{id}", ::updateTodo)

        // DELETE /api/todos/{id} - Delete specific todo
        delete("/{id}", ::deleteOneTodo)

        // DELETE /api/todos - Clear all todos
        delete("/", ::deleteAllTodos)
    }

    // Root path - simple HTML API documentation page
    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Todo API</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }
                    h1 { color: #333; }
                    .endpoint { background: #f5f5f5; padding: 10px; margin: 10px 0; border-radius: 4px; }
                    .method { font-weight: bold; color: #0066cc; }
                    code { background: #e0e0e0; padding: 2px 6px; border-radius: 3px; }
                </style>
            </head>
            <body>
                <h1>📝 Todo API</h1>
                <p>A simple RESTful Todo API example</p>
                
                <h2>API Endpoints</h2>
                
                <div class="endpoint">
                    <span class="method">GET</span> <code>/api/todos</code>
                    <p>Get all TODOs (supports query parameter ?completed=true/false)</p>
                </div>
                
                <div class="endpoint">
                    <span class="method">GET</span> <code>/api/todos/{id}</code>
                    <p>Get a TODO by an ID</p>
                </div>
                
                <div class="endpoint">
                    <span class="method">POST</span> <code>/api/todos</code>
                    <p>Create a new TODO</p>
                    <pre>{ "title": "Title" }</pre>
                </div>
                
                <div class="endpoint">
                    <span class="method">PUT</span> <code>/api/todos/{id}</code>
                    <p>Update a TODO</p>
                    <pre>{ "title": "New title", "completed": true }</pre>
                </div>
                
                <div class="endpoint">
                    <span class="method">DELETE</span> <code>/api/todos/{id}</code>
                    <p>Delete a TODO</p>
                </div>
                
                <div class="endpoint">
                    <span class="method">DELETE</span> <code>/api/todos</code>
                    <p>clear all TODOs</p>
                </div>
                
                <h2>Example Requests</h2>
                <pre>
# Create TODO
curl -X POST http://localhost:8000/api/todos \
  -H "Content-Type: application/json" \
  -d '{"title": "Learn the Colleen framework"}'

# Get all TODOs
curl http://localhost:8000/api/todos

# Update TODO
curl -X PUT http://localhost:8000/api/todos/1 \
  -H "Content-Type: application/json" \
  -d '{"completed": true}'
                </pre>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ Todo API Server running on http://localhost:8000")
}

// ============================================================================
// Services
// ============================================================================

/**
 * Simple in-memory Todo service implementation
 */
class TodoService {

    private val todos = ConcurrentHashMap<Long, Todo>()
    private val idGenerator = AtomicLong(1)

    init {
        // Create some sample data
        val todo1 = Todo(idGenerator.getAndIncrement(), "Write code")
        val todo2 = Todo(idGenerator.getAndIncrement(), "Read book")
        todos[todo1.id] = todo1
        todos[todo2.id] = todo2
    }

    fun getAll(): List<Todo> = todos.values.sortedByDescending { it.createdAt }

    fun getById(id: Long): Todo? = todos[id]

    fun create(title: String): Todo {
        val todo = Todo(
            id = idGenerator.getAndIncrement(),
            title = title
        )
        todos[todo.id] = todo
        return todo
    }

    fun update(id: Long, title: String?, completed: Boolean?): Todo? {
        val existing = todos[id] ?: return null

        val updated = existing.copy(
            title = title ?: existing.title,
            completed = completed ?: existing.completed
        )

        todos[id] = updated
        return updated
    }

    fun delete(id: Long): Boolean = todos.remove(id) != null

    fun clear() = todos.clear()
}
