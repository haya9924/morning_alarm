package com.example.morningalarm.summary

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * Opt-in client for a Hermes-hosted morning summary.
 *
 * Only an exact HTTPS endpoint is accepted: no userinfo, query, fragment, or
 * malformed host. The dedicated bearer token is sent only on the request and is
 * never included in failures or logs. Redirects are not followed, the platform
 * default TLS configuration is used, and both timeouts and the streamed body
 * size are bounded.
 *
 * [fetch] is blocking; callers are responsible for using Dispatchers.IO.
 */
class HermesSummaryClient {
    /** A normalized endpoint plus its dedicated bearer token. */
    data class Config(
        val endpoint: String,
        val token: String,
    ) {
        override fun toString(): String = "HermesSummaryConfig(redacted)"

        companion object {
            /**
             * Validates and normalizes an endpoint/token pair. Failures contain
             * no endpoint or token values.
             */
            fun parse(endpoint: String, token: String): Result<Config> {
                val normalized = normalizeSummaryEndpoint(endpoint)
                    ?: return Result.failure(
                        IllegalArgumentException("Invalid summary endpoint"),
                    )
                if (!SUMMARY_TOKEN_REGEX.matches(token)) {
                    return Result.failure(IllegalArgumentException("Invalid summary token"))
                }
                return Result.success(Config(normalized, token))
            }
        }
    }

    /**
     * Fetches the summary HTML.
     *
     * @throws IOException on an invalid configuration, network/TLS failure,
     * non-200 response, redirect, wrong content type, oversized body, invalid
     * UTF-8, or a blank body. Error messages never contain the URL or token.
     */
    fun fetch(config: Config): String {
        val validated = Config.parse(config.endpoint, config.token).getOrElse {
            throw IOException("Invalid summary configuration")
        }

        val connection = try {
            URL(validated.endpoint).openConnection() as? HttpsURLConnection
                ?: throw IOException("Invalid summary endpoint")
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("Invalid summary endpoint")
        }

        val timedOut = AtomicBoolean(false)
        val deadline = deadlines.schedule({
            timedOut.set(true)
            connection.disconnect()
        }, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doInput = true
            connection.setRequestProperty("Authorization", "Bearer ${validated.token}")
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("Accept-Encoding", "identity")

            val status = connection.responseCode
            if (status in 300..399) {
                throw IOException("Summary redirects are not allowed")
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Summary request failed (HTTP $status)")
            }

            val mediaType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
            if (mediaType == null || !mediaType.equals("text/html", ignoreCase = true)) {
                throw IOException("Summary response is not HTML")
            }

            if (connection.contentLengthLong > MAX_BYTES) {
                throw IOException("Summary is too large")
            }

            val html = readBody(connection.inputStream, timedOut)
            if (timedOut.get()) throw IOException("Summary request timed out")
            if (html.isBlank()) throw IOException("Summary is empty")
            return html
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }

    private fun readBody(input: InputStream, timedOut: AtomicBoolean): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        input.use { stream ->
            while (true) {
                if (timedOut.get()) throw IOException("Summary request timed out")
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() > MAX_BYTES - count) {
                    throw IOException("Summary is too large")
                }
                output.write(buffer, 0, count)
            }
        }
        return decodeStrictUtf8(output.toByteArray())
    }

    companion object {
        /** Maximum accepted HTML body size (256 KiB). */
        const val MAX_BYTES = 256 * 1024

        /** Connect and read timeout, in milliseconds. */
        const val TIMEOUT_MS = 5_000

        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val TOTAL_TIMEOUT_MS = 10_000L
        private val deadlines = Executors.newSingleThreadScheduledExecutor { job ->
            Thread(job, "HermesSummaryDeadline").apply { isDaemon = true }
        }
    }
}

private val SUMMARY_TOKEN_REGEX = Regex("[A-Za-z0-9_-]{43}")
private val HOST_LABEL_REGEX = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val IPV6_CHARS_REGEX = Regex("[0-9a-f:.]+")

/**
 * Returns a canonical HTTPS URL, or null for anything that is not an exact
 * server-based HTTPS endpoint without userinfo/query/fragment and with a valid
 * host. The path is preserved (empty becomes "/") and the host is lowercased.
 */
private fun normalizeSummaryEndpoint(raw: String): String? {
    if (raw.isEmpty() || raw.any { it.isWhitespace() || it.code < 0x20 }) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.isAbsolute || uri.isOpaque) return null
    if (uri.scheme == null || !uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val authority = uri.rawAuthority
    if (authority.isNullOrEmpty() || authority.contains('@')) return null

    val host = uri.host ?: return null
    val normalizedHost = normalizeSummaryHost(host) ?: return null
    val port = uri.port
    if (port != -1 && port !in 1..65535) return null

    val rawPath = uri.rawPath ?: return null
    val path = if (rawPath.isEmpty()) "/" else rawPath
    if (!path.startsWith("/")) return null

    return buildString {
        append("https://")
        append(normalizedHost)
        if (port != -1 && port != 443) {
            append(':')
            append(port)
        }
        append(path)
    }
}

private fun normalizeSummaryHost(host: String): String? {
    val lower = host.lowercase(Locale.ROOT)
    if (lower.length !in 1..253) return null

    if (lower.startsWith("[")) {
        if (!lower.endsWith("]")) return null
        val inner = lower.substring(1, lower.length - 1)
        if (inner.isEmpty() || !inner.contains(':') || !IPV6_CHARS_REGEX.matches(inner)) {
            return null
        }
        val address = runCatching { InetAddress.getByName(inner) }.getOrNull()
        if (address !is Inet6Address) return null
        return "[$inner]"
    }

    if (lower.contains(':')) return null
    val labels = lower.split('.')
    if (labels.any { it.isEmpty() || !HOST_LABEL_REGEX.matches(it) }) return null
    return lower
}

/**
 * Decodes UTF-8 strictly, rejecting malformed or unmappable input.
 *
 * @throws IOException when the bytes are not valid UTF-8.
 */
internal fun decodeStrictUtf8(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw IOException("Text is not valid UTF-8")
    }
}
