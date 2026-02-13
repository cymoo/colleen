package io.github.cymoo.colleen.util.http

/**
 * Media type registry for common MIME types
 */
object MediaTypeRegistry {
    private val mimeTypes = mapOf(
        "html" to "text/html",
        "json" to "application/json",
        "xml" to "application/xml",
        "txt" to "text/plain",
        "text" to "text/plain",
        "css" to "text/css",
        "js" to "application/javascript",
        "javascript" to "application/javascript",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "webp" to "image/webp",
        "pdf" to "application/pdf",
        "bytes" to "application/octet-stream",
        "stream" to "application/octet-stream"
    )

    fun normalize(type: String): String {
        return if ('/' in type) {
            type.lowercase()
        } else {
            mimeTypes[type.lowercase()] ?: type.lowercase()
        }
    }
}

/**
 * Represents a media type with quality factor
 */
data class MediaType(
    val type: String,
    val subtype: String,
    val quality: Double = 1.0
) {
    val fullType: String get() = "$type/$subtype"

    val specificity: Int
        get() = when {
            type != "*" && subtype != "*" -> 2
            type != "*" -> 1
            else -> 0
        }

    fun matches(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val (candType, candSubtype) = parse(candidate)
        return when {
            type == "*" -> true
            !type.equals(candType, ignoreCase = true) -> false
            subtype == "*" -> true
            else -> subtype.equals(candSubtype, ignoreCase = true)
        }
    }

    companion object {
        fun parse(value: String): Pair<String, String> {
            val typeStr = value.trim().substringBefore(';')
            val idx = typeStr.indexOf('/')
            return if (idx > 0) {
                typeStr.take(idx).lowercase() to typeStr.substring(idx + 1).lowercase()
            } else {
                "*" to "*"
            }
        }

        fun from(value: String): MediaType {
            val (type, subtype) = parse(value)
            val quality = extractQuality(value)
            return MediaType(type, subtype, quality)
        }
    }
}

/**
 * Handles content negotiation based on Accept header
 */
object ContentNegotiator {

    /**
     * Parse Accept header into sorted list of MediaType
     */
    fun parseAcceptHeader(header: String?): List<MediaType> {
        if (header.isNullOrBlank()) return emptyList()

        return header.split(',')
            .map { MediaType.from(it) }
            .filter { it.quality > 0.0 }
            .sortedWith(
                compareByDescending<MediaType> { it.quality }
                    .thenByDescending { it.specificity }
            )
    }

    /**
     * Negotiate the best media type match from provided types
     *
     * Returns the first matching type from [types], or null if none match
     */
    fun negotiate(acceptHeader: String?, types: List<String>): String? {
        val validTypes = types.filter { it.isNotBlank() }
        if (validTypes.isEmpty()) return null
        if (acceptHeader.isNullOrBlank()) return validTypes.first()

        val accepted = parseAcceptHeader(acceptHeader)
        if (accepted.isEmpty()) return validTypes.first()

        return accepted.firstNotNullOfOrNull { mediaType ->
            validTypes.firstOrNull { type ->
                mediaType.matches(MediaTypeRegistry.normalize(type))
            }
        }
    }
}

/**
 * Represents a language tag with quality factor
 */
data class LanguageTag(
    val language: String,
    val region: String? = null,
    val quality: Double = 1.0
) {
    val fullTag: String
        get() = if (region != null) "$language-$region" else language

    val specificity: Int
        get() = when {
            region != null -> 2
            language != "*" -> 1
            else -> 0
        }

    fun matches(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (language == "*") return true

        val (candLang, candRegion) = parse(candidate)
        if (!language.equals(candLang, ignoreCase = true)) return false

        // Strict matching: zh-CN only matches zh-CN or zh-*
        // But zh matches both zh and zh-CN
        return region == null || region.equals(candRegion, ignoreCase = true)
    }

    companion object {
        fun parse(value: String): Pair<String, String?> {
            val tag = value.trim().substringBefore(';')
            val parts = tag.split('-', limit = 2)
            return parts[0] to parts.getOrNull(1)
        }

        fun from(value: String): LanguageTag {
            val (language, region) = parse(value)
            val quality = extractQuality(value)
            return LanguageTag(language, region, quality)
        }
    }
}

/**
 * Extract quality factor from header value
 */
private fun extractQuality(value: String): Double {
    return value.trim()
        .substringAfter(';', "")
        .split(';')
        .firstNotNullOfOrNull { part ->
            part.split('=', limit = 2)
                .takeIf { it.size == 2 && it[0].trim() == "q" }
                ?.get(1)?.trim()?.toDoubleOrNull()
        }
        ?.coerceIn(0.0, 1.0)
        ?: 1.0
}

/**
 * Handles language negotiation based on Accept-Language header
 */
object LanguageNegotiator {

    /**
     * Parse Accept-Language header into sorted list of LanguageTag
     */
    fun parseAcceptLanguageHeader(header: String?): List<LanguageTag> {
        if (header.isNullOrBlank()) return emptyList()

        return header.split(',')
            .map { LanguageTag.from(it) }
            .filter { it.quality > 0.0 }
            .sortedWith(
                compareByDescending<LanguageTag> { it.quality }
                    .thenByDescending { it.specificity }
            )
    }

    /**
     * Negotiate the best language match from provided languages
     *
     * Returns the first matching language from [languages], or null if none match
     */
    fun negotiate(acceptLanguageHeader: String?, languages: List<String>): String? {
        val validLanguages = languages.filter { it.isNotBlank() }
        if (validLanguages.isEmpty()) return null
        if (acceptLanguageHeader.isNullOrBlank()) return validLanguages.first()

        val accepted = parseAcceptLanguageHeader(acceptLanguageHeader)
        if (accepted.isEmpty()) return validLanguages.first()

        return accepted.firstNotNullOfOrNull { languageTag ->
            validLanguages.firstOrNull { lang ->
                languageTag.matches(lang)
            }
        }
    }
}
