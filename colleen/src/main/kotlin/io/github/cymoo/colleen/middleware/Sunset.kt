package io.github.cymoo.colleen.middleware

import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Middleware
import io.github.cymoo.colleen.Next
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sunset middleware
 * Sets API deprecation and sunset time headers
 * Reference: https://www.rfc-editor.org/rfc/rfc8594.html
 *
 * @param sunsetAt API sunset/removal time (when API will stop working)
 * @param deprecatedAt API deprecation time (when API is marked as deprecated), optional
 * @param links Related links in RFC 8288 format (e.g., "<https://api.example.com/v2>; rel=\"successor-version\"")
 */
class Sunset @JvmOverloads constructor(
    private val sunsetAt: Instant,
    private val deprecatedAt: Instant? = null,
    private val links: List<String> = emptyList()
) : Middleware {

    companion object {
        private val HTTP_DATE_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME
    }

    override fun invoke(ctx: Context, next: Next) {
        next()

        // Format sunset date
        val sunsetFormatted = HTTP_DATE_FORMATTER.format(
            sunsetAt.atZone(ZoneId.of("GMT"))
        )
        ctx.header("Sunset", sunsetFormatted)

        // Format deprecation date if provided
        deprecatedAt?.let { instant ->
            val deprecationFormatted = HTTP_DATE_FORMATTER.format(
                instant.atZone(ZoneId.of("GMT"))
            )
            ctx.header("Deprecation", deprecationFormatted)
        }

        // Add Link headers
        links.forEach { link ->
            ctx.response.headers.add("Link", link)
        }
    }
}