import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.HttpException
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.ValidationException
import io.github.cymoo.colleen.logger
import io.github.cymoo.colleen.middleware.getRequestId
import io.github.cymoo.colleen.util.http.HtmlEscape
import io.github.cymoo.colleen.util.http.HttpStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.function.BiConsumer

data class ErrorResponse(
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val timestamp: Instant = Instant.now(),
    val requestId: String? = null,
    val errors: Map<String, List<String>>? = null,
    val trace: String? = null,
)

enum class ErrorFormat {
    Json, Html
}

/**
 * Error Handler Middleware
 *
 * Handles exceptions thrown during request processing.
 *
 * @param format Formats error as JSON or HTML responses.
 * @param statusMapping Custom mapping from exception types to HTTP status codes
 * @param showStackTrace Whether to include stack traces in error responses
 * @param onError Custom error handler for logging, monitoring, etc.
 */
class ErrorHandling @JvmOverloads constructor(
    private val format: ErrorFormat = ErrorFormat.Json,
    private val statusMapping: Map<Class<out Throwable>, Int> = emptyMap(),
    private val showStackTrace: Boolean = false,
    private val onError: BiConsumer<Context, Throwable>? = null,
) : Middleware {


    override fun invoke(ctx: Context, next: Next) {
        next()

        val errorState = ctx.error ?: return
        errorState.handled = true

        handleError(ctx, errorState.cause)
    }

    private fun handleError(ctx: Context, error: Throwable) {
        // Determine status code
        val status = when (error) {
            is HttpException -> error.status
            else -> statusMapping[error::class.java] ?: 500
        }
        // Set response status
        ctx.status(status)

        // Call custom error handler or log default
        if (onError != null) {
            try {
                onError.accept(ctx, error)
            } catch (e: Exception) {
                logger.error("Error in custom error handler", e)
            }
        } else if (status == 500) {
            logger.error("Internal server error at ${ctx.method} ${ctx.path}", error)
        }

        val errorResponse = ErrorResponse(
            status = status,
            code = when (error) {
                is HttpException -> error.code
                else -> HttpStatus.statusCodeName(status)
            },
            message = error.message ?: "An error occurred",
            path = ctx.fullPath,
            requestId = ctx.getRequestId(),
            errors = if (error is ValidationException) error.errors else null,
            trace = if (showStackTrace) error.stackTraceToString() else null
        )

        if (format == ErrorFormat.Html) {
            val html = ExceptionHtmlRenderer.render(errorResponse, error)
            ctx.html(html)
        } else {
            ctx.json(errorResponse)
        }
    }
}

/**
 * Exception HTML Renderer
 * Renders exception information as simple, informative HTML pages
 */
object ExceptionHtmlRenderer {

    /**
     * Render complete error page with optional stack trace
     *
     * @param response The error response containing all error information
     * @param exception The original exception (needed for stack trace rendering)
     */
    fun render(response: ErrorResponse, exception: Throwable): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${response.status} Error</title>
                <style>${getStyles()}</style>
            </head>
            <body>
                <div class="page-container">
                    ${renderHeader(response)}
                    ${renderMetadata(response)}
                    ${if (response.errors != null) renderValidationErrors(response.errors) else ""}
                    ${if (response.trace != null) renderStackTrace(exception) else ""}
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderHeader(response: ErrorResponse): String {
        return """
            <div class="error-header">
                <div class="status-text">${response.status} ${response.code.replace("_", " ").lowercase()}</div>
                <div class="error-message">${response.message.escapeHtml()}</div>
            </div>
        """.trimIndent()
    }

    private fun renderMetadata(response: ErrorResponse): String {
        return """
            <div class="metadata">
                <div class="metadata-item">
                    <span class="metadata-label">Path:</span>
                    <span class="metadata-value">${response.path.escapeHtml()}</span>
                </div>
                <div class="metadata-item">
                    <span class="metadata-label">Time:</span>
                    <span class="metadata-value">${response.timestamp.toLocalDateTimeString()}</span>
                </div>
                ${
            if (response.requestId != null) """
                <div class="metadata-item">
                    <span class="metadata-label">Request ID:</span>
                    <span class="metadata-value">${response.requestId.escapeHtml()}</span>
                </div>
                """ else ""
        }
            </div>
        """.trimIndent()
    }

    private fun renderValidationErrors(errors: Map<String, List<String>>): String {
        val errorItems = errors.entries.joinToString("") { (field, messages) ->
            val messageList = messages.joinToString("") { msg ->
                "<li>${msg.escapeHtml()}</li>"
            }
            """
            <div class="validation-field">
                <div class="field-name">${field.escapeHtml()}</div>
                <ul class="field-errors">$messageList</ul>
            </div>
            """
        }

        return """
            <div class="validation-errors">
                <div class="section-title">Validation Errors</div>
                $errorItems
            </div>
        """.trimIndent()
    }

    private fun renderStackTrace(exception: Throwable): String {
        return """
            <div class="stack-trace-section">
                <div class="section-title">Stack Trace</div>
                ${renderException(exception)}
            </div>
        """.trimIndent()
    }

    private fun renderException(exception: Throwable): String {
        val stackTrace = exception.stackTraceToString()
        val highlightedTrace = highlightStackTrace(stackTrace)

        return """
            <div class="exception-container">
                <div class="exception-type-header">
                    <span class="exception-type">${exception::class.simpleName ?: "Exception"}</span>
                </div>
                <pre class="stack-trace">$highlightedTrace</pre>
                ${renderCause(exception.cause)}
            </div>
        """.trimIndent()
    }

    private fun renderCause(cause: Throwable?): String {
        if (cause == null) return ""

        val stackTrace = cause.stackTraceToString()
        val highlightedTrace = highlightStackTrace(stackTrace)

        return """
            <div class="caused-by">
                <div class="caused-by-header">
                    Caused by: <span class="exception-type">${cause::class.simpleName ?: "Exception"}</span>
                </div>
                <pre class="stack-trace">$highlightedTrace</pre>
                ${renderCause(cause.cause)}
            </div>
        """.trimIndent()
    }

    private fun highlightStackTrace(stackTrace: String): String {
        return stackTrace.lines().joinToString("\n") { line ->
            when {
                line.trim().startsWith("at ") -> highlightStackTraceLine(line)
                line.trim().startsWith("Caused by:") -> """<span class="caused-line">${line.escapeHtml()}</span>"""
                else -> line.escapeHtml()
            }
        }
    }

    private fun highlightStackTraceLine(line: String): String {
        val escaped = line.escapeHtml()
        val regex = """(at\s+)([a-zA-Z0-9._$]+)\.([a-zA-Z0-9_$<>]+)\(([^:]+):(\d+)\)""".toRegex()

        return regex.replace(escaped) { matchResult ->
            val groups = matchResult.groupValues
            val at = groups[1]
            val className = groups[2]
            val method = groups[3]
            val file = groups[4]
            val lineNum = groups[5]

            """<span class="at-keyword">$at</span><span class="class-name">$className</span>.<span class="method-name">$method</span>(<span class="file-name">$file</span>:<span class="line-number">$lineNum</span>)"""
        }
    }

    private fun getStyles(): String {
        return """
            * {
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }
            
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                background: #f7fafc;
                color: #2d3748;
                line-height: 1.6;
            }
            
            .page-container {
                max-width: 1200px;
                margin: 0 auto;
                padding: 40px 20px;
            }
            
            /* Header */
            .error-header {
                background: white;
                border-radius: 8px;
                padding: 24px;
                margin-bottom: 24px;
                box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
            }
            
            .status-text {
                font-size: 24px;
                font-weight: 600;
                color: #4a5568;
                margin-bottom: 16px;
                text-transform: capitalize;
            }
            
            .error-message {
                padding: 12px 16px;
                background: #fff3e0;
                color: #e65100;
                font-size: 16px;
                line-height: 1.5;
                border-left: 3px solid #ff9800;
                border-radius: 4px;
            }
            
            /* Metadata */
            .metadata {
                background: white;
                border-radius: 8px;
                padding: 24px;
                margin-bottom: 24px;
                box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
            }
            
            .metadata-item {
                display: flex;
                padding: 8px 0;
                border-bottom: 1px solid #e2e8f0;
            }
            
            .metadata-item:last-child {
                border-bottom: none;
            }
            
            .metadata-label {
                font-weight: 600;
                color: #4a5568;
                min-width: 120px;
            }
            
            .metadata-value {
                color: #718096;
                font-family: 'Monaco', 'Menlo', monospace;
                font-size: 14px;
            }
            
            /* Validation Errors */
            .validation-errors {
                background: white;
                border-radius: 8px;
                padding: 24px;
                margin-bottom: 24px;
                box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
            }
            
            .section-title {
                font-size: 18px;
                font-weight: 600;
                color: #2d3748;
                margin-bottom: 16px;
                padding-bottom: 12px;
                border-bottom: 2px solid #e2e8f0;
            }
            
            .validation-field {
                margin-bottom: 16px;
            }
            
            .validation-field:last-child {
                margin-bottom: 0;
            }
            
            .field-name {
                font-weight: 600;
                color: #e53e3e;
                margin-bottom: 8px;
            }
            
            .field-errors {
                list-style: none;
                padding-left: 20px;
            }
            
            .field-errors li {
                color: #718096;
                padding: 4px 0;
                position: relative;
            }
            
            .field-errors li:before {
                content: "•";
                position: absolute;
                left: -20px;
                color: #e53e3e;
            }
            
            /* Stack Trace */
            .stack-trace-section {
                background: white;
                border-radius: 8px;
                padding: 24px;
                box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
            }
            
            .exception-container {
                margin-bottom: 20px;
            }
            
            .exception-type-header {
                background: #fed7d7;
                padding: 12px 16px;
                border-radius: 6px;
                margin-bottom: 12px;
            }
            
            .exception-type {
                font-weight: 700;
                color: #c53030;
                font-family: 'Monaco', 'Menlo', monospace;
            }
            
            .stack-trace {
                background: #2d3748;
                color: #e2e8f0;
                padding: 16px;
                border-radius: 6px;
                overflow-x: auto;
                font-family: 'Monaco', 'Menlo', monospace;
                font-size: 13px;
                line-height: 1.5;
            }
            
            .caused-by {
                margin-top: 16px;
                padding-left: 20px;
                border-left: 3px solid #e2e8f0;
            }
            
            .caused-by-header {
                color: #718096;
                margin-bottom: 8px;
                font-weight: 600;
            }
            
            /* Syntax Highlighting */
            .at-keyword { color: #fc8181; }
            .class-name { color: #90cdf4; }
            .method-name { color: #9ae6b4; }
            .file-name { color: #fbd38d; }
            .line-number { color: #b794f4; }
            .caused-line { color: #fc8181; font-weight: 600; }
        """.trimIndent()
    }
}

private fun String?.escapeHtml(): String {
    if (this == null) return ""
    return HtmlEscape.escape(this)
}

private fun Instant.toLocalDateTimeString(): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return this.atZone(ZoneId.systemDefault()).format(formatter)
}