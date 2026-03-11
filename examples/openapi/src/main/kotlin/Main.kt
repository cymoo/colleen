import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.extension.*
import io.github.cymoo.colleen.middleware.Cors
import io.github.cymoo.colleen.middleware.RequestLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Restful TODO Application — OpenAPI integration example.
 *
 * Demonstrates how to annotate handler functions with OpenAPI metadata and
 * wire up the [enableOpenApi] extension so that the spec and Swagger UI are
 * served automatically alongside the application routes.
 *
 * Endpoints added by [enableOpenApi]:
 *   GET /openapi.json  — OpenAPI 3.0.3 JSON specification
 *   GET /swagger-ui    — Interactive Swagger UI page
 */

// ============================================================================
// Models
// ============================================================================

data class Todo(
    @Schema(description = "Unique todo ID", example = "1")
    val id: Long,

    @Schema(description = "Short description of the task", example = "Buy groceries")
    val title: String,

    @Schema(description = "Whether the task has been completed", example = "false")
    val completed: Boolean = false,

    @Schema(description = "Unix timestamp (ms) when the todo was created", example = "1700000000000")
    val createdAt: Long = System.currentTimeMillis(),
)

data class CreateTodoRequest(
    @Schema(description = "Short description of the task", example = "Buy groceries")
    val title: String,
)

data class UpdateTodoRequest(
    @Schema(description = "New title; omit to keep the existing one", example = "Buy organic groceries")
    val title: String?,

    @Schema(description = "New completion status; omit to keep the existing one", example = "true")
    val completed: Boolean?,
)

// ============================================================================
// Handler functions
// ============================================================================

@Summary("List all todos")
@Description("Returns every todo in the store, optionally filtered by completion status.")
@Tags("todos")
@ParamDesc(name = "completed", description = "When provided, filters todos by completion status")
@ResponseDesc(200, "List of matching todos")
fun getAllTodos(completed: Query<Boolean?>, todoService: TodoService): List<Todo> {
    val todos = todoService.getAll()
    return if (completed.value != null) todos.filter { it.completed == completed.value } else todos
}

@Summary("Get a todo by ID")
@Tags("todos")
@ParamDesc(name = "id", description = "ID of the todo to retrieve")
@ResponseDesc(200, "The requested todo")
@ResponseDesc(404, "No todo exists with the given ID")
fun getOneTodo(id: Path<Long>, todoService: TodoService): Todo {
    return todoService.getById(id.value) ?: throw NotFound("Todo not found")
}

@Summary("Create a new todo")
@Tags("todos")
@ParamDesc(name = "req", description = "Todo creation payload")
@ResponseDesc(201, "The newly created todo")
fun createTodo(req: Json<CreateTodoRequest>, todoService: TodoService): Result<Todo> {
    val payload = req.value
    expect {
        field("title", payload.title).minSize(1).maxSize(200)
    }
    return Result.created(todoService.create(payload.title))
}

@Summary("Update an existing todo")
@Tags("todos")
@ParamDesc(name = "id", description = "ID of the todo to update")
@ParamDesc(name = "req", description = "Fields to update; omitted fields are left unchanged")
@ResponseDesc(200, "The updated todo")
@ResponseDesc(404, "No todo exists with the given ID")
fun updateTodo(id: Path<Long>, req: Json<UpdateTodoRequest>, todoService: TodoService): Todo {
    val payload = req.value
    expect {
        field("title", payload.title).minSize(1).maxSize(200)
    }
    return todoService.update(id.value, payload.title, payload.completed)
        ?: throw NotFound("Todo not found")
}

@Summary("Delete a todo")
@Tags("todos")
@ParamDesc(name = "id", description = "ID of the todo to delete")
@ResponseDesc(200, "Todo deleted successfully")
@ResponseDesc(404, "No todo exists with the given ID")
fun deleteOneTodo(id: Path<Long>, todoService: TodoService) {
    if (!todoService.delete(id.value)) throw NotFound("Todo not found")
}

@Summary("Clear all todos")
@Description("Permanently removes every todo from the store. This action cannot be undone.")
@Tags("todos")
@ResponseDesc(200, "All todos deleted")
fun deleteAllTodos(todoService: TodoService) {
    todoService.clear()
}

// ============================================================================
// Application entry point
// ============================================================================

fun main() {
    val app = Colleen()

    app.config.json { pretty = true }

    app.provide(TodoService())
    app.use(Cors())
    app.use(RequestLogger())

    // Enable OpenAPI — spec served at GET /openapi.json, UI at GET /swagger-ui.
    app.enableOpenApi(
        title = "Todo API",
        version = "1.0.0",
        description = "A simple RESTful Todo API with OpenAPI documentation.",
    )

    app.group("/api/todos") {
        get("/", ::getAllTodos)
        get("/{id}", ::getOneTodo)
        post("/", ::createTodo)
        put("/{id}", ::updateTodo)
        delete("/{id}", ::deleteOneTodo)
        delete("/", ::deleteAllTodos)
    }

    app.listen(8000)
    println("✅ Todo API server running at http://localhost:8000")
    println("📄 OpenAPI spec  → http://localhost:8000/openapi.json")
    println("🔍 Swagger UI    → http://localhost:8000/swagger-ui")
}

// ============================================================================
// Service
// ============================================================================

class TodoService {
    private val todos = ConcurrentHashMap<Long, Todo>()
    private val idGen = AtomicLong(1)

    init {
        listOf("Write code", "Read book").forEach { create(it) }
    }

    fun getAll(): List<Todo> = todos.values.sortedByDescending { it.createdAt }
    fun getById(id: Long): Todo? = todos[id]

    fun create(title: String): Todo {
        val todo = Todo(id = idGen.getAndIncrement(), title = title)
        todos[todo.id] = todo
        return todo
    }

    fun update(id: Long, title: String?, completed: Boolean?): Todo? {
        val existing = todos[id] ?: return null
        val updated = existing.copy(
            title = title ?: existing.title,
            completed = completed ?: existing.completed,
        )
        todos[id] = updated
        return updated
    }

    fun delete(id: Long): Boolean = todos.remove(id) != null
    fun clear() = todos.clear()
}