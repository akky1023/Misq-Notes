package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*

@Composable
internal fun ColorPicker(rgb: Int, enabled: Boolean, onColor: (Int) -> Unit) {
    val hsv = remember(rgb) { FloatArray(3).also { android.graphics.Color.colorToHSV(rgb or 0xFF000000.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    LaunchedEffect(rgb) { if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0] }
    val saturation = hsv[1]; val brightness = hsv[2]
    val currentHue by rememberUpdatedState(hue)
    val currentColor by rememberUpdatedState(onColor)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics {
        contentDescription = AppMessages.text(R.string.color_picker)
        customActions = listOf(
            CustomAccessibilityAction(AppMessages.text(R.string.increase_saturation)) { if (enabled) onColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, (saturation + 0.05f).coerceAtMost(1f), brightness))); enabled },
            CustomAccessibilityAction(AppMessages.text(R.string.decrease_saturation)) { if (enabled) onColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, (saturation - 0.05f).coerceAtLeast(0f), brightness))); enabled },
            CustomAccessibilityAction(AppMessages.text(R.string.increase_brightness)) { if (enabled) onColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, (brightness + 0.05f).coerceAtMost(1f)))); enabled },
            CustomAccessibilityAction(AppMessages.text(R.string.decrease_brightness)) { if (enabled) onColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, (brightness - 0.05f).coerceAtLeast(0f)))); enabled },
        )
    }.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        fun update(p: Offset) { currentColor(android.graphics.Color.HSVToColor(floatArrayOf(currentHue,
            (p.x / size.width).coerceIn(0f, 1f), (1 - p.y / size.height).coerceIn(0f, 1f)))) }
        awaitEachGesture {
            val down = awaitFirstDown(); down.consume(); update(down.position)
            drag(down.id) { it.consume(); update(it.position) }
        }
    }) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val p = Offset(size.width * saturation, size.height * (1 - brightness))
        drawCircle(Color.Black, 8.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
    }
    RoundedColorSlider(hue, 0f..360f, { hue = it; onColor(android.graphics.Color.HSVToColor(floatArrayOf(it, saturation, brightness))) },
        enabled, Modifier.fillMaxWidth().semantics { contentDescription = AppMessages.text(R.string.hue) }, hue = true)
}
@Composable
internal fun PaletteAction(enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp).semantics { contentDescription = AppMessages.text(R.string.open_palette) }) {
        Canvas(Modifier.size(25.dp)) {
            val colors = listOf(Color(0xFFE34D59), Color(0xFFFFC547), Color(0xFF49B778), Color(0xFF4B8FE2), Color(0xFFA469D9))
            colors.forEachIndexed { i, color ->
                val angle = (i * 72 - 90) * Math.PI / 180
                drawCircle(color, 5.dp.toPx(), center + Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat()) * 7.dp.toPx())
            }
            drawCircle(Color.White, 4.dp.toPx())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoundedColorSlider(value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier, color: Int = 0xFF245E70.toInt(), transparency: Boolean = false, hue: Boolean = false) {
    Slider(value, onValue, enabled = enabled, valueRange = range, steps = 0, modifier = modifier.height(32.dp),
        track = {
            Canvas(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
                val tint = Color(color or 0xFF000000.toInt())
                if (transparency) {
                    val cell = 5.dp.toPx()
                    for (row in 0..(size.height / cell).toInt()) for (column in 0..(size.width / cell).toInt()) {
                        drawRect(if ((row + column) % 2 == 0) Color.White else Color(0xFFD5DADD), Offset(column * cell, row * cell), androidx.compose.ui.geometry.Size(cell, cell))
                    }
                    drawRect(Brush.horizontalGradient(listOf(tint, tint.copy(alpha = 0f))))
                } else if (hue) drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                else drawRect(tint)
            }
        },
        thumb = {
            val outline = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(22.dp)) {
                drawCircle(Color.White, 10.dp.toPx())
                drawCircle(outline, 10.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        })
}
