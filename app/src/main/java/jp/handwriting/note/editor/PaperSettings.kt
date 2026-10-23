package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import jp.handwriting.note.storage.NotePaper
import jp.handwriting.note.storage.PaperPattern

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperSettings(paper: NotePaper, onChange: (NotePaper) -> Unit) {
    var colorDialog by remember { mutableStateOf<String?>(null) }
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = AppMessages.text(R.string.paper_preview) }) {
        val scale = size.width / 1000f
        drawContext.canvas.nativeCanvas.apply {
            save()
            // drawColor ignores transforms: constrain the native canvas before painting the paper.
            clipRect(0f, 0f, size.width, size.height)
            scale(scale, scale)
            PaperRenderer.draw(this, paper, android.graphics.RectF(0f, 0f, 1000f, size.height / scale), scale)
            restore()
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { colorDialog = "background" }) { Text(AppMessages.text(R.string.background_color)) }
        TextButton(onClick = { colorDialog = "lines" }) { Text(AppMessages.text(R.string.rule_color)) }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PaperPattern.entries.forEach { pattern ->
            FilterChip(selected = paper.pattern == pattern, onClick = { onChange(paper.copy(pattern = pattern)) },
                label = { Text(when (pattern) { PaperPattern.PLAIN -> AppMessages.text(R.string.paper_plain); PaperPattern.RULED -> AppMessages.text(R.string.paper_ruled); PaperPattern.GRID -> AppMessages.text(R.string.paper_grid); PaperPattern.DOTS -> AppMessages.text(R.string.paper_dots); PaperPattern.COLLEGE_RULED -> AppMessages.text(R.string.paper_college_ruled) }) })
        }
    }
    if (paper.pattern != PaperPattern.PLAIN) {
        Text(AppMessages.text(R.string.line_spacing, paper.spacing))
        Slider(paper.spacing.toFloat(), { onChange(paper.copy(spacing = it.toInt())) }, valueRange = 12f..100f)
        Text(AppMessages.text(R.string.line_width, "%.2f".format(paper.lineWidth)))
        Slider(paper.lineWidth, { onChange(paper.copy(lineWidth = kotlin.math.round(it * 4) / 4)) }, valueRange = 0.25f..8f)
    }
    if (paper.pattern == PaperPattern.COLLEGE_RULED) {
        TextButton(onClick = { colorDialog = "margin" }) { Text(AppMessages.text(R.string.margin_color)) }
        Text(AppMessages.text(R.string.margin_width, "%.2f".format(paper.marginWidth)))
        Slider(paper.marginWidth, { onChange(paper.copy(marginWidth = kotlin.math.round(it * 4) / 4)) }, valueRange = 0.25f..8f)
        Text(AppMessages.text(R.string.margin_offset, "%.0f".format(paper.marginOffset)))
        Slider(paper.marginOffset, { onChange(paper.copy(marginOffset = kotlin.math.round(it))) }, valueRange = 0f..1000f)
    }
    colorDialog?.let { target ->
        RgbColorDialog(if (target == "background") paper.color else if (target == "margin") paper.marginColor else paper.lineColor, PaletteUi(), true,
            onApply = { color -> onChange(if (target == "background") paper.copy(color = color or 0xFF000000.toInt()) else if (target == "margin") paper.copy(marginColor = color) else paper.copy(lineColor = color)) },
            onSave = {}, onRemove = {}, onRetry = {}, title = if (target == "background") AppMessages.text(R.string.background_color) else if (target == "margin") AppMessages.text(R.string.margin_color) else AppMessages.text(R.string.rule_color), allowPalette = false, allowAlpha = target != "background") { colorDialog = null }
    }
}
