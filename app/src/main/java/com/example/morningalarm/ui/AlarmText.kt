package com.example.morningalarm.ui

import com.example.morningalarm.data.Alarm
import com.example.morningalarm.data.NextOccurrence
import com.example.morningalarm.data.RepeatDays
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display helpers for repeat days and one-shot adjustments. */
object AlarmText {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
    private val DATE = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN)

    private val DAY_LABELS: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to "月",
        DayOfWeek.TUESDAY to "火",
        DayOfWeek.WEDNESDAY to "水",
        DayOfWeek.THURSDAY to "木",
        DayOfWeek.FRIDAY to "金",
        DayOfWeek.SATURDAY to "土",
        DayOfWeek.SUNDAY to "日",
    )

    fun dayLabel(day: DayOfWeek): String = DAY_LABELS[day] ?: day.name

    fun formatTime(hour: Int, minute: Int): String =
        String.format(Locale.JAPAN, "%02d:%02d", hour, minute)

    fun formatTime(time: LocalTime): String = time.format(TIME)

    fun formatDate(date: java.time.LocalDate): String = date.format(DATE)

    fun repeatSummary(days: Set<DayOfWeek>): String {
        val normalized = RepeatDays.normalize(days)
        return when {
            normalized.containsAll(RepeatDays.EVERY_DAY) -> "毎日"
            normalized == RepeatDays.WEEKDAYS -> "平日"
            normalized == RepeatDays.WEEKEND -> "土日"
            else -> RepeatDays.ordered(normalized).joinToString("・") { dayLabel(it) }
        }
    }

    /**
     * Toggles one weekday. A zero-selection can never be persisted ("never ring"),
     * so removing the last selected day restores "every day" immediately instead of
     * silently meaning the same thing as "no selection".
     */
    fun toggledDays(current: Set<DayOfWeek>, day: DayOfWeek): Set<DayOfWeek> {
        val normalized = RepeatDays.normalize(current)
        if (day in normalized) {
            val remaining = normalized - day
            return if (remaining.isEmpty()) RepeatDays.EVERY_DAY else remaining
        }
        return normalized + day
    }

    fun nextOccurrenceText(alarm: Alarm, now: ZonedDateTime): String? =
        NextOccurrence.next(alarm, now)?.let { "次回 ${formatDate(it.toLocalDate())} ${formatTime(it.hour, it.minute)}" }

    fun nextAdjustmentText(alarm: Alarm): String? {
        val parts = buildList {
            alarm.skipNextDate?.let { add("${formatDate(it)} はスキップ") }
            alarm.nextTimeOverrideAt?.let {
                add("${formatDate(it.toLocalDate())} は ${formatTime(it.toLocalTime())} に変更")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    }
}
