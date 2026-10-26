package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import jp.handwriting.note.editor.ViewPosition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MisqMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(): Triple<File, NoteManifest, Map<String, ArchiveBlob>> {
        val source = temp.newFolder(); val old = NoteFiles(File(source, "notes"), false)
        val empty = old.create("notebook", 1234); val ref = StrokeRef(1, UUID.randomUUID().toString())
        val manifest = empty.copy(strokes = listOf(ref), nextId = 2)
        old.save(manifest) { byteArrayOf(1, 2, 3) }
        val folders = File(source, "folders.hwp"); NotebookFolders.write(folders, LibraryPreferences().added("folder", null))
        val local = NoteFiles(File(temp.newFolder(), "notes"))
        val viewed = manifest.copy(info = manifest.info.copy(updatedAt = 9999, position = ViewPosition(2f, 30f, 400f), color = 0xff123456.toInt(), size = 5f))
        local.save(viewed) { old.stroke(manifest.info.id, it) }
        return Triple(source, viewed, mapOf("folders.hwp" to ArchiveBlob(folders.readBytes()),
            "notes/${manifest.info.id}.misq" to ArchiveBlob(local.document(manifest.info.id).file.readBytes())))
    }
    @Test fun formatAndViewportChangesCanMigrateWithoutTreatingThemAsAnInkConflict() {
        val (source, _, snapshot) = fixture()
        assertTrue(MisqMigration.sameContent(snapshot, source, temp.newFolder()))
    }
    @Test fun actualInkBytesMustMatchEvenWithIdenticalStrokeIds() {
        val (source, note, snapshot) = fixture()
        val other = NoteFiles(File(temp.newFolder(), "notes")); other.save(note) { byteArrayOf(9) }
        val altered = snapshot + ("notes/${note.info.id}.misq" to ArchiveBlob(other.document(note.info.id).file.readBytes()))
        assertFalse(MisqMigration.sameContent(altered, source, temp.newFolder()))
    }
    @Test fun notebookTitleAndFolderChangesAreStillConflicts() {
        val (source, note, snapshot) = fixture()
        val other = NoteFiles(File(temp.newFolder(), "notes")); other.save(note.copy(info = note.info.copy(title = "different"))) { byteArrayOf(1, 2, 3) }
        assertFalse(MisqMigration.sameContent(snapshot + ("notes/${note.info.id}.misq" to ArchiveBlob(other.document(note.info.id).file.readBytes())), source, temp.newFolder()))
        val different = File(temp.newFolder(), "folders.hwp"); NotebookFolders.write(different, LibraryPreferences())
        assertFalse(MisqMigration.sameContent(snapshot + ("folders.hwp" to ArchiveBlob(different.readBytes())), source, temp.newFolder()))
    }
}
