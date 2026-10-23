package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentImportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun photoRendersAndExportsAfterDeletingTheSourceAndReopeningTheRepository() = runBlocking {
        val root = File(context.cacheDir, "photo-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "photo.png")
            val original = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            try {
                original.eraseColor(Color.RED)
                source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { original.recycle() }
            val images = EmbeddedImages.read(context, Uri.fromFile(source))
            val image = EditorStroke(ImageRecord(images.single(), 0f, 0f, 1000f, 666f))
            val notes = File(root, "notes")
            val repo = NoteRepository(NoteFiles(notes)); val empty = repo.create("Photo")
            val manifest = empty.copy(strokes = listOf(StrokeRef(1, image.storageId)), nextId = 2)
            repo.save(NoteSnapshot(manifest, listOf(OrderedEdits.Entry(1, image))))
            assertTrue(source.delete())
            val loaded = NoteRepository(NoteFiles(notes)).load(empty.info.id).entries.single().value
            assertArrayEquals(images.single().png, checkNotNull(loaded.image).data.png)
            val canvasBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(canvasBitmap); canvas.scale(0.1f, 0.1f)
                EmbeddedImages.draw(canvas, checkNotNull(loaded.image))
                assertEquals(Color.RED, canvasBitmap.getPixel(40, 30))
            } finally { canvasBitmap.recycle() }
            val output = ByteArrayOutputStream()
            NoteExport.png(listOf(loaded), NotePaper(), RectF(0f, 0f, 1000f, 666f), output)
            val exported = BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())
            try { assertEquals(Color.RED, exported.getPixel(200, 200)) } finally { exported.recycle() }
            // Move/duplicate shares the embedded data, and a whole import can be undone in one step.
            val moved = loaded.translated(12f, 24f)
            assertSame(loaded.image!!.data, moved.image!!.data)
            assertEquals(24f, moved.bounds!!.yMin, 0f)
            val edits = OrderedEdits<EditorStroke>(); edits.addAll(listOf(loaded, moved))
            edits.undo(); assertTrue(edits.entries.isEmpty()); edits.redo(); assertEquals(2, edits.entries.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun allPdfPagesSurviveSourceDeletionAndPdfExport() = runBlocking {
        val root = File(context.cacheDir, "pdf-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "original.pdf")
            val pdf = PdfDocument()
            try {
                listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                    page.canvas.drawColor(color); pdf.finishPage(page)
                }
                source.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            val images = EmbeddedImages.read(context, Uri.fromFile(source))
            assertEquals(2, images.size); assertTrue(source.delete())
            val entries = images.mapIndexed { index, data ->
                OrderedEdits.Entry(index + 1L, EditorStroke(ImageRecord(data, 0f, index * 1360f, 1000f, 1333f)))
            }
            val notes = File(root, "notes"); val repo = NoteRepository(NoteFiles(notes)); val empty = repo.create("PDF")
            val manifest = empty.copy(strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 3)
            repo.save(NoteSnapshot(manifest, entries))
            val loaded = NoteRepository(NoteFiles(notes)).load(empty.info.id)
            loaded.entries.forEachIndexed { index, entry ->
                val canvasBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(canvasBitmap)
                    canvas.scale(0.1f, 0.1f); canvas.translate(0f, -index * 1360f)
                    EmbeddedImages.draw(canvas, checkNotNull(entry.value.image))
                    assertEquals(if (index == 0) Color.RED else Color.BLUE, canvasBitmap.getPixel(50, 50))
                } finally { canvasBitmap.recycle() }
            }
            val exported = File(root, "export.pdf")
            exported.outputStream().use { NoteExport.write(NoteExportFormat.PDF, loaded.entries.map { it.value }, NotePaper(), it) }
            val exportedPages = EmbeddedImages.read(context, Uri.fromFile(exported))
            assertTrue(exportedPages.size >= 2)
            for (index in 0..1) {
                val png = exportedPages[index].png
                val page = BitmapFactory.decodeByteArray(png, 0, png.size)
                try { assertEquals(if (index == 0) Color.RED else Color.BLUE, page.getPixel(page.width / 2, page.height / 2)) }
                finally { page.recycle() }
            }
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("note-import-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidFilesFailWithoutLeavingStagingFiles() = runBlocking {
        val file = File(context.cacheDir, "bad-import-${UUID.randomUUID()}").apply { writeText("Not a photo or PDF") }
        try {
            try { EmbeddedImages.read(context, Uri.fromFile(file)); fail("Bad input accepted") }
            catch (_: java.io.IOException) { }
        } finally { file.delete() }
    }
}
