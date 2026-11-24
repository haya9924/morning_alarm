package com.example.morningalarm.data

import com.example.morningalarm.trace.TraceDifficulty
import org.junit.Assert.*
import org.junit.Test

class TraceSettingsRepositoryTest {
    private class Storage(var key: String? = null, var ok: Boolean = true) : TraceSettingsStorage {
        var writes = 0
        override fun read() = key
        override fun save(key: String): Boolean { writes++; if (ok) this.key = key; return ok }
    }
    @Test fun defaultAndUnknownAreEasyWithoutSaving() {
        for (key in listOf(null, "unknown")) {
            val s = Storage(key)
            assertEquals(TraceDifficulty.EASY, TraceSettingsRepository(s).difficulty.value)
            assertEquals(0, s.writes)
        }
    }
    @Test fun savesAndRestores() {
        val s = Storage()
        TraceSettingsRepository(s).setDifficulty(TraceDifficulty.LEGACY)
        assertEquals(TraceDifficulty.LEGACY, TraceSettingsRepository(s).difficulty.value)
    }
    @Test fun failureKeepsOldSetting() {
        val r = TraceSettingsRepository(Storage(ok = false))
        try { r.setDifficulty(TraceDifficulty.LEGACY); fail("must reject") } catch (_: IllegalStateException) { }
        assertEquals(TraceDifficulty.EASY, r.difficulty.value)
    }
    @Test fun customToleranceSurvivesReloadAndEqualValuesDoNotSave() {
        val storage = Storage()
        val repository = TraceSettingsRepository(storage)
        val custom = TraceDifficulty.fromTolerance(0.051234f)
        repository.setDifficulty(custom)
        assertEquals(custom, TraceSettingsRepository(storage).difficulty.value)
        assertEquals(0.051234f, TraceSettingsRepository(storage).difficulty.value.tolerance, 0f)
        repository.setDifficulty(TraceDifficulty.fromTolerance(0.051234f))
        assertEquals(1, storage.writes)
    }
    @Test fun failedCustomSaveKeepsStoredAndPublishedValues() {
        val storage = Storage("legacy", ok = false)
        val repository = TraceSettingsRepository(storage)
        try {
            repository.setDifficulty(TraceDifficulty.fromTolerance(0.051234f))
            fail("must reject")
        } catch (_: IllegalStateException) { }
        assertEquals("legacy", storage.key)
        assertEquals(TraceDifficulty.LEGACY, repository.difficulty.value)
    }
    @Test fun invalidCustomToleranceFallsBackWithoutSaving() {
        val storage = Storage("tolerance:NaN")
        assertEquals(TraceDifficulty.DEFAULT, TraceSettingsRepository(storage).difficulty.value)
        assertEquals(0, storage.writes)
    }
    @Test fun unchangedDoesNotSave() {
        val s = Storage()
        TraceSettingsRepository(s).setDifficulty(TraceDifficulty.EASY)
        assertEquals(0, s.writes)
    }
}
