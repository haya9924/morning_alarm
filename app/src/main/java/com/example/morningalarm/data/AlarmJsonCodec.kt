package com.example.morningalarm.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * JSON persistence for the alarm list.
 *
 * Corrupt or partially invalid records are dropped instead of crashing the app or
 * the alarm receiver. This is important because the file is read from broadcast
 * receivers and adb-triggered commands as well as the UI.
 *
 * A nullable DTO is used because Gson can assign null to Kotlin non-null fields
 * when a field is missing from older/edited JSON.
 */
object AlarmJsonCodec {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val listType = object : TypeToken<List<AlarmDto>>() {}.type

    fun encode(alarms: List<Alarm>): String = gson.toJson(alarms)

    fun decode(json: String?): List<Alarm> {
        if (json.isNullOrBlank()) return emptyList()
        val decoded: List<AlarmDto>? = runCatching {
            gson.fromJson<List<AlarmDto>>(json, listType)
        }.getOrNull()

        return decoded.orEmpty()
            .mapNotNull { dto ->
                val id = dto.id ?: return@mapNotNull null
                val hour = dto.hour ?: return@mapNotNull null
                val minute = dto.minute ?: return@mapNotNull null
                Alarm(
                    id = id,
                    hour = hour,
                    minute = minute,
                    label = Alarm.normalizeLabel(dto.label.orEmpty()),
                    enabled = dto.enabled ?: true,
                )
            }
            .filter { it.isValid }
            .distinctBy { it.id }
    }

    private data class AlarmDto(
        val id: Long? = null,
        val hour: Int? = null,
        val minute: Int? = null,
        val label: String? = null,
        val enabled: Boolean? = null,
    )
}
