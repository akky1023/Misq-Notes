package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import jp.handwriting.note.withScrollBar
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.PI
import jp.handwriting.note.LocalNotebookDark

@Composable
internal fun PenDetailsDialog(
    ui: EditorUi, preferences: ToolPreferencesUi,
    onSize: (Int) -> Unit, onTransparency: (Int) -> Unit,
    onRetry: () -> Unit, onColor: () -> Unit, onDismiss: () -> Unit,
) {
    EditorPopover(
        onDismiss = onDismiss,
        title = when (ui.tool) { EditorTool.HIGHLIGHTER -> AppMessages.text(R.string.highlighter); EditorTool.ERASER -> AppMessages.text(R.string.eraser); else -> AppMessages.text(R.string.pen) },
        content = {
            HideDialogStatusBar()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.tool == EditorTool.ERASER) EraserPreview(ui.size.toInt())
                else PenPreview(ui.color, PenStyle.brushWidth(ui.size), if (ui.tool == EditorTool.PEN) preferences.value.pressureCurve else null)
                if (ui.tool != EditorTool.ERASER) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RGB ${(ui.color ushr 16) and 255}, ${(ui.color ushr 8) and 255}, ${ui.color and 255}", Modifier.weight(1f))
                    ToolAction(AppMessages.text(R.string.change_color), ToolbarIcons.Palette, !ui.busy, onClick = onColor)
                }
                }
                SliderNumber(AppMessages.text(R.string.width), ui.size.toInt(), 1..50, "", !ui.busy, onSize, ui.color)
                if (ui.tool != EditorTool.ERASER) {
                SliderNumber(AppMessages.text(R.string.opacity), PenStyle.transparency(ui.color), 0..100, "%", !ui.busy, onTransparency, ui.color)
                }
                preferences.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    ToolAction(AppMessages.text(R.string.retry_settings), ToolbarIcons.Redo, !preferences.saving, onClick = onRetry)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderNumber(title: String, value: Int, range: IntRange, suffix: String, enabled: Boolean, onChange: (Int) -> Unit,
    color: Int = 0xFF245E70.toInt()) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
    RoundedColorSlider(value.toFloat(), range.first.toFloat()..range.last.toFloat(), { onChange(it.roundToInt()) },
        enabled, Modifier.weight(1f).semantics { contentDescription = title }, color = color, transparency = suffix == "%")
    Text("$value$suffix", Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WidthPreset(slot: Int, width: Int, active: Boolean, enabled: Boolean, onSelect: () -> Unit, onSave: () -> Unit, compact: Boolean = false, eraser: Boolean = false) {
    Surface(onClick = { if (active) onSave() else onSelect() }, enabled = enabled,
        modifier = Modifier.size(if (compact) 36.dp else 64.dp).semantics {
            contentDescription = AppMessages.text(R.string.width_slot, slot + 1, width); selected = active
            customActions = listOf(CustomAccessibilityAction(AppMessages.text(R.string.edit_width_preset)) { if (enabled) onSave(); enabled })
        },
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
    ) {
        val color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.fillMaxSize()) {
            if (eraser) drawCircle(color, width / 50f * 12.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            else {
                val thickness = (PenStyle.brushWidth(width.toFloat()) / PenStyle.brushWidth(50f) * size.minDimension * 0.58f).coerceAtLeast(1.dp.toPx())
                val inset = maxOf(8.dp.toPx(), thickness / 2f + 3.dp.toPx())
                drawLine(color, Offset(inset, center.y), Offset(size.width - inset, center.y), thickness, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun IntegerInput(
    label: String, value: String, range: IntRange, enabled: Boolean, onValue: (String) -> Unit,
    modifier: Modifier = Modifier, suffix: String = "", onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> if (text.length <= 3 && text.all { it in '0'..'9' }) onValue(text) },
        modifier = modifier,
        enabled = enabled, singleLine = true,
        label = { Text(label) },
        suffix = if (suffix.isEmpty()) null else ({ Text(suffix) }),
        isError = value.toIntOrNull() !in range,
        supportingText = { Text("${range.first}–${range.last}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
internal fun ColorSwatch(rgb: Int, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(36.dp).semantics {
            contentDescription = "RGB ${(rgb ushr 16) and 255}, ${(rgb ushr 8) and 255}, ${rgb and 255}"
            selected = active
        },
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val color = Color(rgb or 0xFF000000.toInt())
            Surface(Modifier.size(24.dp), shape = CircleShape, color = color,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
            if (active) {
                val brightness = ((rgb ushr 16) and 255) * 299 + ((rgb ushr 8) and 255) * 587 + (rgb and 255) * 114
                Icon(ToolbarIcons.Check, null, Modifier.size(18.dp), tint = if (brightness > 150000) Color.Black else Color.White)
            }
        }
    }
}

@Composable
internal fun PenPreview(color: Int, width: Float, curve: PressureCurve? = null) {
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val cell = 8.dp.toPx()
        for (row in 0..(size.height / cell).toInt()) for (column in 0..(size.width / cell).toInt()) {
            drawRect(if ((row + column) % 2 == 0) Color.White else Color(0xFFE1E5E7),
                Offset(column * cell, row * cell), Size(cell, cell))
        }
        if (curve == null) drawLine(Color(color), Offset(32.dp.toPx(), center.y), Offset(size.width - 32.dp.toPx(), center.y), width.dp.toPx(), StrokeCap.Round)
        else {
            val path = Path()
            for (side in listOf(-1, 1)) for (index in 0..100) {
                val t = if (side == -1) index / 100f else 1f - index / 100f
                val x = 24.dp.toPx() + (size.width - 48.dp.toPx()) * t
                val y = center.y + side * width.dp.toPx() * curve.factor(sin(t * PI).toFloat()) / 2f
                if (side == -1 && index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close(); drawPath(path, Color(color))
        }
    }
}

@Composable
internal fun HideDialogStatusBar() {
    val view = LocalView.current
    val dark = LocalNotebookDark.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}
