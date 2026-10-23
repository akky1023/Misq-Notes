package jp.handwriting.note.storage

import jp.handwriting.note.editor.ViewPosition
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun paperAndBothTimesSurviveManifestReloadAndSummary() {
        val files = NoteFiles(temp.newFolder())
        val paper = NotePaper(0xFF112233.toInt(), PaperPattern.COLLEGE_RULED, 0x40556677, 48, 2.5f, 0x80123456.toInt(), 4f, 125f)
        val empty = files.create("paper", 1234, paper)
        val modified = empty.copy(info = empty.info.copy(updatedAt = 5678))
        files.save(modified) { error("No strokes") }
        assertEquals(modified, files.read(empty.info.id).manifest)
        val summary = files.list().single()
        assertEquals(paper, summary.paper); assertEquals(1234L, summary.createdAt); assertEquals(5678L, summary.updatedAt)
    }
    @Test fun versionTwoNotesKeepTheirBackgroundAndReceiveThePreviousLineThickness() {
        val root = temp.newFolder(); val files = NoteFiles(root); val empty = files.create("legacy paper", 1234)
        val bytes = java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { out ->
            out.writeInt(0x484E4D31); out.writeInt(2); out.writeUTF(empty.info.id); out.writeUTF("legacy paper")
            out.writeLong(1234); out.writeLong(5678); out.writeFloat(1f); out.writeFloat(0f); out.writeFloat(0f)
            out.writeInt(0xFF202B33.toInt()); out.writeFloat(3f)
            out.writeInt(0xFF112233.toInt()); out.writeInt(PaperPattern.GRID.ordinal); out.writeInt(0xFF445566.toInt()); out.writeInt(48)
            out.writeLong(1); out.writeInt(0)
        } }.toByteArray()
        NoteFiles.atomicWrite(File(root, "${empty.info.id}/manifest.hwn"), bytes)
        val loaded = files.read(empty.info.id).manifest
        assertEquals(NotePaper(0xFF112233.toInt(), PaperPattern.GRID, 0xFF445566.toInt(), 48, 1f), loaded.info.paper)
        files.save(loaded) { error("No strokes") }; assertEquals(loaded, files.read(empty.info.id).manifest)
    }
    @Test fun versionOneNotesKeepTheirTimesAndReceiveThePreviousPaperColor() {
        val root = temp.newFolder(); val files = NoteFiles(root); val empty = files.create("legacy", 1234)
        val bytes = java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { out ->
            out.writeInt(0x484E4D31); out.writeInt(1); out.writeUTF(empty.info.id); out.writeUTF("legacy")
            out.writeLong(1234); out.writeLong(5678); out.writeFloat(1f); out.writeFloat(0f); out.writeFloat(0f)
            out.writeInt(0xFF202B33.toInt()); out.writeFloat(3f); out.writeLong(1); out.writeInt(0)
        } }.toByteArray()
        NoteFiles.atomicWrite(File(root, "${empty.info.id}/manifest.hwn"), bytes)
        val loaded = files.read(empty.info.id).manifest
        assertEquals(NotePaper(), loaded.info.paper); assertEquals(1234L, loaded.info.createdAt); assertEquals(5678L, loaded.info.updatedAt)
        files.save(loaded) { error("No strokes") }; assertEquals(loaded, files.read(empty.info.id).manifest)
    }
    private fun ref(id: Long) = StrokeRef(id, UUID.randomUUID().toString())
    @Test fun integerWidthEndpointsAndTransparencySurviveManifestReload() {
        val files = NoteFiles(temp.newFolder())
        val empty = files.create("style")
        for (size in listOf(1f, 100f)) for (color in listOf(0x00123456, 0x80123456.toInt(), 0xFF123456.toInt())) {
            val note = empty.copy(info = empty.info.copy(size = size, color = color))
            files.save(note) { error("No strokes") }
            assertEquals(note, files.read(empty.info.id).manifest)
        }
    }
    @Test fun roundTripKeepsIdentityViewportStyleAndOpaqueStrokeBytes() {
        val root = temp.newFolder()
        val files = NoteFiles(root)
        val empty = files.create("  講義「数学」  ", 1234)
        val stroke = ref(7)
        val note = empty.copy(info = empty.info.copy(position = ViewPosition(2f, 300f, 9000f), color = 0xFF205EB8.toInt(), size = 4f), strokes = listOf(stroke), nextId = 10)
        files.save(note) { byteArrayOf(1, 2, 3, 4) }
        val reopened = NoteFiles(root)
        assertEquals(note, reopened.read(empty.info.id).manifest)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reopened.stroke(empty.info.id, stroke.storageId))
        assertEquals("講義「数学」", reopened.list().single().title)
    }
    @Test fun failedStrokeWriteCannotCommitNewManifest() {
        val files = NoteFiles(temp.newFolder())
        val original = files.create("original")
        val refs = listOf(ref(1), ref(2))
        try {
            files.save(original.copy(strokes = refs, nextId = 3)) { id ->
                if (id == refs[1].storageId) throw IOException("disk full") else byteArrayOf(1)
            }
            fail("save should fail")
        } catch (_: IOException) { }
        assertEquals(original, files.read(original.info.id).manifest)
    }
    @Test fun unchangedStrokesAreNotEncodedOnEverySaveOrAfterRestart() {
        val root = temp.newFolder()
        val files = NoteFiles(root)
        val original = files.create("note")
        val ref = ref(1)
        val updated = original.copy(strokes = listOf(ref), nextId = 2)
        files.save(updated) { byteArrayOf(1) }
        NoteFiles(root).save(updated) { error("unchanged stroke should not be encoded") }
        assertEquals(updated, files.read(original.info.id).manifest)
    }
    @Test fun corruptManifestFallsBackAndCanBeSavedWithoutDestroyingGoodBackup() {
        val root = temp.newFolder()
        val files = NoteFiles(root)
        val original = files.create("old")
        files.rename(original.info.id, "new")
        val manifest = File(root, "${original.info.id}/manifest.hwn")
        manifest.writeBytes(byteArrayOf(1, 2, 3))
        val recovered = files.read(original.info.id)
        assertTrue(recovered.recovered)
        assertEquals("old", recovered.manifest.info.title)
        files.save(recovered.manifest) { error("no strokes") }
        assertFalse(files.read(original.info.id).recovered)
        assertEquals("old", files.previous(original.info.id)!!.info.title)
    }
    @Test fun interruptionBetweenRenamesUsesBackupAndIgnoresTemporaryFile() {
        val root = temp.newFolder()
        val files = NoteFiles(root)
        val original = files.create("old")
        files.rename(original.info.id, "new")
        val manifest = File(root, "${original.info.id}/manifest.hwn")
        assertTrue(manifest.delete())
        File(manifest.path + ".tmp").writeText("incomplete")
        assertEquals("old", files.read(original.info.id).manifest.info.title)
        assertTrue(files.read(original.info.id).recovered)
    }
    @Test fun orphanCollectionRetainsBothCommittedGenerations() {
        val root = temp.newFolder()
        val files = NoteFiles(root)
        val empty = files.create("note")
        val a = ref(1); val b = ref(2); val c = ref(3)
        for (stroke in listOf(a, b, c)) files.save(empty.copy(strokes = listOf(stroke), nextId = 4)) { byteArrayOf(1) }
        files.collectOrphans(empty.info.id)
        assertFalse(File(root, "${empty.info.id}/${a.storageId}.ink").exists())
        assertTrue(File(root, "${empty.info.id}/${b.storageId}.ink").exists())
        assertTrue(File(root, "${empty.info.id}/${c.storageId}.ink").exists())
    }
    @Test fun corruptStrokeIsDetectedInsteadOfReturningPartialData() {
        val root = temp.newFolder()
        val files = NoteFiles(root)
        val empty = files.create("note"); val stroke = ref(1)
        files.save(empty.copy(strokes = listOf(stroke), nextId = 2)) { byteArrayOf(1, 2, 3) }
        File(root, "${empty.info.id}/${stroke.storageId}.ink").writeBytes(byteArrayOf(4, 5))
        try { files.stroke(empty.info.id, stroke.storageId); fail("corruption must be reported") } catch (_: IOException) { }
    }
    @Test fun renameAndDeleteKeepOtherNotebooksIntact() {
        val root = temp.newFolder(); val files = NoteFiles(root)
        val a = files.create("A"); val b = files.create("B")
        files.rename(a.info.id, "A renamed")
        assertEquals("A renamed", files.read(a.info.id).manifest.info.title)
        files.delete(a.info.id)
        assertEquals(listOf(b.info.id), files.list().map { it.id })
        assertFalse(File(root, a.info.id).exists())
    }
    @Test fun identifiersCannotEscapeStorageDirectory() {
        val files = NoteFiles(temp.newFolder())
        assertThrows(IllegalArgumentException::class.java) { files.delete("../other") }
        assertThrows(IllegalArgumentException::class.java) { files.read("../other") }
    }

    @Test fun versionThreeNotesRetainPaperAndUseDefaultMargin() {
        val root = temp.newFolder(); val files = NoteFiles(root)
        val note = files.create("legacy", 1234, NotePaper(pattern = PaperPattern.RULED, lineWidth = 3f))
        val file = File(root, "${note.info.id}/manifest.hwn")
        val bytes = NoteFiles.readEnvelope(file)
        // The margin is stored immediately before nextId (8) and the stroke count (4).
        val old = bytes.dropLast(24).toByteArray() + bytes.takeLast(12).toByteArray()
        java.nio.ByteBuffer.wrap(old).putInt(4, 3)
        NoteFiles.atomicWrite(file, old)
        assertEquals(note, files.read(note.info.id).manifest)
    }
    @Test fun invalidMarginGeometryIsRejected() {
        for (offset in listOf(-1f, 1001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try { NotePaper(marginOffset = offset); fail("Invalid offset") } catch (_: IllegalArgumentException) { }
        }
        for (width in listOf(0f, 8.25f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try { NotePaper(marginWidth = width); fail("Invalid width") } catch (_: IllegalArgumentException) { }
        }
    }

}
