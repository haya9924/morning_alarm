package com.example.morningalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRepositoryTest {
    private class FakeStorage(initial: List<Alarm> = emptyList()) : AlarmStorage {
        var stored: List<Alarm> = initial
        var saveCount = 0
        var failSaves = false

        override fun load(): List<Alarm> = stored

        override fun save(alarms: List<Alarm>): Boolean {
            if (failSaves) return false
            stored = alarms
            saveCount++
            return true
        }
    }

    @Test
    fun `add assigns increasing ids and persists sorted list`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage)

        val first = repository.add(8, 30, "  late  ")
        val second = repository.add(6, 15, "early")

        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
        assertEquals("late", first.label)
        assertEquals(listOf(second, first), repository.alarms.value)
        assertEquals(repository.alarms.value, storage.stored)
        assertEquals(2, storage.saveCount)
    }

    @Test
    fun `toggle persists enabled flag`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage)
        val alarm = repository.add(7, 0)

        val updated = repository.setEnabled(alarm.id, false)

        assertFalse(updated!!.enabled)
        assertFalse(repository.get(alarm.id)!!.enabled)
        assertFalse(storage.stored.single().enabled)
    }

    @Test
    fun `delete removes and reports existence`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage)
        val alarm = repository.add(7, 0)

        assertTrue(repository.delete(alarm.id))
        assertFalse(repository.delete(alarm.id))
        assertNull(repository.get(alarm.id))
        assertTrue(storage.stored.isEmpty())
    }

    @Test
    fun `loaded alarms are validated and sorted`() {
        val storage = FakeStorage(
            listOf(
                Alarm(id = 5, hour = 9, minute = 0),
                Alarm(id = 0, hour = 7, minute = 0),
                Alarm(id = 3, hour = 6, minute = 30),
                Alarm(id = 5, hour = 10, minute = 0),
            ),
        )
        val repository = AlarmRepository(storage)

        assertEquals(listOf(3L, 5L), repository.alarms.value.map { it.id })
        assertEquals(6, repository.alarms.value.first().hour)
    }

    @Test
    fun `failed save throws and leaves in-memory state unchanged`() {
        val storage = FakeStorage().apply { failSaves = true }
        val repository = AlarmRepository(storage)

        assertThrows(IllegalStateException::class.java) {
            repository.add(7, 0, "should not persist")
        }
        assertTrue(repository.alarms.value.isEmpty())
        assertEquals(0, storage.saveCount)
    }

    @Test
    fun `injected id generator is used`() {
        val repository = AlarmRepository(FakeStorage()) { 42L }
        assertEquals(42L, repository.add(7, 0).id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid hour is rejected`() {
        AlarmRepository(FakeStorage()).add(25, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid minute is rejected`() {
        AlarmRepository(FakeStorage()).add(7, 61)
    }
}
