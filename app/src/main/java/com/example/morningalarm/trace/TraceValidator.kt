package com.example.morningalarm.trace

/**
 * Outcome of [TraceValidator.validate].
 *
 * @property success true only when the whole closed path was traced in order.
 * @property progress fraction of the reference path that was matched (`0f..1f`).
 * @property message short human-readable explanation.
 */
data class TraceValidationResult(
    val success: Boolean,
    val progress: Float,
    val message: String,
)

/**
 * Validates a user's trace against [TraceChallenge.referencePoints].
 *
 * Rules:
 * - Coordinates must be finite and inside the unit square.
 * - Input is capped at [MAX_INPUT_POINTS] points.
 * - Points are compared against the sampled reference polyline, so straight
 *   interpolation between two accepted points cannot skip a section.
 * - The trace must start at the beginning of the path and move forward. Small
 *   backward jitter is tolerated; larger backtracking is reported as reverse,
 *   and skipping ahead is reported as a shortcut.
 * - Every accepted point must lie within [TraceDifficulty.tolerance] of the path.
 * - Success requires reaching the end and returning to the start (closed loop).
 *
 * The tolerance is taken from the supplied [TraceDifficulty] so callers (UI and
 * service) can share the same rule set.
 */
object TraceValidator {
    /** Upper bound for a single trace; touch input normally stays far below this. */
    const val MAX_INPUT_POINTS: Int = 2000

    /** How far ahead, as a fraction of the path, one input point may advance. */
    const val LOOKAHEAD_FRACTION: Float = 0.03f

    /** Backward jitter allowed before a point counts as reversing. */
    const val BACKTRACK_FRACTION: Float = 0.01f

    /** Progress required before completion is considered. */
    const val COMPLETION_PROGRESS: Float = 0.99f

    private const val MESSAGE_COMPLETE = "完了"
    private const val MESSAGE_INCOMPLETE = "まだ途中です"
    private const val MESSAGE_REVERSE = "逆走しています"
    private const val MESSAGE_SHORTCUT = "近道はできません"
    private const val MESSAGE_OFF_PATH = "経路から外れています"
    private const val MESSAGE_TOO_MANY = "入力が多すぎます"
    private const val MESSAGE_EMPTY = "なぞり始めてください"
    private const val MESSAGE_INVALID = "座標が無効です"

    fun validate(
        points: List<Point>,
        difficulty: TraceDifficulty = TraceDifficulty.DEFAULT,
    ): TraceValidationResult {
        if (points.isEmpty()) {
            return TraceValidationResult(false, 0f, MESSAGE_EMPTY)
        }
        if (points.size > MAX_INPUT_POINTS) {
            return TraceValidationResult(false, 0f, MESSAGE_TOO_MANY)
        }
        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite() ||
                point.x < 0f || point.x > 1f || point.y < 0f || point.y > 1f
            ) {
                return TraceValidationResult(false, 0f, MESSAGE_INVALID)
            }
        }

        val reference = TraceChallenge.referencePoints
        val lastIndex = reference.lastIndex
        val toleranceSquared = difficulty.tolerance * difficulty.tolerance
        val window = (lastIndex * LOOKAHEAD_FRACTION).toInt().coerceAtLeast(4)
        val backtrack = (lastIndex * BACKTRACK_FRACTION).toInt().coerceAtLeast(2)

        // Check the entire finger movement, not only event endpoints. Otherwise
        // sparse touch events could cut across a corner between accepted points.
        val movement = ArrayList<Point>()
        movement.add(points.first())
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            val distance = kotlin.math.sqrt(squaredDistance(a, b))
            val steps = kotlin.math.ceil(distance / TraceChallenge.SAMPLE_SPACING).toInt().coerceAtLeast(1)
            if (movement.size + steps > 100_000) return TraceValidationResult(false, 0f, MESSAGE_TOO_MANY)
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                movement.add(Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        if (squaredDistance(points.first(), reference.first()) > toleranceSquared) {
            return TraceValidationResult(false, 0f, MESSAGE_OFF_PATH)
        }
        var nextIndex = 0
        for (point in movement) {
            val lower = (nextIndex - backtrack).coerceAtLeast(0)
            val upper = (nextIndex + window).coerceAtMost(lastIndex)

            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            for (index in lower..upper) {
                val distance = squaredDistance(point, reference[index])
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }

            if (bestIndex >= 0 && bestDistance <= toleranceSquared) {
                nextIndex = maxOf(nextIndex, bestIndex + 1)
            } else {
                return TraceValidationResult(
                    success = false,
                    progress = progressFor(nextIndex - 1),
                    message = rejectMessage(point, lower, upper, toleranceSquared),
                )
            }
        }

        val matchedIndex = nextIndex - 1
        val progress = progressFor(matchedIndex)
        val returnedToStart = squaredDistance(points.last(), reference.first()) <= toleranceSquared
        return if (progress >= COMPLETION_PROGRESS && returnedToStart) {
            TraceValidationResult(true, 1f, MESSAGE_COMPLETE)
        } else {
            TraceValidationResult(false, progress, MESSAGE_INCOMPLETE)
        }
    }

    /**
     * Classifies a rejected point: somewhere already passed (reverse), somewhere
     * still ahead (shortcut), or nowhere near the path.
     */
    private fun rejectMessage(
        point: Point,
        lower: Int,
        upper: Int,
        toleranceSquared: Float,
    ): String {
        val reference = TraceChallenge.referencePoints
        for (index in 0 until lower) {
            if (squaredDistance(point, reference[index]) <= toleranceSquared) {
                return MESSAGE_REVERSE
            }
        }
        for (index in (upper + 1)..reference.lastIndex) {
            if (squaredDistance(point, reference[index]) <= toleranceSquared) {
                return MESSAGE_SHORTCUT
            }
        }
        return MESSAGE_OFF_PATH
    }

    private fun progressFor(matchedIndex: Int): Float {
        val lastIndex = TraceChallenge.referencePoints.lastIndex
        if (lastIndex <= 0 || matchedIndex < 0) return 0f
        return (matchedIndex.toFloat() / lastIndex).coerceIn(0f, 1f)
    }

    private fun squaredDistance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
