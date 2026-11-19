package com.example.morningalarm.summary

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/**
 * Owns the opt-in Hermes summary configuration and its offline cache.
 *
 * The configuration (endpoint plus dedicated bearer token) lives in app-private
 * SharedPreferences, and [Context.getApplicationContext]'s backup is disabled by
 * the manifest. The cached HTML is atomically replaced only after a complete
 * write, so a partial download or write is never served.
 *
 * The cache is prefixed with a fingerprint of the configuration it was fetched
 * for. A cache written before a reconfigure/disconnect is therefore ignored even
 * if deleting it was interrupted. In-flight fetches are fenced by a generation
 * counter: after a disconnect or reconfigure they cannot publish their result.
 *
 * [refresh] is blocking; callers are responsible for using Dispatchers.IO.
 */
class HermesSummarySync(
    context: Context,
    private val client: HermesSummaryClient = HermesSummaryClient(),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cache = File(appContext.filesDir, CACHE_FILE_NAME)

    /** Guards [generation]; network fetches never hold it. */
    private val stateLock = Any()
    private var generation = 0L

    /** Serializes refreshes so one client never overlaps its own fetches. */
    private val refreshLock = Any()

    private var activeConfig = loadConfig()

    /** Returns the validated active configuration, if enabled. */
    fun currentConfig(): HermesSummaryClient.Config? = synchronized(stateLock) { activeConfig }

    private fun loadConfig(): HermesSummaryClient.Config? {
        val endpoint = prefs.getString(KEY_ENDPOINT, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return HermesSummaryClient.Config.parse(endpoint, token).getOrNull()
    }

    /**
     * Validates, normalizes, and stores the opt-in configuration.
     *
     * @return false for an invalid endpoint/token or a failed write. No value is
     * logged. A changed configuration discards the previous cache.
     */
    fun configure(endpoint: String, token: String): Boolean {
        val config = HermesSummaryClient.Config.parse(endpoint, token).getOrNull()
            ?: return false
        synchronized(stateLock) {
            if (activeConfig == config) return true
            val saved = prefs.edit()
                .putString(KEY_ENDPOINT, config.endpoint)
                .putString(KEY_TOKEN, config.token)
                .commit()
            if (!saved) return false
            activeConfig = config
            generation++
            // The fingerprint check also protects against an interrupted delete.
            cache.delete()
            return true
        }
    }

    /** Disables sync and discards the configuration and cached copy. */
    fun disconnect(): Boolean = synchronized(stateLock) {
        val saved = prefs.edit().remove(KEY_ENDPOINT).remove(KEY_TOKEN).commit()
        if (!saved) return false
        activeConfig = null
        generation++
        cache.delete()
        true
    }

    /**
     * Returns the last successfully fetched HTML for the current configuration,
     * or null when sync is disabled, nothing has been fetched, or the cache was
     * written for a different configuration.
     */
    fun cachedHtml(): String? = synchronized(stateLock) {
        val config = currentConfigLocked() ?: return null
        val bytes = readCache() ?: return null
        parseCache(bytes, fingerprint(config))?.takeIf { it.isNotBlank() }
    }

    /**
     * Fetches and caches the summary for the current configuration. Blocking.
     *
     * A failed fetch keeps the previous good cache. Returns false when sync is
     * disabled, the fetch fails validation/network constraints, or a reconfigure/
     * disconnect happened while the fetch was in flight. Never logs.
     */
    fun refresh(): Boolean = synchronized(refreshLock) {
        val (config, startGeneration) = synchronized(stateLock) {
            val active = currentConfigLocked() ?: return false
            active to generation
        }

        val html = try {
            client.fetch(config)
        } catch (e: IOException) {
            return false
        } catch (e: RuntimeException) {
            return false
        }

        synchronized(stateLock) {
            if (generation != startGeneration || currentConfigLocked() != config) {
                return false
            }
            writeCache(fingerprint(config), html)
        }
    }

    private fun currentConfigLocked(): HermesSummaryClient.Config? = activeConfig

    private fun readCache(): ByteArray? = runCatching {
        cache.inputStream().use { it.readBounded(CACHE_HEADER_BYTES + HermesSummaryClient.MAX_BYTES) }
    }.getOrNull()

    private fun writeCache(configFingerprint: String, html: String): Boolean {
        val bytes = "$CACHE_VERSION:$configFingerprint\n$html".toByteArray(Charsets.UTF_8)
        if (bytes.size > CACHE_HEADER_BYTES + HermesSummaryClient.MAX_BYTES) return false
        return runCatching { replaceCache(cache, bytes) }.isSuccess
    }

    private fun parseCache(bytes: ByteArray, configFingerprint: String): String? {
        val text = runCatching { decodeStrictUtf8(bytes) }.getOrNull() ?: return null
        val headerEnd = text.indexOf('\n')
        if (headerEnd < 0) return null
        val header = text.substring(0, headerEnd)
        if (header != "$CACHE_VERSION:$configFingerprint") return null
        return text.substring(headerEnd + 1)
    }

    private companion object {
        const val PREFS_NAME = "hermes_summary"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
        const val CACHE_FILE_NAME = "hermes_summary_cache.html"
        const val CACHE_VERSION = "v1"
        const val CACHE_HEADER_BYTES = 128
    }
}

internal fun replaceCache(cache: File, bytes: ByteArray) {
    val pending = File.createTempFile("${cache.name}.", ".tmp", cache.parentFile)
    try {
        FileOutputStream(pending).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(pending.toPath(), cache.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        pending.delete()
    }
}

private fun fingerprint(config: HermesSummaryClient.Config): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(config.endpoint.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(config.token.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (output.size() > maxBytes - count) throw IOException("Cached summary is too large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
