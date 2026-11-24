package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Next fire time for a stored [Alarm], including weekday repeat and the one-shot
 * "skip next" / "change next time only" adjustments.
 *
 * Both adjustments carry an explicit target date (see [Alarm]). That is essential:
 * a reschedule triggered by a reboot, a permission grant or an app resume must not
 * reinterpret "next" against the current clock, otherwise
 * - an override later than the regular time would lose its target day once the
 *   regular time has passed, and
 * - an override earlier than the regular time would let the regular time ring
 *   again on the same day.
 *
 * [Alarm.lastFiredDate] is the guard: a date that already fired is never produced
 * again by [resolve], even if a stale PendingIntent is delivered twice.
 */
object NextOccurrence {
    /** A resolved fire time plus whether the one-shot time override was applied. */
    data class Occurrence(
        val at: ZonedDateTime,
        val usedTimeOverride: Boolean,
    )

    /** Legacy signature: daily alarm with no adjustments. */
    fun after(hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
        require(hour in 0..23) { "hour must be 0..23" }
        require(minute in 0..59) { "minute must be 0..59" }
        val alarm = Alarm(id = 1, hour = hour, minute = minute)
        return requireNotNull(next(alarm, now)) {
            "a daily alarm always has a next occurrence"
        }
    }

    fun next(alarm: Alarm, now: ZonedDateTime): ZonedDateTime? = resolve(alarm, now)?.at

    /**
     * Resolves the next fire time.
     *
     * 1. A pending override replaces the regular occurrence of its own target date,
     *    whether it moves the time earlier or later. That is what "change next time
     *    only" promises, and pinning the date keeps the promise across re-registers.
     * 2. [Alarm.lastFiredDate] is skipped, so an early override (e.g. 06:00 for a
     *    09:00 alarm) cannot ring a second time at the regular time the same day.
     * 3. An override pinned to a *later* date than the next regular occurrence (an
     *    explicit date set through Hermes) does not silence the occurrences in
     *    between; those ring normally and the override keeps its target date.
     */
    fun resolve(alarm: Alarm, now: ZonedDateTime): Occurrence? {
        val regularDate = nextDate(
            days = RepeatDays.normalize(alarm.repeat),
            skipDate = alarm.skipNextDate,
            afterDate = alarm.lastFiredDate,
            baseTime = LocalTime.of(alarm.hour, alarm.minute),
            now = now,
        )
        val override = pendingOverride(alarm, now)
        if (override != null && (regularDate == null || !override.toLocalDate().isAfter(regularDate))) {
            return Occurrence(override.atZone(now.zone), usedTimeOverride = true)
        }
        if (regularDate == null) return null
        return Occurrence(
            regularDate.atTime(alarm.hour, alarm.minute).atZone(now.zone),
            usedTimeOverride = false,
        )
    }

    /**
     * The stored override only while it can still fire: in the future, not on an
     * already fired date, and not on the skipped date. A stale override is ignored
     * (and removed by [AlarmRepository.consumeAfterFire]) instead of being silently
     * moved to another day.
     */
    fun pendingOverride(alarm: Alarm, now: ZonedDateTime): LocalDateTime? {
        val override = alarm.nextTimeOverrideAt ?: return null
        if (override.toLocalDate() == alarm.lastFiredDate) return null
        if (override.toLocalDate() == alarm.skipNextDate) return null
        return override.takeIf { it.atZone(now.zone).isAfter(now) }
    }

    /** Date of the next occurrence (override included). */
    fun nextDate(alarm: Alarm, now: ZonedDateTime): LocalDate? =
        resolve(alarm, now)?.at?.toLocalDate()

    /**
     * First repeat date strictly after [afterDate] that is neither skipped nor on an
     * override date. Callers use it to place a one-shot adjustment on the occurrence
     * it is meant for.
     */
    fun nextDateAfter(alarm: Alarm, afterDate: LocalDate?, now: ZonedDateTime): LocalDate? =
        nextDate(
            days = RepeatDays.normalize(alarm.repeat),
            skipDate = alarm.skipNextDate,
            afterDate = afterDate,
            baseTime = LocalTime.of(alarm.hour, alarm.minute),
            now = now,
        )

    /**
     * Walks up to [DAYS_TO_SCAN] days ahead. Twenty-one days cover the worst case: a
     * weekly-only alarm whose matching date is excluded by both [afterDate] (already
     * fired) and [skipDate] resolves two weeks after the start date.
     */
    private fun nextDate(
        days: Set<DayOfWeek>,
        skipDate: LocalDate?,
        afterDate: LocalDate?,
        baseTime: LocalTime,
        now: ZonedDateTime,
    ): LocalDate? {
        var date = now.toLocalDate()
        if (afterDate != null && !date.isAfter(afterDate)) {
            date = afterDate.plusDays(1)
        }
        repeat(DAYS_TO_SCAN) {
            if (date.dayOfWeek in days && date != skipDate) {
                val candidate = date.atTime(baseTime).atZone(now.zone)
                if (candidate.isAfter(now)) return date
            }
            date = date.plusDays(1)
        }
        return null
    }

    private const val DAYS_TO_SCAN = 21
}
