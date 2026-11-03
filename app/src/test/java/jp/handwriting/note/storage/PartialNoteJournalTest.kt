package jp.handwriting.note.storage

import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartialNoteJournalTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun downloadFailureKeepsDurableEditAndRecoveryPreservesUnreadStroke() {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("partial")
        val a = StrokeRef(1, UUID.randomUUID().toString()); val b = StrokeRef(2, UUID.randomUUID().toString())
        val baseline = empty.copy(strokes = listOf(a, b), nextId = 3)
        notes.save(baseline) { if (it == a.storageId) byteArrayOf(1) else byteArrayOf(2) }
        val source = notes.document(empty.info.id).file
        val backup = File(temp.newFolder(), source.name).also { source.copyTo(it) }
        source.delete()
        val changed = a.copy(storageId = UUID.randomUUID().toString())
        val snapshot = baseline.copy(strokes = listOf(changed, b))
        PartialNoteJournal.write(notes.directory, snapshot, mapOf(changed.storageId to StrokePlacement(1000f, 1000f, 1010f))) { byteArrayOf(9) }
        assertThrows(IOException::class.java) { PartialNoteJournal.recover(notes.directory, notes) { throw IOException("offline") } }
        assertTrue(PartialNoteJournal.exists(notes.directory, empty.info.id))
        assertFalse(source.exists())
        PartialNoteJournal.recover(notes.directory, notes) { backup.copyTo(source) }
        assertEquals(snapshot, notes.read(empty.info.id).manifest)
        assertArrayEquals(byteArrayOf(9), notes.stroke(empty.info.id, changed.storageId))
        assertArrayEquals(byteArrayOf(2), notes.stroke(empty.info.id, b.storageId))
        assertFalse(PartialNoteJournal.exists(notes.directory, empty.info.id))
    }
    @Test fun explicitDeletionDoesNotResurrectPendingPartialEdit() {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("partial")
        PartialNoteJournal.write(notes.directory, empty, emptyMap()) { error("no strokes") }
        PartialNoteJournal.recover(notes.directory, notes, deleted = { true }) { error("deleted note must not be fetched") }
        assertFalse(PartialNoteJournal.exists(notes.directory, empty.info.id))
    }
}
