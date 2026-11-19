package com.example.morningalarm.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.data.SummaryRepository
import com.example.morningalarm.ui.theme.MorningAlarmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for the local HTML that is shown after stopping an alarm.
 *
 * This screen only edits app-private HTML; it does not execute it. Preview opens the
 * hardened MorningSummaryActivity.
 */
class SummaryEditorActivity : ComponentActivity() {
    private lateinit var repository: SummaryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MorningAlarmApp
        repository = app.summaryRepository
        val initialHtml = repository.read()
        val sync = app.hermesSummarySync
        var connected by mutableStateOf(sync.currentConfig() != null)
        var syncing by mutableStateOf(false)
        var syncMessage by mutableStateOf("")

        setContent {
            MorningAlarmTheme {
                EditorScreen(
                    initialHtml = initialHtml,
                    onSave = ::saveHtml,
                    onPreview = {
                        if (saveHtml(it)) {
                            startActivity(Intent(this, MorningSummaryActivity::class.java)
                                .putExtra(MorningSummaryActivity.EXTRA_PREVIEW_LOCAL, true))
                        }
                    },
                    onReset = { repository.reset() },
                    hermes = {
                        HermesSettings(
                            connected = connected,
                            syncing = syncing,
                            message = syncMessage,
                            onConnect = { url, token ->
                                syncing = true
                                lifecycleScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        if (!sync.configure(url, token)) null else sync.refresh()
                                    }
                                    connected = sync.currentConfig() != null
                                    syncMessage = when (result) {
                                        null -> "HTTPS URLとトークンを確認してください。設定できませんでした。"
                                        true -> "Hermesから取得しました。"
                                        false -> "設定しましたが取得できませんでした。接続先とトークンを確認してください。"
                                    }
                                    syncing = false
                                }
                            },
                            onSync = {
                                syncing = true
                                lifecycleScope.launch {
                                    syncMessage = if (withContext(Dispatchers.IO) { sync.refresh() }) {
                                        "Hermesから取得しました。"
                                    } else {
                                        "取得できませんでした。前回の内容を保持します。"
                                    }
                                    syncing = false
                                }
                            },
                            onDisconnect = {
                                syncing = true
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { sync.disconnect() }
                                    connected = sync.currentConfig() != null
                                    syncMessage = if (connected) "解除できませんでした。" else "連携を解除しました。"
                                    syncing = false
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    private fun saveHtml(html: String): Boolean {
        val result = repository.save(html)
        val message = result.fold(
            onSuccess = { "保存しました" },
            onFailure = { it.message ?: "保存に失敗しました" },
        )
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        return result.isSuccess
    }
}

@Composable
private fun HermesSettings(
    connected: Boolean,
    syncing: Boolean,
    message: String,
    onConnect: (String, String) -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = !syncing) {
        Text(if (syncing) "Hermesと通信中…" else if (connected) "Hermes連携：設定済み" else "Hermes連携を設定")
    }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (open) {
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Hermes連携") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("連携中は朝のまとめを開いたときに取得します。オフライン時は前回の内容を表示します。")
                    OutlinedTextField(url, { url = it }, label = { Text("HTTPS URL") }, singleLine = true)
                    OutlinedTextField(token, { token = it }, label = { Text("読み取りトークン") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (connected) {
                        TextButton(onClick = { open = false; onSync() }) { Text("今すぐ同期") }
                        TextButton(onClick = { open = false; onDisconnect() }) { Text("連携を解除") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank() && token.isNotBlank(),
                    onClick = { open = false; onConnect(url.trim(), token.trim()) },
                ) { Text("設定して同期") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("閉じる") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    initialHtml: String,
    onSave: (String) -> Boolean,
    onPreview: (String) -> Unit,
    onReset: () -> String,
    hermes: @Composable () -> Unit,
) {
    var html by remember(initialHtml) { mutableStateOf(initialHtml) }
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("朝のまとめを編集") },
                actions = {
                    TextButton(onClick = { onSave(html) }) { Text("保存") }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "このHTMLは端末内に保存されます。JavaScript・外部通信・ローカルファイル参照は無効化され、インラインCSSと data: 画像のみ利用できます。{{date}} は表示時の日時へ置き換わります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = html,
                onValueChange = { html = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                label = { Text("HTML") },
            )

            Text(
                text = "${html.toByteArray(Charsets.UTF_8).size / 1024} KiB / " +
                    "${SummaryRepository.MAX_BYTES / 1024} KiB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onSave(html) },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
                OutlinedButton(
                    onClick = { onPreview(html) },
                    modifier = Modifier.weight(1f),
                ) { Text("プレビュー") }
            }
            hermes()
            TextButton(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("初期HTMLに戻す") }
            Spacer(Modifier.height(2.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("初期HTMLに戻しますか？") },
            text = { Text("現在の内容は削除されます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        html = onReset()
                        confirmReset = false
                    },
                ) { Text("戻す") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("キャンセル") }
            },
        )
    }
}
