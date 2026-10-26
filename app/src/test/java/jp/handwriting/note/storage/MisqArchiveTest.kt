package jp.handwriting.note.storage

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MisqArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private class Memory : PackedFiles {
        val files = linkedMapOf<String, ByteArray>()
        var failName: (String) -> Boolean = { false }
        override fun names() = files.keys.toSet()
        override fun read(name: String) = files.getValue(name).copyOf()
        override fun write(name: String, bytes: ByteArray) {
            if (failName(name)) throw IOException("interrupted")
            check(name !in files || files[name]!!.contentEquals(bytes))
            files[name] = bytes.copyOf()
        }
        override fun delete(name: String) { files.remove(name) }
    }
    private fun fixture(): Pair<NoteFiles, Map<String, File>> {
        val root = temp.newFolder(); val notes = NoteFiles(File(root, "notes")); val note = notes.create("one")
        val folders = File(root, "folders.hwp"); NotebookFolders.write(folders, LibraryPreferences().added("講義", null))
        return notes to mapOf("folders.hwp" to folders, "notes/${note.info.id}.misq" to notes.document(note.info.id).file)
    }
    @Test fun externalLibraryHasOneVisibleMisqPerNoteAndReadableFolderJson() {
        val (_, files) = fixture(); val memory = Memory(); val archive = NotebookArchive(MisqArchiveDirectory(memory))
        archive.commit(files)
        assertEquals(1, memory.names().count { it.endsWith(".misq") })
        val json = JSONObject(String(memory.read("library.json")))
        assertEquals("講義", json.getJSONArray("folders").getJSONObject(0).getString("name"))
        val target = temp.newFolder(); val reopened = NotebookArchive(MisqArchiveDirectory(memory))
        reopened.restore(checkNotNull(reopened.latest()), target)
        assertEquals("one", NoteFiles(File(target, "notes")).list().single().title)
    }
    @Test fun interruptedNewRevisionLeavesPreviousNotebookReadableAndRetryable() {
        val (notes, files) = fixture(); val memory = Memory(); val archive = NotebookArchive(MisqArchiveDirectory(memory))
        archive.commit(files); val before = checkNotNull(archive.latest())
        notes.rename(notes.list().single().id, "two")
        memory.failName = { it.startsWith("revision-") }
        assertThrows(IOException::class.java) { archive.commit(files) }
        val reader = NotebookArchive(MisqArchiveDirectory(memory))
        assertEquals(before, reader.latest())
        memory.failName = { false }; archive.commit(files)
        val target = temp.newFolder(); val reopened = NotebookArchive(MisqArchiveDirectory(memory))
        reopened.restore(checkNotNull(reopened.latest()), target)
        assertEquals("two", NoteFiles(File(target, "notes")).list().single().title)
    }
    @Test fun corruptLatestJsonRecoversPreviousAndCanPublishRepair() {
        val (notes, files) = fixture(); val memory = Memory(); val archive = NotebookArchive(MisqArchiveDirectory(memory))
        archive.commit(files); notes.rename(notes.list().single().id, "two"); archive.commit(files)
        val latest = memory.names().filter { it.startsWith("revision-") }.max()
        memory.files[latest] = byteArrayOf(1)
        val reader = NotebookArchive(MisqArchiveDirectory(memory)); assertNotNull(reader.latest()); assertTrue(reader.recovered)
        reader.commit(files)
        assertEquals(2, memory.names().count { it.startsWith("revision-") })
    }
    @Test fun otherWriterCannotBeSilentlyOverwritten() {
        val (notes, files) = fixture(); val memory = Memory()
        val a = NotebookArchive(MisqArchiveDirectory(memory)); a.commit(files)
        val b = NotebookArchive(MisqArchiveDirectory(memory)); assertNotNull(b.latest())
        notes.rename(notes.list().single().id, "from A"); a.commit(files)
        assertThrows(ArchiveConflictException::class.java) { b.commit(files) }
        assertEquals("from A", notes.list().single().title)
    }
    @Test fun hierarchyJsonRecoversWithoutAnyNoteBodyOrDeviceSettings() {
        val root = temp.newFolder(); val settings = LibraryPreferences().added("parent", null)
        val file = File(root, "library.json"); LibraryJson.write(file, settings)
        assertEquals(settings.folders, LibraryPreferencesFile(File(root, "library.hwp")).read().folders)
        LibraryJson.write(file, settings.copy(folders = settings.folders.map { it.copy(name = "new") }))
        file.writeText("broken")
        assertEquals(settings.folders, LibraryPreferencesFile(File(root, "library.hwp")).read().folders)
    }
}
