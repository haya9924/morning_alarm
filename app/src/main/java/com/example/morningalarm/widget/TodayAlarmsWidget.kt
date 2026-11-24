package com.example.morningalarm.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.R
import com.example.morningalarm.alarm.AlarmScheduler
import com.example.morningalarm.alarm.SnoozeScheduler
import com.example.morningalarm.data.NextAlarmTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

/**
 * Read-only "next alarm" widget. Shows a single time and its day; no labels, tokens,
 * or control actions reach the host. The remote views are static (no adapter or
 * scrolling), built once per snapshot and reused for every widget id.
 */
class TodayAlarmsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        updateAll(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // APPWIDGET_UPDATE is dispatched to onUpdate by super; the rest need a refresh.
        if (intent.action in setOf(REFRESH, Intent.ACTION_DATE_CHANGED, Intent.ACTION_LOCALE_CHANGED)) {
            updateAll(context)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: android.os.Bundle,
    ) = updateAll(context)

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    companion object {
        const val REFRESH = "com.example.morningalarm.widget.REFRESH"

        fun requestUpdate(context: Context) {
            context.sendBroadcast(Intent(context, TodayAlarmsWidget::class.java).setAction(REFRESH))
        }

        private fun refreshIntent(context: Context) = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, TodayAlarmsWidget::class.java).setAction(REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayAlarmsWidget::class.java))
            val timer = context.getSystemService(AlarmManager::class.java)
            if (ids.isEmpty()) {
                timer.cancel(refreshIntent(context))
                return
            }

            // One snapshot: one clock read, one repository read, one resolve for all ids.
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val next = resolveNext(context, now)

            val views = RemoteViews(context.packageName, R.layout.widget_today_alarms)
            if (next == null) {
                views.setViewVisibility(R.id.widget_alarm_time, View.GONE)
                views.setViewVisibility(R.id.widget_alarm_day, View.GONE)
                views.setViewVisibility(R.id.widget_alarm_empty, View.VISIBLE)
                views.setTextViewText(R.id.widget_alarm_empty, context.getString(R.string.widget_no_alarm))
            } else {
                views.setTextViewText(R.id.widget_alarm_time, formatTime(context, next))
                views.setTextViewText(R.id.widget_alarm_day, formatDay(context, next, now))
                views.setViewVisibility(R.id.widget_alarm_time, View.VISIBLE)
                views.setViewVisibility(R.id.widget_alarm_day, View.VISIBLE)
                views.setViewVisibility(R.id.widget_alarm_empty, View.GONE)
            }
            ids.forEach { manager.updateAppWidget(it, views) }

            // Display refresh only: non-wakeup, inexact RTC, no exact-alarm permission.
            if (next == null) {
                timer.cancel(refreshIntent(context))
            } else {
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()
                val at = minOf(next, midnight).toEpochMilli() + 1_000L
                timer.set(AlarmManager.RTC, at, refreshIntent(context))
            }
        }

        private fun resolveNext(context: Context, now: ZonedDateTime): Instant? {
            val app = context.applicationContext as MorningAlarmApp
            // Snooze has no inexact fallback. A persisted record cannot ring if
            // exact alarm access was revoked or could not be used at restore.
            val snooze = if (AlarmScheduler.isExactAlarmAllowed(context)) {
                SnoozeScheduler.read(context)?.let {
                    NextAlarmTime.Snooze(it.alarmId, Instant.ofEpochMilli(it.dueAt))
                }
            } else {
                null
            }
            return NextAlarmTime.resolve(app.repository.alarms.value, snooze, now)
        }

        private fun formatTime(context: Context, next: Instant): String =
            DateFormat.getTimeFormat(context).format(Date.from(next))

        /** Today/tomorrow labels, otherwise an abbreviated weekday + date in the system zone. */
        private fun formatDay(context: Context, next: Instant, now: ZonedDateTime): String {
            val day = next.atZone(now.zone).toLocalDate()
            return when (day) {
                now.toLocalDate() -> context.getString(R.string.widget_alarm_today)
                now.toLocalDate().plusDays(1) -> context.getString(R.string.widget_alarm_tomorrow)
                else -> DateUtils.formatDateTime(
                    context,
                    next.toEpochMilli(),
                    DateUtils.FORMAT_SHOW_DATE or
                        DateUtils.FORMAT_SHOW_WEEKDAY or
                        DateUtils.FORMAT_ABBREV_WEEKDAY,
                )
            }
        }
    }
}
