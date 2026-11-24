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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.morningalarm.data.Alarm
import com.example.morningalarm.data.RepeatDays
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    alarms: List<Alarm>,
    exactAlarmAllowed: Boolean,
    notificationsAllowed: Boolean,
    fullScreenIntentAllowed: Boolean,
    now: ZonedDateTime,
    traceDifficulty: com.example.morningalarm.trace.TraceDifficulty,
    onTraceDifficultyChange: (com.example.morningalarm.trace.TraceDifficulty) -> Unit,
    onPreviewAlarm: () -> Unit,
    onAddAlarm: (hour: Int, minute: Int, label: String, days: Set<DayOfWeek>) -> Unit,
    onEditAlarm: (Alarm, hour: Int, minute: Int, label: String, days: Set<DayOfWeek>) -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onDeleteAlarm: (Alarm) -> Unit,
    onSkipNext: (Alarm) -> Unit,
    onSetNextTime: (Alarm, LocalTime?) -> Unit,
    onClearNextAdjustment: (Alarm) -> Unit,
    onEditSummary: () -> Unit,
    onPreviewSummary: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestFullScreenIntent: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Alarm?>(null) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var changingNextTime by remember { mutableStateOf<Alarm?>(null) }

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
            item { TraceSettingsCard(traceDifficulty, onTraceDifficultyChange, onPreviewAlarm) }
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
                        text = "アラーム",
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
                                "右下の + から、時刻と曜日を指定してアラームを追加できます。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            items(items = alarms, key = { it.id }) { alarm ->
                AlarmRow(
                    alarm = alarm,
                    now = now,
                    onToggle = { enabled -> onToggleAlarm(alarm, enabled) },
                    onEdit = { editing = alarm },
                    onSkipNext = { onSkipNext(alarm) },
                    onChangeNextTime = { changingNextTime = alarm },
                    onClearAdjustment = { onClearNextAdjustment(alarm) },
                    onDelete = { pendingDelete = alarm },
                )
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddDialog) {
        AlarmEditDialog(
            alarm = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { hour, minute, label, days ->
                onAddAlarm(hour, minute, label, days)
                showAddDialog = false
            },
        )
    }

    editing?.let { alarm ->
        AlarmEditDialog(
            alarm = alarm,
            onDismiss = { editing = null },
            onConfirm = { hour, minute, label, days ->
                onEditAlarm(alarm, hour, minute, label, days)
                editing = null
            },
        )
    }

    changingNextTime?.let { alarm ->
        NextTimeDialog(
            alarm = alarm,
            onDismiss = { changingNextTime = null },
            onConfirm = { time ->
                onSetNextTime(alarm, time)
                changingNextTime = null
            },
        )
    }

    pendingDelete?.let { alarm ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("アラームを削除") },
            text = { Text("${AlarmText.formatTime(alarm.hour, alarm.minute)} のアラームを削除しますか？") },
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
    now: ZonedDateTime,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onSkipNext: () -> Unit,
    onChangeNextTime: () -> Unit,
    onClearAdjustment: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 18.dp, top = 14.dp, end = 8.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = AlarmText.formatTime(alarm.hour, alarm.minute),
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
                        text = AlarmText.repeatSummary(alarm.repeat),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (alarm.enabled) {
                            AlarmText.nextOccurrenceText(alarm, now) ?: "次回の予定はありません"
                        } else {
                            "停止中"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AlarmText.nextAdjustmentText(alarm)?.let { adjustment ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = adjustment,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit) { Text("編集") }
                TextButton(onClick = onSkipNext) { Text("次回スキップ") }
                TextButton(onClick = onChangeNextTime) { Text("次回時刻") }
                if (alarm.hasNextAdjustment) {
                    TextButton(onClick = onClearAdjustment) { Text("解除") }
                }
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

/**
 * Add/edit dialog. [alarm] is null when adding a new alarm. Repeat days default to
 * every day, and an emptied selection is persisted as "every day" so an alarm can
 * never end up scheduled never.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditDialog(
    alarm: Alarm?,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, label: String, days: Set<DayOfWeek>) -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = alarm?.hour ?: 7,
        initialMinute = alarm?.minute ?: 0,
        is24Hour = true,
    )
    var label by remember { mutableStateOf(alarm?.label.orEmpty()) }
    var days by remember { mutableStateOf(RepeatDays.normalize(alarm?.repeatDays ?: RepeatDays.EVERY_DAY)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alarm == null) "アラームを追加" else "アラームを編集") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = timeState)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        days.containsAll(RepeatDays.EVERY_DAY) -> "繰り返し: 毎日"
                        days.isEmpty() -> "繰り返し: 毎日（曜日を選ぶと絞り込めます）"
                        else -> "繰り返し: ${AlarmText.repeatSummary(days)}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                DayOfWeekSelector(
                    selected = days,
                    // Toggling the last selected day restores "every day"; it never
                    // collapses to an empty (= same as every day) selection, which
                    // made individual days impossible to re-select.
                    onToggle = { day -> days = AlarmText.toggledDays(days, day) },
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(onClick = { days = RepeatDays.EVERY_DAY }) { Text("毎日") }
                    FilledTonalButton(onClick = { days = RepeatDays.WEEKDAYS }) { Text("平日") }
                    FilledTonalButton(onClick = { days = RepeatDays.WEEKEND }) { Text("土日") }
                }
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
            Button(
                onClick = {
                    onConfirm(
                        timeState.hour,
                        timeState.minute,
                        label,
                        RepeatDays.normalize(days),
                    )
                },
            ) {
                Text(if (alarm == null) "追加" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/** Seven chips in repeat order (Mon..Sun). */
@Composable
private fun DayOfWeekSelector(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
) {
    val normalized = RepeatDays.normalize(selected)
    RepeatDays.ORDERED.chunked(4).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { day ->
                FilterChip(
                    selected = day in normalized,
                    onClick = { onToggle(day) },
                    label = { Text(AlarmText.dayLabel(day)) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** One-shot "change next time only" dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NextTimeDialog(
    alarm: Alarm,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime?) -> Unit,
) {
    val initial = alarm.nextTimeOverrideAt?.toLocalTime()
        ?: LocalTime.of(alarm.hour, alarm.minute)
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    var clear by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("次回のみ時刻を変更") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val target = AlarmText.nextOccurrenceText(alarm, ZonedDateTime.now())
                Text(
                    buildString {
                        append("次に鳴る1回だけ時刻を変更します。")
                        if (target != null) append("対象: $target。")
                        append("以降は通常の ${AlarmText.formatTime(alarm.hour, alarm.minute)} に戻ります。")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TimePicker(state = timeState)
                if (alarm.nextTimeOverrideAt != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { clear = true }) { Text("次回時刻の変更を解除") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (clear) {
                        onConfirm(null)
                    } else {
                        onConfirm(LocalTime.of(timeState.hour, timeState.minute))
                    }
                },
            ) {
                Text(if (clear) "解除" else "設定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
