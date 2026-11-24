package com.example.morningalarm.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.R
import com.example.morningalarm.alarm.SnoozeScheduler
import com.example.morningalarm.data.TodayAlarmTimes
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Read-only widget. No alarm labels, tokens, or alarm control actions reach the host. */
class TodayAlarmsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(REFRESH, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            updateAll(context)
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        updateAll(context)
    }
    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    companion object {
        const val REFRESH = "com.example.morningalarm.widget.REFRESH"
        fun requestUpdate(context: Context) {
            context.sendBroadcast(Intent(context, TodayAlarmsWidget::class.java).setAction(REFRESH))
        }
        private fun refreshIntent(context: Context) = PendingIntent.getBroadcast(context, 0,
            Intent(context, TodayAlarmsWidget::class.java).setAction(REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        @Suppress("DEPRECATION")
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayAlarmsWidget::class.java))
            val timer = context.getSystemService(AlarmManager::class.java)
            if (ids.isEmpty()) { timer.cancel(refreshIntent(context)); return }
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_today_alarms)
                val adapter = Intent(context, TodayAlarmsViewsService::class.java)
                    .setData(Uri.parse("morningalarm://widget/$id"))
                views.setRemoteAdapter(R.id.widget_alarm_list, adapter)
                views.setEmptyView(R.id.widget_alarm_list, R.id.widget_alarm_empty)
                manager.updateAppWidget(id, views)
            }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_alarm_list)
            val now = ZonedDateTime.now()
            val next = WidgetData.times(context, now).firstOrNull()
                ?: now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()
            // Display refresh only: non-wakeup, inexact, and no exact-alarm permission.
            timer.set(AlarmManager.RTC, next.toEpochMilli() + 1_000, refreshIntent(context))
        }
    }
}

private object WidgetData {
    fun times(context: Context, now: ZonedDateTime): List<java.time.Instant> {
        val app = context.applicationContext as MorningAlarmApp
        val snooze = SnoozeScheduler.read(context)?.let {
            TodayAlarmTimes.Snooze(it.alarmId, java.time.Instant.ofEpochMilli(it.dueAt))
        }
        return TodayAlarmTimes.resolve(app.repository.alarms.value, snooze, now)
    }
}

class TodayAlarmsViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var times: List<String> = emptyList()
        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            val now = ZonedDateTime.now()
            val format = DateTimeFormatter.ofPattern("HH:mm")
            times = WidgetData.times(applicationContext, now).map { it.atZone(now.zone).format(format) }
        }
        override fun onDestroy() { times = emptyList() }
        override fun getCount() = times.size
        override fun getViewAt(position: Int): RemoteViews = RemoteViews(packageName, R.layout.widget_alarm_time_row).apply {
            setTextViewText(R.id.widget_alarm_time, times.getOrNull(position) ?: "—")
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
