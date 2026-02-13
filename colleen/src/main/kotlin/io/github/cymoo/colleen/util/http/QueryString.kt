package io.github.cymoo.colleen.util.http

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Query string parsing utilities.
 *
 * Handles standard query string format: key1=value1&key2=value2
 *
 * Edge case behaviors:
 * - Empty keys are ignored
 * - Empty values are preserved
 * - Malformed encoding is preserved as-is
 * - Empty pairs (from && or leading/trailing &) are ignored
 */
object QueryString {

    /**
     * Parse query string into multi-value map.
     *
     * Format: key1=value1&key2=value2&key1=value3
     *
     * Examples:
     * - "name=John&age=30" -> {"name": ["John"], "age": ["30"]}
     * - "tag=a&tag=b" -> {"tag": ["a", "b"]}
     * - "flag&name=val" -> {"flag": [""], "name": ["val"]}
     * - "key=" -> {"key": [""]}
     * - "=value" -> {} (empty key ignored)
     * - "a%26b=c" -> {"a&b": ["c"]}
     * - "key=val1&key=val2" -> {"key": ["val1", "val2"]}
     *
     * @param queryString Query string to parse (without leading '?')
     * @return Immutable map of parameter names to list of values
     */
    fun parse(queryString: String): Map<String, List<String>> {
        if (queryString.isBlank()) return emptyMap()

        val result = mutableMapOf<String, MutableList<String>>()

        // Split by & first (before decoding to handle encoded & correctly)
        queryString.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach // Skip empty pairs from && or leading/trailing &

            // Find first = to split key and value
            when (val separatorIndex = pair.indexOf('=')) {
                -1 -> {
                    // No '=': treat as flag with empty value
                    val key = decode(pair)
                    if (key.isNotEmpty()) {
                        result.getOrPut(key) { mutableListOf() }.add("")
                    }
                }

                0 -> {
                    // Starts with '=': empty key, ignore
                    // Examples: "=value", "="
                    return@forEach
                }

                else -> {
                    // Normal case: key=value
                    val rawKey = pair.substring(0, separatorIndex)
                    val rawValue = pair.substring(separatorIndex + 1)

                    val key = decode(rawKey)
                    if (key.isNotEmpty()) {
                        val value = decode(rawValue)
                        result.getOrPut(key) { mutableListOf() }.add(value)
                    }
                }
            }
        }

        // Return immutable copy
        return result.mapValues { it.value.toList() }
    }

    /**
     * URL-decode a string using UTF-8.
     *
     * Handles:
     * - Percent encoding (%20, %3D, etc.)
     * - Plus signs as spaces (+ -> space)
     * - Malformed encoding (returns original string)
     *
     * @param value String to decode
     * @return Decoded string, or original if decoding fails
     */
    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            // Malformed encoding (e.g., incomplete %, invalid hex)
            value
        }
}