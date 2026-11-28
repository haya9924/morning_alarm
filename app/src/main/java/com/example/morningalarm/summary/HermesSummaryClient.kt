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

/**
 * Opt-in client for a Hermes-hosted morning summary.
 *
 * Accepted endpoints:
 * - `https://` for any valid host (default port 443 is elided).
 * - `http://` only for Tailscale endpoints: CGNAT IPv4 `100.64.0.0/10`, ULA
 *   IPv6 `fd7a:115c:a1e0::/48`, or a MagicDNS `*.ts.net` name. Plain HTTP is
 *   otherwise rejected, so cleartext is never sent to arbitrary hosts.
 *
 * No userinfo, query, fragment, or malformed host is accepted. The dedicated
 * bearer token is sent only on the request and is never included in failures or
 * logs. Redirects are not followed, the platform default TLS configuration is
 * used for HTTPS, and both timeouts and the streamed body size are bounded.
 *
 * [fetch] is blocking; callers are responsible for using Dispatchers.IO.
 */
class HermesSummaryClient internal constructor(
    private val connectionFactory: (String) -> HttpURLConnection,
) {
    /**
     * Production client: re-validates the (already normalized) endpoint and
     * opens a real connection for it. The endpoint policy lives here as well as
     * in [Config.parse] so a directly constructed [Config] cannot make the app
     * talk cleartext to a non-Tailscale host.
     */
    constructor() : this({ endpoint ->
        if (normalizeSummaryEndpoint(endpoint) != endpoint) {
            throw IOException("Invalid summary endpoint")
        }
        URL(endpoint).openConnection() as? HttpURLConnection
            ?: throw IOException("Invalid summary endpoint")
    })

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
     * The endpoint is expected to come from [Config.parse], which enforces the
     * HTTPS / Tailscale-only-HTTP policy; the default connection factory
     * re-applies that policy, while an injected factory (unit tests) is called
     * as-is. The token is re-checked here so a directly constructed [Config]
     * cannot smuggle a malformed credential.
     *
     * @throws IOException on an invalid token, network/TLS failure, non-200
     * response, redirect, wrong content type, oversized body, invalid UTF-8, or
     * a blank body. Error messages never contain the URL or token.
     */
    fun fetch(config: Config): String {
        if (!SUMMARY_TOKEN_REGEX.matches(config.token)) {
            throw IOException("Invalid summary configuration")
        }

        val connection = try {
            connectionFactory(config.endpoint)
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
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
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
private const val TS_NET_SUFFIX = ".ts.net"

/** First 6 bytes (48 bits) of the Tailscale IPv6 ULA prefix fd7a:115c:a1e0::/48. */
private val TAILSCALE_IPV6_PREFIX = byteArrayOf(
    0xfd.toByte(),
    0x7a,
    0x11,
    0x5c,
    0xa1.toByte(),
    0xe0.toByte(),
)

private const val HTTPS_DEFAULT_PORT = 443
private const val HTTP_DEFAULT_PORT = 80

/**
 * Returns a canonical URL, or null for anything that is not an exact
 * server-based endpoint without userinfo/query/fragment and with a valid host.
 *
 * `https://` accepts any valid host. `http://` is accepted only when the host is
 * a Tailscale CGNAT IPv4 address (100.64.0.0/10), a Tailscale IPv6 ULA address
 * (fd7a:115c:a1e0::/48), or a `*.ts.net` MagicDNS name; every other cleartext
 * endpoint is rejected. The path is preserved (empty becomes "/"), the host is
 * lowercased, and the scheme default port (443/80) is elided.
 */
private fun normalizeSummaryEndpoint(raw: String): String? {
    if (raw.isEmpty() || raw.any { it.isWhitespace() || it.code < 0x20 }) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.isAbsolute || uri.isOpaque) return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "https" && scheme != "http") return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val authority = uri.rawAuthority
    if (authority.isNullOrEmpty() || authority.contains('@')) return null

    val host = uri.host ?: return null
    val cleartext = scheme == "http"
    val normalizedHost = if (cleartext) {
        normalizeTailscaleHost(host) ?: return null
    } else {
        normalizeSummaryHost(host) ?: return null
    }
    val port = uri.port
    if (port != -1 && port !in 1..65535) return null

    val rawPath = uri.rawPath ?: return null
    val path = if (rawPath.isEmpty()) "/" else rawPath
    if (!path.startsWith("/")) return null

    val defaultPort = if (cleartext) HTTP_DEFAULT_PORT else HTTPS_DEFAULT_PORT
    return buildString {
        append(scheme)
        append("://")
        append(normalizedHost)
        if (port != -1 && port != defaultPort) {
            append(':')
            append(port)
        }
        append(path)
    }
}

/**
 * Returns the canonical host when it is a Tailscale endpoint, or null otherwise.
 *
 * Only `100.64.0.0/10`, `fd7a:115c:a1e0::/48` (verified on the parsed address
 * bytes, not just the text), and `*.ts.net` names are accepted. Bare `ts.net`,
 * single-label names, and look-alikes such as `not-ts.net` are rejected.
 */
private fun normalizeTailscaleHost(host: String): String? {
    val lower = host.lowercase(Locale.ROOT)
    if (lower.length !in 1..253) return null

    if (lower.startsWith("[")) {
        val address = parseBracketedIpv6(lower) ?: return null
        if (!address.address.hasPrefix(TAILSCALE_IPV6_PREFIX)) return null
        return lower
    }

    if (lower.contains(':')) return null

    if (lower.length > TS_NET_SUFFIX.length && lower.endsWith(TS_NET_SUFFIX)) {
        return normalizeDnsLabels(lower)
    }

    val octets = parseStrictIpv4(lower) ?: return null
    if (octets[0] != 100 || octets[1] !in 64..127) return null
    return lower
}

/**
 * Parses a dotted-quad IPv4 address strictly: exactly four decimal octets in
 * 0..255 with no leading zeros (only "0" itself may start with a zero).
 */
private fun parseStrictIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (index in 0 until 4) {
        val part = parts[index]
        if (part.isEmpty() || part.length > 3) return null
        if (part.length > 1 && part[0] == '0') return null
        if (!part.all { it in '0'..'9' }) return null
        val value = part.toInt()
        if (value !in 0..255) return null
        octets[index] = value
    }
    return octets
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}

private fun normalizeSummaryHost(host: String): String? {
    val lower = host.lowercase(Locale.ROOT)
    if (lower.length !in 1..253) return null

    if (lower.startsWith("[")) {
        parseBracketedIpv6(lower) ?: return null
        return lower
    }

    if (lower.contains(':')) return null
    return normalizeDnsLabels(lower)
}

private fun parseBracketedIpv6(lower: String): Inet6Address? {
    if (!lower.endsWith("]")) return null
    val inner = lower.substring(1, lower.length - 1)
    if (inner.isEmpty() || !inner.contains(':') || !IPV6_CHARS_REGEX.matches(inner)) {
        return null
    }
    return runCatching { InetAddress.getByName(inner) }.getOrNull() as? Inet6Address
}

private fun normalizeDnsLabels(lower: String): String? {
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
