package com.example.morningalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.morningalarm.data.Alarm
import java.time.ZonedDateTime

/**
 * Schedules one-shot exact alarms. The receiver schedules the next day's occurrence
 * as soon as the current one fires, so a missed dismiss does not stop the series.
 *
 * On Android 12+ exact alarms require the SCHEDULE_EXACT_ALARM special app access.
 * If it is unavailable we fall back to an inexact alarm instead of crashing; the UI
 * shows a prompt to grant it.
 */
class AlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(alarm: Alarm) {
        if (!alarm.enabled) {
            cancel(alarm.id)
            return
        }

        val triggerAtMillis = NextOccurrence.after(
            alarm.hour,
            alarm.minute,
            ZonedDateTime.now(),
        ).toInstant().toEpochMilli()

        val operation = firePendingIntent(alarm.id)
        if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation,
                )
                Log.d(TAG, "Exact alarm scheduled id=${alarm.id} at=$triggerAtMillis")
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm permission lost; falling back", e)
            }
        }

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            operation,
        )
        Log.w(TAG, "Inexact fallback alarm scheduled id=${alarm.id} at=$triggerAtMillis")
    }

    fun scheduleAll(alarms: List<Alarm>) {
        alarms.forEach { alarm ->
            if (alarm.enabled) schedule(alarm) else cancel(alarm.id)
        }
    }

    fun cancel(alarmId: Long) {
        alarmManager.cancel(firePendingIntent(alarmId))
        Log.d(TAG, "Alarm cancelled id=$alarmId")
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun firePendingIntent(alarmId: Long): PendingIntent {
        val intent = Intent(appContext, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            appContext,
            requestCodeFor(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"

        /**
         * Stable 32-bit request code derived from the 64-bit alarm ID. This is also
         * used as the Notification ID basis.
         */
        fun requestCodeFor(alarmId: Long): Int =
            (alarmId xor (alarmId ushr 32)).toInt()

        fun isExactAlarmAllowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return am.canScheduleExactAlarms()
        }
    }
}
