package com.example.morningalarm.hermes

import com.example.morningalarm.data.Alarm
import com.example.morningalarm.data.RepeatDays
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesProtocolTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = ZonedDateTime.of(2026, 3, 9, 6, 0, 0, 0, zone) // Monday

    @Test
    fun `valid create request is normalized`() {
        val request = HermesProtocol.Args.create(7, 30, "  Wake up  ").getOrThrow()
        assertEquals(7, request.hour)
        assertEquals(30, request.minute)
        assertEquals("Wake up", request.label)
        assertEquals(RepeatDays.EVERY_DAY, request.repeatDays)
    }

    @Test
    fun `missing or out of range time is rejected`() {
        assertTrue(HermesProtocol.Args.create(null, 30, null).isFailure)
        assertTrue(HermesProtocol.Args.create(24, 30, null).isFailure)
        assertTrue(HermesProtocol.Args.create(-1, 30, null).isFailure)
        assertTrue(HermesProtocol.Args.create(7, null, null).isFailure)
        assertTrue(HermesProtocol.Args.create(7, 60, null).isFailure)
    }

    @Test
    fun `null label becomes empty and long label is bounded`() {
        val empty = HermesProtocol.Args.create(0, 0, null).getOrThrow()
        assertEquals("", empty.label)

        val long = HermesProtocol.Args.create(23, 59, "x".repeat(500)).getOrThrow()
        assertEquals(80, long.label.length)
    }

    @Test
    fun `create accepts repeat days`() {
        val request = HermesProtocol.Args.create(7, 0, null, "monday, friday").getOrThrow()
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), request.repeatDays)
    }

    @Test
    fun `blank repeat days mean every day`() {
        assertEquals(
            RepeatDays.EVERY_DAY,
            HermesProtocol.Args.create(7, 0, null, null).getOrThrow().repeatDays,
        )
        assertEquals(
            RepeatDays.EVERY_DAY,
            HermesProtocol.Args.create(7, 0, null, "  ").getOrThrow().repeatDays,
        )
    }

    @Test
    fun `unknown weekday is rejected`() {
        assertTrue(HermesProtocol.Args.repeatDays("FUNDAY").isFailure)
        assertTrue(HermesProtocol.Args.repeatDays("MONDAY,FUNDAY").isFailure)
    }

    @Test
    fun `alarm id must be positive`() {
        assertTrue(HermesProtocol.Args.alarmId(null).isFailure)
        assertTrue(HermesProtocol.Args.alarmId(0L).isFailure)
        assertTrue(HermesProtocol.Args.alarmId(-1L).isFailure)
        assertEquals(7L, HermesProtocol.Args.alarmId(7L).getOrThrow())
    }

    @Test
    fun `enabled flag requires a real boolean`() {
        assertEquals(true, HermesProtocol.Args.enabled(true).getOrThrow())
        assertEquals(false, HermesProtocol.Args.enabled(false).getOrThrow())

        assertTrue(HermesProtocol.Args.enabled(null).isFailure)
        assertTrue(HermesProtocol.Args.enabled("true").isFailure)
        assertTrue(HermesProtocol.Args.enabled(1).isFailure)
        assertTrue(HermesProtocol.Args.enabled(1L).isFailure)
    }

    // --- one-shot next time ------------------------------------------------

    @Test
    fun `blank or none clears the next time override`() {
        assertNull(HermesProtocol.Args.nextTimeOverride(null).getOrThrow())
        assertNull(HermesProtocol.Args.nextTimeOverride("").getOrThrow())
        assertNull(HermesProtocol.Args.nextTimeOverride(" none ").getOrThrow())
    }

    @Test
    fun `explicit date and time is parsed as the target`() {
        assertEquals(
            LocalDateTime.of(2026, 3, 12, 6, 45),
            HermesProtocol.Args.nextTimeOverride("2026-03-12T06:45").getOrThrow(),
        )
    }

    @Test
    fun `bare time is accepted for the repository to resolve`() {
        val parsed = HermesProtocol.Args.nextTimeOverride("06:45").getOrThrow()!!
        assertEquals(6, parsed.hour)
        assertEquals(45, parsed.minute)
    }

    @Test
    fun `invalid next time is rejected`() {
        assertTrue(HermesProtocol.Args.nextTimeOverride("25:99").isFailure)
        assertTrue(HermesProtocol.Args.nextTimeOverride("tomorrow").isFailure)
    }

    // --- result maps -------------------------------------------------------

    @Test
    fun `alarm map exposes the one-shot target dates`() {
        val alarm = Alarm(
            id = 3,
            hour = 7,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            skipNextDate = LocalDate.of(2026, 3, 9),
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 16, 6, 15),
            lastFiredDate = LocalDate.of(2026, 3, 2),
        )
        val map = HermesProtocol.alarmToMap(alarm)

        assertEquals(listOf("MONDAY"), map["repeatDays"])
        assertEquals("2026-03-09", map["skipNextDate"])
        assertEquals("06:15", map["nextTimeOverride"])
        assertEquals("2026-03-16T06:15", map["nextTimeOverrideAt"])
        assertEquals("2026-03-02", map["lastFiredDate"])
    }

    @Test
    fun `schedule map resolves the same instant the app would use`() {
        val alarm = Alarm(
            id = 3,
            hour = 7,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 9, 9, 0),
        )
        val map = HermesProtocol.scheduleToMap(alarm, now)

        assertTrue(map["usingTimeOverride"] as Boolean)
        val iso = map["nextAtIso"] as String
        assertEquals("2026-03-09T09:00+09:00[Asia/Tokyo]", iso)
        assertEquals(
            ZonedDateTime.of(2026, 3, 9, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
            map["nextAt"],
        )
    }

    @Test
    fun `combined map contains both alarm fields and schedule`() {
        val map = HermesProtocol.alarmWithScheduleToMap(Alarm(id = 3, hour = 7, minute = 0), now)
        assertEquals(3L, map["id"])
        assertFalse(map["usingTimeOverride"] as Boolean)
        assertEquals("2026-03-09T07:00+09:00[Asia/Tokyo]", map["nextAtIso"])
    }
}
