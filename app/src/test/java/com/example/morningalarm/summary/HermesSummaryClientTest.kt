package com.example.morningalarm.summary

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesSummaryClientTest {
    private val token = "A".repeat(43)

    @Test fun acceptsAndNormalizesHttpsEndpoint() {
        val config = HermesSummaryClient.Config.parse("HTTPS://Example.COM:443/v1/morning-summary", token).getOrThrow()
        assertEquals("https://example.com/v1/morning-summary", config.endpoint)
        assertFalse(config.toString().contains(token))
    }

    @Test fun rejectsUnsafeEndpointAndInvalidToken() {
        listOf(
            "http://example.com/", "https://example.com@other.test/", "https://example.com/a?b",
            "https://example.com/#a", "https:///v1/morning-summary", "https://example.com:0/",
            "https://bad_host/", " https://example.com/",
        ).forEach { assertTrue(it, HermesSummaryClient.Config.parse(it, token).isFailure) }
        assertTrue(HermesSummaryClient.Config.parse("https://example.com/", "password").isFailure)
    }

    @Test fun acceptsTailscaleHttpEndpoints() {
        listOf(
            "http://100.64.0.1" to "http://100.64.0.1/",
            "http://100.64.0.1:80/v1/morning-summary" to "http://100.64.0.1/v1/morning-summary",
            "http://100.127.255.254:3647/v1/morning-summary" to "http://100.127.255.254:3647/v1/morning-summary",
            "HTTP://100.64.0.1:3647/v1/morning-summary" to "http://100.64.0.1:3647/v1/morning-summary",
            "http://[FD7A:115C:A1E0::1]:3647/v1/morning-summary" to "http://[fd7a:115c:a1e0::1]:3647/v1/morning-summary",
            "http://host.tailnet.ts.net:3647/v1/morning-summary" to "http://host.tailnet.ts.net:3647/v1/morning-summary",
            "http://TS.NET.example.ts.net/" to "http://ts.net.example.ts.net/",
        ).forEach { (raw, expected) ->
            assertEquals(raw, expected, HermesSummaryClient.Config.parse(raw, token).getOrThrow().endpoint)
        }
    }

    @Test fun rejectsNonTailscaleHttpEndpoints() {
        listOf(
            "http://example.com/",
            "http://100.63.255.255/",
            "http://100.128.0.0/",
            "http://100.64.0.999/",
            "http://100.64.0.01/",
            "http://0100.64.0.1/",
            "http://192.168.1.1/",
            "http://127.0.0.1/",
            "http://localhost/",
            "http://myhost/",
            "http://ts.net/",
            "http://not-ts.net/",
            "http://[fd7a:115c:a1e1::1]/",
            "http://[::1]/",
            "http://0.0.0.0/",
        ).forEach { assertTrue(it, HermesSummaryClient.Config.parse(it, token).isFailure) }
    }

    @Test fun rejectsInvalidTokenEvenOverTailscaleHttp() {
        assertTrue(HermesSummaryClient.Config.parse("http://100.64.0.1/", "password").isFailure)
        assertTrue(HermesSummaryClient.Config.parse("http://100.64.0.1/", "").isFailure)
    }

    @Test fun fetchesHtmlOverLoopbackHttp() {
        val authorization = AtomicReference<String?>(null)
        withServer(
            handler = { request ->
                authorization.set(request.headers["authorization"])
                HttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
                    body = "朝のまとめ".toByteArray(Charsets.UTF_8),
                )
            },
            block = { base ->
                val client = HermesSummaryClient(::httpConnection)
                val config = HermesSummaryClient.Config(base.endpoint(ROUTE), token)
                assertEquals("朝のまとめ", client.fetch(config))
            },
        )
        assertEquals("Bearer $token", authorization.get())
    }

    @Test fun rejectsRedirectOverHttp() {
        withServer(
            handler = {
                HttpResponse(status = 302, headers = mapOf("Location" to "/elsewhere"))
            },
            block = { base ->
                val client = HermesSummaryClient(::httpConnection)
                val error = assertFailure { client.fetch(HermesSummaryClient.Config(base.endpoint(ROUTE), token)) }
                assertTrue(error.message.orEmpty().contains("redirect", ignoreCase = true))
                assertFalse(error.message.orEmpty().contains(token))
            },
        )
    }

    @Test fun rejectsNonOkStatusOverHttp() {
        withServer(
            handler = {
                HttpResponse(status = 500, headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
            },
            block = { base ->
                val client = HermesSummaryClient(::httpConnection)
                val error = assertFailure { client.fetch(HermesSummaryClient.Config(base.endpoint(ROUTE), token)) }
                assertFalse(error.message.orEmpty().contains(token))
            },
        )
    }

    @Test fun rejectsNonHtmlResponseOverHttp() {
        withServer(
            handler = {
                HttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = "plain".toByteArray(Charsets.UTF_8),
                )
            },
            block = { base ->
                val client = HermesSummaryClient(::httpConnection)
                assertFailure { client.fetch(HermesSummaryClient.Config(base.endpoint(ROUTE), token)) }
            },
        )
    }

    @Test fun productionClientRejectsNonTailscaleHttpOnFetch() {
        val client = HermesSummaryClient()
        assertFailure { client.fetch(HermesSummaryClient.Config("http://127.0.0.1:1$ROUTE", token)) }
        assertFailure { client.fetch(HermesSummaryClient.Config("http://example.com$ROUTE", token)) }
    }

    @Test fun rejectsMalformedTokenBeforeConnecting() {
        val attempted = AtomicReference<String?>(null)
        val client = HermesSummaryClient { endpoint ->
            attempted.set(endpoint)
            throw IOException("should not connect")
        }
        assertFailure { client.fetch(HermesSummaryClient.Config("http://127.0.0.1:1$ROUTE", "password")) }
        assertNull(attempted.get())
    }

    @Test fun decodesJapaneseUtf8() {
        assertEquals("朝のまとめ", decodeStrictUtf8("朝のまとめ".toByteArray(Charsets.UTF_8)))
    }

    @Test(expected = IOException::class)
    fun rejectsMalformedUtf8() {
        decodeStrictUtf8(byteArrayOf(0xc3.toByte(), 0x28))
    }

    /** Plain HttpURLConnection factory: the injected factory may target loopback. */
    private fun httpConnection(endpoint: String): HttpURLConnection =
        URL(endpoint).openConnection() as HttpURLConnection

    private fun assertFailure(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        throw AssertionError("Expected IOException")
    }

    /** Runs [handler] on a minimal loopback HTTP/1.1 ServerSocket for the duration of [block]. */
    private fun withServer(handler: (HttpRequest) -> HttpResponse, block: (ServerBase) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    reader.readLine() // request line
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                                line.substring(colon + 1).trim()
                        }
                    }
                    val response = handler(HttpRequest(headers))
                    val out = socket.getOutputStream()
                    val head = buildString {
                        append("HTTP/1.1 ").append(response.status).append(" Test\r\n")
                        append("Connection: close\r\n")
                        append("Content-Length: ").append(response.body.size).append("\r\n")
                        response.headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                        append("\r\n")
                    }
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(response.body)
                    out.flush()
                }
            } catch (_: IOException) {
                // Socket closed during teardown.
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            block(ServerBase(server.localPort))
        } finally {
            server.close()
            worker.join(2_000)
        }
    }

    private class HttpRequest(val headers: Map<String, String>)

    private class HttpResponse(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    private data class ServerBase(val port: Int) {
        fun endpoint(path: String): String = "http://127.0.0.1:$port$path"
    }

    private companion object {
        const val ROUTE = "/v1/morning-summary"
    }
}
