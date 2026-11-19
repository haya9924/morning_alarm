package com.example.morningalarm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val BackgroundTop = Color(0xFF0B0B14)
private val BackgroundBottom = Color(0xFF141024)
private val VioletBand = Color(0xFF6C5CE7)
private val BlueBand = Color(0xFF3D6BF5)
private val TealBand = Color(0xFF26C6B0)

private const val PhasePeriodMillis = 9_000
private const val IntensityFadeMillis = 600
private val FullPhase = (2.0 * PI).toFloat()
private const val BaseDepthFraction = 0.10f
private const val AmplitudeFraction = 0.025f
private const val MaxSampleStepDp = 6f
private const val CrestStrokeWidth = 2.5f

/**
 * Calm, dark alarm background with a connected water region hugging the whole
 * perimeter and a closed crest line just inside it. The centre stays clear so
 * alarm content reads well on top.
 *
 * The water band is a single `EvenOdd` path built from the outer rectangle and
 * a wavy inner loop, so all four edges connect into one shape and the corners
 * are never overdrawn. Both the fill and the crest use the same inner loop;
 * sampling is capped at [MaxSampleStepDp] per edge and the loop is periodic, so
 * the corners stay anchored to the inset anchors in every frame.
 *
 * The composable only draws; it installs no pointer input, so touches reach the
 * content or scroll container behind/in front of it.
 *
 * [animated] toggles the motion without resetting it. The wave phase is a
 * remembered [Animatable]: stopping cancels the phase loop and keeps the
 * current value, and restarting plays only the remaining arc to [FullPhase] at
 * the original angular speed (one full period is [PhasePeriodMillis]) before
 * snapping back to zero exactly on the periodic boundary, so the wave never
 * jumps. The wave displacement is scaled by a separate [Animatable] intensity
 * that fades between 0 and 1 over [IntensityFadeMillis] with smooth easing, so
 * when [animated] is false the composable settles on a finite, static frame
 * with no infinite animation left running. Animation values are read in the
 * draw phase, so a running wave only redraws and never recomposes per frame.
 */
@Composable
fun WavyAlarmBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val phase = remember { Animatable(0f) }
    val intensity = remember { Animatable(0f) }

    // Finite fade of the wave displacement; settles on 0f when stopped.
    LaunchedEffect(animated) {
        intensity.animateTo(
            targetValue = if (animated) 1f else 0f,
            animationSpec = tween(
                durationMillis = IntensityFadeMillis,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    // Runs only while [animated]; on stop the loop is cancelled mid-arc and the
    // phase keeps its value for the next start.
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        while (true) {
            // Explicitly suspend even when the system disables animations and
            // animateTo completes immediately, preventing a busy repeat loop.
            withFrameNanos { }
            val remaining = FullPhase - phase.value
            if (remaining <= 0f) {
                phase.snapTo(0f)
            } else {
                // Linear at the original angular speed, shortened proportionally
                // to whatever part of the period is still left.
                val durationMillis = max(
                    1,
                    ((remaining / FullPhase) * PhasePeriodMillis).roundToInt(),
                )
                phase.animateTo(
                    targetValue = FullPhase,
                    animationSpec = tween(
                        durationMillis = durationMillis,
                        easing = LinearEasing,
                    ),
                )
                // Boundary: sin() is 2*PI periodic, so this is visually seamless.
                phase.snapTo(0f)
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Draw-phase reads keep per-frame updates out of composition.
        val offset = phase.value
        val waveScale = intensity.value

        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(BackgroundTop, BackgroundBottom),
                startY = 0f,
                endY = height,
            ),
        )

        val minSide = min(width, height)
        val baseDepth = minSide * BaseDepthFraction
        val amplitude = minSide * AmplitudeFraction
        val maxStep = MaxSampleStepDp.dp.toPx().coerceAtLeast(1f)

        // Spans between the inset corner anchors.
        val spanX = (width - 2f * baseDepth).coerceAtLeast(0f)
        val spanY = (height - 2f * baseDepth).coerceAtLeast(0f)

        fun depth(t: Float, edgePhase: Float): Float =
            baseDepth + amplitude * waveScale *
                sin(PI.toFloat() * t) *
                sin(4f * PI.toFloat() * t + edgePhase + offset)

        fun segmentCount(span: Float): Int = max(2, ceil(span / maxStep).toInt())

        // Inner loop: top (left->right), right (top->bottom), bottom
        // (right->left), left (bottom->top). The corners are the explicit,
        // shared endpoints and always land on the inset anchors because
        // sin(PI * t) == 0 at both t == 0 and t == 1, while each edge phase
        // keeps depth(t, p) periodic over the loop.
        fun addPerimeterLoop(path: Path) {
            val topSegments = segmentCount(spanX)
            for (i in 0..topSegments) {
                val t = i.toFloat() / topSegments
                val x = baseDepth + spanX * t
                val y = depth(t, 0f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            val rightSegments = segmentCount(spanY)
            for (i in 1..rightSegments) {
                val t = i.toFloat() / rightSegments
                val x = width - depth(t, PI.toFloat() * 0.5f)
                val y = baseDepth + spanY * t
                path.lineTo(x, y)
            }

            val bottomSegments = segmentCount(spanX)
            for (i in 1..bottomSegments) {
                val t = i.toFloat() / bottomSegments
                val x = width - baseDepth - spanX * t
                val y = height - depth(t, PI.toFloat())
                path.lineTo(x, y)
            }

            val leftSegments = segmentCount(spanY)
            for (i in 1..leftSegments) {
                val t = i.toFloat() / leftSegments
                val x = depth(t, PI.toFloat() * 1.5f)
                val y = height - baseDepth - spanY * t
                path.lineTo(x, y)
            }
            path.close()
        }

        // One EvenOdd path: outer rect + inner loop describes the water band
        // along every edge without overdrawing or double-blending the corners.
        val water = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, width, height))
            addPerimeterLoop(this)
        }
        drawPath(
            path = water,
            brush = Brush.verticalGradient(
                colors = listOf(
                    VioletBand.copy(alpha = 0.42f),
                    BlueBand.copy(alpha = 0.28f),
                    TealBand.copy(alpha = 0.32f),
                ),
                startY = 0f,
                endY = height,
            ),
        )

        // Closed crest drawn from the same loop.
        val crest = Path().apply { addPerimeterLoop(this) }
        drawPath(
            path = crest,
            brush = Brush.linearGradient(
                colors = listOf(
                    VioletBand.copy(alpha = 0.55f),
                    BlueBand.copy(alpha = 0.45f),
                    TealBand.copy(alpha = 0.60f),
                ),
            ),
            style = Stroke(width = CrestStrokeWidth),
        )
    }
}
