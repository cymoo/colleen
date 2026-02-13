package io.github.cymoo.colleen.util.http

/**
 * HTML escaping utilities to prevent XSS attacks.
 */
object HtmlEscape {

    /**
     * Escape HTML special characters.
     *
     * Converts: & < > " '
     * To: &amp; &lt; &gt; &quot; &#39;
     *
     * @param text Text to escape
     * @return Escaped HTML-safe text
     */
    fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}