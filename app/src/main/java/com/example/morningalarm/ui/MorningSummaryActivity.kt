package com.example.morningalarm.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.summary.SummaryHtml
import com.example.morningalarm.ui.theme.MorningAlarmTheme
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Displays the user-editable local morning summary after the alarm is stopped.
 *
 * Hardening summary (all applied together):
 * - JavaScript, DOM storage, database, geolocation, file/content access disabled.
 * - Network loads disabled and the app has no INTERNET permission.
 * - All navigations and non-data subresource requests are blocked.
 * - HTML is loaded with a null base URL, never file://.
 * - A restrictive CSP is injected by SummaryHtml.
 */
class MorningSummaryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MorningAlarmApp
        val rawHtml = app.summaryRepository.read()
        val dateText = DATE_FORMAT.format(ZonedDateTime.now())
        val html = SummaryHtml.render(rawHtml, dateText)

        setContent {
            MorningAlarmTheme {
                SummaryScreen(
                    html = html,
                    onEdit = {
                        startActivity(Intent(this, SummaryEditorActivity::class.java))
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy年M月d日 (E) HH:mm", Locale.JAPAN)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryScreen(
    html: String,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("朝のまとめ") },
                actions = {
                    TextButton(onClick = onEdit) { Text("編集") }
                    TextButton(onClick = onClose) { Text("閉じる") }
                },
            )
        },
    ) { contentPadding ->
        SummaryWebView(
            html = html,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
    }
}

@Composable
private fun SummaryWebView(
    html: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember { createHardenedWebView(context, html) }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}

private fun createHardenedWebView(context: Context, html: String): WebView {
    WebView.setWebContentsDebuggingEnabled(false)

    return WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)

        settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            blockNetworkLoads = true
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadsImagesAutomatically = true
        }

        webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = true

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val scheme = request?.url?.scheme?.lowercase(Locale.ROOT)
                if (scheme == null || scheme == "data" || scheme == "about") return null
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
        }

        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
