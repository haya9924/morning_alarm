package com.example.morningalarm.summary

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun decodesJapaneseUtf8() {
        assertEquals("朝のまとめ", decodeStrictUtf8("朝のまとめ".toByteArray(Charsets.UTF_8)))
    }

    @Test(expected = IOException::class)
    fun rejectsMalformedUtf8() {
        decodeStrictUtf8(byteArrayOf(0xc3.toByte(), 0x28))
    }
}
