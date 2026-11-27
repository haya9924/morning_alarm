package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextOccurrenceTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 3, 10, hour, minute, second, 0, zone)

    private fun on(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    @Test
    fun `later today is used when the time is still ahead`() {
        val result = NextOccurrence.after(7, 30, at(6, 0))
        assertEquals(at(7, 30), result)
    }

    @Test
    fun `exactly at the alarm time schedules the next day`() {
        val result = NextOccurrence.after(7, 30, at(7, 30))
        assertEquals(at(7, 30).plusDays(1), result)
    }

    @Test
    fun `one second after the alarm time schedules the next day`() {
        val result = NextOccurrence.after(7, 30, at(7, 30, second = 1))
        assertEquals(at(7, 30).plusDays(1), result)
    }

    @Test
    fun `earlier clock time rolls over midnight`() {
        val result = NextOccurrence.after(5, 0, at(23, 30))
        assertEquals(at(5, 0).plusDays(1), result)
    }

    @Test
    fun `result is always strictly after now`() {
        listOf(
            at(0, 0),
            at(6, 59, 59),
            at(7, 0),
            at(12, 0),
            at(23, 59, 59),
        ).forEach { now ->
            assertTrue(NextOccurrence.after(7, 0, now).isAfter(now))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid hour is rejected`() {
        NextOccurrence.after(24, 0, at(6, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid minute is rejected`() {
        NextOccurrence.after(7, 60, at(6, 0))
    }

    @Test
    fun `dst gap is resolved forward by java time`() {
        val newYork = ZoneId.of("America/New_York")
        // 2026-03-08 02:00-02:59 does not exist in New York.
        val now = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork)
        val result = NextOccurrence.after(2, 30, now)
        assertEquals(3, result.hour)
        assertEquals(30, result.minute)
        assertTrue(result.isAfter(now))
    }

    // --- weekday repeat -----------------------------------------------------

    private val monday = ZonedDateTime.of(2026, 3, 9, 6, 0, 0, 0, zone)

    @Test
    fun `2026-03-09 is a monday and 2026-03-10 a tuesday`() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(DayOfWeek.TUESDAY, at(6, 0).dayOfWeek)
    }

    @Test
    fun `only selected weekdays are considered`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.FRIDAY),
        )
        val next = NextOccurrence.next(alarm, monday)!!
        assertEquals(LocalDate.of(2026, 3, 13), next.toLocalDate())
        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
    }

    @Test
    fun `skips days that are not selected even when later today`() {
        // Monday 06:00, alarm on Sunday only: the next Sunday is six days away.
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.SUNDAY))
        val next = NextOccurrence.next(alarm, monday)!!
        assertEquals(LocalDate.of(2026, 3, 15), next.toLocalDate())
    }

    @Test
    fun `same weekday later today still fires today`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY))
        val next = NextOccurrence.next(alarm, monday)!!
        assertEquals(LocalDate.of(2026, 3, 9), next.toLocalDate())
        assertEquals(7, next.hour)
    }

    @Test
    fun `same weekday at or after the time rolls a full week`() {
        val alarm = Alarm(id = 1, hour = 6, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY))
        val next = NextOccurrence.next(alarm, monday)!!
        assertEquals(LocalDate.of(2026, 3, 16), next.toLocalDate())
    }

    @Test
    fun `empty repeat set behaves as every day for legacy data`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = emptySet())
        assertEquals(at(7, 0), NextOccurrence.next(alarm, at(6, 0)))
    }

    // --- one-shot: skip next ------------------------------------------------

    @Test
    fun `skip date removes only that occurrence`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = RepeatDays.EVERY_DAY,
            skipNextDate = LocalDate.of(2026, 3, 10),
        )
        val next = NextOccurrence.next(alarm, at(6, 0))!!
        assertEquals(LocalDate.of(2026, 3, 11), next.toLocalDate())
    }

    @Test
    fun `skip date combined with weekday selection`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.TUESDAY),
            skipNextDate = LocalDate.of(2026, 3, 10),
        )
        val next = NextOccurrence.next(alarm, monday)!!
        assertEquals(LocalDate.of(2026, 3, 17), next.toLocalDate())
    }

    /** Regression: an 8-day window used to lose the occurrence after a weekly skip. */
    @Test
    fun `weekly alarm skipped this week resolves the following week`() {
        val friday = Alarm(id = 1, hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.FRIDAY))
        val skipped = friday.copy(skipNextDate = LocalDate.of(2026, 3, 13))

        val next = NextOccurrence.next(skipped, monday)!!

        assertEquals(LocalDate.of(2026, 3, 20), next.toLocalDate())
        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
    }

    /** Regression: skip + already-fired date must still find the third week. */
    @Test
    fun `weekly alarm skipped and already fired resolves two weeks later`() {
        val friday = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.FRIDAY),
            skipNextDate = LocalDate.of(2026, 3, 20),
            lastFiredDate = LocalDate.of(2026, 3, 13),
        )

        val next = NextOccurrence.next(friday, ZonedDateTime.of(2026, 3, 13, 7, 30, 0, 0, zone))!!

        assertEquals(LocalDate.of(2026, 3, 27), next.toLocalDate())
    }

    @Test
    fun `skip date in the past has no effect`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            skipNextDate = LocalDate.of(2026, 1, 1),
        )
        assertEquals(at(7, 0), NextOccurrence.next(alarm, at(6, 0)))
    }

    // --- one-shot: next time only ------------------------------------------

    @Test
    fun `earlier override on the target date is used`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 6, 15),
        )
        val occurrence = NextOccurrence.resolve(alarm, at(5, 0))!!
        assertEquals(at(6, 15), occurrence.at)
        assertTrue(occurrence.usedTimeOverride)
    }

    /** Regression: after an early override fires, the regular time must not re-fire. */
    @Test
    fun `early override does not ring again at the regular time the same day`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 6, 0),
            lastFiredDate = LocalDate.of(2026, 3, 10),
        )

        val occurrence = NextOccurrence.resolve(alarm, at(6, 0, second = 1))!!

        assertEquals(LocalDate.of(2026, 3, 11), occurrence.at.toLocalDate())
        assertEquals(9, occurrence.at.hour)
        assertFalse(occurrence.usedTimeOverride)
    }

    /** Regression: a later override keeps its target date after the regular time passed. */
    @Test
    fun `later override keeps its target date when re-registered after the regular time`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 9, 0),
        )

        // The regular 07:00 has already passed; a time-only model would slide the
        // override to 2026-03-11.
        val occurrence = NextOccurrence.resolve(alarm, at(8, 0))!!

        assertEquals(at(9, 0), occurrence.at)
        assertTrue(occurrence.usedTimeOverride)
    }

    @Test
    fun `override in the past falls back to the regular time`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 6, 0),
        )
        val occurrence = NextOccurrence.resolve(alarm, at(7, 0))!!
        assertEquals(at(9, 0), occurrence.at)
        assertFalse(occurrence.usedTimeOverride)
    }

    @Test
    fun `override keeps weekday selection intact`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.FRIDAY),
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 13, 6, 30),
        )
        val occurrence = NextOccurrence.resolve(alarm, monday)!!
        assertEquals(LocalDate.of(2026, 3, 13), occurrence.at.toLocalDate())
        assertEquals(6, occurrence.at.hour)
        assertTrue(occurrence.usedTimeOverride)
    }

    @Test
    fun `skip and override combine on the following occurrence`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            skipNextDate = LocalDate.of(2026, 3, 10),
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 11, 6, 45),
        )
        val occurrence = NextOccurrence.resolve(alarm, at(6, 0))!!
        assertEquals(LocalDate.of(2026, 3, 11), occurrence.at.toLocalDate())
        assertEquals(6, occurrence.at.hour)
        assertEquals(45, occurrence.at.minute)
        assertTrue(occurrence.usedTimeOverride)
    }

    @Test
    fun `explicit override on a later date does not silence the next regular occurrence`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 12, 9, 0),
        )

        val occurrence = NextOccurrence.resolve(alarm, at(6, 0))!!

        assertEquals(at(7, 0), occurrence.at)
        assertFalse(occurrence.usedTimeOverride)
    }

    @Test
    fun `override is ignored on the skipped date`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            skipNextDate = LocalDate.of(2026, 3, 10),
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 6, 0),
        )
        val occurrence = NextOccurrence.resolve(alarm, at(5, 0))!!
        assertEquals(LocalDate.of(2026, 3, 11), occurrence.at.toLocalDate())
        assertFalse(occurrence.usedTimeOverride)
    }

    @Test
    fun `next date helper reflects the override target date`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 23, 0),
        )
        assertEquals(LocalDate.of(2026, 3, 10), NextOccurrence.nextDate(alarm, at(6, 0)))
    }

    @Test
    fun `next occurrence after a fired date ignores that date`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, lastFiredDate = LocalDate.of(2026, 3, 10))
        assertEquals(
            LocalDate.of(2026, 3, 11),
            NextOccurrence.nextDate(alarm, at(6, 0)),
        )
    }

    @Test
    fun `weekly alarm finds the same weekday one week later`() {
        val now = ZonedDateTime.of(2026, 3, 9, 8, 0, 0, 0, zone) // Monday 08:00
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
        )
        val next = NextOccurrence.next(alarm, now)!!
        assertEquals(LocalDate.of(2026, 3, 16), next.toLocalDate())
        assertTrue(next.isAfter(now))
    }

    @Test
    fun `weekly alarm with a skip before the matching weekday still resolves`() {
        // The skip target can be a non-matching date (set via Hermes); it must not
        // shrink the search window enough to lose the weekly occurrence.
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            skipNextDate = LocalDate.of(2026, 3, 9),
        )
        val next = NextOccurrence.next(alarm, on(2026, 3, 9, 6, 0))!!
        assertEquals(LocalDate.of(2026, 3, 16), next.toLocalDate())
    }
}
