package jp.handwriting.note.storage

import java.io.File
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic notebooks only: count provider IO independently of device/cache timing. */
class ExternalIoInvestigationTest {
    @get:Rule val temp = TemporaryFolder()

    private class CountingFiles : PackedFiles {
        val data = linkedMapOf<String, ByteArray>()
        val reads = mutableMapOf<String, Int>()
        val writes = mutableMapOf<String, Int>()
        var scans = 0
        override fun names(): Set<String> { scans++; return data.keys.toSet() }
        override fun read(name: String) = openRead(name).use { it.readBytes() }
        override fun openRead(name: String): InputStream {
            reads[name] = reads.getOrDefault(name, 0) + 1
            return data.getValue(name).inputStream()
        }
        override fun write(name: String, bytes: ByteArray) {
            writes[name] = writes.getOrDefault(name, 0) + 1
            data[name] = bytes.copyOf()
        }
        override fun delete(name: String) { data.remove(name) }
        fun reset() { reads.clear(); writes.clear(); scans = 0 }
    }

    @Test fun countWholeNotebookIoForEditMetadataChangeAndDeletion() {
        val root = temp.newFolder()
        val notes = NoteFiles(File(root, "notes"))
        val first = notes.create("first")
        val second = notes.create("second")
        val folders = File(root, "folders.hwp")
        NotebookFolders.write(folders, LibraryPreferences())
        val sources = linkedMapOf("folders.hwp" to folders,
            "notes/${first.info.id}.misq" to notes.document(first.info.id).file,
            "notes/${second.info.id}.misq" to notes.document(second.info.id).file)
        val provider = CountingFiles()
        val archive = NotebookArchive(MisqArchiveDirectory(provider))
        archive.commit(sources)
        val unchangedName = "${second.info.id}.misq"
        fun report(label: String) {
            println("$label: scans=${provider.scans}, unchangedNoteReads=${provider.reads[unchangedName]}, " +
                "noteReads=${provider.reads.filterKeys { it.endsWith(".misq") }}, " +
                "blobReads=${provider.reads.filterKeys { it.startsWith("blob-") }}, " +
                "writes=${provider.writes}")
        }
        provider.reset()
        notes.rename(first.info.id, "edited")
        archive.commit(sources)
        report("edit")
        assertEquals(1, provider.reads[unchangedName])
        assertEquals(1, provider.writes["${first.info.id}.misq"])
        val changedBlob = "blob-${NotebookArchive.digest(sources.getValue("notes/${first.info.id}.misq"))}"
        assertEquals(1, provider.reads[changedBlob])

        provider.reset()
        NotebookFolders.write(folders, LibraryPreferences().added("folder", null))
        archive.commit(sources)
        report("metadata")
        assertEquals(1, provider.reads[unchangedName])
        assertNull(provider.writes[unchangedName])

        provider.reset()
        archive.commit(sources.filterKeys { it != "notes/${first.info.id}.misq" })
        report("delete")
        assertEquals(1, provider.reads[unchangedName])
    }
}
