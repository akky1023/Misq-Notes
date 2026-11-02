package jp.handwriting.note.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveSnapshotTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun detachedDiskSnapshotSurvivesEditsAndIsRemovedAfterFailure() {
        val source = temp.newFile("folders.hwp").also { it.writeText("before") }
        val hash = NotebookArchive.digest(source)
        assertThrows(IllegalStateException::class.java) {
            ArchiveSnapshot.capture(mapOf("folders.hwp" to source)).use { snapshot ->
                source.writeText("after")
                val restored = temp.newFile("restored")
                snapshot.getValue("folders.hwp").copyTo(restored)
                assertEquals(hash, NotebookArchive.digest(restored))
                assertEquals("before", restored.readText())
                error("interrupted upload")
            }
        }
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith(".notes-snapshot-") })
        assertEquals("after", source.readText())
    }

    @Test fun copyingSelectedRemoteBlobOntoItselfPreservesContent() {
        val source = temp.newFile().also { it.writeText("remote content") }
        ArchiveBlob(source).copyTo(source)
        assertEquals("remote content", source.readText())
    }

    @Test fun diskMergeMatchesByteMergeForIndependentNotebookEdits() {
        val notes = NoteFiles(temp.newFolder())
        val note = notes.create("merge")
        val baseFile = notes.document(note.info.id).file
        val base = temp.newFolder().resolve(baseFile.name).also { baseFile.copyTo(it) }
        val left = temp.newFolder().resolve(baseFile.name).also { base.copyTo(it) }
        val right = temp.newFolder().resolve(baseFile.name).also { base.copyTo(it) }
        val a = StrokeRef(1, java.util.UUID.randomUUID().toString())
        val b = StrokeRef(1, java.util.UUID.randomUUID().toString())
        MisqFile(left).save(note.copy(strokes = listOf(a), nextId = 2)) { byteArrayOf(1) }
        MisqFile(right).save(note.copy(strokes = listOf(b), nextId = 2)) { byteArrayOf(2) }
        val path = "notes/${baseFile.name}"
        val work = temp.newFolder()
        val merged = MisqMerge.mergeFiles(mapOf(path to ArchiveBlob(base)), mapOf(path to ArchiveBlob(left)),
            mapOf(path to ArchiveBlob(right)), work)
        val output = File(temp.newFolder(), baseFile.name)
        merged.getValue(path).copyTo(output)
        val manifest = MisqFile(output).readManifest().manifest
        assertEquals(setOf(a.storageId, b.storageId), manifest.strokes.map { it.storageId }.toSet())
        assertEquals(2, manifest.strokes.size)
        assertEquals(NotebookArchive.digest(left), ArchiveBlob(left).hash)
    }
}
