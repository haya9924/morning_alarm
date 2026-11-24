package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A repeating alarm at a fixed local time.
 *
 * IDs are positive and stable for the lifetime of the alarm. They are used as the
 * PendingIntent request code basis and as the key for Hermes commands.
 *
 * [repeatDays] selects the weekdays on which the alarm rings. An empty set is
 * normalized to [RepeatDays.EVERY_DAY] so alarms stored by older versions (which
 * were always daily) keep ringing every day.
 *
 * [skipNextDate] and [nextTimeOverrideAt] are one-shot adjustments. Both carry an
 * explicit target date so a reschedule (reboot, permission grant, app resume) can
 * never move the adjustment to a different day:
 * - [skipNextDate]: that date's occurrence is suppressed.
 * - [nextTimeOverrideAt]: that date rings at the stored time instead of the regular
 *   one. The date is fixed when the user sets it, so re-registering the alarm after
 *   the regular time has passed on the target date still rings the override.
 *
 * [lastFiredDate] enforces "at most one ring per date". Without it an early override
 * (e.g. 06:00 for a 09:00 alarm) would ring again at 09:00 the same day after the
 * receiver reschedules the next occurrence.
 *
 * Adjustments are cleared when the time or repeat days are edited, because their
 * explicit target dates belong to the previous schedule.
 */
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    val repeatDays: Set<DayOfWeek> = RepeatDays.EVERY_DAY,
    val skipNextDate: LocalDate? = null,
    val nextTimeOverrideAt: LocalDateTime? = null,
    val lastFiredDate: LocalDate? = null,
) {
    val isValid: Boolean
        get() = id > 0 && hour in 0..23 && minute in 0..59

    /** Repeat days with the legacy "no days selected means daily" rule applied. */
    val repeat: Set<DayOfWeek>
        get() = RepeatDays.normalize(repeatDays)

    val hasNextAdjustment: Boolean
        get() = skipNextDate != null || nextTimeOverrideAt != null

    fun withNormalizedRepeatDays(): Alarm =
        if (repeatDays.isEmpty()) copy(repeatDays = RepeatDays.EVERY_DAY) else this

    /**
     * Drops one-shot state that can no longer have any effect. Called on load so a
     * stale target date (e.g. the app was not opened for a week) is not kept around
     * and later misread as "the next occurrence".
     */
    fun withoutStaleAdjustments(today: LocalDate): Alarm {
        val skip = skipNextDate?.takeIf { it >= today }
        val override = nextTimeOverrideAt?.takeIf { it.toLocalDate() >= today }
        return if (skip == skipNextDate && override == nextTimeOverrideAt) {
            this
        } else {
            copy(skipNextDate = skip, nextTimeOverrideAt = override)
        }
    }

    /** Clears both one-shot adjustments without touching the regular schedule. */
    fun withClearedAdjustments(): Alarm =
        if (!hasNextAdjustment) this else copy(skipNextDate = null, nextTimeOverrideAt = null)

    companion object {
        const val MAX_LABEL_LENGTH = 80

        fun normalizeLabel(value: String): String =
            value.trim().take(MAX_LABEL_LENGTH)

        val ORDERING: Comparator<Alarm> =
            compareBy<Alarm>({ it.hour }, { it.minute }, { it.id })
    }
}

/** Weekday set helpers shared by persistence, scheduling and the UI. */
object RepeatDays {
    val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    val WEEKDAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /** Display/serialization order starting on Monday. */
    val ORDERED: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )

    /** Empty input means "every day" for backward compatibility. */
    fun normalize(days: Set<DayOfWeek>): Set<DayOfWeek> =
        if (days.isEmpty()) EVERY_DAY else days

    fun ordered(days: Set<DayOfWeek>): List<DayOfWeek> =
        ORDERED.filter { it in normalize(days) }
}

/** Largest of the given non-null values, or null when all are null. */
internal fun maxOfNotNull(a: LocalDate?, b: LocalDate?): LocalDate? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
