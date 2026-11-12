package com.example.morningalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.morningalarm.MorningAlarmApp

/**
 * Fired by the exact alarm PendingIntent.
 *
 * The next occurrence is scheduled first, so disabling the alarm with Hermes while
 * it rings still prevents the next day's ring, while a normal stop never breaks the
 * daily series.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) {
            Log.w(TAG, "Ignoring alarm with invalid id=$alarmId")
            return
        }

        val app = context.applicationContext as MorningAlarmApp
        val alarm = app.repository.get(alarmId)
        if (alarm == null) {
            Log.w(TAG, "Alarm id=$alarmId no longer exists")
            app.scheduler.cancel(alarmId)
            return
        }

        if (!alarm.enabled) {
            Log.d(TAG, "Alarm id=$alarmId is disabled")
            app.scheduler.cancel(alarmId)
            return
        }

        app.scheduler.schedule(alarm)
        runCatching {
            AlarmService.start(context, alarm.id)
        }.onFailure {
            // Some OEM/OS combinations can reject a background FGS start. Preserve
            // at least the notification/full-screen path in that case.
            Log.e(TAG, "Failed to start AlarmService; falling back to notification", it)
            AlarmNotifier.show(context, alarm)
        }
        Log.i(TAG, "Alarm fired id=$alarmId")
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.example.morningalarm.action.FIRE_ALARM"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}
