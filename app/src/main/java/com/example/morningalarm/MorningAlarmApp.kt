package com.example.morningalarm

import android.app.Application
import com.example.morningalarm.alarm.AlarmNotifier
import com.example.morningalarm.alarm.AlarmScheduler
import com.example.morningalarm.data.AlarmRepository
import com.example.morningalarm.data.SharedPreferencesAlarmStorage
import com.example.morningalarm.data.SummaryRepository
import com.example.morningalarm.summary.HermesSummarySync

class MorningAlarmApp : Application() {
    val repository: AlarmRepository by lazy {
        AlarmRepository(SharedPreferencesAlarmStorage(this), onPersisted = {
            com.example.morningalarm.widget.TodayAlarmsWidget.requestUpdate(this)
        })
    }

    val traceSettings by lazy {
        com.example.morningalarm.data.TraceSettingsRepository(
            com.example.morningalarm.data.SharedPreferencesTraceSettingsStorage(this))
    }

    val scheduler: AlarmScheduler by lazy { AlarmScheduler(this) }

    val summaryRepository: SummaryRepository by lazy { SummaryRepository(this) }
    val hermesSummarySync: HermesSummarySync by lazy { HermesSummarySync(this) }

    override fun onCreate() {
        super.onCreate()
        AlarmNotifier.ensureChannel(this)
    }
}
