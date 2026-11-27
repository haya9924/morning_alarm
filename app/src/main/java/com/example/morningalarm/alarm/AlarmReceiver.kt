package com.example.morningalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.data.Alarm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fired by the exact alarm PendingIntent.
 *
 * The next occurrence is scheduled first, so disabling the alarm with Hermes while
 * it rings still prevents the next ring, while a normal stop never breaks the
 * series.
 *
 * One-shot adjustments ("skip next", "change next time only") are consumed here,
 * exactly once, right after the occurrence fired. Consumed state is persisted before
 * the next alarm is registered so a crash or reboot cannot replay a skip.
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
        val scheduled = app.repository.get(alarmId)
        if (scheduled == null) {
            Log.w(TAG, "Alarm id=$alarmId no longer exists")
            app.scheduler.cancel(alarmId)
            return
        }

        if (!scheduled.enabled) {
            Log.d(TAG, "Alarm id=$alarmId is disabled")
            app.scheduler.cancel(alarmId)
            return
        }

        val consumed = app.repository.consumeAfterFire(
            id = alarmId,
            firedDate = firedDate(intent, scheduled),
            usedTimeOverride = intent.getBooleanExtra(EXTRA_USED_TIME_OVERRIDE, false),
        ) ?: run {
            Log.w(TAG, "Alarm id=$alarmId was deleted while firing")
            app.scheduler.cancel(alarmId)
            return
        }

        app.scheduler.schedule(consumed.alarm)

        if (!consumed.shouldRing) {
            // The same date already rang (duplicate delivery or a stale regular-time
            // PendingIntent after the time was moved earlier). Rescheduling above is
            // enough; ringing again would double-fire the alarm.
            Log.i(TAG, "Suppressed duplicate fire id=$alarmId date=${firedDate(intent, scheduled)}")
            return
        }

        val alarm = consumed.alarm
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

    /**
     * The logical date the alarm belongs to. AlarmManager extras are authoritative;
     * the current local date is only a fallback for alarms registered by older
     * versions of the app.
     */
    private fun firedDate(intent: Intent, alarm: Alarm): LocalDate {
        val epochMillis = intent.getLongExtra(EXTRA_SCHEDULED_AT, -1L)
        if (epochMillis > 0L) {
            return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
        // Alarms registered by older versions carry no timestamp; the receiver runs
        // at fire time, so the current local date is the fired date.
        return LocalDate.now()
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.example.morningalarm.action.FIRE_ALARM"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at_epoch_millis"
        const val EXTRA_USED_TIME_OVERRIDE = "used_time_override"
    }
}
