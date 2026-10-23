package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.handwriting.note.withScrollBar

internal val LocalColorPreviewBackground = compositionLocalOf<Color?> { null }

@Composable
internal fun RgbColorDialog(initialColor: Int, palette: PaletteUi, enabled: Boolean,
    onApply: (Int) -> Unit, onSave: (Int) -> Unit, onRemove: (Int) -> Unit,
    onRetry: () -> Unit, title: String = AppMessages.text(R.string.pen_color), allowPalette: Boolean = true, allowAlpha: Boolean = false, onDismiss: () -> Unit) {
    var color by rememberSaveable { mutableIntStateOf(initialColor) }
    var values by rememberSaveable { mutableStateOf<List<String>>(ColorText.channels(initialColor)) }
    var hex by rememberSaveable { mutableStateOf(ColorText.hex(initialColor, allowAlpha)) }
    var valid by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberScrollState()
    fun accept(value: Int) {
        color = value; values = ColorText.channels(value); hex = ColorText.hex(value, allowAlpha); valid = true
    }
    val rgb = color and 0xFFFFFF
    val windowSize = LocalWindowInfo.current.containerSize
    val maxHeight = (with(LocalDensity.current) { windowSize.height.toDp() } - 32.dp).coerceAtLeast(200.dp)
    CompactPopover(onDismiss, width = 380.dp) {
        Surface(Modifier.heightIn(max = maxHeight), shape = RoundedCornerShape(16.dp)) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HideDialogStatusBar()
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f, fill = false).withScrollBar(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ColorPicker(rgb, enabled) { accept((color and 0xFF000000.toInt()) or (it and 0xFFFFFF)) }
                                if (allowAlpha) RoundedColorSlider(PenStyle.transparency(color).toFloat(), 0f..100f,
                                    { accept(PenStyle.withTransparency(color, it.toInt())) }, enabled, Modifier.fillMaxWidth().semantics { contentDescription = AppMessages.text(R.string.opacity) }, color = rgb, transparency = true)
                            }
                            Column(Modifier.width(124.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                ColorSample(color, LocalColorPreviewBackground.current ?: MaterialTheme.colorScheme.surfaceContainerHigh)
                                listOf("R", "G", "B", "A").take(if (allowAlpha) 4 else 3).forEachIndexed { index, label ->
                                    CompactColorField(label, values[index], enabled, values[index].toIntOrNull() !in 0..255, numeric = true) { text ->
                                        if (text.length <= 3 && text.all { it in '0'..'9' }) {
                                            values = values.mapIndexed { i, old -> if (i == index) text else old }
                                            val parsed = ColorText.fromChannels(values, allowAlpha)
                                            valid = parsed != null
                                            if (parsed != null) { color = if (allowAlpha) parsed else (color and 0xFF000000.toInt()) or (parsed and 0xFFFFFF); hex = ColorText.hex(color, allowAlpha) }
                                        }
                                    }
                                }
                                CompactColorField("HEX", hex, enabled, ColorText.parseHex(hex, allowAlpha) == null, numeric = false) { text ->
                                    if (text.length <= if (allowAlpha) 9 else 7) {
                                        hex = text.uppercase()
                                        val parsed = ColorText.parseHex(hex, allowAlpha); valid = parsed != null
                                        if (parsed != null) { color = if (allowAlpha) parsed else (color and 0xFF000000.toInt()) or (parsed and 0xFFFFFF); values = ColorText.channels(color) }
                                    }
                                }
                            }
                        }
                        if (allowPalette) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                                    palette.colors.forEach { saved -> ColorSwatch(saved, (color and 0xFFFFFF) == (saved and 0xFFFFFF), enabled) {
                                        accept((color and 0xFF000000.toInt()) or (saved and 0xFFFFFF))
                                    } }
                                }
                                ToolAction(AppMessages.text(R.string.save_rgb), ToolbarIcons.Save, enabled && valid && !palette.saving) { onSave(rgb) }
                                ToolAction(AppMessages.text(R.string.remove_rgb), ToolbarIcons.Delete, enabled && valid && rgb in palette.colors && !palette.saving) { onRemove(rgb) }
                            }
                            palette.error?.let { Text(it, color = MaterialTheme.colorScheme.error); ToolAction(AppMessages.text(R.string.retry_save), ToolbarIcons.Redo, !palette.saving, onClick = onRetry) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(AppMessages.text(R.string.discard)) }
                        TextButton(enabled = enabled && valid, onClick = { onApply(if (allowAlpha) color else rgb); onDismiss() }) { Text(AppMessages.text(R.string.apply)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactColorField(label: String, value: String, enabled: Boolean, error: Boolean, numeric: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            modifier = Modifier.weight(1f).height(32.dp).semantics { contentDescription = label }
                .border(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Ascii),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { field -> Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { field() } })
    }
}

@Composable
private fun ColorSample(color: Int, background: Color) {
    Surface(Modifier.size(52.dp).semantics { contentDescription = AppMessages.text(R.string.color_preview) }, shape = CircleShape, color = background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) { Surface(Modifier.size(34.dp), shape = CircleShape, color = Color(color)) {} }
    }
}
