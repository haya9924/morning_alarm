package com.example.morningalarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.morningalarm.trace.Point
import com.example.morningalarm.trace.TraceChallenge
import com.example.morningalarm.trace.TraceDifficulty
import com.example.morningalarm.trace.TraceValidator

private const val HINT_DEFAULT = "開始点から一筆でなぞってください"
private const val HINT_OFF_PATH = "経路から外れています"
private const val HINT_RETURN = "開始点まで戻って指を離してください"
private const val HINT_TOO_MANY = "入力が多すぎます"

private val CanvasBackground = Color(0xFF14141C)
private val ReferenceColor = Color(0xFF8892B0)
private val StartColor = Color(0xFF6FE3A5)

/**
 * Square, one-stroke trace challenge rendered on a [Canvas].
 *
 * Input is normalized into the unit square and checked with [TraceValidator] when the
 * finger is lifted. [onCompleted] is invoked only for a valid trace, exactly once per
 * successful attempt. Multi-touch, cancelled/empty gestures and the retry button reset
 * the current trace. Pointer events are consumed so a surrounding scroll container
 * cannot steal the drag.
 *
 * Changing [difficulty] re-keys the internal state holder, so a fresh attempt starts
 * with the new tolerance instead of reusing half-finished progress.
 */
@Composable
fun TraceChallengeView(
    onCompleted: (List<Point>) -> Unit,
    modifier: Modifier = Modifier,
    difficulty: TraceDifficulty = TraceDifficulty.DEFAULT,
) {
    key(difficulty) {
        TraceChallengeContent(
            onCompleted = onCompleted,
            difficulty = difficulty,
            modifier = modifier,
        )
    }
}

@Composable
private fun TraceChallengeContent(
    onCompleted: (List<Point>) -> Unit,
    difficulty: TraceDifficulty,
    modifier: Modifier = Modifier,
) {
    val reference = remember { TraceChallenge.referencePoints }
    val lastReferenceIndex = reference.lastIndex
    // Same search window / backtrack / completion rules as the validator, so the UI
    // preview cannot accept a point the final validation would reject.
    val lookahead = (lastReferenceIndex * TraceValidator.LOOKAHEAD_FRACTION)
        .toInt().coerceAtLeast(4)
    val backtrack = (lastReferenceIndex * TraceValidator.BACKTRACK_FRACTION)
        .toInt().coerceAtLeast(2)
    val toleranceSquared = difficulty.tolerance * difficulty.tolerance

    var points by remember { mutableStateOf<List<Point>>(emptyList()) }
    var progress by remember { mutableFloatStateOf(0f) }
    var cursor by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf(HINT_DEFAULT) }
    var completed by remember { mutableStateOf(false) }
    // Once the cap is hit the attempt is dead: it can no longer be validated
    // successfully, so it must not silently succeed with a truncated trace.
    var overflow by remember { mutableStateOf(false) }

    val currentOnCompleted by rememberUpdatedState(onCompleted)

    fun resetTrace() {
        points = emptyList()
        progress = 0f
        cursor = 0
        message = HINT_DEFAULT
        completed = false
        overflow = false
    }

    fun addPoint(position: Offset, size: IntSize) {
        if (completed || overflow || size.width <= 0 || size.height <= 0) return
        if (points.size >= TraceValidator.MAX_INPUT_POINTS) {
            overflow = true
            message = HINT_TOO_MANY
            return
        }
        val point = Point(
            x = (position.x / size.width).coerceIn(0f, 1f),
            y = (position.y / size.height).coerceIn(0f, 1f),
        )

        val lower = (cursor - backtrack).coerceAtLeast(0)
        val upper = (cursor + lookahead).coerceAtMost(lastReferenceIndex)
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        for (index in lower..upper) {
            val dx = point.x - reference[index].x
            val dy = point.y - reference[index].y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        // The raw point is always recorded (so the drawn path stays truthful and the
        // validator sees every off-path sample), but only in-tolerance points advance
        // the cursor.
        points = points + point
        val onPath = bestIndex >= 0 && bestDistance <= toleranceSquared
        if (onPath) {
            cursor = maxOf(cursor, bestIndex + 1)
            progress = ((cursor - 1).toFloat() / lastReferenceIndex).coerceIn(0f, 1f)
        }
        message = when {
            !onPath -> HINT_OFF_PATH
            progress >= TraceValidator.COMPLETION_PROGRESS -> HINT_RETURN
            else -> HINT_DEFAULT
        }
    }

    fun finishGesture(multiTouch: Boolean) {
        if (completed) return
        if (overflow) {
            // Keep the failure visible until the user retries.
            message = HINT_TOO_MANY
            return
        }
        if (multiTouch || points.isEmpty()) {
            resetTrace()
            return
        }
        val result = TraceValidator.validate(points, difficulty)
        progress = result.progress
        message = result.message
        if (result.success) {
            completed = true
            progress = 1f
            currentOnCompleted(points.toList())
        }
    }

    val traceColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "一筆書きチャレンジ",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "図形全体を、緑の開始点から一筆でなぞってください。指を離した時に判定します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(CanvasBackground),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            if (completed) {
                                consumeUntilUp()
                                return@awaitEachGesture
                            }
                            resetTrace()
                            addPoint(down.position, size)

                            var multiTouch = false
                            var released = false
                            while (!released) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.changes.count { it.pressed } > 1) {
                                    multiTouch = true
                                    resetTrace()
                                }
                                val primary = event.changes.firstOrNull { it.id == down.id }
                                if (!multiTouch && primary != null && primary.pressed) {
                                    addPoint(primary.position, size)
                                }
                                released = event.changes.none { it.pressed }
                                event.changes.forEach { change ->
                                    if (change.pressed) change.consume()
                                }
                            }
                            finishGesture(multiTouch)
                        }
                    },
            ) {
                val width = size.width
                val height = size.height

                fun toOffset(point: Point): Offset =
                    Offset(point.x * width, point.y * height)

                val referencePath = Path().apply {
                    moveTo(reference[0].x * width, reference[0].y * height)
                    for (index in 1..lastReferenceIndex) {
                        lineTo(reference[index].x * width, reference[index].y * height)
                    }
                }
                drawPath(
                    path = referencePath,
                    color = ReferenceColor.copy(alpha = 0.45f),
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(9.dp.toPx(), 7.dp.toPx()),
                        ),
                    ),
                )

                val start = toOffset(reference.first())
                drawCircle(
                    color = StartColor.copy(alpha = 0.25f),
                    radius = 18.dp.toPx(),
                    center = start,
                )
                drawCircle(
                    color = StartColor,
                    radius = 7.dp.toPx(),
                    center = start,
                )

                if (points.isNotEmpty()) {
                    val tracePath = Path().apply {
                        val first = toOffset(points.first())
                        moveTo(first.x, first.y)
                        for (index in 1 until points.size) {
                            val offset = toOffset(points[index])
                            lineTo(offset.x, offset.y)
                        }
                    }
                    drawPath(
                        path = tracePath,
                        color = traceColor.copy(alpha = 0.28f),
                        style = Stroke(
                            width = 14.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                    drawPath(
                        path = tracePath,
                        color = traceColor,
                        style = Stroke(
                            width = 5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "進捗 ${(progress * 100f).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (completed) "完了" else message,
                style = MaterialTheme.typography.labelMedium,
                color = if (completed) StartColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { resetTrace() },
                enabled = points.isNotEmpty() || completed || overflow,
            ) {
                Text("やり直す")
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilUp() {
    var released = false
    while (!released) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        released = event.changes.none { it.pressed }
        event.changes.forEach { change ->
            if (change.pressed) change.consume()
        }
    }
}
