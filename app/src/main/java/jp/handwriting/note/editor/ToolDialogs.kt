package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun WidthTemplateDialog(ui: EditorUi, slot: Int, initial: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(ui.tool, slot) { mutableIntStateOf(initial) }
    EditorPopover(
        onDismiss = onDismiss,
        title = AppMessages.text(R.string.width_preset, slot + 1),
        content = {
            HideDialogStatusBar()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.tool == EditorTool.ERASER) EraserPreview(value) else PenPreview(ui.color, PenStyle.brushWidth(value.toFloat()))
                SliderNumber(AppMessages.text(R.string.width), value, 1..50, "", !ui.busy, { value = it }, ui.color)
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text(AppMessages.text(R.string.discard)) }
            TextButton(enabled = !ui.busy, onClick = { onSave(value); onDismiss() }) { Text(AppMessages.text(R.string.apply)) }
        },
    )
}

@Composable
internal fun EraserPreview(size: Int) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val background = LocalColorPreviewBackground.current ?: MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        drawCircle(background)
        drawCircle(color, radius = (size / 2f).dp.toPx(), style = Stroke(1.5.dp.toPx()))
    }
}

