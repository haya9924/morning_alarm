package com.example.morningalarm.summary

/**
 * Prepares user-authored HTML for the summary WebView.
 *
 * The HTML may come from the local editor or the configured Hermes source and can
 * contain remote resources or scripts. A restrictive CSP is injected in addition
 * to WebView settings that disable JavaScript, file access, and network loads.
 */
object SummaryHtml {
    const val DATE_PLACEHOLDER = "{{date}}"

    private const val CSP = "<meta http-equiv=\"Content-Security-Policy\" " +
        "content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; " +
        "font-src data:; script-src 'none'; connect-src 'none'; media-src 'none'; " +
        "frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">"

    private val headTag = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)

    fun render(rawHtml: String, dateText: String): String {
        val withDate = rawHtml.replace(DATE_PLACEHOLDER, escapeHtml(dateText))
        return withCsp(withDate)
    }

    fun withCsp(html: String): String {
        headTag.find(html)?.let { match ->
            val insertAt = match.range.last + 1
            return html.substring(0, insertAt) + CSP + html.substring(insertAt)
        }
        htmlTag.find(html)?.let { match ->
            val insertAt = match.range.last + 1
            return html.substring(0, insertAt) + CSP + html.substring(insertAt)
        }
        return CSP + html
    }

    fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
