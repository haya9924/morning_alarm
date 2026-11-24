package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextAlarmTimeTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun at(hour: Int, minute: Int, day: Int = 10): ZonedDateTime =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone)

    private fun instant(hour: Int, minute: Int, day: Int = 10): Instant =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone).toInstant()

    // --- today / tomorrow boundaries ---------------------------------------

    @Test
    fun `alarm later today is returned`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30)

        assertEquals(instant(7, 30), NextAlarmTime.resolve(listOf(alarm), null, at(6, 0)))
    }

    @Test
    fun `alarm already passed today yields tomorrow`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30)

        assertEquals(instant(7, 30, day = 11), NextAlarmTime.resolve(listOf(alarm), null, at(8, 0)))
    }

    @Test
    fun `alarm exactly at now yields tomorrow`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30)

        assertEquals(
            instant(7, 30, day = 11),
            NextAlarmTime.resolve(listOf(alarm), null, at(7, 30)),
        )
    }

    @Test
    fun `no alarms resolves to null`() {
        assertNull(NextAlarmTime.resolve(emptyList(), null, at(6, 0)))
    }

    @Test
    fun `no enabled alarm resolves to null`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 30, enabled = false)

        assertNull(NextAlarmTime.resolve(listOf(alarm), null, at(6, 0)))
    }

    // --- weekday repeat / skip / override / lastFired ----------------------

    @Test
    fun `weekday repeat selects the next matching day`() {
        // 2026-03-10 is a Tuesday; the next Monday is 2026-03-16.
        val monday = Alarm(id = 1, hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY))

        assertEquals(instant(7, 0, day = 16), NextAlarmTime.resolve(listOf(monday), null, at(6, 0)))
    }

    @Test
    fun `skip next date moves the alarm to the following occurrence`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.TUESDAY),
            skipNextDate = LocalDate.of(2026, 3, 10),
        )

        assertEquals(instant(7, 0, day = 17), NextAlarmTime.resolve(listOf(alarm), null, at(6, 0)))
    }

    @Test
    fun `time override replaces the next occurrence`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 6, 15),
        )

        assertEquals(instant(6, 15), NextAlarmTime.resolve(listOf(alarm), null, at(5, 0)))
    }

    @Test
    fun `override pinned past the next regular occurrence does not hide it`() {
        val alarm = Alarm(
            id = 1,
            hour = 9,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 12, 6, 15),
        )

        assertEquals(instant(9, 0), NextAlarmTime.resolve(listOf(alarm), null, at(7, 0)))
    }

    @Test
    fun `already fired date is skipped even before the regular time`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, lastFiredDate = LocalDate.of(2026, 3, 10))

        assertEquals(instant(7, 0, day = 11), NextAlarmTime.resolve(listOf(alarm), null, at(6, 0)))
    }

    // --- snooze -------------------------------------------------------------

    @Test
    fun `snooze across midnight is earlier than the next regular alarm`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = NextAlarmTime.Snooze(alarmId = 1, dueAt = instant(0, 5, day = 11))

        assertEquals(
            instant(0, 5, day = 11),
            NextAlarmTime.resolve(listOf(alarm), snooze, at(23, 50)),
        )
    }

    @Test
    fun `snooze of a disabled alarm is ignored`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, enabled = false)
        val snooze = NextAlarmTime.Snooze(alarmId = 1, dueAt = instant(7, 5))

        assertNull(NextAlarmTime.resolve(listOf(alarm), snooze, at(6, 0)))
    }

    @Test
    fun `snooze referencing a deleted alarm is ignored`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = NextAlarmTime.Snooze(alarmId = 2, dueAt = instant(6, 30))

        assertEquals(instant(7, 0), NextAlarmTime.resolve(listOf(alarm), snooze, at(6, 0)))
    }

    @Test
    fun `past snooze is ignored`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = NextAlarmTime.Snooze(alarmId = 1, dueAt = instant(5, 55))

        assertEquals(instant(7, 0), NextAlarmTime.resolve(listOf(alarm), snooze, at(6, 0)))
    }

    @Test
    fun `snooze due exactly now is ignored`() {
        val alarm = Alarm(id = 1, hour = 8, minute = 0)
        val snooze = NextAlarmTime.Snooze(alarmId = 1, dueAt = instant(6, 0))

        assertEquals(instant(8, 0), NextAlarmTime.resolve(listOf(alarm), snooze, at(6, 0)))
    }

    // --- earliest selection -------------------------------------------------

    @Test
    fun `earliest of all alarms and the snooze is returned`() {
        val later = Alarm(id = 1, hour = 8, minute = 0)
        val earlier = Alarm(id = 2, hour = 6, minute = 30)
        val snooze = NextAlarmTime.Snooze(alarmId = 1, dueAt = instant(7, 0))

        assertEquals(
            instant(6, 30),
            NextAlarmTime.resolve(listOf(later, earlier), snooze, at(6, 0)),
        )
    }

    @Test
    fun `snooze wins when it is the earliest`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        val snooze = NextAlarmTime.Snooze(alarmId = 1, dueAt = instant(6, 10))

        assertEquals(instant(6, 10), NextAlarmTime.resolve(listOf(alarm), snooze, at(6, 0)))
    }
}
