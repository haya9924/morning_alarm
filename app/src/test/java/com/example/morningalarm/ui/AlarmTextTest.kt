package com.example.morningalarm.ui

import com.example.morningalarm.data.Alarm
import com.example.morningalarm.data.RepeatDays
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTextTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = ZonedDateTime.of(2026, 3, 9, 6, 0, 0, 0, zone) // Monday

    // --- weekday selection (regression: days could never be re-selected) ----

    @Test
    fun `deselecting a day from every day leaves the other six`() {
        val result = AlarmText.toggledDays(RepeatDays.EVERY_DAY, DayOfWeek.MONDAY)
        assertEquals(RepeatDays.EVERY_DAY - DayOfWeek.MONDAY, result)
    }

    /** Regression: the old `days - day` UI could not add a day back. */
    @Test
    fun `a deselected day can be selected again`() {
        val withoutMonday = AlarmText.toggledDays(RepeatDays.EVERY_DAY, DayOfWeek.MONDAY)
        val restored = AlarmText.toggledDays(withoutMonday, DayOfWeek.MONDAY)
        assertEquals(RepeatDays.EVERY_DAY, restored)
    }

    @Test
    fun `empty selection is treated as every day before toggling`() {
        val result = AlarmText.toggledDays(emptySet(), DayOfWeek.WEDNESDAY)
        assertEquals(RepeatDays.EVERY_DAY - DayOfWeek.WEDNESDAY, result)
    }

    /** Deselecting the last day must not persist "never rings". */
    @Test
    fun `deselecting the last selected day falls back to every day`() {
        val result = AlarmText.toggledDays(setOf(DayOfWeek.FRIDAY), DayOfWeek.FRIDAY)
        assertEquals(RepeatDays.EVERY_DAY, result)
    }

    @Test
    fun `toggling an unselected day twice restores the selection`() {
        val withSaturday = AlarmText.toggledDays(RepeatDays.WEEKDAYS, DayOfWeek.SATURDAY)
        val restored = AlarmText.toggledDays(withSaturday, DayOfWeek.SATURDAY)
        assertEquals(RepeatDays.WEEKDAYS, restored)
    }

    // --- summaries ---------------------------------------------------------

    @Test
    fun `repeat summary uses japanese shorthands`() {
        assertEquals("毎日", AlarmText.repeatSummary(RepeatDays.EVERY_DAY))
        assertEquals("平日", AlarmText.repeatSummary(RepeatDays.WEEKDAYS))
        assertEquals("土日", AlarmText.repeatSummary(RepeatDays.WEEKEND))
        assertEquals("月・水", AlarmText.repeatSummary(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))
        assertEquals("毎日", AlarmText.repeatSummary(emptySet()))
    }

    @Test
    fun `time and date formatting are zero padded`() {
        assertEquals("07:05", AlarmText.formatTime(7, 5))
        assertEquals("07:05", AlarmText.formatTime(java.time.LocalTime.of(7, 5)))
        assertEquals("3月9日(月)", AlarmText.formatDate(LocalDate.of(2026, 3, 9)))
    }

    // --- adjustments -------------------------------------------------------

    @Test
    fun `next occurrence text includes the override time`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 9, 6, 15),
        )
        assertEquals("次回 3月9日(月) 06:15", AlarmText.nextOccurrenceText(alarm, now))
    }

    @Test
    fun `next occurrence text is null only when there is no occurrence`() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        assertEquals("次回 3月9日(月) 07:00", AlarmText.nextOccurrenceText(alarm, now))
    }

    @Test
    fun `adjustment text lists skip and override target dates`() {
        val alarm = Alarm(
            id = 1,
            hour = 7,
            minute = 0,
            skipNextDate = LocalDate.of(2026, 3, 10),
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 11, 6, 15),
        )
        val text = AlarmText.nextAdjustmentText(alarm)!!

        assertTrue(text.contains("3月10日(火) はスキップ"))
        assertTrue(text.contains("3月11日(水) は 06:15 に変更"))
    }

    @Test
    fun `adjustment text is null without adjustments`() {
        assertNull(AlarmText.nextAdjustmentText(Alarm(id = 1, hour = 7, minute = 0)))
    }
}
