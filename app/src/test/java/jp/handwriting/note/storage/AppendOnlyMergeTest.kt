package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppendOnlyMergeTest {
    @get:Rule val temp = TemporaryFolder()
    private val payloads = mutableMapOf<String, ByteArray>()
    private fun ref(id: Long) = StrokeRef(id, UUID.randomUUID().toString()).also { payloads[it.storageId] = it.storageId.toByteArray() }
    private fun setup(): Triple<MisqTestFiles, NoteManifest, NoteConflicts> {
        val files = MisqTestFiles(temp.newFolder())
        val empty = files.create("note")
        val note = empty.copy(strokes = listOf(ref(5)), nextId = 10)
        files.save(note) { payloads.getValue(it) }
        return Triple(files, note, NoteConflicts(files.directory, temp.newFolder()))
    }
    private fun other(files: MisqTestFiles, note: NoteManifest): MisqFile {
        val staged = File(temp.newFolder(), "${note.info.id}.misq")
        files.document(note.info.id).file.copyTo(staged)
        return MisqFile(staged)
    }
    private fun save(file: MisqFile, note: NoteManifest) = file.save(note) { payloads.getValue(it) }
    private fun publish(files: MisqTestFiles, other: MisqFile) = File(files.directory, "other-copy.misq").also { other.file.copyTo(it) }

    @Test fun rightOnlyAppendUpdatesOriginalAndPreservesIdsAliasAndPreviousContents() {
        val (files, note, store) = setup()
        val other = other(files, note); val extra = ref(6)
        save(other, note.copy(strokes = note.strokes + extra, nextId = 11))
        val alias = publish(files, other); val hash = ContentHash.digest(alias.readBytes())
        val conflict = store.scan().single()
        assertTrue(store.canAutoMerge(conflict))
        val result = store.autoMerge(conflict)!!
        assertEquals(note.info.id, result.info.id)
        assertEquals(listOf(5L, 10L), result.strokes.map { it.id })
        assertEquals(note.strokes, files.document(note.info.id).previous()!!.strokes)
        assertEquals(hash, ContentHash.digest(alias.readBytes()))
        assertTrue(store.scan().isEmpty())
    }
    @Test fun existingLeftAppendIsAlreadyResolvedAndIsNotRewritten() {
        val (files, note, store) = setup()
        val other = other(files, note)
        files.save(note.copy(strokes = note.strokes + ref(10), nextId = 11)) { payloads.getValue(it) }
        publish(files, other)
        val local = files.document(note.info.id).file; val hash = ContentHash.digest(local.readBytes())
        assertNotNull(store.autoMerge(store.scan().single()))
        assertEquals(hash, ContentHash.digest(local.readBytes()))
        assertTrue(store.scan().isEmpty())
    }
    @Test fun knownDeletionAndMetadataDifferencesNeverFastForward() {
        val (files, note, store) = setup()
        val other = other(files, note)
        files.save(note.copy(strokes = emptyList())) { error("empty") }
        publish(files, other)
        assertNull(store.autoMerge(store.scan().single()))
        assertTrue(files.read(note.info.id).manifest.strokes.isEmpty())
        val edited = other.readManifest().manifest.copy(info = note.info.copy(title = "different"))
        save(other, edited)
        assertFalse(AppendOnlyMerge.canMerge(files.document(note.info.id).index(), other.index()))
    }
    @Test fun editsOnBothSidesAreNotMistakenForOneSidedAppend() {
        val (files, note, store) = setup()
        val other = other(files, note)
        save(other, note.copy(strokes = note.strokes + ref(6), nextId = 11))
        files.save(note.copy(strokes = note.strokes + ref(10), nextId = 11)) { payloads.getValue(it) }
        publish(files, other)
        assertNull(store.autoMerge(store.scan().single()))
        assertEquals(1, store.scan().size)
    }
}
