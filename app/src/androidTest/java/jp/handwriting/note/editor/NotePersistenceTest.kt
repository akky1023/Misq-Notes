package jp.handwriting.note.editor

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
class NotePersistenceTest {
    private fun stroke(color: Int, marker: Boolean = false, size: Float = 4f): EditorStroke {
        val input = MutableStrokeInputBatch()
        input.add(InputToolType.STYLUS, 100f, 12000f, 0, pressure = 0.1f)
        input.add(InputToolType.STYLUS, 120f, 12020f, 20, pressure = 0.7f)
        input.add(InputToolType.STYLUS, 150f, 12030f, 40, pressure = 0.3f)
        return EditorStroke(Stroke(Brush.createWithColorIntArgb(
            if (marker) StockBrushes.marker() else StockBrushes.pressurePen(), color, size, 0.1f), input))
    }
    private fun folder() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "persistence-${UUID.randomUUID()}")
    @Test fun repositoryReloadsInkBrushStyleOrderAndViewport() {
        val root = folder()
        try {
            val repo = NoteRepository(NoteFiles(root))
            val empty = repo.create("講義ノート")
            assertNotNull(android.graphics.BitmapFactory.decodeFile(NoteRepository(NoteFiles(root)).preview(empty.info.id).path))
            val entries = listOf(
                OrderedEdits.Entry(2L, stroke(0xFF205EB8.toInt())),
                OrderedEdits.Entry(7L, stroke(0xFFB93838.toInt(), true)),
                OrderedEdits.Entry(8L, stroke(0x80123456.toInt(), size = 100f)),
                OrderedEdits.Entry(9L, stroke(0x00123456, size = 1f)),
            )
            val manifest = empty.copy(info = empty.info.copy(position = ViewPosition(3f, 200f, 11800f), size = 100f, color = 0x80123456.toInt()),
                strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 10)
            repo.save(NoteSnapshot(manifest, entries))
            val loaded = NoteRepository(NoteFiles(root)).load(empty.info.id)
            assertEquals(manifest, loaded.manifest)
            assertEquals(listOf(2L, 7L, 8L, 9L), loaded.entries.map { it.id })
            for (i in entries.indices) {
                val before = entries[i].value.ink; val after = loaded.entries[i].value.ink
                assertEquals(before.brush.colorIntArgb, after.brush.colorIntArgb)
                assertEquals(before.brush.size, after.brush.size, 0.001f)
                assertEquals(before.inputs.size, after.inputs.size)
                assertEquals(before.inputs[1].pressure, after.inputs[1].pressure, 0.001f)
                assertEquals(before.inputs[1].y, after.inputs[1].y, 0.05f)
                assertNotNull(after.shape.computeBoundingBox())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun brokenLatestStrokeRecoversPreviousCompleteManifest() {
        val root = folder()
        try {
            val repo = NoteRepository(NoteFiles(root)); val empty = repo.create("note")
            val a = OrderedEdits.Entry(1L, stroke(0xFF202B33.toInt()))
            val first = empty.copy(strokes = listOf(StrokeRef(a.id, a.value.storageId)), nextId = 2)
            repo.save(NoteSnapshot(first, listOf(a)))
            val b = OrderedEdits.Entry(2L, stroke(0xFF205EB8.toInt()))
            repo.save(NoteSnapshot(first.copy(strokes = first.strokes + StrokeRef(b.id, b.value.storageId), nextId = 3), listOf(a, b)))
            val document = NoteFiles(root).document(empty.info.id)
            val offset = document.index().records.getValue(b.value.storageId).offset
            java.io.RandomAccessFile(document.file, "rw").use { it.seek(offset); it.write(0) }
            val loaded = NoteRepository(NoteFiles(root)).load(empty.info.id)
            assertTrue(loaded.recovered)
            assertEquals(first, loaded.manifest)
            assertEquals(a.value.storageId, loaded.entries.single().value.storageId)
        } finally { root.deleteRecursively() }
    }
    @Test fun savingRetainsCausalHistoryAndUndoCanRestoreRemovedStrokes() {
        val root = folder()
        try {
            val repository = NoteRepository(NoteFiles(root))
            val empty = repository.create("large note")
            val entries = (1L..3L).map { OrderedEdits.Entry(it, stroke(0xFF202B33.toInt())) }
            fun save(entry: OrderedEdits.Entry<EditorStroke>) {
                repository.save(NoteSnapshot(empty.copy(strokes = listOf(StrokeRef(entry.id, entry.value.storageId)), nextId = 4), listOf(entry)))
            }
            entries.forEach(::save)
            val document = NoteFiles(root).document(empty.info.id)
            assertTrue(document.index().events.any { it.blob == null })
            assertEquals(listOf(entries[2].value.storageId), document.index().manifest.strokes.map { it.storageId })
            save(entries[0]) // In-memory Undo restores a removed stroke using its original identity.
            val loaded = NoteRepository(NoteFiles(root)).load(empty.info.id)
            assertEquals(entries[0].value.storageId, loaded.entries.single().value.storageId)
            assertTrue(document.file.exists())
        } finally { root.deleteRecursively() }
    }
}
