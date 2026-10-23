package jp.handwriting.note.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotebookArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private class MemoryDirectory : ArchiveDirectory {
        val files = mutableMapOf<String, ByteArray>()
        var interruptRevision = false
        var writes = 0
        var organizations = 0
        var scans = 0
        val reads = mutableMapOf<String, Int>()
        override fun organize() { organizations++ }
        override fun names(): Set<String> { scans++; return files.keys.toSet() }
        override fun read(name: String): ByteArray { reads[name] = reads.getOrDefault(name, 0) + 1; return files.getValue(name) }
        override fun write(name: String, bytes: ByteArray) {
            writes++
            if (interruptRevision && name.startsWith("revision-")) {
                files[name] = bytes.copyOf(bytes.size / 2)
                throw java.io.IOException("Interrupted")
            }
            files[name] = bytes.copyOf()
        }
        override fun delete(name: String) { files.remove(name) }
    }
    private fun fixture(): Pair<File, Map<String, File>> {
        val root = temp.newFolder()
        val notes = NoteFiles(File(root, "notes")); val note = notes.create("無題")
        val folders = File(root, "folders.hwp"); NotebookFolders.write(folders, LibraryPreferences())
        return root to mapOf("folders.hwp" to folders, "notes/${note.info.id}/manifest.hwn" to File(root, "notes/${note.info.id}/manifest.hwn"))
    }
    @Test fun interruptedCommitRecoversThePreviousRevisionAndRetryRepairsIt() {
        val (_, files) = fixture(); val directory = MemoryDirectory(); val archive = NotebookArchive(directory)
        archive.commit(files)
        val first = archive.latest()!!
        NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("one", null))
        directory.interruptRevision = true
        assertThrows(java.io.IOException::class.java) { archive.commit(files) }
        assertEquals(first, NotebookArchive(directory).latest())
        directory.interruptRevision = false
        archive.commit(files)
        assertNotEquals(first, NotebookArchive(directory).latest())
        assertEquals(2, directory.names().count { it.startsWith("revision-") })
        val restored = temp.newFolder(); archive.restore(archive.latest()!!, restored)
        assertEquals("one", NotebookFolders.read(File(restored, "folders.hwp"), LibraryPreferences()).folders.single().name)
        assertEquals("無題", NoteFiles(File(restored, "notes")).list().single().title)
    }
    @Test fun unchangedContentDoesNotCreateRevisionsAndUnrelatedFilesSurviveCleanup() {
        val (_, files) = fixture(); val directory = MemoryDirectory(); directory.files["user-file"] = byteArrayOf(1)
        val archive = NotebookArchive(directory); archive.commit(files); val writes = directory.writes
        val organizations = directory.organizations
        archive.commit(files); assertEquals(writes, directory.writes)
        assertEquals(organizations + 1, directory.organizations)
        repeat(3) { index ->
            NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("$index", null)); archive.commit(files)
        }
        assertEquals(2, directory.names().count { it.startsWith("revision-") })
        assertArrayEquals(byteArrayOf(1), directory.read("user-file"))
    }
    @Test fun corruptLatestBlobFallsBackToPreviousContent() {
        val (_, files) = fixture(); val directory = MemoryDirectory(); val archive = NotebookArchive(directory)
        archive.commit(files); val first = archive.latest()!!
        NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("new", null)); archive.commit(files)
        val latest = archive.latest()!!
        directory.files["blob-${latest.files.getValue("folders.hwp")}"] = byteArrayOf(0)
        assertEquals(first, NotebookArchive(directory).latest())
    }
    @Test fun archiveRejectsTraversalAndApplicationPreferences() {
        val (_, files) = fixture(); val archive = NotebookArchive(MemoryDirectory())
        for (path in listOf("../escape", "tools.hwp", "library.hwp", "/absolute"))
            assertThrows(IllegalArgumentException::class.java) { archive.commit(files + (path to files.values.first())) }
    }
    @Test fun foldersRoundTripWithoutImportingApplicationSettings() {
        val file = temp.newFile()
        val source = LibraryPreferences(theme = AppThemeMode.DARK, amoled = true, grid = false,
            paper = NotePaper(color = 0xFF000000.toInt())).added("folder", null)
        NotebookFolders.write(file, source)
        val local = LibraryPreferences(theme = AppThemeMode.LIGHT)
        val loaded = NotebookFolders.read(file, local)
        assertEquals(source.folders, loaded.folders)
        assertEquals(local.copy(folders = source.folders), loaded)
    }
    @Test fun incrementalSaveUsesOneInventoryAndRetainsRecoveryAndUnrelatedFiles() {
        val (_, files) = fixture(); val directory = MemoryDirectory()
        val archive = NotebookArchive(directory)
        archive.commit(files)
        val previous = archive.latest()!!
        val orphan = "blob-${"0".repeat(64)}"
        directory.files[orphan] = byteArrayOf(1)
        directory.files["user-file"] = byteArrayOf(2)
        NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("updated", null))
        directory.scans = 0
        archive.commit(files)
        assertEquals(1, directory.scans)
        assertFalse(orphan in directory.files)
        assertTrue(previous.name in directory.files)
        assertArrayEquals(byteArrayOf(2), directory.files["user-file"])
        val latest = archive.latest()!!
        directory.files["blob-${latest.files.getValue("folders.hwp")}"] = byteArrayOf(0)
        assertEquals(previous, NotebookArchive(directory).latest())
    }
    @Test fun sha256RetainsLeadingZerosAndUnsignedBytes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", NotebookArchive.digest(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", NotebookArchive.digest("abc".toByteArray()))
    }
    @Test fun restoreReusesVerifiedBytesInsteadOfReadingEveryExternalBlobTwice() {
        val (_, files) = fixture(); val directory = MemoryDirectory()
        NotebookArchive(directory).commit(files)
        directory.reads.clear()
        val archive = NotebookArchive(directory)
        val revision = archive.latest()!!
        archive.restore(revision, temp.newFolder())
        assertTrue(directory.reads.filterKeys { it.startsWith("blob-") }.values.all { it == 1 })
    }
    @Test fun matchingLocalCopyAvoidsExternalBlobReadsAndDamagedLocalDataIsReplaced() {
        val (_, files) = fixture(); val directory = MemoryDirectory()
        NotebookArchive(directory).commit(files)
        directory.reads.clear()
        val warm = NotebookArchive(directory)
        warm.latest(files)
        assertTrue(warm.localMatches)
        assertTrue(directory.reads.keys.none { it.startsWith("blob-") })
        files.getValue("folders.hwp").writeBytes(byteArrayOf(0))
        directory.reads.clear()
        val repair = NotebookArchive(directory); val revision = repair.latest(files)!!
        assertFalse(repair.localMatches)
        assertEquals(1, directory.reads.keys.count { it.startsWith("blob-") })
        val target = temp.newFolder(); repair.restore(revision, target)
        assertEquals(LibraryPreferences().folders, NotebookFolders.read(File(target, "folders.hwp"), LibraryPreferences()).folders)
        assertTrue(directory.reads.filterKeys { it.startsWith("blob-") }.values.all { it == 1 })
    }
    @Test fun capturedSnapshotSurvivesLaterLocalOverwriteAndDeletion() {
        val (_, files) = fixture(); val directory = MemoryDirectory(); val archive = NotebookArchive(directory)
        val captured = archive.capture(files)
        NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("later", null))
        assertTrue(files.values.last().delete())
        archive.commitSnapshot(captured)
        val target = temp.newFolder(); archive.restore(archive.latest()!!, target)
        assertTrue(NotebookFolders.read(File(target, "folders.hwp"), LibraryPreferences()).folders.isEmpty())
        assertEquals("無題", NoteFiles(File(target, "notes")).list().single().title)
    }
}
