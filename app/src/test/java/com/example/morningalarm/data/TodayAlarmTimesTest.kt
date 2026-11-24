package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayAlarmTimesTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 3, 10, hour, minute, second, 0, zone)

    private fun instant(hour: Int, minute: Int, day: Int = 10): Instant =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone).toInstant()

    // --- today / future boundaries -----------------------------------------

    @Test
    fun `later today is included`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30)

        assertEquals(listOf(instant(7, 30)), TodayAlarmTimes.resolve(listOf(alarm), null, at(6, 0)))
    }

    @Test
    fun `alarm already passed today is excluded even though it fires tomorrow`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30)

        assertTrue(TodayAlarmTimes.resolve(listOf(alarm), null, at(8, 0)).isEmpty())
    }

    @Test
    fun `alarm exactly at now rolls to tomorrow and is excluded`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30)

        assertTrue(TodayAlarmTimes.resolve(listOf(alarm), null, at(7, 30)).isEmpty())
    }

    @Test
    fun `disabled alarm is excluded`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30, enabled = false)

        assertTrue(TodayAlarmTimes.resolve(listOf(alarm), null, at(6, 0)).isEmpty())
    }

    // --- weekday repeat / skip / override ----------------------------------

    @Test
    fun `weekday repeat only includes today when selected`() {
        // 2026-03-10 is a Tuesday.
        val tuesday = Alarm(id = 1, hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.TUESDAY))
        val monday = Alarm(id = 2, hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY))

        assertEquals(
            listOf(instant(7, 0)),
            TodayAlarmTimes.resolve(listOf(tuesday, monday), null, at(6, 0)),
        )
    }

    @Test
    fun `skip today omits the alarm`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            skipNextDate = LocalDate.of(2026, 3, 10),
        )

        assertTrue(TodayAlarmTimes.resolve(listOf(alarm), null, at(6, 0)).isEmpty())
    }

    @Test
    fun `time override today is used`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 6, 15),
        )

        assertEquals(listOf(instant(6, 15)), TodayAlarmTimes.resolve(listOf(alarm), null, at(5, 0)))
    }

    @Test
    fun `override pinned to another day is not listed today`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 12, 6, 15),
        )

        assertEquals(listOf(instant(9, 0)), TodayAlarmTimes.resolve(listOf(alarm), null, at(7, 0)))
    }

    @Test
    fun `expired override falls back to the regular time today`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 6, 15),
        )

        assertEquals(listOf(instant(9, 0)), TodayAlarmTimes.resolve(listOf(alarm), null, at(7, 0)))
    }

    @Test
    fun `expired and tomorrow snoozes are not today's future schedule`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, lastFiredDate = at(8, 0).toLocalDate())
        for (due in listOf(instant(7, 5), instant(8, 0), instant(8, 0).plusSeconds(86400))) {
            assertTrue(TodayAlarmTimes.resolve(listOf(alarm), TodayAlarmTimes.Snooze(1, due), at(8, 0)).isEmpty())
        }
    }

    // --- snooze -------------------------------------------------------------

    @Test
    fun `snooze of an enabled alarm is included`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = TodayAlarmTimes.Snooze(alarmId = 1, dueAt = instant(7, 5))

        assertEquals(
            listOf(instant(7, 0), instant(7, 5)),
            TodayAlarmTimes.resolve(listOf(alarm), snooze, at(6, 0)),
        )
    }

    @Test
    fun `snooze of a disabled alarm is ignored`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, enabled = false)
        val snooze = TodayAlarmTimes.Snooze(alarmId = 1, dueAt = instant(7, 5))

        assertTrue(TodayAlarmTimes.resolve(listOf(alarm), snooze, at(6, 0)).isEmpty())
    }

    @Test
    fun `snooze referencing a missing alarm is ignored`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = TodayAlarmTimes.Snooze(alarmId = 2, dueAt = instant(7, 5))

        assertEquals(listOf(instant(7, 0)), TodayAlarmTimes.resolve(listOf(alarm), snooze, at(6, 0)))
    }

    @Test
    fun `snooze due time is added as-is`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = TodayAlarmTimes.Snooze(alarmId = 1, dueAt = instant(8, 0))

        assertEquals(
            listOf(instant(7, 0), instant(8, 0)),
            TodayAlarmTimes.resolve(listOf(alarm), snooze, at(6, 0)),
        )
    }

    @Test
    fun `snooze is listed even when the alarm has no later occurrence today`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = TodayAlarmTimes.Snooze(alarmId = 1, dueAt = instant(7, 5))

        // The 07:00 occurrence already rang; only the snoozed repeat is still ahead.
        assertEquals(listOf(instant(7, 5)), TodayAlarmTimes.resolve(listOf(alarm), snooze, at(7, 1)))
    }

    // --- ordering and duplicates -------------------------------------------

    @Test
    fun `result is sorted ascending and keeps duplicate times`() {
        val first = Alarm(id = 1, hour = 8, minute = 0)
        val second = Alarm(id = 2, hour = 6, minute = 30)
        val third = Alarm(id = 3, hour = 8, minute = 0)
        val snooze = TodayAlarmTimes.Snooze(alarmId = 2, dueAt = instant(8, 0))

        assertEquals(
            listOf(instant(6, 30), instant(8, 0), instant(8, 0), instant(8, 0)),
            TodayAlarmTimes.resolve(listOf(first, second, third), snooze, at(6, 0)),
        )
    }
}
