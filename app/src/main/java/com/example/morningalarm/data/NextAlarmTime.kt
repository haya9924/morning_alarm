package com.example.morningalarm.data

import java.time.Instant
import java.time.ZonedDateTime

/**
 * The earliest fire time still ahead, across all alarms and a pending snooze.
 *
 * Only enabled alarms contribute. Weekday repeat, skip-next, one-shot time
 * overrides and the [Alarm.lastFiredDate] guard are delegated to
 * [NextOccurrence.resolve], so the result always matches the next AlarmManager
 * registration. There is no "today only" limit: an alarm that already passed
 * today yields its next occurrence tomorrow (or later for a weekday schedule).
 *
 * A snooze contributes only while it is strictly in the future and references an
 * alarm that still exists and is enabled. Its due time may cross midnight; it is
 * compared against the regular occurrences as-is.
 *
 * Returns null when nothing is scheduled (no enabled alarm and no valid snooze).
 */
object NextAlarmTime {
    data class Snooze(val alarmId: Long, val dueAt: Instant)

    fun resolve(alarms: List<Alarm>, snooze: Snooze?, now: ZonedDateTime): Instant? {
        val nowInstant = now.toInstant()
        val times = ArrayList<Instant>(alarms.size + 1)
        alarms.forEach { alarm ->
            if (!alarm.enabled) return@forEach
            NextOccurrence.resolve(alarm, now)?.at?.toInstant()?.let(times::add)
        }
        if (snooze != null && snooze.dueAt.isAfter(nowInstant) &&
            alarms.any { it.id == snooze.alarmId && it.enabled }) {
            times += snooze.dueAt
        }
        return times.minOrNull()
    }
}
