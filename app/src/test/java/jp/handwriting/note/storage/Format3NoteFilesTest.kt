package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Format3NoteFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun dailyEditsSurviveRestartWithoutCreatingStandaloneNotes() {
        val directory = temp.newFolder()
        val files = NoteFiles.open(directory)
        val empty = files.create("new storage")
        val ref = StrokeRef(1, UUID.randomUUID().toString())
        val manifest = empty.copy(strokes = listOf(ref), nextId = 2)
        files.save(manifest, mapOf(ref.storageId to StrokePlacement(100f, 100f, 110f))) { byteArrayOf(1, 2, 3) }
        val reopened = NoteFiles.open(directory)
        assertEquals(manifest, reopened.read(empty.info.id).manifest)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.stroke(empty.info.id, ref.storageId))
        assertEquals(listOf(empty.info.id), reopened.list().map { it.id })
        assertFalse(directory.walkTopDown().any { it.extension in setOf("misq", "ink", "hwn") })
        reopened.delete(empty.info.id)
        assertTrue(NoteFiles.open(directory).list().isEmpty())
    }

    @Test fun oldStorageIsRejectedWithoutCreatingAnActivationMarker() {
        for (oldName in listOf("${UUID.randomUUID()}.misq", "${UUID.randomUUID()}/manifest.hwn")) {
            val directory = temp.newFolder()
            val old = File(directory, oldName).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7)) }
            assertThrows(IllegalStateException::class.java) { NoteFiles.open(directory) }
            assertArrayEquals(byteArrayOf(7), old.readBytes())
            assertFalse(File(directory, ".format3-active").exists())
        }
    }

    @Test fun migratedActivationWithRollbackMetadataOpensWithoutTouchingOriginals() {
        val directory = temp.newFolder()
        val files = NoteFiles.open(directory)
        val note = files.create("migrated")
        val marker = File(directory, ".format3-active")
        val activation = JSONObject(String(NoteFiles.readEnvelope(marker), Charsets.UTF_8))
        val rollback = File(directory, "format3-rollback-${UUID.randomUUID()}").apply { mkdirs() }
        val original = File(rollback, "${note.info.id}.misq").apply { writeBytes(byteArrayOf(9)) }
        activation.put("rollback", rollback.name)
        NoteFiles.atomicWrite(marker, activation.toString().toByteArray())
        assertEquals(note, NoteFiles.open(directory).read(note.info.id).manifest)
        assertArrayEquals(byteArrayOf(9), original.readBytes())
    }

    @Test fun reviewFilesNeverBecomeNormalNotesOrReplaceAuthoritativeContents() {
        val directory = temp.newFolder()
        val files = NoteFiles.open(directory)
        val note = files.create("authoritative")
        val exported = files.document(note.info.id)
        exported.save(note.copy(info = note.info.copy(title = "review only"))) { error("empty") }
        val fixture = MisqTestFiles(temp.newFolder())
        val alias = fixture.create("unrelated")
        fixture.document(alias.info.id).file.copyTo(File(directory, "alias.misq"))
        val reopened = NoteFiles.open(directory)
        assertEquals(listOf(note.info.id), reopened.list().map { it.id })
        assertEquals("authoritative", reopened.read(note.info.id).manifest.info.title)
    }

    @Test fun deletionRemovesReviewCopiesAndPreservesOtherNotebooks() {
        val directory = temp.newFolder()
        val files = NoteFiles.open(directory)
        val note = files.create("remove")
        val other = files.create("keep")
        val export = files.document(note.info.id).file
        val alias = export.copyTo(File(directory, "renamed.misq"))
        val kept = files.document(other.info.id).file
        val expected = kept.readBytes()
        files.deleteNotebook(note.info.id)
        assertFalse(export.exists())
        assertFalse(alias.exists())
        assertArrayEquals(expected, kept.readBytes())
        assertEquals(listOf(other.info.id), NoteFiles.open(directory).list().map { it.id })
    }
}
