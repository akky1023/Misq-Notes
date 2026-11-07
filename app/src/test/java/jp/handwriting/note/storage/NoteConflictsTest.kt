package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteConflictsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun setup(): Triple<MisqTestFiles, NoteManifest, NoteConflicts> {
        val root = temp.newFolder()
        val files = MisqTestFiles(root)
        val note = files.create("original", 123)
        return Triple(files, note, NoteConflicts(root, temp.newFolder()))
    }
    private fun alias(files: MisqTestFiles, manifest: NoteManifest, name: String, payload: Byte = 7): File {
        val stage = MisqTestFiles(temp.newFolder())
        val ref = StrokeRef(1, UUID.randomUUID().toString())
        stage.save(manifest.copy(strokes = listOf(ref), nextId = 2)) { byteArrayOf(payload) }
        return File(files.document(manifest.info.id).file.parentFile, name).also { stage.document(manifest.info.id).file.copyTo(it) }
    }
    @Test fun detectsEmbeddedIdentityWithAnyFilenameAndDeduplicatesIdenticalAliases() {
        val (files, note, store) = setup()
        assertTrue(store.scan().isEmpty())
        val a = alias(files, note, "other device renamed this.misq")
        a.copyTo(File(a.parentFile, "${UUID.randomUUID()}.misq"))
        val conflicts = store.scan()
        assertEquals(1, conflicts.size)
        assertEquals(note.info.id, conflicts.single().noteId)
        assertEquals(1, conflicts.single().strokes)
        assertEquals(listOf(note.info.id), files.list().map { it.id })
    }
    @Test fun viewportOrThumbnailOnlyChangesAndUnrelatedNotesAreNotConflicts() {
        val (files, note, store) = setup()
        val duplicate = File(files.document(note.info.id).file.parentFile, "copy.misq")
        files.document(note.info.id).file.copyTo(duplicate)
        val staged = MisqTestFiles(temp.newFolder())
        staged.save(note.copy(info = note.info.copy(updatedAt = 999, position = jp.handwriting.note.editor.ViewPosition(2f, 100f, 200f)))) { error("empty") }
        staged.document(note.info.id).file.copyTo(duplicate, overwrite = true)
        files.create("unrelated", 456)
        assertTrue(store.scan().isEmpty())
    }
    @Test fun copiesSelectedVersionWithNewIdentityAndLeavesBothSourcesUnchanged() {
        val (files, note, store) = setup()
        val alias = alias(files, note, "arbitrary.misq")
        val originalRef = StrokeRef(5, UUID.randomUUID().toString())
        files.save(note.copy(strokes = listOf(originalRef), nextId = 6)) { byteArrayOf(1) }
        val originalHash = ContentHash.digest(files.document(note.info.id).file.readBytes())
        val aliasHash = ContentHash.digest(alias.readBytes())
        val conflict = store.scan().single()
        val result = store.copy(conflict, "other version")
        assertNotEquals(note.info.id, result.info.id)
        assertEquals("other version", result.info.title)
        assertArrayEquals(byteArrayOf(7), files.stroke(result.info.id, result.strokes.single().storageId))
        assertEquals(originalHash, ContentHash.digest(files.document(note.info.id).file.readBytes()))
        assertArrayEquals(byteArrayOf(1), files.stroke(note.info.id, originalRef.storageId))
        assertEquals(aliasHash, ContentHash.digest(alias.readBytes()))
        store.markOpened(conflict, result.info.id)
        assertTrue(store.scan().isEmpty())
        assertTrue(NoteConflicts(alias.parentFile!!, File(temp.root, "unknown-state")).scan().isNotEmpty())
        files.delete(result.info.id)
        assertEquals(1, store.scan().size)
    }
    @Test fun refusesChangedAliasesAndIgnoresIncompleteCopiesWithoutHidingOtherConflicts() {
        val (files, note, store) = setup()
        val alias = alias(files, note, "manual copy.misq")
        val conflict = store.scan().single()
        val replacement = alias(files, note, "replacement.misq", 9)
        replacement.copyTo(alias, overwrite = true)
        File(alias.parentFile, "broken.misq").writeBytes(byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { store.copy(conflict, "changed") }
        assertEquals(1, store.scan().size)
        assertEquals(1, files.list().size)
    }
    @Test fun singleRemainingVersionIsProvisionalAndIsNeverAutomaticallyPublished() {
        val (files, note, store) = setup()
        val alias = alias(files, note, "only version.misq")
        files.delete(note.info.id)
        val conflict = store.scan().single()
        assertTrue(conflict.provisional)
        assertEquals(alias, conflict.file)
        assertFalse(store.canAutoMerge(conflict))
        assertNull(store.autoMerge(conflict))
        assertFalse(files.document(note.info.id).file.exists())
    }
    @Test fun identicalNamesAndTimestampsInDifferentLocationsIgnoreDiscoveryOrder() {
        val (files, note, store) = setup()
        val a = alias(files, note, "first.misq", 7)
        val b = alias(files, note, "second.misq", 8)
        files.delete(note.info.id)
        val remoteA = File(temp.newFolder(), "same.misq"); a.copyTo(remoteA)
        val remoteB = File(temp.newFolder(), "same.misq"); b.copyTo(remoteB)
        a.delete(); b.delete()
        val forward = store.scan(listOf(ConflictSource(remoteA), ConflictSource(remoteB)))
        val reverse = store.scan(listOf(ConflictSource(remoteB), ConflictSource(remoteA)))
        assertEquals(2, forward.size)
        assertEquals(forward.map { it.key }, reverse.map { it.key })
        assertEquals(forward.first().file, store.mergeSources(forward.last(), forward.first()).first)
        assertEquals(reverse.first().file, store.mergeSources(reverse.last(), reverse.first()).first)
    }
    @Test fun aliasReaderCannotWriteOrCompactTheSource() {
        val (files, note, _) = setup()
        val alias = alias(files, note, "renamed.misq")
        val reader = MisqFile(alias, readOnlyAlias = true)
        val before = alias.readBytes()
        assertEquals(note.info.id, reader.readManifest().manifest.info.id)
        assertThrows(IllegalStateException::class.java) { reader.saveThumbnail(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { reader.compactIfNeeded() }
        assertThrows(IllegalStateException::class.java) { reader.save(note) { error("empty") } }
        assertArrayEquals(before, alias.readBytes())
    }
}
