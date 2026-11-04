package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlockChangesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun initial(): Pair<NoteFiles, NoteManifest> {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("changes")
        val refs = (1L..2L).map { StrokeRef(it, UUID.randomUUID().toString()) }
        val manifest = empty.copy(strokes = refs, nextId = 3)
        notes.save(manifest, mapOf(refs[0].storageId to StrokePlacement(100f, 100f, 120f), refs[1].storageId to StrokePlacement(5100f, 5100f, 5120f))) { byteArrayOf(1) }
        val file = notes.document(empty.info.id).file
        val captured = BlockChanges.capture(file)!!
        BlockChanges.acknowledge(file, captured, NotebookArchive.digest(file))
        return notes to manifest
    }
    @Test fun movingAcrossBandsMarksBothBandsAndDeletionMarksTheOldBand() {
        val (notes, original) = initial(); val file = notes.document(original.info.id).file
        assertTrue(BlockChanges.capture(file)!!.dirty.isEmpty())
        val moved = original.strokes.first().copy(storageId = UUID.randomUUID().toString())
        val after = original.copy(strokes = listOf(moved, original.strokes[1]))
        notes.save(after, mapOf(moved.storageId to StrokePlacement(2100f, 2100f, 2120f))) { byteArrayOf(2) }
        assertEquals(setOf(0L, 2L), BlockChanges.capture(file)!!.dirty.keys)
        val captured = BlockChanges.capture(file)!!
        BlockChanges.acknowledge(file, captured, NotebookArchive.digest(file))
        notes.save(after.copy(strokes = listOf(moved))) { error("no new payload") }
        assertEquals(setOf(5L), BlockChanges.capture(file)!!.dirty.keys)
    }
    @Test fun oldUploadAcknowledgesOnlyItsOwnVersions() {
        val (notes, original) = initial(); val file = notes.document(original.info.id).file
        val a = original.strokes[0].copy(storageId = UUID.randomUUID().toString())
        val first = original.copy(strokes = listOf(a, original.strokes[1]))
        notes.save(first, mapOf(a.storageId to StrokePlacement(100f, 100f, 130f))) { byteArrayOf(2) }
        val old = BlockChanges.capture(file)!!
        val b = a.copy(storageId = UUID.randomUUID().toString())
        notes.save(first.copy(strokes = listOf(b, original.strokes[1])), mapOf(b.storageId to StrokePlacement(100f, 100f, 140f))) { byteArrayOf(3) }
        val latest = BlockChanges.capture(file)!!
        BlockChanges.acknowledge(file, old, "a".repeat(64))
        assertEquals(latest.dirty, BlockChanges.capture(file)!!.dirty)
        val latestHash = NotebookArchive.digest(file)
        BlockChanges.acknowledge(file, latest, latestHash)
        assertTrue(BlockChanges.capture(file)!!.dirty.isEmpty())
        BlockChanges.acknowledge(file, old, "c".repeat(64))
        assertEquals(latestHash, BlockChanges.capture(file)!!.baseline)
    }
    @Test fun viewportAndThumbnailDoNotMarkInkBandsDirty() {
        val (notes, original) = initial(); val file = notes.document(original.info.id).file
        notes.save(original.copy(info = original.info.copy(position = jp.handwriting.note.editor.ViewPosition(top = 5000f)))) { error("no new ink") }
        val metadata = BlockChanges.capture(file)!!
        assertTrue(metadata.dirty.isEmpty()); assertTrue(metadata.metadataDirty > 0)
        BlockChanges.acknowledge(file, metadata, NotebookArchive.digest(file))
        notes.document(original.info.id).saveThumbnail(byteArrayOf(1, 2, 3))
        val preview = BlockChanges.capture(file)!!
        assertTrue(preview.dirty.isEmpty()); assertEquals(0L, preview.metadataDirty)
    }
    @Test fun dirtyStateSurvivesCaptureAndCorruptionFallsBackToAllDirty() {
        val (notes, original) = initial(); val file = notes.document(original.info.id).file
        val changed = original.strokes[0].copy(storageId = UUID.randomUUID().toString())
        notes.save(original.copy(strokes = listOf(changed, original.strokes[1])), mapOf(changed.storageId to StrokePlacement(100f, 100f, 150f))) { byteArrayOf(2) }
        val state = BlockChanges.capture(file)!!
        assertEquals(state, BlockChanges.capture(file))
        File(file.parentFile, ".block-state/${original.info.id}").writeBytes(byteArrayOf(0))
        assertEquals(setOf(0L, 5L), BlockChanges.capture(file)!!.dirty.keys)
    }
    @Test fun snapshotKeepsCapturedChangesAndDeleteDoesNotRecreateTracking() {
        val (notes, original) = initial(); val file = notes.document(original.info.id).file
        notes.rename(original.info.id, "captured")
        ArchiveSnapshot.capture(mapOf("notes/${file.name}" to file)).use { snapshot ->
            val captured = snapshot.values.single().changes!!
            notes.rename(original.info.id, "later")
            assertTrue(BlockChanges.capture(file)!!.generation > captured.generation)
            assertEquals(captured, snapshot.values.single().changes)
            notes.delete(original.info.id)
            BlockChanges.acknowledge(file, captured, "b".repeat(64))
            assertFalse(File(file.parentFile, ".block-state/${original.info.id}").exists())
        }
    }
}
