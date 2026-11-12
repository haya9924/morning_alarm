package com.example.morningalarm.summary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryHtmlTest {
    @Test
    fun `csp is injected into existing head`() {
        val html = "<!doctype html><html><head><title>x</title></head><body>hi</body></html>"
        val result = SummaryHtml.withCsp(html)

        assertTrue(result.contains("Content-Security-Policy"))
        assertTrue(result.indexOf("Content-Security-Policy") < result.indexOf("</head>"))
        assertTrue(result.indexOf("<head>") < result.indexOf("Content-Security-Policy"))
    }

    @Test
    fun `csp is injected after html tag when head is absent`() {
        val result = SummaryHtml.withCsp("<html lang=\"ja\"><body>hi</body></html>")
        assertTrue(result.startsWith("<html lang=\"ja\">"))
        assertTrue(result.contains("Content-Security-Policy"))
    }

    @Test
    fun `csp is prepended when no html or head tag exists`() {
        val result = SummaryHtml.withCsp("<p>hi</p>")
        assertTrue(result.startsWith("<meta http-equiv=\"Content-Security-Policy\""))
    }

    @Test
    fun `date placeholder is replaced and escaped`() {
        val result = SummaryHtml.render("<p>{{date}}</p>", "2026/03/10 <script>")
        assertTrue(result.contains("2026/03/10 &lt;script&gt;"))
        assertFalse(result.contains("<script>alert"))
        assertFalse(result.contains("{{date}}"))
        assertTrue(result.contains("script-src 'none'"))
    }

    @Test
    fun `escapeHtml covers special characters`() {
        val escaped = SummaryHtml.escapeHtml("&<>\"'")
        assertTrue(escaped.contains("&amp;"))
        assertTrue(escaped.contains("&lt;"))
        assertTrue(escaped.contains("&gt;"))
        assertTrue(escaped.contains("&quot;"))
        assertTrue(escaped.contains("&#39;"))
    }

    @Test
    fun `csp forbids network and scripts`() {
        val result = SummaryHtml.withCsp("<html><head></head><body></body></html>")
        assertTrue(result.contains("default-src 'none'"))
        assertTrue(result.contains("script-src 'none'"))
        assertTrue(result.contains("connect-src 'none'"))
        assertTrue(result.contains("img-src data:"))
    }
}
