package com.example.morningalarm.ui

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.alarm.AlarmService
import com.example.morningalarm.alarm.SnoozeScheduler
import com.example.morningalarm.data.Alarm
import com.example.morningalarm.trace.Point
import com.example.morningalarm.ui.theme.MorningAlarmTheme
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** A view of the service, never an owner of playback or an unverified stop path. */
class AlarmRingActivity : ComponentActivity() {
    private var alarmId by mutableStateOf(-1L)
    private var alarm by mutableStateOf<Alarm?>(null)
    private var openedToken by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_ALARM
        handleIntent(intent)
        setContent {
            MorningAlarmTheme(darkTheme = true) {
                val ringingId by AlarmService.ringingAlarmId.collectAsStateWithLifecycle()
                val token by AlarmService.activeToken.collectAsStateWithLifecycle()
                val dismissed by AlarmService.dismissedToken.collectAsStateWithLifecycle()
                val snoozedUntil by AlarmService.snoozedUntil.collectAsStateWithLifecycle()
                LaunchedEffect(token, ringingId) {
                    if (ringingId == alarmId && token.isNotEmpty()) openedToken = token
                    else SnoozeScheduler.read(this@AlarmRingActivity)?.let { if (it.alarmId == alarmId) openedToken = it.token }
                }
                LaunchedEffect(dismissed) {
                    if (openedToken.isNotEmpty() && dismissed == openedToken) {
                        startActivity(Intent(this@AlarmRingActivity, MorningSummaryActivity::class.java))
                        finish()
                    }
                }
                val difficulty by (application as MorningAlarmApp).traceSettings.difficulty.collectAsStateWithLifecycle()
                BackHandler(ringingId == alarmId) { }
                AlarmRingScreen(
                    difficulty = difficulty,
                    alarm = alarm,
                    active = ringingId == alarmId,
                    snoozedUntil = snoozedUntil ?: SnoozeScheduler.read(this)?.dueAt,
                    challengeKey = openedToken,
                    onSnooze = { AlarmService.snooze(this, alarmId, token) },
                    onTrace = { AlarmService.dismissWithTrace(this, alarmId, openedToken, it) },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        alarm = (application as MorningAlarmApp).repository.get(alarmId)
        // Opening an old notification must not start playback or bypass a snooze.
        openedToken = if (AlarmService.ringingAlarmId.value == alarmId) AlarmService.activeToken.value else ""
    }

    companion object { const val EXTRA_ALARM_ID = "alarm_id" }
}
