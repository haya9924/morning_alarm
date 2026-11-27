package com.example.morningalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.morningalarm.trace.TraceDifficulty

@Composable
fun TraceSettingsCard(difficulty: TraceDifficulty, onChange: (TraceDifficulty) -> Unit, onPreview: () -> Unit) {
    var draft by remember(difficulty) { mutableFloatStateOf(difficulty.tolerance) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("図形の判定設定", style = MaterialTheme.typography.titleMedium)
            Text("全アラーム共通。右に動かすほど、線からのズレをより許容します。")
            Text("許容幅：${String.format(Locale.JAPAN, "%.2f", draft * 100f)}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = {
                    try {
                        val selected = TraceDifficulty.fromTolerance(draft)
                        if (selected != difficulty) onChange(selected)
                    } finally {
                        draft = difficulty.tolerance
                    }
                },
                valueRange = TraceDifficulty.MIN_TOLERANCE..TraceDifficulty.MAX_TOLERANCE,
                steps = 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "図形判定の許容幅" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("厳しめ", style = MaterialTheme.typography.bodySmall)
                Text("やさしめ", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
                Text("朝アラームをプレビュー")
            }
            Text("音は鳴りません。波の演出と図形の判定を試せます。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
