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
import androidx.compose.ui.unit.dp
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.data.SummaryRepository
import com.example.morningalarm.ui.theme.MorningAlarmTheme

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
        repository = (application as MorningAlarmApp).summaryRepository
        val initialHtml = repository.read()

        setContent {
            MorningAlarmTheme {
                EditorScreen(
                    initialHtml = initialHtml,
                    onSave = ::saveHtml,
                    onPreview = {
                        if (saveHtml(it)) {
                            startActivity(Intent(this, MorningSummaryActivity::class.java))
                        }
                    },
                    onReset = { repository.reset() },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    initialHtml: String,
    onSave: (String) -> Boolean,
    onPreview: (String) -> Unit,
    onReset: () -> String,
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
