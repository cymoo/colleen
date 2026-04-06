package io.github.cymoo.colleen.util.http

object HttpStatus {
    private val statusMap = mapOf(
        // 1xx Informational
        100 to "Continue",
        101 to "Switching Protocols",
        // 2xx Success
        200 to "OK",
        201 to "Created",
        202 to "Accepted",
        204 to "No Content",
        // 3xx Redirection
        301 to "Moved Permanently",
        302 to "Found",
        304 to "Not Modified",
        307 to "Temporary Redirect",
        308 to "Permanent Redirect",
        // 4xx Client Errors
        400 to "Bad Request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        405 to "Method Not Allowed",
        406 to "Not Acceptable",
        409 to "Conflict",
        410 to "Gone",
        413 to "Payload Too Large",
        415 to "Unsupported Media Type",
        422 to "Unprocessable Entity",
        429 to "Too Many Requests",
        // 5xx Server Errors
        500 to "Internal Server Error",
        501 to "Not Implemented",
        502 to "Bad Gateway",
        503 to "Service Unavailable",
        504 to "Gateway Timeout"
    )

    /**
     * Returns the HTTP reason phrase for the given status code.
     *
     * For example:
     * - 404 -> "Not Found"
     * - 500 -> "Internal Server Error"
     *
     * Returns "Unknown Status" if the status code is not recognized.
     */
    fun reasonPhrase(status: Int): String = statusMap[status] ?: "Unknown Status"

    /**
     * Converts an HTTP status code into a stable, machine-readable error code.
     *
     * The returned value is derived from the HTTP reason phrase by uppercasing
     * and replacing spaces or hyphens with underscores.
     *
     * For example:
     * - 404 -> "NOT_FOUND"
     * - 500 -> "INTERNAL_SERVER_ERROR"
     *
     * This value is suitable for use as a public error identifier.
     */
    fun statusCodeName(status: Int): String =
        reasonPhrase(status)
            .uppercase()
            .replace(" ", "_")
            .replace("-", "_")
}