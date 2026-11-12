package com.example.morningalarm.alarm

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextOccurrenceTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun at(hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 3, 10, hour, minute, second, 0, zone)

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
}
