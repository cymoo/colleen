import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.extension.*
import io.github.cymoo.colleen.middleware.Cors
import io.github.cymoo.colleen.middleware.RequestLogger
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Restful TODO Application — OpenAPI integration example.
 *
 * NOTE: All annotations shown here (@Summary, @Tags, @ParamDesc, etc.) are
 * optional. The OpenAPI extension works out of the box with sensible defaults;
 * add annotations only when you want to enrich or customize the generated spec.
 *
 * Demonstrates the full feature set of the OpenAPI extension:
 *   - @Summary / @Description / @Tags for operation metadata
 *   - @ParamDesc / @ResponseDesc for parameter and response docs
 *   - @ParamDesc(required = OptionalBool.TRUE) to force a parameter as required
 *   - @Hidden to exclude operations or entire controllers from the spec
 *   - openApi(filter = ...) for programmatic route exclusion
 *   - @Schema with name / hidden / type / format / required overrides on DTO fields
 *   - Extended type support: UUID, LocalDate, Instant, BigDecimal, etc.
 *   - Customizable documentation UI: Swagger UI (default) or ReDoc
 *
 * Endpoints added by openApi():
 *   GET /openapi.json  — OpenAPI 3.0.3 JSON specification
 *   GET /docs          — Interactive API documentation page (Swagger UI by default)
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

    // UUID — demonstrates uuid format support
    @Schema(description = "Idempotency key for deduplication", example = "550e8400-e29b-41d4-a716-446655440000")
    val idempotencyKey: UUID = UUID.randomUUID(),

    // LocalDate — demonstrates date format support
    @Schema(description = "Due date for this task", example = "2024-12-31")
    val dueDate: LocalDate? = null,

    // Instant serialized as Unix ms — @Schema(type, format) overrides the inferred schema.
    // Use this when the actual JSON representation differs from the Java type
    // (e.g. Instant serialized as a long rather than an ISO string).
    @Schema(
        description = "Unix timestamp (ms) when the todo was created",
        example = "1700000000000",
        type = "integer",
        format = "int64",
    )
    val createdAt: Instant = Instant.now(),

    // @Schema(hidden = true) — field is excluded entirely from the OpenAPI schema.
    // Useful for internal bookkeeping that should never be exposed in the spec.
    @Schema(hidden = true)
    val internalVersion: Int = 0,
)

data class CreateTodoRequest(
    @Schema(description = "Short description of the task", example = "Buy groceries")
    val title: String,

    @Schema(description = "Optional due date (YYYY-MM-DD)", example = "2024-12-31")
    val dueDate: LocalDate? = null,

    // BigDecimal — demonstrates number type support
    @Schema(description = "Optional estimated hours to complete", example = "1.5")
    val estimatedHours: BigDecimal? = null,
)

data class UpdateTodoRequest(
    @Schema(description = "New title; omit to keep the existing one", example = "Buy organic groceries", required = OptionalBool.FALSE)
    val title: String?,

    @Schema(description = "New completion status; omit to keep the existing one", example = "true", required = OptionalBool.FALSE)
    val completed: Boolean?,

    @Schema(description = "New due date; omit to keep the existing one", example = "2024-12-31", required = OptionalBool.FALSE)
    val dueDate: LocalDate?,
)

data class BatchDeleteRequest(
    @Schema(description = "List of todo IDs to delete")
    val ids: List<Long>,
)

// ============================================================================
// Public todo handlers
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
    return Result.created(todoService.create(payload.title, payload.dueDate))
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
    return todoService.update(id.value, payload.title, payload.completed, payload.dueDate)
        ?: throw NotFound("Todo not found")
}

@Summary("Delete a todo")
@Tags("todos")
@ParamDesc(name = "id", description = "ID of the todo to delete")
@ResponseDesc(204, "Todo deleted successfully")
@ResponseDesc(404, "No todo exists with the given ID")
fun deleteOneTodo(id: Path<Long>, todoService: TodoService) {
    if (!todoService.delete(id.value)) throw NotFound("Todo not found")
}

@Summary("Delete todos in batch")
@Tags("todos")
@ParamDesc(name = "req", description = "List of todo IDs to delete")
@ResponseDesc(204, "Todos deleted successfully")
fun deleteManyTodos(req: Json<BatchDeleteRequest>, todoService: TodoService) {
    req.value.ids.forEach { todoService.delete(it) }
}

@Summary("Clear all todos")
@Description("Permanently removes every todo from the store. This action cannot be undone.")
@Tags("todos")
@ResponseDesc(204, "All todos deleted")
fun deleteAllTodos(todoService: TodoService) {
    todoService.clear()
}

// ============================================================================
// Internal controller — excluded from the OpenAPI spec via @Hidden on the class.
// All operations inside are invisible to the spec regardless of their own annotations.
// ============================================================================

@Hidden
class InternalController {
    fun healthCheck(): Map<String, String> = mapOf("status" to "ok")
    fun metrics(todoService: TodoService): Map<String, Long> =
        mapOf("total" to todoService.getAll().size.toLong())
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

    val internal = InternalController()

    // Enable OpenAPI.
    // The filter excludes /admin/* routes programmatically, complementing
    // @Hidden which handles annotation-based exclusion on InternalController.
    // The default documentation UI is Swagger UI; to use ReDoc instead, pass:
    //   uiHtml = ::redocHtml
    app.openApi(
        title = "Todo API",
        version = "2.0.0",
        description = "A simple RESTful Todo API showcasing OpenAPI doc generation.",
        filter = { path, _ -> !path.startsWith("/admin") },
    )

    app.group("/api/todos") {
        get("/", ::getAllTodos)
        get("/{id}", ::getOneTodo)
        post("/", ::createTodo)
        put("/{id}", ::updateTodo)
        // Static segment /batch is registered before the dynamic /{id} segment
        // so the router matches it first, avoiding ambiguity.
        post("/batch-delete", ::deleteManyTodos)
        delete("/{id}", ::deleteOneTodo)
        delete("/", ::deleteAllTodos)
    }

    // Internal routes: @Hidden on InternalController suppresses them from the spec.
    app.group("/internal") {
        get("/health", internal::healthCheck)
        get("/metrics", internal::metrics)
    }

    // Admin routes: excluded via the filter lambda passed to openApi().
    app.group("/admin") {
        get("/todos", ::getAllTodos)
    }

    app.listen(8000)
    println("✅ Todo API server running at http://localhost:8000")
    println("📄 OpenAPI spec  → http://localhost:8000/openapi.json")
    println("🔍 API docs      → http://localhost:8000/docs")
}

// ============================================================================
// Service
// ============================================================================

class TodoService {
    private val todos = ConcurrentHashMap<Long, Todo>()
    private val idGen = AtomicLong(1)

    init {
        create("Write code")
        create("Read book", dueDate = LocalDate.now().plusDays(7))
    }

    fun getAll(): List<Todo> = todos.values.sortedByDescending { it.createdAt }
    fun getById(id: Long): Todo? = todos[id]

    fun create(title: String, dueDate: LocalDate? = null): Todo {
        val todo = Todo(id = idGen.getAndIncrement(), title = title, dueDate = dueDate)
        todos[todo.id] = todo
        return todo
    }

    fun update(id: Long, title: String?, completed: Boolean?, dueDate: LocalDate?): Todo? {
        val existing = todos[id] ?: return null
        val updated = existing.copy(
            title = title ?: existing.title,
            completed = completed ?: existing.completed,
            dueDate = dueDate ?: existing.dueDate,
        )
        todos[id] = updated
        return updated
    }

    fun delete(id: Long): Boolean = todos.remove(id) != null
    fun clear() = todos.clear()
}