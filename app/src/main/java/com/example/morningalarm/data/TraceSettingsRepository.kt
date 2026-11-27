package com.example.morningalarm.data

import android.content.Context
import com.example.morningalarm.trace.TraceDifficulty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TraceSettingsStorage {
    fun read(): String?
    fun save(key: String): Boolean
}

class SharedPreferencesTraceSettingsStorage(context: Context) : TraceSettingsStorage {
    private val prefs = context.getSharedPreferences("trace_settings", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("difficulty", null)
    override fun save(key: String): Boolean = prefs.edit().putString("difficulty", key).commit()
}

/** Independent of alarm records and scheduling. */
class TraceSettingsRepository(private val storage: TraceSettingsStorage) {
    private val value = MutableStateFlow(TraceDifficulty.fromStorageKey(storage.read()))
    val difficulty = value.asStateFlow()
    @Synchronized fun setDifficulty(difficulty: TraceDifficulty) {
        if (value.value == difficulty) return
        check(storage.save(difficulty.storageKey)) { "判定設定を保存できませんでした" }
        value.value = difficulty
    }
}
