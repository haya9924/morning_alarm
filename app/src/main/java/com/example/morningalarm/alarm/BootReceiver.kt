package com.example.morningalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.morningalarm.MorningAlarmApp

/**
 * Re-registers exact alarms after reboot, app update, manual clock/timezone change,
 * or a later grant of SCHEDULE_EXACT_ALARM. AlarmManager does not persist alarms
 * across reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MorningAlarmApp
        val alarms = app.repository.alarms.value
        app.scheduler.scheduleAll(alarms)
        Log.i(
            TAG,
            "Rescheduled ${alarms.count { it.enabled }} alarm(s) after ${intent.action}",
        )
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
