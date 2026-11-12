package com.example.morningalarm.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.alarm.AlarmService
import com.example.morningalarm.alarm.AlarmNotifier
import com.example.morningalarm.data.Alarm
import com.example.morningalarm.data.AlarmRepository
import com.example.morningalarm.ui.theme.MorningAlarmTheme
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * Full-screen ringing UI. Playback is owned by [AlarmService]; this Activity only
 * displays the alarm and forwards user actions to the service. Closing or recreating
 * the Activity therefore does not silence the alarm.
 */
class AlarmRingActivity : ComponentActivity() {
    private lateinit var repository: AlarmRepository

    private var currentAlarmId by mutableStateOf(-1L)
    private var currentAlarm by mutableStateOf<Alarm?>(null)
    private var stopping by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        repository = (application as MorningAlarmApp).repository
        handleIntent(intent)

        setContent {
            MorningAlarmTheme(darkTheme = true) {
                val alarm = currentAlarm
                val ringingId by AlarmService.ringingAlarmId.collectAsStateWithLifecycle()
                var hasObservedRinging by remember(currentAlarmId) { mutableStateOf(false) }

                LaunchedEffect(ringingId, currentAlarmId) {
                    val id = currentAlarmId
                    if (id > 0L && ringingId == id) {
                        hasObservedRinging = true
                    } else if (hasObservedRinging && ringingId == null && !stopping) {
                        // Stopped from the notification (or elsewhere) while this UI
                        // was visible. Match the in-app stop behavior.
                        startActivity(
                            Intent(this@AlarmRingActivity, MorningSummaryActivity::class.java),
                        )
                        finish()
                    }
                }

                RingScreen(
                    alarm = alarm,
                    onStop = ::stopAndShowSummary,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleIntent(intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) return

        currentAlarmId = alarmId
        currentAlarm = repository.get(alarmId)

        // The receiver normally starts the service first. This also repairs the case
        // where the process/service was killed while the notification remained.
        runCatching { AlarmService.start(this, alarmId) }
    }

    private fun stopAndShowSummary() {
        if (stopping) return
        stopping = true

        if (currentAlarmId > 0L) {
            AlarmService.stop(this, currentAlarmId)
            AlarmNotifier.cancel(this, currentAlarmId)
        }
        startActivity(Intent(this, MorningSummaryActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}

@Composable
private fun RingScreen(
    alarm: Alarm?,
    onStop: () -> Unit,
) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000L)
        }
    }

    BackHandler(enabled = true) {
        // Prevent an accidental back press from silencing the alarm.
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "アラーム",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = formatTime(now.hour, now.minute),
                fontSize = 76.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (alarm != null) {
                Spacer(Modifier.height(8.dp))
                if (alarm.label.isNotBlank()) {
                    Text(
                        text = alarm.label,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = "設定時刻 ${formatTime(alarm.hour, alarm.minute)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                Text("停止", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "停止すると朝のまとめを表示します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatTime(hour: Int, minute: Int): String =
    String.format(java.util.Locale.JAPAN, "%02d:%02d", hour, minute)
