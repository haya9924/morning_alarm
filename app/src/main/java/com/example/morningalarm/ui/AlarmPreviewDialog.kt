package com.example.morningalarm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.morningalarm.trace.TraceDifficulty
import com.example.morningalarm.ui.theme.MorningAlarmTheme

/** Only local UI state. Never connects to a service, alarm, notification or scheduler. */
@Composable
fun AlarmPreviewDialog(difficulty: TraceDifficulty, onDismiss: () -> Unit) {
    var success by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MorningAlarmTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize()) {
                key(attempt) {
                    AlarmRingScreen(alarm = null, active = true, snoozedUntil = null,
                        challengeKey = "preview", onSnooze = {}, onTrace = { success = true },
                        onClose = onDismiss, difficulty = difficulty, preview = true)
                }
                if (success) AlertDialog(onDismissRequest = { success = false; attempt++ },
                    title = { Text("トレース成功") },
                    text = { Text("プレビューです。実際のアラームやスヌーズは変更していません。") },
                    confirmButton = { TextButton(onClick = { success = false; attempt++ }) { Text("もう一度試す") } },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
            }
        }
    }
}
