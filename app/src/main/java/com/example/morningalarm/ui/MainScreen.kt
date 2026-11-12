package com.example.morningalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.morningalarm.data.Alarm
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    alarms: List<Alarm>,
    exactAlarmAllowed: Boolean,
    notificationsAllowed: Boolean,
    fullScreenIntentAllowed: Boolean,
    onAddAlarm: (hour: Int, minute: Int, label: String) -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onDeleteAlarm: (Alarm) -> Unit,
    onEditSummary: () -> Unit,
    onPreviewSummary: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestFullScreenIntent: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Alarm?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("モーニングアラーム") },
                actions = {
                    TextButton(onClick = onEditSummary) { Text("朝のまとめ") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "アラームを追加")
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!notificationsAllowed) {
                item {
                    PermissionCard(
                        title = "通知の許可が必要です",
                        body = "Android 13 以降では、アラームの通知と全画面表示に通知の許可が必要です。",
                        button = "許可する",
                        onClick = onRequestNotifications,
                    )
                }
            }
            if (!exactAlarmAllowed) {
                item {
                    PermissionCard(
                        title = "正確なアラームの許可が必要です",
                        body = "Android 12 以降では、指定時刻どおりに鳴らすために「アラームとリマインダー」の許可が必要です。未許可の場合は近似時刻で鳴ります。",
                        button = "設定を開く",
                        onClick = onRequestExactAlarm,
                    )
                }
            }
            if (!fullScreenIntentAllowed) {
                item {
                    PermissionCard(
                        title = "全画面アラームの許可が必要です",
                        body = "Android 14 以降では、ロック画面でもアラーム画面を表示するために全画面通知の許可が必要です。",
                        button = "設定を開く",
                        onClick = onRequestFullScreenIntent,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "毎日のアラーム",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPreviewSummary) { Text("プレビュー") }
                }
            }

            if (alarms.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("アラームはまだありません", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "右下の + から、毎日鳴るアラームを追加できます。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            items(items = alarms, key = { it.id }) { alarm ->
                AlarmRow(
                    alarm = alarm,
                    onToggle = { enabled -> onToggleAlarm(alarm, enabled) },
                    onDelete = { pendingDelete = alarm },
                )
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddDialog) {
        AddAlarmDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { hour, minute, label ->
                onAddAlarm(hour, minute, label)
                showAddDialog = false
            },
        )
    }

    pendingDelete?.let { alarm ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("アラームを削除") },
            text = { Text("${formatTime(alarm.hour, alarm.minute)} のアラームを削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAlarm(alarm)
                        pendingDelete = null
                    },
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTime(alarm.hour, alarm.minute),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    color = if (alarm.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (alarm.label.isNotBlank()) {
                    Text(
                        text = alarm.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (alarm.enabled) "毎日・有効" else "停止中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAlarmDialog(
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, label: String) -> Unit,
) {
    val timeState = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = true)
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アラームを追加") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = timeState)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        if (it.length <= Alarm.MAX_LABEL_LENGTH) label = it
                    },
                    label = { Text("ラベル（任意）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(timeState.hour, timeState.minute, label) }) {
                Text("追加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

private fun formatTime(hour: Int, minute: Int): String =
    String.format(Locale.JAPAN, "%02d:%02d", hour, minute)
