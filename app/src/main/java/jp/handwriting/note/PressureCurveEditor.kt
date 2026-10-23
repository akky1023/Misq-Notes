package jp.handwriting.note

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.handwriting.note.editor.*
import kotlin.math.roundToInt

@Composable
internal fun PressureCurveSettings(curve: PressureCurve, enabled: Boolean, onChange: (PressureCurve) -> Unit, graphOnly: Boolean = false) {
    val editable = remember(curve) { curve.asHandleCurve() }
    val spline = editable.spline!!
    val current by rememberUpdatedState(editable)
    val currentOnChange by rememberUpdatedState(onChange)
    var selectedX by remember { mutableIntStateOf(0) }
    val selectedIndex = spline.points.indexOfFirst { it.x == selectedX }
    val selected = spline.points.getOrNull(selectedIndex)
    val line = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pointColor = MaterialTheme.colorScheme.primary
    val minimumColor = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.surface
    fun commit(value: PressureSpline) = onChange(PressureCurve(value.minimumPercent, spline = value))
    fun nudge(dx: Int, dy: Int): Boolean {
        if (!enabled || selected == null || selectedIndex == spline.points.lastIndex) return false
        val moved = spline.moved(selectedIndex, selected.x + dx, selected.y + dy)
        selectedX = moved.points[selectedIndex].x; commit(moved); return true
    }
    if (!graphOnly) Text(AppMessages.text(R.string.relative_width), style = MaterialTheme.typography.labelMedium)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f)) {
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription = AppMessages.text(R.string.pressure_curve_description)
                customActions = listOf(
                    CustomAccessibilityAction(AppMessages.text(R.string.point_left)) { nudge(-1, 0) },
                    CustomAccessibilityAction(AppMessages.text(R.string.point_right)) { nudge(1, 0) },
                    CustomAccessibilityAction(AppMessages.text(R.string.point_up)) { nudge(0, 1) },
                    CustomAccessibilityAction(AppMessages.text(R.string.point_down)) { nudge(0, -1) },
                )
            }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val padding = 28.dp.toPx()
                fun side() = (size.width - padding * 2).coerceAtLeast(1f)
                fun deleteMargin() = maxOf(20f, 72.dp.toPx() * 100 / side())
                fun position(point: PressureControlPoint) = Offset(padding + side() * point.x / 100,
                    padding + side() * (1 - point.y / 100f))
                fun coordinates(offset: Offset) = Pair(((offset.x - padding) * 100 / side()).roundToInt(),
                    ((1 - (offset.y - padding) / side()) * 100).roundToInt())
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val initial = current.spline!!
                    val selected = initial.points.indexOfFirst { it.x == selectedX }
                    fun handlePosition(incoming: Boolean): Offset {
                        val sample = initial.handlePosition(selected, incoming)
                        return Offset(padding + side() * sample.x, padding + side() * (1 - sample.y))
                    }
                    val handleHit = if (selected < 0) null else listOf(true, false)
                        .filter { if (it) selected > 0 else selected < initial.points.lastIndex }
                        .filter { (handlePosition(it) - position(initial.points[selected])).getDistance() > 12.dp.toPx() }
                        .minByOrNull { (handlePosition(it) - down.position).getDistance() }
                        ?.takeIf { (handlePosition(it) - down.position).getDistance() <= 18.dp.toPx() &&
                            (position(initial.points[selected]) - down.position).getDistance() > 12.dp.toPx() }
                    // Capture the point on DOWN, before touch slop or parent scrolling can move it.
                    val hit = initial.points.indices.minByOrNull { (position(initial.points[it]) - down.position).getDistance() }
                        ?.takeIf { (position(initial.points[it]) - down.position).getDistance() <= 24.dp.toPx() }
                    if (handleHit != null) {
                        down.consume()
                        var draft = initial
                        val grabOffset = handlePosition(handleHit) - down.position
                        drag(down.id) { change ->
                            change.consume()
                            val p = change.position + grabOffset
                            val moved = draft.movedHandle(selected, handleHit, (p.x - padding) * 100 / side(),
                                (1 - (p.y - padding) / side()) * 100)
                            if (moved != draft) {
                                draft = moved
                                currentOnChange(PressureCurve(moved.minimumPercent, spline = moved))
                            }
                        }
                    } else if (hit != null) {
                        down.consume(); selectedX = initial.points[hit].x
                        var draft = initial
                        var deleted = false
                        val grabOffset = position(initial.points[hit]) - down.position
                        drag(down.id) { change ->
                            change.consume()
                            if (deleted) return@drag
                            val position = change.position + grabOffset
                            val x = (position.x - padding) * 100 / side()
                            val y = (1 - (position.y - padding) / side()) * 100
                            val moved = draft.dragged(hit, x, y, deleteMargin())
                            if (moved != draft) {
                                deleted = moved.points.size < draft.points.size
                                draft = moved; selectedX = if (deleted) 0 else moved.points[hit].x
                                currentOnChange(PressureCurve(moved.minimumPercent, spline = moved))
                            }
                        }
                    } else {
                        val up = waitForUpOrCancellation()
                        if (up != null && (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                            val (x, y) = coordinates(up.position)
                            if (x in 1..99 && y in 0..100) {
                                val added = current.spline!!.added(x, y)
                                selectedX = x
                                currentOnChange(PressureCurve(added.minimumPercent, spline = added))
                                up.consume()
                            }
                        }
                    }
                }
            }) {
                val padding = 28.dp.toPx(); val side = (size.width - padding * 2).coerceAtLeast(1f)
                fun position(x: Float, y: Float) = Offset(padding + side * x, padding + side * (1 - y))
                for (i in 0..4) {
                    val t = i / 4f
                    drawLine(grid, position(t, 0f), position(t, 1f), 1.dp.toPx())
                    drawLine(grid, position(0f, t), position(1f, t), 1.dp.toPx())
                }
                val path = Path()
                spline.samples.forEachIndexed { i, sample ->
                    val p = position(sample.x, sample.y)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, line, style = Stroke(2.5.dp.toPx()))
                if (selectedIndex >= 0) {
                    val anchor = spline.points[selectedIndex].let { position(it.x / 100f, it.y / 100f) }
                    listOf(true, false).filter { if (it) selectedIndex > 0 else selectedIndex < spline.points.lastIndex }.forEach { incoming ->
                        val sample = spline.handlePosition(selectedIndex, incoming)
                        val handle = position(sample.x, sample.y)
                        drawLine(pointColor, anchor, handle, 1.5.dp.toPx())
                        val radius = 5.dp.toPx()
                        drawRect(background, handle - Offset(radius, radius), Size(radius * 2, radius * 2))
                        drawRect(pointColor, handle - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(2.dp.toPx()))
                    }
                }
                spline.points.forEachIndexed { index, point ->
                    val p = position(point.x / 100f, point.y / 100f)
                    if (index == spline.points.lastIndex && point.x != selectedX) drawCircle(line, 4.dp.toPx(), p)
                    else {
                        val color = if (index == 0) minimumColor else pointColor
                        drawCircle(color, (if (point.x == selectedX) 11 else 8).dp.toPx(), p)
                        drawCircle(background, 3.dp.toPx(), p)
                    }
                }
            }
            if (!graphOnly) {
                Text("100%", Modifier.align(Alignment.TopStart), style = MaterialTheme.typography.labelSmall)
                Text("0%", Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!graphOnly) Row(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AppMessages.text(R.string.pressure_zero), style = MaterialTheme.typography.labelMedium)
            Text(AppMessages.text(R.string.pressure_full), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (!graphOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolAction(AppMessages.text(R.string.add_curve_point), ToolbarIcons.Add, enabled && spline.points.size < 101) {
            val (a, b) = spline.points.zipWithNext().maxBy { (a, b) -> b.x - a.x }
            val x = (a.x + b.x) / 2
            val added = spline.added(x, (spline.factor(x / 100f) * 100).roundToInt())
            selectedX = x; commit(added)
        }
        Spacer(Modifier.weight(1f))
        ToolAction(AppMessages.text(R.string.reset_handles), ToolbarIcons.Undo, enabled && selectedIndex >= 0) {
            commit(spline.resetHandles(selectedIndex))
        }
        ToolAction(AppMessages.text(R.string.delete_curve_point), ToolbarIcons.Delete, enabled && selectedIndex in 1 until spline.points.lastIndex) {
            commit(spline.removed(selectedIndex)); selectedX = 0
        }
    }
}
