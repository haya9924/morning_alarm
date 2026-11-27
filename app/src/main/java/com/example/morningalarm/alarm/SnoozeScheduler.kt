package com.example.morningalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Separate from recurring alarms: snoozing never consumes a recurring occurrence. */
object SnoozeScheduler {
    const val DURATION_MS = 5 * 60 * 1000L
    data class Pending(val alarmId: Long, val token: String, val dueAt: Long)
    private fun prefs(c: Context) = c.getSharedPreferences("pending_snooze", Context.MODE_PRIVATE)
    fun read(c: Context): Pending? {
        val p = prefs(c)
        val id = p.getLong("id", -1)
        val token = p.getString("token", null) ?: return null
        val due = p.getLong("due", 0)
        return if (id > 0 && due > 0) Pending(id, token, due) else null
    }
    private fun operation(c: Context, p: Pending): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, SnoozeReceiver::class.java).setData(Uri.parse("morningalarm://snooze/${p.token}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    fun schedule(c: Context, p: Pending) {
        val manager = c.getSystemService(AlarmManager::class.java)
        val pi = operation(c, p)
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.dueAt, pi)
        } else {
            error("スヌーズには「アラームとリマインダー」の許可が必要です")
        }
    }
    fun saveAndSchedule(c: Context, p: Pending) {
        val manager = c.getSystemService(AlarmManager::class.java)
        check(Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            "スヌーズには「アラームとリマインダー」の許可が必要です"
        }
        check(prefs(c).edit().putLong("id", p.alarmId).putString("token", p.token)
            .putLong("due", p.dueAt).commit()) { "スヌーズを保存できませんでした" }
        try { schedule(c, p) } catch (e: Exception) { clear(c); throw e }
        runCatching { com.example.morningalarm.widget.TodayAlarmsWidget.requestUpdate(c) }
    }
    fun clear(c: Context) {
        read(c)?.let { c.getSystemService(AlarmManager::class.java).cancel(operation(c, it)) }
        val hadPending = read(c) != null
        check(prefs(c).edit().clear().commit()) { "スヌーズを解除できませんでした" }
        if (hadPending) runCatching { com.example.morningalarm.widget.TodayAlarmsWidget.requestUpdate(c) }
    }
    fun restore(c: Context) { read(c)?.let { schedule(c, it) } }
}

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = SnoozeScheduler.read(context) ?: return
        if (intent.data?.lastPathSegment != pending.token) return
        if (System.currentTimeMillis() < pending.dueAt) return
        // Keep the record until the service has actually resumed successfully.
        runCatching { AlarmService.resume(context, pending) }.onFailure {
            android.util.Log.e("SnoozeReceiver", "Could not resume snoozed alarm", it)
            val app = context.applicationContext as com.example.morningalarm.MorningAlarmApp
            app.repository.get(pending.alarmId)?.let { alarm -> AlarmNotifier.show(context, alarm) }
        }
    }
}
