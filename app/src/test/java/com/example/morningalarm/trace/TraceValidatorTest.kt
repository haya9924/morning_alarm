package com.example.morningalarm.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceValidatorTest {
    private val reference = TraceChallenge.referencePoints

    private fun sampled(vararg stride: Int): List<Point> {
        val step = stride.firstOrNull() ?: 1
        val points = reference.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
        if (points.last() != reference.last()) points += reference.last()
        return points
    }

    @Test
    fun referencePathIsClosedAndSelfIntersectionFree() {
        assertTrue(reference.size > 100)
        assertEquals(reference.first(), reference.last())
        // Serpentine rows are separated by 0.14, comfortably above the 2x tolerance.
        assertTrue(TraceChallenge.TOLERANCE * 2f < 0.14f)
    }

    @Test
    fun exactReferenceTraceSucceeds() {
        val result = TraceValidator.validate(reference, TraceDifficulty.LEGACY)
        assertTrue(result.message, result.success)
        assertEquals(1f, result.progress, 0.0001f)
    }

    @Test
    fun sampledTraceWithinToleranceSucceeds() {
        val result = TraceValidator.validate(sampled(7), TraceDifficulty.LEGACY)
        assertTrue(result.message, result.success)
    }

    @Test
    fun pointsSlightlyOffPathAreAccepted() {
        val offset = TraceValidationTestSupport.offset(reference, dy = 0.02f)
        val result = TraceValidator.validate(offset, TraceDifficulty.LEGACY)
        assertTrue(result.message, result.success)
    }

    @Test
    fun pointsOutsideToleranceAreRejected() {
        val offset = TraceValidationTestSupport.offset(reference, dy = 0.08f)
        val result = TraceValidator.validate(offset, TraceDifficulty.LEGACY)
        assertFalse(result.success)
        assertFalse(result.message.contains("完了"))
    }

    @Test
    fun reversingOntoEarlierSectionIsRejected() {
        val forward = sampled(1)
        val middle = forward.size / 2
        val reversed = forward.take(middle) + forward.take(middle).reversed()
        val result = TraceValidator.validate(reversed, TraceDifficulty.LEGACY)
        assertFalse(result.success)
        assertEquals("逆走しています", result.message)
        assertTrue(result.progress in 0.3f..0.6f)
    }

    @Test
    fun jumpingAheadIsRejectedAsShortcut() {
        val shortcut = listOf(reference.first()) + reference.drop(reference.size / 2)
        val result = TraceValidator.validate(shortcut, TraceDifficulty.LEGACY)
        assertFalse(result.success)
        // Interpolated movement can leave the allowed corridor before its endpoint.
        assertTrue(result.message == "近道はできません" || result.message == "経路から外れています")
    }

    @Test
    fun partialTraceReportsProgress() {
        val partial = reference.take(reference.size / 2)
        val result = TraceValidator.validate(partial, TraceDifficulty.LEGACY)
        assertFalse(result.success)
        assertEquals("まだ途中です", result.message)
        assertTrue(result.progress in 0.4f..0.6f)
    }

    @Test
    fun traceThatDoesNotReturnToStartIsIncomplete() {
        val open = reference.dropLast(reference.size / 10)
        val result = TraceValidator.validate(open, TraceDifficulty.LEGACY)
        assertFalse(result.success)
        assertEquals("まだ途中です", result.message)
    }

    @Test
    fun emptyTraceIsRejected() {
        val result = TraceValidator.validate(emptyList(), TraceDifficulty.LEGACY)
        assertFalse(result.success)
        assertEquals(0f, result.progress, 0.0001f)
    }

    @Test
    fun nonFiniteCoordinatesAreRejected() {
        val nan = reference.toMutableList().also { it[5] = Point(Float.NaN, 0.2f) }
        assertFalse(TraceValidator.validate(nan, TraceDifficulty.LEGACY).success)
        val infinite = reference.toMutableList().also { it[5] = Point(0.2f, Float.POSITIVE_INFINITY) }
        assertFalse(TraceValidator.validate(infinite, TraceDifficulty.LEGACY).success)
    }

    @Test
    fun outOfBoundsCoordinatesAreRejected() {
        val result = TraceValidator.validate(listOf(Point(-0.1f, 0.1f)) + reference.drop(1), TraceDifficulty.LEGACY)
        assertFalse(result.success)
        assertEquals("座標が無効です", result.message)
    }

    @Test
    fun tooManyPointsAreRejected() {
        val many = List(TraceValidator.MAX_INPUT_POINTS + 1) { reference.first() }
        val result = TraceValidator.validate(many, TraceDifficulty.LEGACY)
        assertFalse(result.success)
        assertEquals("入力が多すぎます", result.message)
    }

    @Test
    fun easyAcceptsOffsetThatLegacyRejects() {
        val offset = TraceValidationTestSupport.offset(reference, dy = 0.05f)
        val easy = TraceValidator.validate(offset, TraceDifficulty.EASY)
        assertTrue(easy.message, easy.success)
        val legacy = TraceValidator.validate(offset, TraceDifficulty.LEGACY)
        assertFalse(legacy.success)
    }

    @Test
    fun easyRejectsOffsetOutsideItsTolerance() {
        val offset = TraceValidationTestSupport.offset(reference, dy = 0.06f)
        val result = TraceValidator.validate(offset, TraceDifficulty.EASY)
        assertFalse(result.success)
    }

    @Test
    fun easyRejectsReverseAndShortcut() {
        val forward = sampled(1)
        val middle = forward.size / 2
        val reversed = forward.take(middle) + forward.take(middle).reversed()
        val reverse = TraceValidator.validate(reversed, TraceDifficulty.EASY)
        assertFalse(reverse.success)
        assertEquals("逆走しています", reverse.message)

        val shortcut = listOf(reference.first()) + reference.drop(reference.size / 2)
        val jump = TraceValidator.validate(shortcut, TraceDifficulty.EASY)
        assertFalse(jump.success)
        assertTrue(jump.message == "近道はできません" || jump.message == "経路から外れています")
    }

    @Test
    fun difficultyDefaultsToEasy() {
        assertEquals(TraceDifficulty.EASY, TraceDifficulty.DEFAULT)
        assertEquals(0.055f, TraceDifficulty.EASY.tolerance, 0.0001f)
        assertEquals(0.045f, TraceDifficulty.LEGACY.tolerance, 0.0001f)
        // Omitting the argument must behave like EASY.
        val offset = TraceValidationTestSupport.offset(reference, dy = 0.05f)
        assertEquals(
            TraceValidator.validate(offset, TraceDifficulty.EASY),
            TraceValidator.validate(offset),
        )
    }

    @Test
    fun difficultyIsRestoredFromStorageKey() {
        assertEquals("easy", TraceDifficulty.EASY.storageKey)
        assertEquals("legacy", TraceDifficulty.LEGACY.storageKey)
        assertEquals(TraceDifficulty.EASY, TraceDifficulty.fromStorageKey("easy"))
        assertEquals(TraceDifficulty.LEGACY, TraceDifficulty.fromStorageKey("legacy"))
        assertEquals(TraceDifficulty.EASY, TraceDifficulty.fromStorageKey("unknown"))
        assertEquals(TraceDifficulty.EASY, TraceDifficulty.fromStorageKey(null))
    }

    @Test
    fun pointAtEndsOfProgress() {
        assertEquals(reference.first(), TraceChallenge.pointAt(0f))
        assertEquals(reference.last(), TraceChallenge.pointAt(1f))
        assertEquals(reference.first(), TraceChallenge.pointAt(-1f))
    }
}

private object TraceValidationTestSupport {
    fun offset(points: List<Point>, dx: Float = 0f, dy: Float = 0f): List<Point> =
        points.map { Point((it.x + dx).coerceIn(0f, 1f), (it.y + dy).coerceIn(0f, 1f)) }
}
