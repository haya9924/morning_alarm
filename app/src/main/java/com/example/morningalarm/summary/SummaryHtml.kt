package com.example.morningalarm.summary

/**
 * Prepares user-authored HTML for the summary WebView.
 *
 * The HTML itself is stored locally and is trusted as app data, but it may contain
 * pasted remote resources or scripts. A restrictive CSP is injected as defense in
 * depth on top of WebView settings (JavaScript off, file access off, network off,
 * no INTERNET permission).
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
