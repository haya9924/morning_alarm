package com.example.morningalarm.data

import java.time.Instant
import java.time.ZonedDateTime

/**
 * All fire times still ahead today, as a clock-independent [Instant] list.
 *
 * Only enabled alarms contribute, and only their next occurrence when it falls on
 * [now]'s local date. Weekday repeat, skip-next and one-shot time overrides are
 * delegated to [NextOccurrence.resolve]; an occurrence that already passed (or that
 * rolled over to tomorrow) is therefore not listed.
 *
 * A pending snooze is added when it references an enabled alarm. Its due time is
 * included only when it is still in the future on today's local date. Equal times
 * are kept, and the result is sorted ascending.
 */
object TodayAlarmTimes {
    data class Snooze(val alarmId: Long, val dueAt: Instant)

    fun resolve(alarms: List<Alarm>, snooze: Snooze?, now: ZonedDateTime): List<Instant> {
        val today = now.toLocalDate()
        val times = ArrayList<Instant>(alarms.size + if (snooze != null) 1 else 0)

        alarms.forEach { alarm ->
            if (!alarm.enabled) return@forEach
            val at = NextOccurrence.resolve(alarm, now)?.at ?: return@forEach
            if (at.toLocalDate() == today) times += at.toInstant()
        }

        if (snooze != null && snooze.dueAt.isAfter(now.toInstant()) &&
            snooze.dueAt.atZone(now.zone).toLocalDate() == today &&
            alarms.any { it.id == snooze.alarmId && it.enabled }) {
            times += snooze.dueAt
        }

        return times.sorted()
    }
}
