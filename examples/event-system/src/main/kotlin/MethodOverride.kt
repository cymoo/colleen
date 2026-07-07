import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Event
import io.github.cymoo.colleen.Request
import io.github.cymoo.colleen.logger

/**
 * HTTP method override support.
 *
 * This feature allows clients (typically HTML forms) to tunnel
 * non-POST HTTP methods (e.g. PUT, PATCH, DELETE) via:
 *
 * - a request header (default: `X-HTTP-Method-Override`)
 * - or a query parameter (default: `_method`)
 *
 * Header value takes precedence over query parameter.
 *
 * Method overriding is only applied to POST requests by default.
 */
@JvmOverloads
fun Colleen.httpMethodOverride(
    headerKey: String = "x-http-method-override",
    queryKey: String = "_method",
    allowOverride: (Request) -> Boolean = DEFAULT_OVERRIDE_POLICY
) {
    on<Event.RequestReceived> { event ->
        val request = event.request
        if (!allowOverride(request)) {
            return@on
        }

        val overriddenMethod = extractOverriddenHttpMethod(
            request = request,
            headerKey = headerKey,
            queryKey = queryKey
        ) ?: return@on

        logger.debug("HTTP method overridden: {} -> {}", request.method, overriddenMethod)
        event.request = event.request.withMethod(overriddenMethod)
    }
}

/**
 * Extracts an overridden HTTP method from the request, if present and valid.
 *
 * Rules:
 * - Header value has higher priority than query parameter
 * - Method must be a valid and supported HTTP verb
 *
 * @return the overridden HTTP method in uppercase, or null if not applicable
 */
internal fun extractOverriddenHttpMethod(
    request: Request,
    headerKey: String,
    queryKey: String
): String? {
    // 1. Header has the highest priority
    request.headers[headerKey]
        ?.trim()
        ?.uppercase()
        ?.takeIf(::isValidHttpMethod)?.let {
            return it
        }

    // 2. Fallback to query parameter
    return request.queries[queryKey]
        ?.firstOrNull()
        ?.trim()
        ?.uppercase()
        ?.takeIf(::isValidHttpMethod)
}

/**
 * Determines whether method override is allowed for the given request.
 *
 * By convention, only POST requests are allowed to be overridden.
 */
private val DEFAULT_OVERRIDE_POLICY: (Request) -> Boolean = {
    it.method.equals("POST", ignoreCase = true)
}

/**
 * Set of HTTP methods allowed for method overriding.
 *
 * TRACE and CONNECT are intentionally excluded for security reasons.
 */
private val ALLOWED_OVERRIDE_METHODS = setOf(
    "PUT",
    "PATCH",
    "DELETE",
    "OPTIONS"
)

/**
 * Checks whether the given method is a valid HTTP method for override.
 */
private fun isValidHttpMethod(method: String): Boolean {
    return method in ALLOWED_OVERRIDE_METHODS
}
