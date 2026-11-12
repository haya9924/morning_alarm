package com.example.morningalarm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.alarm.AlarmNotifier
import com.example.morningalarm.alarm.AlarmScheduler
import com.example.morningalarm.data.AlarmRepository
import com.example.morningalarm.ui.theme.MorningAlarmTheme

class MainActivity : ComponentActivity() {
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler

    private var exactAlarmAllowed by mutableStateOf(true)
    private var notificationsAllowed by mutableStateOf(true)
    private var fullScreenIntentAllowed by mutableStateOf(true)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MorningAlarmApp
        repository = app.repository
        scheduler = app.scheduler

        setContent {
            MorningAlarmTheme {
                val alarms by repository.alarms.collectAsStateWithLifecycle()
                MainScreen(
                    alarms = alarms,
                    exactAlarmAllowed = exactAlarmAllowed,
                    notificationsAllowed = notificationsAllowed,
                    fullScreenIntentAllowed = fullScreenIntentAllowed,
                    onAddAlarm = { hour, minute, label ->
                        persistOrToast {
                            scheduler.schedule(repository.add(hour, minute, label))
                        }
                    },
                    onToggleAlarm = { alarm, enabled ->
                        persistOrToast {
                            val updated = repository.setEnabled(alarm.id, enabled)
                            if (updated != null) {
                                if (updated.enabled) {
                                    scheduler.schedule(updated)
                                } else {
                                    scheduler.cancel(updated.id)
                                }
                            }
                        }
                    },
                    onDeleteAlarm = { alarm ->
                        persistOrToast {
                            repository.delete(alarm.id)
                            scheduler.cancel(alarm.id)
                            AlarmNotifier.cancel(this, alarm.id)
                        }
                    },
                    onEditSummary = {
                        startActivity(Intent(this, SummaryEditorActivity::class.java))
                    },
                    onPreviewSummary = {
                        startActivity(Intent(this, MorningSummaryActivity::class.java))
                    },
                    onRequestExactAlarm = ::openExactAlarmSettings,
                    onRequestNotifications = ::requestNotificationPermission,
                    onRequestFullScreenIntent = ::openFullScreenIntentSettings,
                )
            }
        }

        refreshPermissionState()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        // Also repairs alarms after the user grants a permission in Settings.
        scheduler.scheduleAll(repository.alarms.value)
    }

    private fun refreshPermissionState() {
        exactAlarmAllowed = AlarmScheduler.isExactAlarmAllowed(this)
        notificationsAllowed = AlarmNotifier.canPostNotifications(this)
        fullScreenIntentAllowed = AlarmNotifier.canUseFullScreenIntent(this)
    }

    /** Persistence failures must not crash the UI or leave a half-applied change. */
    private fun persistOrToast(action: () -> Unit) {
        runCatching(action).onFailure { error ->
            Toast.makeText(
                this,
                "アラームを保存できませんでした: ${error.message ?: "不明なエラー"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_REQUESTED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openSettingsOrAppDetails(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            openSettingsOrAppDetails(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        }
    }

    private fun openSettingsOrAppDetails(action: String) {
        val packageUri = Uri.parse("package:$packageName")
        val opened = runCatching {
            startActivity(Intent(action, packageUri))
        }.isSuccess
        if (!opened) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
        }
    }

    private companion object {
        const val UI_PREFS = "morning_alarm_ui"
        const val KEY_NOTIFICATION_REQUESTED = "notification_permission_requested"
    }
}
