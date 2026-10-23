package jp.handwriting.note.editor

import android.graphics.BitmapFactory
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailTest {
    @Test fun previewIncludesOnlyTheFirstThousandLogicalPixelsAndUpdatesPaper() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "thumbnail-${UUID.randomUUID()}")
        try {
            val files = NoteFiles(root); val repo = NoteRepository(files)
            val note = repo.create("preview", NotePaper(0xFFFFFFFF.toInt()))
            fun line(y: Float, color: Int): EditorStroke {
                val input = MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, y, 0, pressure = 1f)
                    .add(InputToolType.STYLUS, 900f, y, 100, pressure = 1f)
                return EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), color, 30f, 0.1f), input))
            }
            val entries = listOf(OrderedEdits.Entry(1L, line(500f, 0xFFFF0000.toInt())), OrderedEdits.Entry(2L, line(1500f, 0xFF0000FF.toInt())))
            val manifest = note.copy(strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 3)
            repo.save(NoteSnapshot(manifest, entries))
            val bitmap = BitmapFactory.decodeFile(repo.preview(note.info.id).path)
            assertNotNull(bitmap); assertEquals(320, bitmap.width); assertEquals(320, bitmap.height)
            assertEquals(0xFFFF0000.toInt(), bitmap.getPixel(160, 160))
            assertEquals(0xFFFFFFFF.toInt(), bitmap.getPixel(160, 300)); bitmap.recycle()
            repo.save(NoteSnapshot(manifest.copy(info = manifest.info.copy(paper = NotePaper(0xFF102030.toInt(), PaperPattern.GRID))), entries))
            val updated = BitmapFactory.decodeFile(repo.preview(note.info.id).path)
            assertEquals(0xFF102030.toInt(), updated.getPixel(16, 16)); updated.recycle()
            assertEquals(PaperPattern.GRID, NoteRepository(NoteFiles(root)).load(note.info.id).manifest.info.paper.pattern)
            // Neither opaque lines nor dots should enter the preview, even after restarting.
            for (pattern in listOf(PaperPattern.GRID, PaperPattern.RULED, PaperPattern.DOTS)) {
                val paper = NotePaper(0xFF102030.toInt(), pattern, 0xFFFF00FF.toInt(), 32, 8f)
                repo.save(NoteSnapshot(manifest.copy(info = manifest.info.copy(paper = paper)), entries))
                val preview = BitmapFactory.decodeFile(NoteRepository(NoteFiles(root)).preview(note.info.id).path)
                for (y in 0..60) for (x in 0..60) assertEquals("$pattern at $x,$y", paper.color, preview.getPixel(x, y))
                preview.recycle()
            }
        } finally { root.deleteRecursively() }
    }
}
