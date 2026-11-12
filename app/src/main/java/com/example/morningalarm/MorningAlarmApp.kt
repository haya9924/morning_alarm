package com.example.morningalarm

import android.app.Application
import com.example.morningalarm.alarm.AlarmNotifier
import com.example.morningalarm.alarm.AlarmScheduler
import com.example.morningalarm.data.AlarmRepository
import com.example.morningalarm.data.SharedPreferencesAlarmStorage
import com.example.morningalarm.data.SummaryRepository

class MorningAlarmApp : Application() {
    val repository: AlarmRepository by lazy {
        AlarmRepository(SharedPreferencesAlarmStorage(this))
    }

    val scheduler: AlarmScheduler by lazy { AlarmScheduler(this) }

    val summaryRepository: SummaryRepository by lazy { SummaryRepository(this) }

    override fun onCreate() {
        super.onCreate()
        AlarmNotifier.ensureChannel(this)
    }
}
