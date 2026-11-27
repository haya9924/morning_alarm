package com.example.morningalarm.trace

import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A normalized point in the unit square. Both coordinates are in `0f..1f`.
 *
 * The type is deliberately independent of Android so the same challenge data and
 * validation can be used from a Compose Canvas, a test, or any other renderer.
 */
data class Point(val x: Float, val y: Float)

/**
 * The fixed trace challenge: a closed serpentine with several switchbacks.
 *
 * The path starts at the top-left corner of the serpentine, sweeps right/left
 * across five rows, returns along the bottom, and climbs the outer left edge back
 * to the start. Parallel sections are separated by more than twice [TOLERANCE]
 * so a matching point cannot belong to two nearby sections.
 */
object TraceChallenge {
    /** Maximum distance from the reference path for an input point to count. */
    const val TOLERANCE: Float = 0.045f

    /** Spacing between consecutive [referencePoints], in normalized units. */
    const val SAMPLE_SPACING: Float = 0.004f

    /**
     * Corner vertices of the closed path. The last vertex is connected back to the
     * first one implicitly; the path is not explicitly repeated here.
     */
    val vertices: List<Point> = listOf(
        Point(0.18f, 0.18f), // start
        Point(0.88f, 0.18f),
        Point(0.88f, 0.32f),
        Point(0.18f, 0.32f),
        Point(0.18f, 0.46f),
        Point(0.88f, 0.46f),
        Point(0.88f, 0.60f),
        Point(0.18f, 0.60f),
        Point(0.18f, 0.74f),
        Point(0.88f, 0.74f),
        Point(0.88f, 0.90f),
        Point(0.06f, 0.90f),
        Point(0.06f, 0.06f),
        Point(0.18f, 0.06f),
        // Implicit closing edge: (0.18, 0.06) -> (0.18, 0.18).
    )

    /**
     * Dense, closed polyline sampled from [vertices]. The first and last elements
     * are equal so renderers can draw it directly and validators can require the
     * user to return to the start.
     */
    val referencePoints: List<Point> = buildReference(vertices, SAMPLE_SPACING)

    /** Returns the reference point at [progress] (`0f..1f`). */
    fun pointAt(progress: Float): Point {
        val clamped = progress.coerceIn(0f, 1f)
        val index = (clamped * referencePoints.lastIndex + 0.5f).toInt()
            .coerceIn(0, referencePoints.lastIndex)
        return referencePoints[index]
    }

    private fun buildReference(vertices: List<Point>, spacing: Float): List<Point> {
        val points = ArrayList<Point>()
        for (i in vertices.indices) {
            val from = vertices[i]
            val to = vertices[(i + 1) % vertices.size]
            val length = hypot(to.x - from.x, to.y - from.y)
            val steps = ceil(length / spacing).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val t = step.toFloat() / steps
                points += Point(
                    x = from.x + (to.x - from.x) * t,
                    y = from.y + (to.y - from.y) * t,
                )
            }
        }
        points += vertices.first()
        return points
    }
}
