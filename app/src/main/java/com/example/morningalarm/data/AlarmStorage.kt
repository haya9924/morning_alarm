package com.example.morningalarm.data

import android.content.Context

/**
 * Storage boundary so [AlarmRepository] can be unit tested without Android.
 */
interface AlarmStorage {
    fun load(): List<Alarm>

    /** @return true only when the list was durably written. */
    fun save(alarms: List<Alarm>): Boolean
}

/**
 * Small local-only store. commit() is used deliberately: alarm mutations can happen
 * from a broadcast receiver, and losing the write would silently drop an alarm.
 */
class SharedPreferencesAlarmStorage(context: Context) : AlarmStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<Alarm> =
        AlarmJsonCodec.decode(prefs.getString(KEY_ALARMS, null))

    override fun save(alarms: List<Alarm>): Boolean =
        prefs.edit()
            .putString(KEY_ALARMS, AlarmJsonCodec.encode(alarms))
            .commit()

    private companion object {
        const val PREFS_NAME = "morning_alarm"
        const val KEY_ALARMS = "alarms_json"
    }
}
