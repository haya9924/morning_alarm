package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmJsonCodecTest {
    @Test
    fun `round trip preserves fields`() {
        val alarms = listOf(
            Alarm(id = 1, hour = 6, minute = 45, label = "起きる", enabled = true),
            Alarm(id = 2, hour = 7, minute = 0, label = "", enabled = false),
        )

        val decoded = AlarmJsonCodec.decode(AlarmJsonCodec.encode(alarms))

        assertEquals(alarms, decoded)
    }

    @Test
    fun `round trip preserves repeat days and one-shot adjustments`() {
        val alarm = Alarm(
            id = 7,
            hour = 6,
            minute = 30,
            label = "平日",
            enabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            skipNextDate = LocalDate.of(2026, 3, 13),
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 16, 6, 15),
            lastFiredDate = LocalDate.of(2026, 3, 9),
        )

        val decoded = AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(alarm))).single()

        assertEquals(alarm, decoded)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), decoded.repeatDays)
        assertEquals(LocalDate.of(2026, 3, 13), decoded.skipNextDate)
        assertEquals(LocalDateTime.of(2026, 3, 16, 6, 15), decoded.nextTimeOverrideAt)
        assertEquals(LocalDate.of(2026, 3, 9), decoded.lastFiredDate)
    }

    @Test
    fun `override target date survives a round trip`() {
        val alarm = Alarm(
            id = 3,
            hour = 7,
            minute = 0,
            nextTimeOverrideAt = LocalDateTime.of(2026, 3, 10, 9, 30),
        )

        val decoded = AlarmJsonCodec.decode(AlarmJsonCodec.encode(listOf(alarm))).single()

        assertEquals(LocalDate.of(2026, 3, 10), decoded.nextTimeOverrideAt?.toLocalDate())
        assertEquals(9, decoded.nextTimeOverrideAt?.hour)
        assertEquals(30, decoded.nextTimeOverrideAt?.minute)
    }

    @Test
    fun `malformed json yields empty list`() {
        assertTrue(AlarmJsonCodec.decode("{not json").isEmpty())
        assertTrue(AlarmJsonCodec.decode(null).isEmpty())
        assertTrue(AlarmJsonCodec.decode("").isEmpty())
    }

    @Test
    fun `missing optional fields use safe defaults`() {
        val decoded = AlarmJsonCodec.decode("""[{"id":9,"hour":7,"minute":5}]""")
        assertEquals(1, decoded.size)
        assertEquals("", decoded.single().label)
        assertTrue(decoded.single().enabled)
    }

    /** Records written by the previous version have no weekday/one-shot fields. */
    @Test
    fun `legacy record without repeat fields becomes a daily alarm`() {
        val legacy = """[{"id":3,"hour":7,"minute":0,"label":"旧","enabled":true}]"""

        val decoded = AlarmJsonCodec.decode(legacy).single()

        assertEquals(Alarm(id = 3, hour = 7, minute = 0, label = "旧", enabled = true), decoded)
        assertEquals(RepeatDays.EVERY_DAY, decoded.repeatDays)
        assertEquals(RepeatDays.EVERY_DAY, decoded.repeat)
        assertNull(decoded.skipNextDate)
        assertNull(decoded.nextTimeOverrideAt)
        assertNull(decoded.lastFiredDate)
    }

    @Test
    fun `empty repeat day list is read as every day`() {
        val decoded = AlarmJsonCodec.decode(
            """[{"id":1,"hour":7,"minute":0,"repeatDays":[]}]""",
        ).single()
        assertEquals(RepeatDays.EVERY_DAY, decoded.repeatDays)
    }

    @Test
    fun `unknown weekday names are ignored and fall back to daily when nothing remains`() {
        val mixed = AlarmJsonCodec.decode(
            """[{"id":1,"hour":7,"minute":0,"repeatDays":["MONDAY","FUNDAY"]}]""",
        ).single()
        assertEquals(setOf(DayOfWeek.MONDAY), mixed.repeatDays)

        val onlyUnknown = AlarmJsonCodec.decode(
            """[{"id":2,"hour":7,"minute":0,"repeatDays":["FUNDAY"]}]""",
        ).single()
        assertEquals(RepeatDays.EVERY_DAY, onlyUnknown.repeatDays)
    }

    @Test
    fun `weekday names are case insensitive`() {
        val decoded = AlarmJsonCodec.decode(
            """[{"id":1,"hour":7,"minute":0,"repeatDays":["monday"," Sunday "]}]""",
        ).single()
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), decoded.repeatDays)
    }

    @Test
    fun `corrupt one-shot fields do not drop the alarm`() {
        val decoded = AlarmJsonCodec.decode(
            """[{"id":1,"hour":7,"minute":0,"skipNextDate":"not-a-date",""" +
                """"nextTimeOverrideAt":"25:99","lastFiredDate":"2026-13-40"}]""",
        ).single()
        assertEquals(1L, decoded.id)
        assertNull(decoded.skipNextDate)
        assertNull(decoded.nextTimeOverrideAt)
        assertNull(decoded.lastFiredDate)
    }

    /** The intermediate version stored a time without a target date; it is ignored. */
    @Test
    fun `legacy time-only override field is ignored`() {
        val decoded = AlarmJsonCodec.decode(
            """[{"id":1,"hour":7,"minute":0,"nextTimeOverride":"06:45"}]""",
        ).single()
        assertNull(decoded.nextTimeOverrideAt)
        assertEquals(7, decoded.hour)
    }

    @Test
    fun `override is accepted with or without seconds`() {
        val plain = AlarmJsonCodec.decode(
            """[{"id":1,"hour":7,"minute":0,"nextTimeOverrideAt":"2026-03-10T06:45"}]""",
        ).single()
        assertEquals(LocalDateTime.of(2026, 3, 10, 6, 45), plain.nextTimeOverrideAt)

        val withSeconds = AlarmJsonCodec.decode(
            """[{"id":2,"hour":7,"minute":0,"nextTimeOverrideAt":"2026-03-10T06:45:30"}]""",
        ).single()
        assertEquals(LocalDateTime.of(2026, 3, 10, 6, 45, 30), withSeconds.nextTimeOverrideAt)
    }

    @Test
    fun `invalid records are filtered`() {
        val json = """
            [
              {"id":0,"hour":7,"minute":0},
              {"id":1,"hour":24,"minute":0},
              {"id":2,"hour":7,"minute":60},
              {"id":3,"hour":7,"minute":15},
              {"id":3,"hour":8,"minute":15},
              {"id":4,"hour":9,"minute":0,"label":null,"enabled":null}
            ]
        """.trimIndent()

        val decoded = AlarmJsonCodec.decode(json)

        assertEquals(listOf(3L, 4L), decoded.map { it.id })
        assertEquals("", decoded.last().label)
        assertTrue(decoded.last().enabled)
    }

    @Test
    fun `labels are trimmed and bounded`() {
        val longLabel = "x".repeat(200)
        val decoded = AlarmJsonCodec.decode(
            """[{"id":1,"hour":7,"minute":0,"label":"  $longLabel  "}]""",
        )
        assertEquals(Alarm.MAX_LABEL_LENGTH, decoded.single().label.length)
    }
}
