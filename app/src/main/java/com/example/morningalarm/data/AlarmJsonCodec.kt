package com.example.morningalarm.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JSON persistence for the alarm list.
 *
 * Corrupt or partially invalid records are dropped instead of crashing the app or
 * the alarm receiver. This is important because the file is read from broadcast
 * receivers and adb-triggered commands as well as the UI.
 *
 * A nullable DTO is used because Gson can assign null to Kotlin non-null fields
 * when a field is missing from older/edited JSON.
 *
 * Version compatibility:
 * - Records written before weekday support have no `repeatDays` field; they are
 *   read back as "every day" (see [RepeatDays.normalize]).
 * - The one-shot adjustments are stored as ISO strings with an explicit target
 *   date (`skipNextDate`, `nextTimeOverrideAt`, `lastFiredDate`). Unparsable values
 *   are ignored so a bad field cannot make the whole alarm disappear. A time-only
 *   `nextTimeOverride` field written by an intermediate version has no target date
 *   and is ignored, letting the alarm fall back to its regular time instead of
 *   guessing a date.
 */
object AlarmJsonCodec {
    private val DATE_TIME_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val listType = object : TypeToken<List<AlarmDto>>() {}.type

    fun encode(alarms: List<Alarm>): String =
        gson.toJson(alarms.map(::toDto))

    fun decode(json: String?): List<Alarm> {
        if (json.isNullOrBlank()) return emptyList()
        val decoded: List<AlarmDto>? = runCatching {
            gson.fromJson<List<AlarmDto>>(json, listType)
        }.getOrNull()

        return decoded.orEmpty()
            .mapNotNull { dto -> fromDto(dto) }
            .filter { it.isValid }
            .distinctBy { it.id }
    }

    private fun toDto(alarm: Alarm): AlarmDto = AlarmDto(
        id = alarm.id,
        hour = alarm.hour,
        minute = alarm.minute,
        label = alarm.label,
        enabled = alarm.enabled,
        repeatDays = RepeatDays.ordered(alarm.repeat).map { it.name },
        skipNextDate = alarm.skipNextDate?.toString(),
        nextTimeOverrideAt = alarm.nextTimeOverrideAt?.format(DATE_TIME_SECONDS),
        lastFiredDate = alarm.lastFiredDate?.toString(),
    )

    private fun fromDto(dto: AlarmDto): Alarm? {
        val id = dto.id ?: return null
        val hour = dto.hour ?: return null
        val minute = dto.minute ?: return null

        return Alarm(
            id = id,
            hour = hour,
            minute = minute,
            label = Alarm.normalizeLabel(dto.label.orEmpty()),
            enabled = dto.enabled ?: true,
            repeatDays = parseDays(dto.repeatDays),
            skipNextDate = parseDate(dto.skipNextDate),
            nextTimeOverrideAt = parseOverrideAt(dto.nextTimeOverrideAt),
            lastFiredDate = parseDate(dto.lastFiredDate),
        )
    }

    /**
     * Missing, malformed or empty day lists become "every day". Unknown names are
     * ignored, and an unknown-only list also falls back to daily so an alarm is
     * never silently made to never ring.
     */
    private fun parseDays(values: List<String?>?): Set<DayOfWeek> {
        if (values.isNullOrEmpty()) return RepeatDays.EVERY_DAY
        val parsed = values.mapNotNull { raw ->
            val name = raw?.trim()?.uppercase().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            runCatching { DayOfWeek.valueOf(name) }.getOrNull()
        }.toSet()
        return RepeatDays.normalize(parsed)
    }

    /** `skipNextDate` / `lastFiredDate`: ISO local date, unparsable values ignored. */
    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
    }

    /** Parses the explicit override target date+time written by this version. */
    private fun parseOverrideAt(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        return runCatching { LocalDateTime.parse(value) }
            .recoverCatching { LocalDateTime.parse(value, DATE_TIME_SECONDS) }
            .getOrNull()
    }

    private data class AlarmDto(
        val id: Long? = null,
        val hour: Int? = null,
        val minute: Int? = null,
        val label: String? = null,
        val enabled: Boolean? = null,
        val repeatDays: List<String?>? = null,
        val skipNextDate: String? = null,
        val nextTimeOverrideAt: String? = null,
        val lastFiredDate: String? = null,
    )
}
