package jp.handwriting.note.editor

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import kotlin.math.abs

/** Tap/swipe toggles the preview; holding first reserves the gesture for adjustment. */
@Composable
internal fun PreviewHandle(visible: Boolean, onRight: Boolean, openProgress: Float, onToggle: () -> Unit,
    onSwipeStart: () -> Unit, onSwipe: (Float) -> Unit, onSwipeEnd: (Boolean?) -> Unit,
    onResize: (Float) -> Unit, onMove: (Float) -> Unit, modifier: Modifier = Modifier) {
    var adjusting by remember { mutableStateOf(false) }
    val toggle by rememberUpdatedState(onToggle)
    val resize by rememberUpdatedState(onResize)
    val move by rememberUpdatedState(onMove)
    val startSwipe by rememberUpdatedState(onSwipeStart)
    val swipe by rememberUpdatedState(onSwipe)
    val finishSwipe by rememberUpdatedState(onSwipeEnd)
    val haptic = LocalHapticFeedback.current
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val handleColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val roundProgress by animateFloatAsState(if (adjusting && visible) 1f else 0f, tween(180), label = "previewHandleRound")
    val adjustmentProgress by animateFloatAsState(if (adjusting) 1f else 0f, tween(180), label = "previewHandleArrows")
    Box(modifier.systemGestureExclusion().semantics {
        contentDescription = AppMessages.text(R.string.note_preview)
        role = Role.Switch
        toggleableState = ToggleableState(visible)
        stateDescription = AppMessages.text(if (visible) R.string.preview_handle_open else R.string.preview_handle_closed)
        onClick { toggle(); true }
    }.pointerInput(visible, onRight) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var change = down
            // Movement before the timeout is a swipe; a stationary hold starts adjustment.
            val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var pastSlop = false
                while (change.pressed && !pastSlop) {
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    pastSlop = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    change.consume()
                }
                pastSlop
            }
            if (moved == false) {
                if (!change.pressed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop) toggle()
                return@awaitEachGesture
            }
            val held = moved == null
            var total = if (held) Offset.Zero else change.position - down.position
            var adjustmentStarted = false
            var swiping = false
            val direction = if (onRight) -1f else 1f
            try {
                if (held) {
                    adjusting = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                if (!held && abs(total.x) > viewConfiguration.touchSlop && abs(total.x) > abs(total.y)) {
                    swiping = true
                    startSwipe()
                    swipe(total.x * direction)
                }
                while (change.pressed) {
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    change.consume()
                    total += delta
                    if (!held) {
                        if (swiping) swipe(delta.x * direction)
                        else if (abs(total.x) > viewConfiguration.touchSlop && abs(total.x) > abs(total.y)) {
                            swiping = true
                            startSwipe()
                            swipe(total.x * direction)
                        }
                    }
                    if (!change.pressed) break
                    if (held) {
                        var adjustment = delta
                        if (!adjustmentStarted && total.getDistance() > viewConfiguration.touchSlop) {
                            adjustmentStarted = true
                            adjustment = total
                        }
                        if (adjustmentStarted) {
                            if (visible) resize(adjustment.x * if (onRight) -1f else 1f)
                            move(adjustment.y)
                        }
                    }
                }
                if (swiping && !change.pressed) {
                    val inward = total.x * direction
                    val completed = abs(inward) > maxOf(24.dp.toPx(), viewConfiguration.touchSlop) && abs(total.x) > abs(total.y)
                    val target = if (completed) inward > 0f else visible
                    swiping = false
                    finishSwipe(target)
                }
            } finally {
                adjusting = false
                if (swiping) finishSwipe(null)
            }
        }
    }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                fun x(distance: Float) = if (onRight) size.width - distance else distance
                fun blend(closed: Float, opened: Float) = closed + (opened - closed) * openProgress
                fun point(closedX: Float, closedY: Float, openedX: Float, openedY: Float,
                    roundX: Float, roundY: Float): Offset {
                    val normalX = blend(closedX.dp.toPx(), openedX.dp.toPx())
                    val normalY = blend(center.y + size.height * (closedY - 0.5f) * 1.2f, size.height * openedY)
                    return Offset(x(normalX + (roundX.dp.toPx() - normalX) * roundProgress),
                        normalY + (center.y + roundY.dp.toPx() - normalY) * roundProgress)
                }
                fun Path.curve(a: Offset, b: Offset, end: Offset) = cubicTo(a.x, a.y, b.x, b.y, end.x, end.y)
                val shape = Path().apply {
                    val top = point(0f, 0f, 24f, 0.15f, 24f, -24f)
                    moveTo(top.x, top.y)
                    curve(point(0f, 0.3f, 34f, 0.15f, 37.255f, -24f), point(20f, 0.2f, 34f, 0.22f, 48f, -13.255f),
                        point(20f, 0.5f, 34f, 0.5f, 48f, 0f))
                    curve(point(20f, 0.8f, 34f, 0.78f, 48f, 13.255f), point(0f, 0.7f, 34f, 0.85f, 37.255f, 24f),
                        point(0f, 1f, 24f, 0.85f, 24f, 24f))
                    curve(point(0f, 1f, 14f, 0.85f, 10.745f, 24f), point(0f, 0.5f, 14f, 0.78f, 0f, 13.255f),
                        point(0f, 0.5f, 14f, 0.5f, 0f, 0f))
                    curve(point(0f, 0.5f, 14f, 0.22f, 0f, -13.255f), point(0f, 0f, 14f, 0.15f, 10.745f, -24f), top)
                    close()
                }
                drawPath(shape, handleColor)
                drawPath(shape, tint.copy(alpha = 0.18f), style = Stroke(1.dp.toPx()))
                val arrowX = blend(9.dp.toPx(), 24.dp.toPx())
                val chevronWing = 3.dp.toPx() * (1f - 2f * openProgress)
                val chevronTip = Offset(x(arrowX + chevronWing), center.y)
                val chevronTint = tint.copy(alpha = 1f - adjustmentProgress)
                drawLine(chevronTint, Offset(x(arrowX - chevronWing), center.y - 6.dp.toPx()), chevronTip, 2.dp.toPx(), StrokeCap.Round)
                drawLine(chevronTint, chevronTip, Offset(x(arrowX - chevronWing), center.y + 6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                val stroke = 2.dp.toPx()
                    val cx = x(arrowX)
                    val cy = center.y
                    val tip = 10.dp.toPx()
                    val wing = 4.dp.toPx()
                    fun line(a: Offset, b: Offset, alpha: Float = adjustmentProgress) =
                        drawLine(tint.copy(alpha = alpha), a, b, stroke, StrokeCap.Round)
                    line(Offset(cx - tip, cy), Offset(cx + tip, cy), roundProgress * adjustmentProgress)
                    line(Offset(cx, cy - tip), Offset(cx, cy + tip))
                    for (sign in listOf(-1f, 1f)) {
                        val x = cx + sign * tip
                        val y = cy + sign * tip
                        line(Offset(x, cy), Offset(x - sign * wing, cy - wing), roundProgress * adjustmentProgress)
                        line(Offset(x, cy), Offset(x - sign * wing, cy + wing), roundProgress * adjustmentProgress)
                        line(Offset(cx, y), Offset(cx - wing, y - sign * wing))
                        line(Offset(cx, y), Offset(cx + wing, y - sign * wing))
                    }
            }
    }
}
