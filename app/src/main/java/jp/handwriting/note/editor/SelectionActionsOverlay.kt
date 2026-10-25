package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun SelectionActionsOverlay(selection: SelectionOverlay?, onDelete: () -> Unit, onClear: () -> Unit,
    onCopy: () -> Unit = {}, onCapture: () -> Unit = {}) {
    if (selection == null) return
    var area by remember { mutableStateOf(IntSize.Zero) }
    var panel by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    val backButtonArea = with(LocalDensity.current) { 64.dp.toPx() }
    Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
        Surface(
            Modifier.offset {
                val x = (selection.right - panel.width).coerceIn(0f, (area.width - panel.width).coerceAtLeast(0).toFloat())
                val above = selection.top - panel.height - gap
                val preferredY = if (above >= gap) above else selection.bottom + gap
                val safeY = if (x < backButtonArea && preferredY < backButtonArea) maxOf(backButtonArea, selection.bottom + gap) else preferredY
                val y = safeY.coerceIn(0f, (area.height - panel.height).coerceAtLeast(0).toFloat())
                IntOffset(x.roundToInt(), y.roundToInt())
            }.onSizeChanged { panel = it }.pointerInput(Unit) { detectTapGestures(onTap = {}) },
            shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp, shadowElevation = 6.dp,
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                Row(Modifier.padding(4.dp)) {
                    ToolAction(AppMessages.text(R.string.duplicate_selection), ToolbarIcons.Copy, selection.enabled, onClick = onCopy)
                    if (selection.canCapture) ToolAction(AppMessages.text(R.string.capture_selection), ToolbarIcons.Capture, selection.enabled, onClick = onCapture)
                    ToolAction(AppMessages.text(R.string.delete_selection), ToolbarIcons.Delete, selection.enabled, onClick = onDelete)
                    ToolAction(AppMessages.text(R.string.clear_selection), ToolbarIcons.Close, selection.enabled, onClick = onClear)
                }
            }
        }
    }
}
