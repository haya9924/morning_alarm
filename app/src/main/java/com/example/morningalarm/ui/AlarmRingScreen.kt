package com.example.morningalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.morningalarm.data.Alarm
import com.example.morningalarm.trace.Point
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun AlarmRingScreen(
    alarm: Alarm?, active: Boolean, snoozedUntil: Long?, challengeKey: String,
    onSnooze: () -> Unit, onTrace: (List<Point>) -> Unit, onClose: () -> Unit,
    difficulty: com.example.morningalarm.trace.TraceDifficulty = com.example.morningalarm.trace.TraceDifficulty.DEFAULT,
    preview: Boolean = false,
) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1_000) } }
    Box(Modifier.fillMaxSize()) {
        WavyAlarmBackground(Modifier.fillMaxSize(), animated = active || preview)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (preview) TextButton(onClick = onClose) { Text("プレビューを閉じる（音は鳴りません）") }
            Text("M O R N I N G", color = Color(0xFF8DE1DA), fontSize = 13.sp)
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 64.sp,
                fontWeight = FontWeight.Light, color = Color.White)
            Text(alarm?.label?.ifBlank { "新しい朝を始めましょう" } ?: "アラーム",
                style = MaterialTheme.typography.titleLarge, color = Color.White)
            if (active) {
                Text("完全停止するには、開始点から図形を一筆でなぞってください。",
                    color = Color(0xFFCAD2E5), style = MaterialTheme.typography.bodyMedium)
                key(challengeKey) { TraceChallengeView(difficulty = difficulty, onCompleted = onTrace, modifier = Modifier.fillMaxWidth()) }
                Button(onClick = onSnooze, enabled = !preview, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("5分スヌーズ（一時停止）")
                }
                Text("スヌーズでは朝のまとめを開かず、5分後に再び鳴ります。",
                    color = Color(0xFFA8B3CF), style = MaterialTheme.typography.bodySmall)
            } else {
                val due = snoozedUntil?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm")) }
                Text(if (due != null) "$due に再び鳴ります" else "現在鳴動していません",
                    color = Color.White)
                if (due != null) {
                    Text("スヌーズを解除して完全停止するには図形をなぞってください。", color = Color(0xFFCAD2E5))
                    key(challengeKey) { TraceChallengeView(difficulty = difficulty, onCompleted = onTrace, modifier = Modifier.fillMaxWidth()) }
                }
                TextButton(onClick = onClose) { Text("画面を閉じる") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
