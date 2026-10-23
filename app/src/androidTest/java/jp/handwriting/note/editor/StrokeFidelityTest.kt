package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.NotePaper
import jp.handwriting.note.storage.NoteRepository
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StrokeFidelityTest {
    @Test fun allVisibleRefinedStrokesSurviveRepeatedFramesBeyond128Strokes() {
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202b33.toInt(), 1f, 0.1f)
        val originals = (0 until 300).map { i -> Stroke(brush, MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, i.toFloat(), 10f, 0)) }
        val cache = StrokeFidelityCache()
        cache.retainVisible(originals)
        val refined = originals.map(cache::get)
        repeat(3) {
            cache.retainVisible(originals)
            originals.forEachIndexed { i, stroke -> assertSame(refined[i], cache.get(stroke)) }
        }
        cache.retainVisible(originals.drop(100))
        originals.drop(100).forEachIndexed { i, stroke -> assertSame(refined[i + 100], cache.get(stroke)) }
        assertNotSame(refined.first(), cache.get(originals.first()))
        cache.clear()
        assertNotSame(refined.last(), cache.get(originals.last()))
    }

    @Test fun limitedRetentionNeverEvictsPinnedStrokesAndStalePrefetchCannotRepopulateIt() {
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202b33.toInt(), 1f, 0.1f)
        val originals = (0 until 200).map { i -> Stroke(brush, MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, i.toFloat(), 10f, 0)) }
        val cache = StrokeFidelityCache()
        cache.retainVisible(originals.takeLast(128))
        val pinned = originals.takeLast(128).map(cache::get)
        val uncached = cache.get(originals.first())
        assertNotSame(uncached, cache.get(originals.first()))
        originals.takeLast(128).forEachIndexed { i, stroke -> assertSame(pinned[i], cache.get(stroke)) }
        cache.retainVisible(emptyList())
        originals.forEach(cache::prefetch)
        assertNotSame(pinned.last(), cache.get(originals.last()))
    }

    @Test fun roundTipsUseMoreOutlineVerticesWithoutChangingSavedInputsOrBrushStyle() {
        val inputs = MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 100f, 0, pressure = 0.5f)
        val original = EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(),
            0x66123456, 1f, 0.1f), inputs))
        val saved = NoteRepository.encodeStroke(original)
        val displayCache = StrokeFidelityCache()
        val display = displayCache.get(original.ink)
        val exported = StrokeFidelityCache(forExport = true).get(original.ink)
        fun vertices(stroke: Stroke) = (0 until stroke.shape.getOutlineCount(0)).sumOf {
            stroke.shape.getOutlineVertexCount(0, it)
        }
        assertTrue(vertices(display) > vertices(original.ink))
        assertTrue(vertices(exported) > vertices(display))
        assertSame(display, displayCache.get(original.ink))
        assertEquals(original.ink.inputs, exported.inputs)
        assertEquals(original.ink.brush.family, exported.brush.family)
        assertEquals(original.ink.brush.size, exported.brush.size, 0f)
        assertEquals(original.ink.brush.colorIntArgb, exported.brush.colorIntArgb)
        assertArrayEquals(saved, NoteRepository.encodeStroke(original))
    }

    @Test fun pdfAndCaptureKeepTranslucentHighlighterColorAndOriginalDocument() {
        val inputs = MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 100f, 0)
            .add(InputToolType.STYLUS, 300f, 100f, 20)
        val stroke = EditorStroke(Stroke(Brush.createWithColorIntArgb(InkBrushes.highlighter,
            0x66000000, 20f, 0.1f), inputs))
        val saved = NoteRepository.encodeStroke(stroke)
        val strokes = listOf(stroke)
        val paper = NotePaper(color = 0xFFFFFFFF.toInt())
        val png = ByteArrayOutputStream().also {
            NoteExport.png(strokes, paper, NoteExport.selectionBounds(strokes), it, highQuality = true)
        }.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        try { assertEquals(0xFF999999.toInt(), bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
        finally { bitmap.recycle() }
        val file = File.createTempFile("fidelity-", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.outputStream().use { NoteExport.write(NoteExportFormat.PDF, strokes, paper, it) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val pageBitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertEquals(0xFF999999.toInt(), pageBitmap.getPixel(119, 59))
                        } finally { pageBitmap.recycle() }
                    }
                }
            }
        } finally { file.delete() }
        assertArrayEquals(saved, NoteRepository.encodeStroke(stroke))
    }
}
