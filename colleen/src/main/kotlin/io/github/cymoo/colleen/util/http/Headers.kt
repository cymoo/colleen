package io.github.cymoo.colleen.util.http

import java.util.function.BiConsumer

/**
 * HTTP headers container.
 *
 * Header names are case-insensitive and stored in lowercase form.
 * Each header may contain multiple values.
 */
class Headers {
    private val data = mutableMapOf<String, MutableList<String>>()

    companion object {
        /** Creates a Headers instance with a single header entry. */
        @JvmStatic
        fun of(key: String, value: String) = Headers().apply { add(key, value) }
    }

    /** Adds a header value without replacing existing ones. */
    fun add(key: String, value: String) {
        validateHeaderName(key)
        data.getOrPut(key.lowercase()) { mutableListOf() }.add(value)
    }

    /** Sets a header value, replacing any existing values. */
    operator fun set(key: String, value: String) {
        validateHeaderName(key)
        data[key.lowercase()] = mutableListOf(value)
    }

    /** Sets multiple values for a header, replacing existing ones. */
    operator fun set(key: String, value: List<String>) {
        validateHeaderName(key)
        data[key.lowercase()] = value.toMutableList()
    }

    /** Sets the header only if it does not already exist. */
    fun putIfAbsent(key: String, value: String) {
        if (!has(key)) set(key, value)
    }

    /** Returns the first value of the specified header, if present. */
    operator fun get(key: String): String? = data[key.lowercase()]?.firstOrNull()

    /** Returns all values of the specified header. */
    fun getAll(key: String): List<String> = data[key.lowercase()] ?: emptyList()

    /** Iterates over all headers. */
    fun forEach(consumer: BiConsumer<String, List<String>>) {
        data.forEach { (k, v) -> consumer.accept(k, v) }
    }

    /** Removes the specified header. */
    fun remove(key: String) {
        data.remove(key.lowercase())
    }

    /** Removes all headers. */
    fun clear() {
        data.clear()
    }

    /** Checks whether the specified header exists. */
    fun has(key: String): Boolean = data.containsKey(key.lowercase())

    /** Returns true if no headers are present. */
    fun isEmpty(): Boolean = data.isEmpty()

    /**
     * Creates a deep copy of this Headers instance.
     * Changes to the copy will not affect the original.
     */
    fun copy(): Headers {
        return Headers().apply {
            this@Headers.data.forEach { (key, values) ->
                // Deep copy the value list
                this.data[key] = values.toMutableList()
            }
        }
    }

    /** Returns a readable, multi-line representation of the headers. */
    override fun toString() = buildString {
        appendLine("Headers {")
        data.entries.sortedBy { it.key }.forEach { (key, values) ->
            appendLine("  $key: ${values.joinToString(", ")}")
        }
        append("}")
    }

    /**
     * Validates header name format.
     *
     * Restricts to a safe subset for compatibility.
     */
    private fun validateHeaderName(name: String) {
        require(name.isNotBlank()) { "Header name cannot be blank" }
        require(name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            "Invalid header name: '$name'"
        }
    }
}