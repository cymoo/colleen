import io.github.cymoo.colleen.BadRequest
import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.ExtractorFactory
import io.github.cymoo.colleen.ParamExtractor
import io.github.cymoo.colleen.Unauthorized
import io.github.cymoo.colleen.middleware.RequestLogger
import java.lang.reflect.Parameter

/**
 * Custom Parameter Extractor Examples
 *
 * Demonstrates how to create domain-specific parameter extractors:
 * 1. BearerToken - Extracts JWT token from Authorization header
 * 2. Pagination - Extracts and validates pagination parameters
 * 3. DateRange - Extracts and validates date range from query params
 */

// ========================================================================
// 1. Bearer Token Extractor
// ========================================================================

/**
 * Extracts Bearer token from Authorization header
 *
 * Authorization header format: "Bearer <token>"
 *
 * Example:
 * ```
 * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
 * ```
 */
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
     * Validates that token is present, throws Unauthorized if missing
     */
    fun require(): String {
        return value ?: throw Unauthorized("Bearer token is required")
    }
}

// ========================================================================
// 2. Pagination Extractor
// ========================================================================

/**
 * Pagination data with calculated offset
 */
data class PaginationData(
    val page: Int,
    val size: Int
) {
    val offset: Int = (page - 1) * size

    init {
        require(page > 0) { "Page must be greater than 0" }
        require(size in 1..100) { "Page size must be between 1 and 100" }
    }
}

/**
 * Extracts pagination parameters from query string
 *
 * Query params:
 * - page: page number (default: 1)
 * - size: items per page (default: 20)
 *
 * Example:
 * ```
 * GET /api/users?page=2&size=50
 * ```
 */
class Pagination(value: PaginationData) : ParamExtractor<PaginationData>(value) {
    companion object : ExtractorFactory<Pagination> {
        private const val DEFAULT_PAGE = 1
        private const val DEFAULT_SIZE = 20

        override fun build(paramName: String, param: Parameter): (Context) -> Pagination {
            return { ctx ->
                val page = ctx.query("page")?.toIntOrNull() ?: DEFAULT_PAGE
                val size = ctx.query("size")?.toIntOrNull() ?: DEFAULT_SIZE

                try {
                    Pagination(PaginationData(page, size))
                } catch (e: IllegalArgumentException) {
                    throw BadRequest(e.message ?: "Invalid pagination parameters")
                }
            }
        }
    }
}

// ========================================================================
// 3. Date Range Extractor
// ========================================================================

/**
 * Date range with start and end dates
 */
data class DateRangeData(
    val start: String,
    val end: String
) {
    init {
        require(start.matches(ISO_DATE_REGEX)) {
            "Start date must be in ISO format (YYYY-MM-DD)"
        }
        require(end.matches(ISO_DATE_REGEX)) {
            "End date must be in ISO format (YYYY-MM-DD)"
        }
        require(start <= end) {
            "Start date must be before or equal to end date"
        }
    }

    companion object {
        private val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

/**
 * Extracts and validates date range from query parameters
 *
 * Query params:
 * - start: start date in ISO format (YYYY-MM-DD)
 * - end: end date in ISO format (YYYY-MM-DD)
 *
 * Example:
 * ```
 * GET /api/reports?start=2024-01-01&end=2024-12-31
 * ```
 */
class DateRange(value: DateRangeData?) : ParamExtractor<DateRangeData?>(value) {
    companion object : ExtractorFactory<DateRange> {
        override fun build(paramName: String, param: Parameter): (Context) -> DateRange {
            return { ctx ->
                val start = ctx.query("start")
                val end = ctx.query("end")

                val range = if (start != null && end != null) {
                    try {
                        DateRangeData(start, end)
                    } catch (e: IllegalArgumentException) {
                        throw BadRequest(e.message ?: "Invalid date range parameters")
                    }
                } else {
                    null
                }

                DateRange(range)
            }
        }
    }
}

// ========================================================================
// Handler Functions
// ========================================================================

fun getProfile(token: BearerToken): Map<String, Any> {
    // Require token to be present
    val validToken = token.require()

    return mapOf(
        "token" to validToken,
        "user" to mapOf(
            "id" to 123,
            "name" to "John Doe",
            "email" to "john@example.com"
        )
    )
}

fun listUsers(pagination: Pagination): Map<String, Any> {
    val page = pagination.value

    return mapOf(
        "page" to page.page,
        "size" to page.size,
        "offset" to page.offset,
        "total" to 250,
        "users" to (1..page.size).map { index ->
            mapOf(
                "id" to (page.offset + index),
                "name" to "User ${page.offset + index}"
            )
        }
    )
}

fun getReports(dateRange: DateRange): Map<String, Any?> {
    return mapOf(
        "dateRange" to dateRange.value,
        "reports" to listOf(
            mapOf("id" to 1, "title" to "Monthly Report"),
            mapOf("id" to 2, "title" to "Quarterly Report")
        )
    )
}

fun getTransactions(
    token: BearerToken,
    dateRange: DateRange,
    pagination: Pagination
): Map<String, Any?> {
    // Require authentication
    token.require()

    val page = pagination.value

    return mapOf(
        "authenticated" to true,
        "dateRange" to dateRange.value,
        "pagination" to mapOf(
            "page" to page.page,
            "size" to page.size,
            "offset" to page.offset
        ),
        "transactions" to (1..page.size).map { index ->
            mapOf(
                "id" to (page.offset + index),
                "amount" to (100..1000).random(),
                "date" to (dateRange.value?.start ?: "2024-01-01")
            )
        }
    )
}

// ========================================================================
// Example Application
// ========================================================================

fun customExtractorsDemo() {
    val app = Colleen()

    app.use(RequestLogger())

    // Example 1: Bearer Token for authentication
    app.get("/api/profile", ::getProfile)

    // Example 2: Pagination for listing
    app.get("/api/users", ::listUsers)

    // Example 3: Date Range for filtering
    app.get("/api/reports", ::getReports)

    // Example 4: Combined - all three extractors
    app.get("/api/transactions", ::getTransactions)

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Custom Parameter Extractors</title>
                <style>
                    body { 
                        font-family: 'Segoe UI', Arial, sans-serif; 
                        max-width: 1200px; 
                        margin: 40px auto; 
                        padding: 0 20px;
                        line-height: 1.6;
                    }
                    h1 { color: #2c3e50; border-bottom: 3px solid #3498db; padding-bottom: 10px; }
                    h2 { color: #34495e; margin-top: 40px; }
                    .example { 
                        background: #f8f9fa; 
                        padding: 20px; 
                        margin: 15px 0; 
                        border-radius: 8px;
                        border-left: 4px solid #3498db;
                    }
                    .endpoint { 
                        background: #2c3e50; 
                        color: #ecf0f1; 
                        padding: 10px 15px; 
                        border-radius: 5px;
                        font-family: 'Courier New', monospace;
                        margin: 10px 0;
                    }
                    .method { 
                        background: #27ae60; 
                        color: white; 
                        padding: 3px 8px; 
                        border-radius: 3px;
                        font-size: 12px;
                        font-weight: bold;
                    }
                    .code { 
                        background: #ecf0f1; 
                        padding: 15px; 
                        border-radius: 5px;
                        font-family: 'Courier New', monospace;
                        overflow-x: auto;
                        margin: 10px 0;
                    }
                    a { color: #3498db; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    .note { 
                        background: #fff3cd; 
                        border-left: 4px solid #ffc107; 
                        padding: 10px 15px;
                        margin: 10px 0;
                    }
                </style>
            </head>
            <body>
                <h1>🔧 Custom Parameter Extractor Examples</h1>
                <p>This demo shows how to create and use custom parameter extractors in Colleen.</p>
                
                <h2>1. BearerToken Extractor</h2>
                <div class="example">
                    <p><strong>Purpose:</strong> Extract JWT token from Authorization header</p>
                    
                    <div class="endpoint">
                        <span class="method">GET</span> /api/profile
                    </div>
                    
                    <div class="code">curl -H "Authorization: Bearer your-token-here" http://localhost:8000/api/profile</div>
                    
                    <p><a href="/api/profile">Try without token (will fail)</a></p>
                    
                    <div class="note">
                        <strong>Note:</strong> The BearerToken extractor automatically extracts the token 
                        from "Authorization: Bearer &lt;token&gt;" header format.
                    </div>
                </div>
                
                <h2>2. Pagination Extractor</h2>
                <div class="example">
                    <p><strong>Purpose:</strong> Extract and validate pagination parameters with defaults</p>
                    
                    <div class="endpoint">
                        <span class="method">GET</span> /api/users?page=2&size=50
                    </div>
                    
                    <p>Examples:</p>
                    <ul>
                        <li><a href="/api/users">/api/users</a> - Default pagination (page=1, size=20)</li>
                        <li><a href="/api/users?page=2">/api/users?page=2</a> - Page 2 with default size</li>
                        <li><a href="/api/users?page=3&size=50">/api/users?page=3&size=50</a> - Custom page and size</li>
                        <li><a href="/api/users?page=0&size=10">/api/users?page=0&size=10</a> - Invalid (page must be > 0)</li>
                        <li><a href="/api/users?page=1&size=200">/api/users?page=1&size=200</a> - Invalid (size max 100)</li>
                    </ul>
                    
                    <div class="note">
                        <strong>Validation:</strong> Page must be > 0, Size must be 1-100
                    </div>
                </div>
                
                <h2>3. DateRange Extractor</h2>
                <div class="example">
                    <p><strong>Purpose:</strong> Extract and validate date range in ISO format</p>
                    
                    <div class="endpoint">
                        <span class="method">GET</span> /api/reports?start=2024-01-01&end=2024-12-31
                    </div>
                    
                    <p>Examples:</p>
                    <ul>
                        <li><a href="/api/reports?start=2024-01-01&end=2024-03-31">/api/reports?start=2024-01-01&end=2024-03-31</a> - Q1 2024</li>
                        <li><a href="/api/reports?start=2024-06-01&end=2024-06-30">/api/reports?start=2024-06-01&end=2024-06-30</a> - June 2024</li>
                        <li><a href="/api/reports">/api/reports</a> - No date range</li>
                        <li><a href="/api/reports?start=2024-12-31&end=2024-01-01">/api/reports?start=2024-12-31&end=2024-01-01</a> - Invalid (start > end)</li>
                    </ul>
                    
                    <div class="note">
                        <strong>Format:</strong> Dates must be in YYYY-MM-DD format. Start must be ≤ end.
                    </div>
                </div>
                
                <h2>4. Combined Example</h2>
                <div class="example">
                    <p><strong>Purpose:</strong> Use multiple custom extractors together</p>
                    
                    <div class="endpoint">
                        <span class="method">GET</span> /api/transactions?start=2024-01-01&end=2024-12-31&page=1&size=25
                    </div>
                    
                    <div class="code">curl -H "Authorization: Bearer your-token" "http://localhost:8000/api/transactions?start=2024-01-01&end=2024-12-31&page=1&size=25"</div>
                    
                    <p><a href="/api/transactions?start=2024-01-01&end=2024-03-31&page=1&size=25">Try without token (will fail)</a></p>
                    
                    <div class="note">
                        <strong>This endpoint demonstrates:</strong> BearerToken (required) + DateRange (optional) + Pagination (with defaults)
                    </div>
                </div>
                
                <hr style="margin: 40px 0; border: none; border-top: 2px solid #ecf0f1;">
                
                <h2>📚 How to Create Custom Extractors</h2>
                <div class="example">
                    <p>All custom extractors follow the similar pattern:</p>
                    
                    <pre class="code">class MyExtractor&lt;T&gt;(value: T) : ParamExtractor&lt;T&gt;(value) {
    companion object : ExtractorFactory&lt;MyExtractor&lt;*&gt;&gt; {
        override fun build(paramName: String, param: Parameter): (Context) -> MyExtractor&lt;*&gt; {
            return { ctx ->
                // 1. Extract raw data from Context
                val rawValue = ctx.query("param") // or header(), pathParam(), etc.
                
                // 2. Validate and transform
                val transformed = try {
                    validateAndTransform(rawValue)
                } catch (e: Exception) {
                    throw BadRequest(e.message ?: "Invalid MyExtractor parameters")
                }
                
                // 3. Return wrapped value
                MyExtractor(transformed)
            }
        }
    }
}</pre>
                </div>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ Custom extractors demo running on http://localhost:8000")
    println()
    println("Examples:")
    println("  - Bearer Token:  curl -H 'Authorization: Bearer test-token' http://localhost:8000/api/profile")
    println("  - Pagination:    http://localhost:8000/api/users?page=2&size=50")
    println("  - Date Range:    http://localhost:8000/api/reports?start=2024-01-01&end=2024-12-31")
    println("  - Combined:      curl -H 'Authorization: Bearer test' 'http://localhost:8000/api/transactions?start=2024-01-01&end=2024-03-31&page=1'")

}

// ========================================================================
// Main
// ========================================================================

fun main() {
    customExtractorsDemo()
}