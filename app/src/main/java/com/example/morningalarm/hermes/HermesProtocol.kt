package com.example.morningalarm.hermes

import com.example.morningalarm.data.Alarm
import com.example.morningalarm.data.NextOccurrence
import com.example.morningalarm.data.RepeatDays
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Wire protocol for the explicit, DUMP-permission-protected Hermes receiver.
 *
 * ADB example:
 *   adb shell am broadcast -n com.example.morningalarm/.hermes.HermesCommandReceiver \
 *     -a com.example.morningalarm.hermes.action.CREATE_ALARM \
 *     --ei hour 7 --ei minute 30 --es label "Wake up"
 *
 * Results are returned as ordered-broadcast result data/extras (JSON) and are also
 * written to logcat under the tag "HermesCommand". No command returns file contents
 * or accepts arbitrary file paths.
 */
object HermesProtocol {
    const val PROTOCOL_VERSION = 1
    const val RESULT_JSON_EXTRA = "hermes_result_json"

    object Action {
        const val PING = "com.example.morningalarm.hermes.action.PING"
        const val LIST_ALARMS = "com.example.morningalarm.hermes.action.LIST_ALARMS"
        const val CREATE_ALARM = "com.example.morningalarm.hermes.action.CREATE_ALARM"
        const val SET_ENABLED = "com.example.morningalarm.hermes.action.SET_ENABLED"
        const val DELETE_ALARM = "com.example.morningalarm.hermes.action.DELETE_ALARM"
        const val SET_REPEAT_DAYS = "com.example.morningalarm.hermes.action.SET_REPEAT_DAYS"
        const val SKIP_NEXT = "com.example.morningalarm.hermes.action.SKIP_NEXT"
        const val SET_NEXT_TIME = "com.example.morningalarm.hermes.action.SET_NEXT_TIME"
        const val CLEAR_NEXT_ADJUSTMENT =
            "com.example.morningalarm.hermes.action.CLEAR_NEXT_ADJUSTMENT"
    }

    object Extra {
        const val HOUR = "hour"
        const val MINUTE = "minute"
        const val LABEL = "label"
        const val ID = "id"
        const val ENABLED = "enabled"

        /** Comma-separated weekday names, e.g. "MONDAY,FRIDAY". */
        const val DAYS = "days"

        /** "HH:mm" for SET_NEXT_TIME; "none"/empty clears the override. */
        const val NEXT_TIME = "nextTime"
    }

    data class Response(
        val ok: Boolean,
        val message: String,
        val data: Any? = null,
    )

    data class CreateRequest(
        val hour: Int,
        val minute: Int,
        val label: String,
        val repeatDays: Set<DayOfWeek> = RepeatDays.EVERY_DAY,
    )

    object Args {
        fun create(
            hour: Int?,
            minute: Int?,
            label: String?,
            days: String? = null,
        ): Result<CreateRequest> {
            if (hour == null || hour !in 0..23) {
                return Result.failure(IllegalArgumentException("hour must be 0..23"))
            }
            if (minute == null || minute !in 0..59) {
                return Result.failure(IllegalArgumentException("minute must be 0..59"))
            }
            val repeatDays = repeatDays(days)
                .getOrElse { return Result.failure(it) }
            return Result.success(
                CreateRequest(
                    hour = hour,
                    minute = minute,
                    label = Alarm.normalizeLabel(label.orEmpty()),
                    repeatDays = repeatDays,
                ),
            )
        }

        /**
         * Parses a comma-separated weekday list. Absent/blank means "every day",
         * matching the UI default and the legacy daily behavior. Names are the
         * java.time English constants (MONDAY..SUNDAY), case-insensitive.
         */
        fun repeatDays(raw: String?): Result<Set<DayOfWeek>> {
            if (raw.isNullOrBlank()) return Result.success(RepeatDays.EVERY_DAY)
            val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) return Result.success(RepeatDays.EVERY_DAY)

            val days = mutableSetOf<DayOfWeek>()
            for (name in names) {
                val day = runCatching { DayOfWeek.valueOf(name.uppercase()) }.getOrNull()
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "days must be a comma-separated list of MONDAY..SUNDAY (got: $name)",
                        ),
                    )
                days += day
            }
            return Result.success(RepeatDays.normalize(days))
        }

        /**
         * Parses a one-shot time override. Blank or "none" clears it.
         *
         * "HH:mm" means "the next occurrence at that time". An explicit
         * "yyyy-MM-ddTHH:mm" pins the target date, which is what the UI and the
         * persisted model use so a reschedule cannot move the adjustment.
         */
        fun nextTimeOverride(raw: String?): Result<LocalDateTime?> {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value.equals("none", ignoreCase = true)) {
                return Result.success(null)
            }
            runCatching { LocalDateTime.parse(value) }.getOrNull()?.let {
                return Result.success(it)
            }
            val time = runCatching { LocalTime.parse(value) }
                .recoverCatching { LocalTime.parse(value, NEXT_TIME_FORMAT) }
                .getOrNull()
                ?: return Result.failure(
                    IllegalArgumentException(
                        "nextTime must be HH:mm, yyyy-MM-ddTHH:mm or none (got: $value)",
                    ),
                )
            // A bare time is resolved by the repository against the next occurrence.
            return Result.success(LocalDateTime.of(LocalDate.now(), time))
        }

        fun alarmId(id: Long?): Result<Long> {
            if (id == null || id <= 0L) {
                return Result.failure(IllegalArgumentException("id must be a positive long"))
            }
            return Result.success(id)
        }

        /**
         * Strictly validates the enabled flag. Bundle#getBooleanExtra silently
         * coerces mismatched types, so the receiver passes the raw value here.
         */
        fun enabled(value: Any?): Result<Boolean> {
            if (value !is Boolean) {
                return Result.failure(
                    IllegalArgumentException("enabled must be a boolean (true/false)"),
                )
            }
            return Result.success(value)
        }
    }

    fun alarmToMap(alarm: Alarm): Map<String, Any> = linkedMapOf(
        "id" to alarm.id,
        "hour" to alarm.hour,
        "minute" to alarm.minute,
        "label" to alarm.label,
        "enabled" to alarm.enabled,
        "repeatDays" to RepeatDays.ordered(alarm.repeat).map { it.name },
        "skipNextDate" to alarm.skipNextDate?.toString().orEmpty(),
        "nextTimeOverride" to alarm.nextTimeOverrideAt?.format(NEXT_TIME_FORMAT).orEmpty(),
        "nextTimeOverrideAt" to alarm.nextTimeOverrideAt?.toString().orEmpty(),
        "lastFiredDate" to alarm.lastFiredDate?.toString().orEmpty(),
    )

    /**
     * Human/debug summary of the one-shot state and the resolved next fire time.
     * Included in LIST_ALARMS output so an external agent can verify its effect.
     */
    fun scheduleToMap(alarm: Alarm, now: ZonedDateTime): Map<String, Any> {
        val occurrence = NextOccurrence.resolve(alarm, now)
        return linkedMapOf(
            "nextAt" to (occurrence?.at?.toInstant()?.toEpochMilli() ?: 0L),
            "nextAtIso" to (occurrence?.at?.toString().orEmpty()),
            "usingTimeOverride" to (occurrence?.usedTimeOverride ?: false),
        )
    }

    fun alarmWithScheduleToMap(alarm: Alarm, now: ZonedDateTime): Map<String, Any> =
        alarmToMap(alarm) + scheduleToMap(alarm, now)

    private val NEXT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
