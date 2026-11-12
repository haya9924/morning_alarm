package com.example.morningalarm.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesProtocolTest {
    @Test
    fun `valid create request is normalized`() {
        val request = HermesProtocol.Args.create(7, 30, "  Wake up  ").getOrThrow()
        assertEquals(7, request.hour)
        assertEquals(30, request.minute)
        assertEquals("Wake up", request.label)
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
}
