package com.example.morningalarm.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TraceDifficultyTest {
    @Test
    fun presetsKeepToleranceAndStorageKey() {
        assertEquals(0.055f, TraceDifficulty.EASY.tolerance, 0f)
        assertEquals(0.045f, TraceDifficulty.LEGACY.tolerance, 0f)
        assertEquals("easy", TraceDifficulty.EASY.storageKey)
        assertEquals("legacy", TraceDifficulty.LEGACY.storageKey)
        assertSame(TraceDifficulty.EASY, TraceDifficulty.DEFAULT)
        assertEquals(listOf(TraceDifficulty.EASY, TraceDifficulty.LEGACY), TraceDifficulty.entries)
    }

    @Test
    fun customToleranceRoundTripsThroughStorageKey() {
        val custom = TraceDifficulty.fromTolerance(0.051234f)
        assertEquals(0.051234f, custom.tolerance, 0f)
        assertEquals("tolerance:0.051234", custom.storageKey)

        val restored = TraceDifficulty.fromStorageKey(custom.storageKey)
        assertEquals(custom.tolerance, restored.tolerance, 0f)
        assertEquals(custom, restored)
    }

    @Test
    fun toleranceBoundsAreAccepted() {
        assertEquals(TraceDifficulty.MIN_TOLERANCE, TraceDifficulty.fromTolerance(0.030f).tolerance, 0f)
        assertEquals(TraceDifficulty.MAX_TOLERANCE, TraceDifficulty.fromTolerance(0.065f).tolerance, 0f)
        assertEquals(0.030f, TraceDifficulty.MIN_TOLERANCE, 0f)
        assertEquals(0.065f, TraceDifficulty.MAX_TOLERANCE, 0f)
        assertEquals(TraceDifficulty.MIN_TOLERANCE, TraceDifficulty.fromStorageKey("tolerance:0.03").tolerance, 0f)
        assertEquals(TraceDifficulty.MAX_TOLERANCE, TraceDifficulty.fromStorageKey("tolerance:0.065").tolerance, 0f)
    }

    @Test
    fun exactPresetToleranceReturnsPreset() {
        assertSame(TraceDifficulty.EASY, TraceDifficulty.fromTolerance(0.055f))
        assertSame(TraceDifficulty.LEGACY, TraceDifficulty.fromTolerance(0.045f))
        // Storage keys of presets stay the short form, never "tolerance:...".
        assertEquals("easy", TraceDifficulty.fromStorageKey("tolerance:0.055").storageKey)
        assertEquals("legacy", TraceDifficulty.fromStorageKey("tolerance:0.045").storageKey)
    }

    @Test
    fun factoryRejectsNonFiniteAndOutOfRangeTolerances() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.0299f, 0.0651f, -1f)
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    TraceDifficulty.fromTolerance(invalid)
                }
            }
    }

    @Test
    fun parserFallsBackToDefaultForInvalidKeys() {
        listOf(
            null,
            "",
            "unknown",
            "tolerance:",
            "tolerance:abc",
            "tolerance:NaN",
            "tolerance:Infinity",
            "tolerance:-Infinity",
            "tolerance:0.01",
            "tolerance:0.1",
        ).forEach { key ->
            assertSame(key.toString(), TraceDifficulty.DEFAULT, TraceDifficulty.fromStorageKey(key))
        }
    }

    @Test
    fun equalityAndHashCodeFollowTolerance() {
        assertEquals(TraceDifficulty.EASY, TraceDifficulty.fromTolerance(0.055f))
        assertEquals(TraceDifficulty.EASY.hashCode(), TraceDifficulty.fromTolerance(0.055f).hashCode())
        assertEquals(TraceDifficulty.LEGACY, TraceDifficulty.fromTolerance(0.045f))
        assertNotEquals(TraceDifficulty.EASY, TraceDifficulty.LEGACY)

        val custom = TraceDifficulty.fromTolerance(0.051234f)
        assertEquals(custom, TraceDifficulty.fromTolerance(0.051234f))
        assertEquals(custom.hashCode(), TraceDifficulty.fromStorageKey("tolerance:0.051234").hashCode())
        assertNotEquals(custom, TraceDifficulty.fromTolerance(0.051235f))
        assertNotEquals(custom, TraceDifficulty.EASY)
        assertNotEquals(TraceDifficulty.EASY, null)
        assertNotEquals(TraceDifficulty.EASY, "easy")
    }
}
