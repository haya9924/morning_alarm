package com.example.morningalarm.data

import org.junit.Assert.assertEquals
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
