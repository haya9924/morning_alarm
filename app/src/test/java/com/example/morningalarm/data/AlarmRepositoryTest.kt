package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRepositoryTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    /** Monday 2026-03-09 06:00. */
    private val now = ZonedDateTime.of(2026, 3, 9, 6, 0, 0, 0, zone)

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

    /** Repository whose clock can be moved forward between calls. */
    private class Clock(var current: ZonedDateTime) {
        fun advanceTo(value: ZonedDateTime) {
            current = value
        }
    }

    private fun repositoryWithClock(
        storage: AlarmStorage = FakeStorage(),
        clock: Clock,
    ): AlarmRepository = AlarmRepository(storage, idGenerator = null, now = { clock.current })

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
        val repository = AlarmRepository(FakeStorage(), idGenerator = { 42L })
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

    // --- weekday repeat -----------------------------------------------------

    @Test
    fun `add stores the selected weekdays`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })

        val alarm = repository.add(7, 0, "weekday", RepeatDays.WEEKDAYS)

        assertEquals(RepeatDays.WEEKDAYS, alarm.repeatDays)
        assertEquals(RepeatDays.WEEKDAYS, storage.stored.single().repeatDays)
    }

    @Test
    fun `add with no weekdays falls back to every day`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        assertEquals(RepeatDays.EVERY_DAY, repository.add(7, 0, "", emptySet()).repeatDays)
    }

    @Test
    fun `setRepeatDays persists and rejects unknown ids`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0)

        val updated = repository.setRepeatDays(alarm.id, RepeatDays.WEEKEND)!!

        assertEquals(RepeatDays.WEEKEND, updated.repeatDays)
        assertEquals(RepeatDays.WEEKEND, storage.stored.single().repeatDays)
        assertNull(repository.setRepeatDays(999L, RepeatDays.WEEKDAYS))
    }

    @Test
    fun `empty weekday selection is normalized to every day`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0, "", RepeatDays.WEEKDAYS)

        val updated = repository.setRepeatDays(alarm.id, emptySet())!!

        assertEquals(RepeatDays.EVERY_DAY, updated.repeatDays)
    }

    /** Legacy rows stored before weekday support are normalized on load. */
    @Test
    fun `legacy empty weekday selection is normalized on load`() {
        val storage = FakeStorage(listOf(Alarm(id = 1, hour = 7, minute = 0, repeatDays = emptySet())))
        val repository = AlarmRepository(storage, now = { now })

        assertEquals(RepeatDays.EVERY_DAY, repository.get(1)!!.repeatDays)
    }

    @Test
    fun `update replaces time label and weekdays in one save`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0, "old")
        val savesBefore = storage.saveCount

        val updated = repository.update(
            id = alarm.id,
            hour = 6,
            minute = 30,
            label = "  new  ",
            repeatDays = setOf(DayOfWeek.SATURDAY),
        )!!

        assertEquals(6, updated.hour)
        assertEquals(30, updated.minute)
        assertEquals("new", updated.label)
        assertEquals(setOf(DayOfWeek.SATURDAY), updated.repeatDays)
        assertEquals(savesBefore + 1, storage.saveCount)
        assertEquals(updated, storage.stored.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `update rejects invalid hour`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0)
        repository.update(alarm.id, 24, 0, "", RepeatDays.EVERY_DAY)
    }

    @Test
    fun `changing the schedule clears pending one-shot adjustments`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0)
        repository.skipNext(alarm.id)
        repository.setNextTimeOverride(alarm.id, LocalTime.of(6, 15))

        val updated = repository.update(alarm.id, 8, 0, "", RepeatDays.EVERY_DAY)!!

        assertNull(updated.skipNextDate)
        assertNull(updated.nextTimeOverrideAt)
    }

    @Test
    fun `label-only update keeps pending one-shot adjustments`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0)
        val skipped = repository.skipNext(alarm.id)!!

        val updated = repository.update(alarm.id, 7, 0, "renamed", RepeatDays.EVERY_DAY)!!

        assertEquals("renamed", updated.label)
        assertEquals(skipped.skipNextDate, updated.skipNextDate)
    }

    // --- one-shot: skip next -----------------------------------------------

    @Test
    fun `skipNext stores the next occurrence date and persists it`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0)

        val updated = repository.skipNext(alarm.id)!!

        // Monday 06:00 -> next occurrence is today (2026-03-09).
        assertEquals(LocalDate.of(2026, 3, 9), updated.skipNextDate)
        assertEquals(LocalDate.of(2026, 3, 9), storage.stored.single().skipNextDate)
        assertEquals(LocalDate.of(2026, 3, 10), NextOccurrence.nextDate(updated, now))
    }

    /** Regression: pressing skip twice must not move the skip to the next day. */
    @Test
    fun `skipNext twice with the same clock is idempotent`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0)

        val first = repository.skipNext(alarm.id)!!
        val savesAfterFirst = storage.saveCount
        val second = repository.skipNext(alarm.id)!!

        assertEquals(first, second)
        assertEquals(LocalDate.of(2026, 3, 9), second.skipNextDate)
        assertEquals(savesAfterFirst, storage.saveCount)
    }

    /** Regression: skip must not consume the *following* occurrence by mistake. */
    @Test
    fun `skipNext twice still skips only one occurrence`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0)

        repository.skipNext(alarm.id)
        val twice = repository.skipNext(alarm.id)!!

        // 3/9 skipped once; 3/10 is the next ring, not 3/11.
        assertEquals(LocalDate.of(2026, 3, 10), NextOccurrence.nextDate(twice, now))
    }

    @Test
    fun `skipNext supports weekly alarms`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0, "fri", setOf(DayOfWeek.FRIDAY))

        val updated = repository.skipNext(alarm.id)!!

        assertEquals(LocalDate.of(2026, 3, 13), updated.skipNextDate)
        assertEquals(LocalDate.of(2026, 3, 20), NextOccurrence.nextDate(updated, now))
    }

    /** Regression: a weekly alarm still resolves after its only skip. */
    @Test
    fun `skipNext on a weekly alarm still leaves the following week scheduled`() {
        val storage = FakeStorage()
        val clock = Clock(now)
        val repository = repositoryWithClock(storage, clock)
        val alarm = repository.add(7, 0, "mon", setOf(DayOfWeek.MONDAY))

        val skipped = repository.skipNext(alarm.id)!!
        // Ring time passes; the skipping is consumed and the next week is scheduled.
        clock.advanceTo(ZonedDateTime.of(2026, 3, 16, 7, 0, 1, 0, zone))
        val consumed = repository.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 16),
            usedTimeOverride = false,
        )!!

        assertNull(consumed.alarm.skipNextDate)
        assertEquals(LocalDate.of(2026, 3, 23), NextOccurrence.nextDate(consumed.alarm, clock.current))
        assertEquals(LocalDate.of(2026, 3, 9), skipped.skipNextDate)
    }

    @Test
    fun `skipNext returns null for unknown ids`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        assertNull(repository.skipNext(999L))
    }

    // --- one-shot: next time only ------------------------------------------

    @Test
    fun `setNextTimeOverride persists the target date and can be cleared`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0)

        val withOverride = repository.setNextTimeOverride(alarm.id, LocalTime.of(6, 15))!!
        assertEquals(LocalDateTime.of(2026, 3, 9, 6, 15), withOverride.nextTimeOverrideAt)
        assertEquals(
            LocalDateTime.of(2026, 3, 9, 6, 15),
            storage.stored.single().nextTimeOverrideAt,
        )
        assertEquals(6, NextOccurrence.next(withOverride, now)!!.hour)

        val cleared = repository.setNextTimeOverride(alarm.id, null)!!
        assertNull(cleared.nextTimeOverrideAt)
        assertNull(storage.stored.single().nextTimeOverrideAt)
    }

    /**
     * Regression: a later override keeps its target date when the regular time has
     * already passed and the alarm is registered again.
     */
    @Test
    fun `later override does not lose its target date after the regular time`() {
        val storage = FakeStorage()
        val clock = Clock(now)
        val repository = repositoryWithClock(storage, clock)
        val alarm = repository.add(7, 0)

        val withOverride = repository.setNextTimeOverride(alarm.id, LocalTime.of(9, 0))!!
        assertEquals(LocalDateTime.of(2026, 3, 9, 9, 0), withOverride.nextTimeOverrideAt)

        // 07:30, after the regular 07:00. A time-only model would move the override
        // to 3/10.
        clock.advanceTo(ZonedDateTime.of(2026, 3, 9, 7, 30, 0, 0, zone))

        assertEquals(
            LocalDateTime.of(2026, 3, 9, 9, 0),
            NextOccurrence.resolve(withOverride, clock.current)!!.at.toLocalDateTime(),
        )
    }

    @Test
    fun `override time already past today moves to the next matching date`() {
        val storage = FakeStorage()
        val clock = Clock(ZonedDateTime.of(2026, 3, 9, 8, 0, 0, 0, zone))
        val repository = repositoryWithClock(storage, clock)
        val alarm = repository.add(7, 0)

        val withOverride = repository.setNextTimeOverride(alarm.id, LocalTime.of(6, 0))!!

        assertEquals(LocalDateTime.of(2026, 3, 10, 6, 0), withOverride.nextTimeOverrideAt)
    }

    @Test
    fun `explicit target date is honored when it is today and still ahead`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0)

        val updated = repository.setNextTimeOverride(
            id = alarm.id,
            time = LocalTime.of(8, 0),
            date = LocalDate.of(2026, 3, 11),
        )!!

        assertEquals(LocalDateTime.of(2026, 3, 11, 8, 0), updated.nextTimeOverrideAt)
    }

    @Test
    fun `clearNextAdjustment removes skip and time override together`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0)
        repository.skipNext(alarm.id)
        repository.setNextTimeOverride(alarm.id, LocalTime.of(6, 15))

        val cleared = repository.clearNextAdjustment(alarm.id)!!

        assertNull(cleared.skipNextDate)
        assertNull(cleared.nextTimeOverrideAt)
        assertFalse(cleared.hasNextAdjustment)
        assertNull(storage.stored.single().skipNextDate)
    }

    // --- consumption after firing ------------------------------------------

    @Test
    fun `consumeAfterFire clears an applied time override`() {
        val storage = FakeStorage()
        val repository = AlarmRepository(storage, now = { now })
        val alarm = repository.add(7, 0)
        repository.setNextTimeOverride(alarm.id, LocalTime.of(6, 15))

        val consumed = repository.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 9),
            usedTimeOverride = true,
        )!!

        assertTrue(consumed.shouldRing)
        assertNull(consumed.alarm.nextTimeOverrideAt)
        assertNull(storage.stored.single().nextTimeOverrideAt)
        assertEquals(LocalDate.of(2026, 3, 9), consumed.alarm.lastFiredDate)
        // The regular time is restored for the following occurrence.
        val afterFire = ZonedDateTime.of(2026, 3, 9, 6, 15, 1, 0, zone)
        assertEquals(7, NextOccurrence.next(consumed.alarm, afterFire)!!.hour)
        assertEquals(LocalDate.of(2026, 3, 10), NextOccurrence.nextDate(consumed.alarm, afterFire))
    }

    @Test
    fun `consumeAfterFire keeps an unused time override`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0)
        repository.setNextTimeOverride(alarm.id, LocalTime.of(6, 15))

        val consumed = repository.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 9),
            usedTimeOverride = false,
        )!!

        assertTrue(consumed.shouldRing)
        assertNull(consumed.alarm.nextTimeOverrideAt)
    }

    /** Regression: an early override must not let the regular time re-fire that day. */
    @Test
    fun `regular time does not ring again after an early override fired`() {
        val storage = FakeStorage()
        val clock = Clock(ZonedDateTime.of(2026, 3, 9, 5, 30, 0, 0, zone))
        val repository = repositoryWithClock(storage, clock)
        val alarm = repository.add(9, 0)

        val withOverride = repository.setNextTimeOverride(alarm.id, LocalTime.of(6, 0))!!
        assertEquals(LocalDateTime.of(2026, 3, 9, 6, 0), withOverride.nextTimeOverrideAt)
        // 06:00 override fires.
        clock.advanceTo(ZonedDateTime.of(2026, 3, 9, 6, 0, 1, 0, zone))
        val first = repository.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 9),
            usedTimeOverride = true,
        )!!

        assertTrue(first.shouldRing)
        assertNull(first.alarm.nextTimeOverrideAt)

        // Now the regular 09:00 PendingIntent is still registered at AlarmManager
        // (the earlier override was scheduled separately); it fires.
        clock.advanceTo(ZonedDateTime.of(2026, 3, 9, 9, 0, 1, 0, zone))
        val second = repository.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 9),
            usedTimeOverride = false,
        )!!

        assertFalse(second.shouldRing)
        assertEquals(LocalDate.of(2026, 3, 9), second.alarm.lastFiredDate)
        // Next ring is the following day's regular time, not today at 09:00.
        assertEquals(
            LocalDate.of(2026, 3, 10),
            NextOccurrence.nextDate(second.alarm, clock.current),
        )
        assertEquals(withOverride.nextTimeOverrideAt, LocalDateTime.of(2026, 3, 9, 6, 0))
    }

    @Test
    fun `consumeAfterFire removes a fired skip date`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0)
        val skipped = repository.skipNext(alarm.id)!!
        val skippedDate = skipped.skipNextDate!!

        // The following occurrence fires (2026-03-10): the skip is now spent.
        val consumed = repository.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 10),
            usedTimeOverride = false,
        )!!

        assertNull(consumed.alarm.skipNextDate)
        // Resolve from after the fired occurrence, not from the stale injected clock.
        val afterFire = ZonedDateTime.of(2026, 3, 10, 7, 0, 1, 0, zone)
        assertEquals(LocalDate.of(2026, 3, 11), NextOccurrence.nextDate(consumed.alarm, afterFire))
        assertEquals(LocalDate.of(2026, 3, 9), skippedDate)
    }

    @Test
    fun `consumeAfterFire keeps a skip date that is still ahead`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        val alarm = repository.add(7, 0)
        val skipped = repository.skipNext(alarm.id)!!

        // A different, earlier occurrence fires (clock changed): keep the skip.
        val consumed = repository.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 8),
            usedTimeOverride = false,
        )!!

        assertTrue(consumed.shouldRing)
        assertEquals(skipped.skipNextDate, consumed.alarm.skipNextDate)
    }

    @Test
    fun `consumeAfterFire returns null for a deleted alarm`() {
        val repository = AlarmRepository(FakeStorage(), now = { now })
        assertNull(
            repository.consumeAfterFire(
                id = 42L,
                firedDate = LocalDate.of(2026, 3, 9),
                usedTimeOverride = false,
            ),
        )
    }

    @Test
    fun `one-shot state survives a reload from storage`() {
        val storage = FakeStorage()
        val first = AlarmRepository(storage, now = { now })
        val alarm = first.add(7, 0, "fri", setOf(DayOfWeek.FRIDAY))
        first.skipNext(alarm.id)
        first.setNextTimeOverride(alarm.id, LocalTime.of(6, 15))

        val reloaded = AlarmRepository(storage, now = { now })
        val restored = reloaded.get(alarm.id)!!

        // The skip consumed 3/13, so "next time only" targets the following Friday.
        assertEquals(LocalDate.of(2026, 3, 13), restored.skipNextDate)
        assertEquals(LocalDateTime.of(2026, 3, 20, 6, 15), restored.nextTimeOverrideAt)
        assertEquals(setOf(DayOfWeek.FRIDAY), restored.repeatDays)
        // The skip plus override move the next fire to the following week.
        assertEquals(LocalDate.of(2026, 3, 20), NextOccurrence.nextDate(restored, now))
    }

    @Test
    fun `stale adjustments are dropped on load`() {
        val storage = FakeStorage(
            listOf(
                Alarm(
                    id = 1,
                    hour = 7,
                    minute = 0,
                    skipNextDate = LocalDate.of(2026, 3, 1),
                    nextTimeOverrideAt = LocalDateTime.of(2026, 3, 1, 6, 0),
                ),
            ),
        )
        val repository = AlarmRepository(storage, now = { now })
        val loaded = repository.get(1)!!

        assertNull(loaded.skipNextDate)
        assertNull(loaded.nextTimeOverrideAt)
    }

    @Test
    fun `last fired date survives a reload so it cannot double fire`() {
        val storage = FakeStorage()
        val first = AlarmRepository(storage, now = { now })
        val alarm = first.add(9, 0)
        first.consumeAfterFire(
            id = alarm.id,
            firedDate = LocalDate.of(2026, 3, 9),
            usedTimeOverride = false,
        )

        val reloaded = AlarmRepository(storage, now = { now })
        assertEquals(LocalDate.of(2026, 3, 9), reloaded.get(alarm.id)!!.lastFiredDate)
        assertEquals(
            LocalDate.of(2026, 3, 10),
            NextOccurrence.nextDate(reloaded.get(alarm.id)!!, now),
        )
    }
}
