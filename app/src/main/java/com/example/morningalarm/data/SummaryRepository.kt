package com.example.morningalarm.data

import android.content.Context
import java.io.File

/**
 * User-editable morning summary stored in app-private internal storage.
 *
 * The HTML is never loaded from a file:// URL. It is rendered with
 * loadDataWithBaseURL(null, ...) after [com.example.morningalarm.summary.SummaryHtml]
 * injects a restrictive CSP. See MorningSummaryActivity for the WebView hardening.
 */
class SummaryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    private val defaultHtml: String by lazy {
        appContext.assets.open(DEFAULT_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    fun defaultHtml(): String = defaultHtml

    fun read(): String {
        val stored = runCatching {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        }.getOrNull()
        return stored?.takeIf { it.isNotBlank() } ?: defaultHtml
    }

    fun save(html: String): Result<Unit> = runCatching {
        val bytes = html.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) {
            "HTML is too large (max ${MAX_BYTES / 1024} KiB)"
        }
        file.writeBytes(bytes)
    }

    fun reset(): String {
        runCatching { file.delete() }
        return defaultHtml
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        private const val FILE_NAME = "morning_summary.html"
        private const val DEFAULT_ASSET = "default_summary.html"
    }
}
