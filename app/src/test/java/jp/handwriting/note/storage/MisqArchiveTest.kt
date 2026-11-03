package jp.handwriting.note.storage

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MisqArchiveTest {
    @Test fun materializedRemoteSourceCanBeReleasedBeforeViewPublication() {
        val (_, sources) = fixture(); val memory = Memory()
        val archive = NotebookArchive(MisqArchiveDirectory(memory))
        archive.commit(sources)
        val note = sources.entries.single { it.key.endsWith(".misq") }
        val bytes = note.value.readBytes(); val hash = NotebookArchive.digest(bytes)
        memory.files.remove("blob-$hash")
        memory.files.remove(note.value.name)
        val blobs = sources.mapValues { (path, file) ->
            if (path == note.key) ArchiveBlob(hash, temp.root) { it.writeBytes(bytes) } else ArchiveBlob(file)
        }
        archive.commitSnapshot(blobs)
        assertArrayEquals(bytes, memory.files[note.value.name])
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith(".notes-fetch-") })
    }
    @Test fun cleanupPreservesOtherToolsCopiesButRemovesExplicitlyDeletedOwnedNotes() {
        val (_, sources) = fixture()
        val memory = Memory()
        val directory = MisqArchiveDirectory(memory)
        val archive = NotebookArchive(directory)
        archive.commit(sources)
        val original = sources.keys.single { it.endsWith(".misq") }.substringAfterLast('/')
        val payload = memory.files.getValue(original).copyOf()
        val alias = java.util.UUID.randomUUID().toString() + ".misq"
        memory.files[alias] = payload
        memory.files["arbitrary renamed copy.misq"] = payload
        directory.organize()
        assertArrayEquals(payload, memory.files[alias])
        archive.commit(sources.filterKeys { !it.endsWith(".misq") })
        directory.organize()
        assertFalse(memory.files.containsKey(original))
        assertArrayEquals(payload, memory.files[alias])
        assertArrayEquals(payload, memory.files["arbitrary renamed copy.misq"])
    }
    @get:Rule val temp = TemporaryFolder()
    private class Memory : PackedFiles {
        val files = linkedMapOf<String, ByteArray>()
        var failName: (String) -> Boolean = { false }
        var rejectNoteReads = false
        var failStream = false
        var failUpload = false
        override fun names() = files.keys.toSet()
        override fun read(name: String): ByteArray {
            check(!rejectNoteReads || !(name.startsWith("blob-") || name.endsWith(".misq"))) { "Whole notebook read: $name" }
            return files.getValue(name).copyOf()
        }
        override fun openRead(name: String) = files.getValue(name).inputStream()
        override fun writeStream(name: String, input: java.io.InputStream) {
            if (failStream && name.endsWith(".misq") || failUpload && name.startsWith("blob-")) {
                files[name] = byteArrayOf(input.read().toByte())
                throw IOException("interrupted stream")
            }
            write(name, input.readBytes())
        }
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
    @Test fun largeNotebookPublicationAndStartupRepairNeverReadWholeNotebookCopies() {
        val (_, files) = fixture()
        val note = files.entries.single { it.key.endsWith(".misq") }
        note.value.outputStream().use { output ->
            val block = ByteArray(8192) { (it % 251).toByte() }
            repeat(3072) { output.write(block) }
        }
        val memory = Memory().apply { rejectNoteReads = true }
        val directory = MisqArchiveDirectory(memory)
        val archive = NotebookArchive(directory)
        archive.commit(files)
        val name = note.value.name
        val hash = NotebookArchive.digest(note.value.readBytes())
        assertEquals(hash, memory.digest(name))
        // Startup with an unchanged revision still runs organize(). Repair a truncated view.
        val restarted = NotebookArchive(MisqArchiveDirectory(memory))
        val snapshot = restarted.capture(files)
        assertNotNull(restarted.latestCaptured(snapshot))
        memory.files[name] = byteArrayOf(1)
        restarted.commitSnapshot(snapshot)
        assertEquals(hash, memory.digest(name))
        archive.commit(files)
        assertEquals(hash, memory.digest(name))
    }
    @Test fun interruptedStreamLeavesCommittedRecoveryIntactAndViewCanBeRepaired() {
        val (_, files) = fixture()
        val memory = Memory().apply { failStream = true }
        val directory = MisqArchiveDirectory(memory)
        assertThrows(IOException::class.java) { NotebookArchive(directory).commit(files) }
        val reopened = NotebookArchive(MisqArchiveDirectory(memory))
        val revision = checkNotNull(reopened.latest())
        val target = temp.newFolder()
        reopened.restore(revision, target)
        assertEquals("one", NoteFiles(File(target, "notes")).list().single().title)
        memory.failStream = false
        reopened.commit(files)
        val note = files.entries.single { it.key.endsWith(".misq") }
        assertEquals(NotebookArchive.digest(note.value.readBytes()), memory.digest(note.value.name))
    }
    @Test fun interruptedNotebookUploadKeepsPreviousRevisionAndRetriesPartialBlob() {
        val (notes, files) = fixture()
        val memory = Memory()
        NotebookArchive(MisqArchiveDirectory(memory)).commit(files)
        notes.rename(notes.list().single().id, "after upload")
        memory.failUpload = true
        assertThrows(IOException::class.java) { NotebookArchive(MisqArchiveDirectory(memory)).commit(files) }
        val reader = NotebookArchive(MisqArchiveDirectory(memory))
        val previous = temp.newFolder()
        reader.restore(checkNotNull(reader.latest()), previous)
        assertEquals("one", NoteFiles(File(previous, "notes")).list().single().title)
        memory.failUpload = false
        reader.commit(files)
        val reopened = NotebookArchive(MisqArchiveDirectory(memory))
        val restored = temp.newFolder()
        reopened.restore(checkNotNull(reopened.latest()), restored)
        assertEquals("after upload", NoteFiles(File(restored, "notes")).list().single().title)
        assertTrue(files.getValue("folders.hwp").parentFile!!.listFiles()!!.none { it.name.startsWith(".notes-snapshot-") })
    }
}
