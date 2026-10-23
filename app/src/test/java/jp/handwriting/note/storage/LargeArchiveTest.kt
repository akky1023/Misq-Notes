package jp.handwriting.note.storage

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in regression with a user's archive; private notebook contents are never bundled. */
class LargeArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun suppliedArchiveRestoresAndCollectsOnlyUnreferencedBlobs() {
        val path = System.getenv("MISQ_PERF_ZIP")
        assumeTrue("Set MISQ_PERF_ZIP to run the private archive regression", path != null)
        val records = ZipFile(checkNotNull(path)).use { zip -> zip.entries().asSequence()
            .filter { !it.isDirectory && ArchiveLayout.isRecord(it.name.substringAfterLast('/')) }
            .associate { it.name.substringAfterLast('/') to zip.getInputStream(it).use { input -> input.readBytes() } }.toMutableMap() }
        var reads = 0
        var scans = 0
        var blobReads = 0
        val directory = object : ArchiveDirectory {
            override fun names(): Set<String> { scans++; return records.keys.toSet() }
            override fun read(name: String): ByteArray { reads++; if (name.startsWith("blob-")) blobReads++; return records.getValue(name) }
            override fun write(name: String, bytes: ByteArray) { records[name] = bytes }
            override fun delete(name: String) { records.remove(name) }
        }
        val start = System.nanoTime()
        val initialRecordCount = records.size
        val archive = NotebookArchive(directory)
        val revision = checkNotNull(archive.latest())
        val coldBlobReads = blobReads
        assertFalse(archive.recovered)
        val root = temp.newFolder()
        archive.restore(revision, root)
        assertEquals("Restore must reuse the bytes verified by latest()", coldBlobReads, blobReads)
        val notes = NoteFiles(File(root, "notes"))
        val summaries = notes.list()
        assertTrue(summaries.isNotEmpty())
        assertTrue(summaries.all { it.problem == null })
        val manifests = summaries.associate { it.id to notes.read(it.id).manifest }
        summaries.forEach { notes.collectOrphans(it.id) }
        val files = revision.files.keys.mapNotNull { relative -> File(root, relative).takeIf { it.exists() }?.let { relative to it } }.toMap()
        scans = 0; reads = 0
        val saving = System.nanoTime()
        archive.commit(files)
        val saveMillis = (System.nanoTime() - saving) / 1_000_000
        val saveScans = scans; val saveReads = reads
        assertEquals("A save must enumerate the external archive only once", 1, saveScans)
        val reopened = NotebookArchive(directory)
        val saved = checkNotNull(reopened.latest())
        val restored = temp.newFolder()
        reopened.restore(saved, restored)
        manifests.forEach { (id, manifest) -> assertEquals(manifest, NoteFiles(File(restored, "notes")).read(id).manifest) }
        assertEquals(2, records.keys.count { it.startsWith("revision-") })
        assertTrue(records.size < initialRecordCount)
        // Reproduce ordinary saves after initialization, with all immutable strokes already hashed.
        val warm = System.nanoTime()
        repeat(3) { index ->
            NotebookFolders.write(File(root, "folders.hwp"), LibraryPreferences().added("benchmark-$index", null))
            archive.commit(files)
        }
        val warmMillis = (System.nanoTime() - warm) / 3 / 1_000_000
        blobReads = 0
        val restartStart = System.nanoTime()
        val restart = NotebookArchive(directory)
        assertNotNull(restart.latest(files))
        assertTrue(restart.localMatches)
        assertEquals("An unchanged local copy must not reread external stroke files", 0, blobReads)
        val restartMillis = (System.nanoTime() - restartStart) / 1_000_000
        println("ARCHIVE_PERF strokes=${summaries.sumOf { it.strokes }} inputBlobs=${revision.files.values.distinct().size} coldBlobReads=$coldBlobReads restoreExtraReads=0 restartBlobReads=0 restartMs=$restartMillis saveMs=$saveMillis saveScans=$saveScans saveReads=$saveReads warmSaveMs=$warmMillis totalMs=${(System.nanoTime() - start) / 1_000_000}")
    }
}
