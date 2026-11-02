package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalWorkingSetTest {
    @get:Rule val temp = TemporaryFolder()
    private val remote = mutableMapOf<String, ByteArray>()
    private var reads = 0
    private fun set(root: File) = ExternalWorkingSet(root, "test-tree") { hash, file ->
        reads++; file.writeBytes(remote.getValue(hash))
    }
    private fun publish(root: File, name: String): Pair<NoteManifest, String> {
        val notes = NoteFiles(File(root, "notes"))
        val note = notes.create(name)
        val file = notes.document(note.info.id).file
        val hash = NotebookArchive.digest(file)
        remote[hash] = file.readBytes()
        return note to hash
    }
    private fun baseline(vararg notes: Pair<NoteManifest, String>): Map<String, String> {
        val folders = LibraryJson.envelope(NotebookFolders.encode(LibraryPreferences()))
        val hash = NotebookArchive.digest(folders); remote[hash] = folders
        return mapOf("folders.hwp" to hash) + notes.associate { "notes/${it.first.info.id}.misq" to it.second }
    }

    @Test fun savedBodiesAreEvictedAndListSurvivesRestartWithoutDownloading() {
        val root = temp.newFolder(); val a = publish(root, "one"); val b = publish(root, "two")
        val working = set(root).also { it.available = true; it.accept(baseline(a, b)); it.rebuildCatalog(); it.evict() }
        assertTrue(File(root, "notes").listFiles().orEmpty().none { it.extension == "misq" })
        val restarted = set(root)
        val notes = NoteFiles(File(root, "notes")).also { it.backing = restarted }
        assertEquals(setOf("one", "two"), notes.list().map { it.title }.toSet())
        assertEquals(0, reads)
        assertEquals(2, working.references().size)
    }
    @Test fun openingOneNotebookDownloadsOnlyThatBodyAndPreservesEveryStrokeByte() {
        val root = temp.newFolder(); val a = publish(root, "one"); val b = publish(root, "two")
        val raw = NoteFiles(File(root, "notes"))
        val stroke = StrokeRef(1, UUID.randomUUID().toString())
        val manifest = a.first.copy(strokes = listOf(stroke), nextId = 2)
        raw.save(manifest) { byteArrayOf(1, 2, 3) }
        val file = raw.document(a.first.info.id).file
        val hash = NotebookArchive.digest(file); remote[hash] = file.readBytes()
        val working = set(root).also { it.available = true; it.accept(baseline(manifest to hash, b)); it.rebuildCatalog(); it.evict() }
        val notes = NoteFiles(File(root, "notes")).also { it.backing = working }
        assertEquals(manifest, notes.read(manifest.info.id).manifest)
        assertArrayEquals(byteArrayOf(1, 2, 3), notes.stroke(manifest.info.id, stroke.storageId))
        assertEquals(1, reads)
        assertFalse(File(root, "notes/${b.first.info.id}.misq").exists())
    }
    @Test fun evictionKeepsActiveModifiedAndUnknownBodies() {
        val root = temp.newFolder(); val a = publish(root, "active"); val b = publish(root, "dirty"); val c = publish(root, "saved")
        val working = set(root).also { it.available = true; it.accept(baseline(a, b, c)); it.rebuildCatalog() }
        val notes = NoteFiles(File(root, "notes"))
        notes.rename(b.first.info.id, "not yet saved")
        val unknown = publish(root, "new note")
        working.evict(a.first.info.id)
        assertTrue(notes.document(a.first.info.id).file.exists())
        assertTrue(notes.document(b.first.info.id).file.exists())
        assertTrue(notes.document(unknown.first.info.id).file.exists())
        assertFalse(File(root, "notes/${c.first.info.id}.misq").exists())
    }
    @Test fun deleteDoesNotDownloadAndTombstoneSurvivesRestartAndAnOlderSave() {
        val root = temp.newFolder(); val a = publish(root, "one")
        val working = set(root).also { it.available = true; it.accept(baseline(a)); it.rebuildCatalog(); it.evict() }
        val notes = NoteFiles(File(root, "notes")).also { it.backing = working }
        notes.delete(a.first.info.id)
        working.accept(baseline(a)) // An upload captured before the deletion completed later.
        val restarted = set(root)
        assertTrue(restarted.references().isEmpty())
        assertTrue(restarted.summaries().isEmpty())
        assertFalse(restarted.contains(a.first.info.id))
        assertEquals(0, reads)
    }
    @Test fun failedOrCorruptDownloadNeverPublishesAWorkingFile() {
        val root = temp.newFolder(); val a = publish(root, "one")
        val working = set(root).also { it.available = true; it.accept(baseline(a)); it.rebuildCatalog(); it.evict() }
        remote[a.second] = byteArrayOf(7)
        assertThrows(IllegalStateException::class.java) { working.ensure(a.first.info.id) }
        assertFalse(File(root, "notes/${a.first.info.id}.misq").exists())
        assertTrue(File(root, "notes").listFiles().orEmpty().none { it.name.startsWith(".download-") })
        assertEquals(a.second, set(root).baseline["notes/${a.first.info.id}.misq"])
    }
    @Test fun unavailableFolderKeepsListButDoesNotInventAnEmptyNotebook() {
        val root = temp.newFolder(); val a = publish(root, "one")
        set(root).also { it.accept(baseline(a)); it.rebuildCatalog(); it.evict() }
        val restarted = set(root)
        assertEquals("one", restarted.summaries().single().title)
        assertThrows(IllegalStateException::class.java) { restarted.ensure(a.first.info.id) }
        assertFalse(File(root, "notes/${a.first.info.id}.misq").exists())
    }
    @Test fun derivedCatalogCanBeRebuiltWithoutKeepingBodyCopies() {
        val root = temp.newFolder(); val a = publish(root, "one")
        set(root).also { it.accept(baseline(a)); it.rebuildCatalog(); it.evict() }
        File(root, ".notes-external-catalog").writeText("damaged cache")
        val restarted = set(root).also { it.available = true; it.rebuildCatalog() }
        assertEquals("one", restarted.summaries().single().title)
        assertEquals(1, reads)
        assertTrue(File(root, "notes").listFiles().orEmpty().none { it.extension == "misq" })
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".notes-catalog-") })
    }
    @Test fun lazyReferencesKeepUnopenedBodiesWhenPublishingAMetadataChange() {
        val root = temp.newFolder(); val a = publish(root, "one")
        val folders = File(root, "folders.hwp").also { NotebookFolders.write(it, LibraryPreferences()) }
        val directory = object : ArchiveDirectory {
            val records = mutableMapOf<String, ByteArray>()
            override fun names() = records.keys.toSet()
            override fun read(name: String) = records.getValue(name)
            override fun write(name: String, bytes: ByteArray) { records[name] = bytes }
            override fun delete(name: String) { records.remove(name) }
        }
        val archive = NotebookArchive(directory)
        archive.commit(mapOf("folders.hwp" to folders, "notes/${a.first.info.id}.misq" to File(root, "notes/${a.first.info.id}.misq")))
        val working = set(root).also { it.accept(baseline(a)); it.rebuildCatalog(); it.evict() }
        NotebookFolders.write(folders, LibraryPreferences().added("new folder", null))
        ArchiveSnapshot.capture(mapOf("folders.hwp" to folders), working.references()).use { archive.commitSnapshot(it) }
        assertEquals(a.second, archive.latest()!!.files["notes/${a.first.info.id}.misq"])
        assertEquals(0, reads)
        assertFalse(File(root, "notes/${a.first.info.id}.misq").exists())
    }
    @Test fun missingRemoteBodyCanBeRepublishedThroughBoundedFileMaterialization() {
        val root = temp.newFolder(); val a = publish(root, "one")
        val working = set(root).also { it.accept(baseline(a)); it.rebuildCatalog(); it.evict() }
        val directory = object : ArchiveDirectory {
            var written: ByteArray? = null
            override fun names() = emptySet<String>()
            override fun read(name: String) = error("unused")
            override fun write(name: String, bytes: ByteArray) { written = bytes }
            override fun writeFile(name: String, file: File) { written = file.readBytes() }
            override fun delete(name: String) = Unit
        }
        working.references().values.single().writeTo(directory, "blob-${a.second}")
        assertArrayEquals(remote.getValue(a.second), directory.written)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".notes-fetch-") })
    }
    @Test fun catalogFailureDoesNotMakeModifiedWorkEligibleForEviction() {
        val root = temp.newFolder(); val a = publish(root, "one")
        val working = set(root).also { it.accept(baseline(a)); it.rebuildCatalog(); it.evict(); it.available = true; it.ensure(a.first.info.id) }
        NoteFiles(File(root, "notes")).rename(a.first.info.id, "local change")
        val newer = "a".repeat(64)
        working.accept(mapOf("notes/${a.first.info.id}.misq" to newer))
        assertThrows(NoSuchElementException::class.java) { working.rebuildCatalog() }
        working.evict()
        assertEquals("local change", NoteFiles(File(root, "notes")).read(a.first.info.id).manifest.info.title)
    }
    @Test fun deletedPreviewCacheIsRebuiltWithoutPublishingAWorkingBody() {
        val root = temp.newFolder(); val a = publish(root, "one")
        val file = NoteFiles(File(root, "notes")).document(a.first.info.id)
        file.saveThumbnail(byteArrayOf(4, 5, 6))
        val hash = NotebookArchive.digest(file.file); remote[hash] = file.file.readBytes()
        val working = set(root).also { it.available = true; it.accept(baseline(a.first to hash)); it.rebuildCatalog(); it.evict() }
        File(root, "notes/${a.first.info.id}/thumbnail-v2.png").delete()
        val notes = NoteFiles(File(root, "notes")).also { it.backing = working }
        assertArrayEquals(byteArrayOf(4, 5, 6), notes.storedThumbnail(a.first.info.id)!!.readBytes())
        assertFalse(File(root, "notes/${a.first.info.id}.misq").exists())
        assertEquals(1, reads)
    }
    @Test fun legacyOriginalsCannotReappearAsUnsavedWorkingNotebooksAfterEviction() {
        val root = temp.newFolder()
        val old = NoteFiles(File(root, "notes"), false)
        val note = old.create("old format")
        val current = NoteFiles(File(root, "notes"))
        current.save(note) { error("empty note") }
        val file = current.document(note.info.id).file
        val hash = NotebookArchive.digest(file); remote[hash] = file.readBytes()
        val working = set(root).also { it.available = true; it.accept(baseline(note to hash)) }
        working.archiveLegacyOriginals(); working.rebuildCatalog(); working.evict()
        assertTrue(NoteFiles(File(root, "notes")).list().isEmpty())
        assertEquals("old format", working.summaries().single().title)
        assertEquals(1, File(root, ".notes-legacy-originals").listFiles()!!.size)
        assertTrue(File(root, ".notes-legacy-originals").walkTopDown().any { it.name == "manifest.hwn" })
    }
}
