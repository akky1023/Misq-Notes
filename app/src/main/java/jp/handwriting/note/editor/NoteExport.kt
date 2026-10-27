package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withSave
import jp.handwriting.note.storage.NotePaper
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class NoteExportFormat(val extension: String, val mime: String) { PDF("pdf", "application/pdf"), PNG("png", "image/png") }

internal object NoteExport {
    /** Bounded bitmap for the complete document; never allocates an export-sized image. */
    suspend fun preview(strokes: List<EditorStroke>, paper: NotePaper, bounds: RectF = noteBounds(strokes),
        enlargedStrokes: Set<String> = emptySet()): Bitmap {
        val scale = minOf(360f / bounds.width(), 1200f / bounds.height())
        val bitmap = createBitmap(ceil(bounds.width() * scale).toInt().coerceAtLeast(1),
            ceil(bounds.height() * scale).toInt().coerceAtLeast(1))
        val job = currentCoroutineContext()
        try {
            draw(Canvas(bitmap), strokes, paper, bounds, scale, previewPaper = true,
                enlargedStrokes = enlargedStrokes, checkpoint = { job.ensureActive() })
            job.ensureActive()
            return bitmap
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }
    fun noteBounds(strokes: List<EditorStroke>): RectF = RectF(0f, 0f, Viewport.WIDTH,
        maxOf(1000f, (strokes.mapNotNull { it.bounds?.yMax }.maxOrNull() ?: 0f) + 24f))
    fun selectionBounds(strokes: List<EditorStroke>): RectF {
        val bounds = strokes.mapNotNull { it.bounds }; require(bounds.isNotEmpty())
        return RectF(bounds.minOf { it.xMin } - 8f, bounds.minOf { it.yMin } - 8f,
            bounds.maxOf { it.xMax } + 8f, bounds.maxOf { it.yMax } + 8f)
    }
    fun write(format: NoteExportFormat, strokes: List<EditorStroke>, paper: NotePaper, output: OutputStream) {
        if (format == NoteExportFormat.PDF) pdf(strokes, paper, output)
        else png(strokes, paper, noteBounds(strokes), output)
    }
    fun png(strokes: List<EditorStroke>, paper: NotePaper, bounds: RectF, output: OutputStream,
        highQuality: Boolean = false) {
        val width = bounds.width().toDouble(); val height = bounds.height().toDouble()
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        val scale = minOf(2.0, sqrt(24_000_000.0 / (width * height)), 16384.0 / maxOf(width, height)).toFloat()
        val bitmap = createBitmap(ceil(width * scale).toInt().coerceAtLeast(1), ceil(height * scale).toInt().coerceAtLeast(1))
        try {
            draw(Canvas(bitmap), strokes, paper, bounds, scale,
                if (highQuality) StrokeFidelityCache(forExport = true) else null)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { AppMessages.text(R.string.png_failed) }
        } finally { bitmap.recycle() }
    }
    private fun pdf(strokes: List<EditorStroke>, paper: NotePaper, output: OutputStream) {
        val scale = 595f / Viewport.WIDTH
        val pageHeight = 842f / scale
        val bands = strokes.mapNotNull { stroke -> stroke.bounds?.let { b ->
            val padding = if (stroke.image == null) 1f else 0f
            ExportPagination.Band(b.yMin - padding, b.yMax + padding)
        } }
        val pages = ExportPagination.pages(bands, pageHeight, noteBounds(strokes).bottom)
        val document = PdfDocument()
        val quality = StrokeFidelityCache(forExport = true)
        try {
            for ((index, range) in pages.withIndex()) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(paper.color)
                page.canvas.withSave {
                    clipRect(0f, 0f, 595f, (range.bottom - range.top) * scale)
                    draw(this, strokes, paper.copy(pattern = jp.handwriting.note.storage.PaperPattern.PLAIN), RectF(0f, range.top, Viewport.WIDTH, range.bottom), scale, quality)
                }
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
    }
    private fun draw(canvas: Canvas, strokes: List<EditorStroke>, paper: NotePaper, bounds: RectF, scale: Float,
        quality: StrokeFidelityCache? = null, previewPaper: Boolean = false,
        enlargedStrokes: Set<String> = emptySet(), checkpoint: () -> Unit = {}) {
        val matrix = Matrix().apply { setValues(floatArrayOf(scale, 0f, -bounds.left * scale, 0f, scale, -bounds.top * scale, 0f, 0f, 1f)) }
        canvas.withMatrix(matrix) {
            PaperRenderer.draw(this, paper, bounds, if (previewPaper) 1f else scale)
            val renderer = CanvasStrokeRenderer.create()
            strokes.forEach { stroke -> checkpoint(); stroke.bounds?.let { b ->
                val factor = if (stroke.storageId in enlargedStrokes && stroke.image == null) 1.5f else 1f
                val outlineWidth = if (factor != 1f) 2f / (scale * factor) else 0f
                val extraX = (b.xMax - b.xMin) * (factor - 1f) / 2f + outlineWidth * factor / 2f
                val extraY = (b.yMax - b.yMin) * (factor - 1f) / 2f + outlineWidth * factor / 2f
                if (b.xMax + extraX >= bounds.left && b.xMin - extraX <= bounds.right &&
                    b.yMax + extraY >= bounds.top && b.yMin - extraY <= bounds.bottom) {
                    if (stroke.image != null) EmbeddedImages.draw(this, stroke.image)
                    else if (factor != 1f) {
                        val x = (b.xMin + b.xMax) / 2f; val y = (b.yMin + b.yMax) / 2f
                        val enlargedMatrix = Matrix(matrix).apply { preScale(factor, factor, x, y) }
                        val outlined = stroke.ink.copy(stroke.ink.brush.copyWithColorIntArgb(
                            0xFFFF9800.toInt(), size = stroke.ink.brush.size + outlineWidth))
                        withSave {
                            scale(factor, factor, x, y)
                            renderer.draw(this, outlined, enlargedMatrix)
                            renderer.draw(this, stroke.ink, enlargedMatrix)
                        }
                    }
                    else renderer.draw(this, quality?.get(stroke.ink) ?: stroke.ink, matrix)
                }
            } }
        }
    }
}
