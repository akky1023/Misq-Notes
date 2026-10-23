package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private val folderColors = listOf(0xFFE9B949, 0xFFF3D98B, 0xFFE68A4D, 0xFFD95757, 0xFFE994A5,
    0xFFAC72C4, 0xFF7867B5, 0xFF547DCE, 0xFF71B8DD, 0xFF48A6A6,
    0xFF65A567, 0xFFB2BF65, 0xFF99785D, 0xFF88949E, 0xFF414B55).map { it.toInt() }

@Composable
internal fun FolderColorDialog(initial: Int, enabled: Boolean, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    var free by remember { mutableStateOf(false) }
    if (free) RgbColorDialog(initial, PaletteUi(), enabled, onSelect, {}, {}, {}, title = AppMessages.text(R.string.folder_color), allowPalette = false) { free = false }
    else Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(AppMessages.text(R.string.folder_color), style = MaterialTheme.typography.titleMedium)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                    folderColors.chunked(5).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { rgb -> ColorSwatch(rgb, rgb == initial, enabled) { onSelect(rgb) } }
                    } }
                    PaletteAction(enabled) { free = true }
                }
            }
        }
    }
}
