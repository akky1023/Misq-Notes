package jp.handwriting.note.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MisqFileTest {
    @get:Rule val temp = TemporaryFolder()
    private fun ref(id: Long) = StrokeRef(id, UUID.randomUUID().toString())
    private fun note(): Pair<NoteFiles, NoteManifest> {
        val files = NoteFiles(temp.newFolder())
        return files to files.create("ノート", 1234)
    }
    private fun copy(source: MisqFile): MisqFile = MisqFile(File(temp.newFolder(), source.file.name).also { source.file.copyTo(it) })
    @Test fun oneFileRetainsStrokesAndDoesNotReencodeExistingPayloads() {
        val (files, empty) = note(); val a = ref(1); val b = ref(2)
        val one = empty.copy(strokes = listOf(a), nextId = 2)
        files.save(one) { ByteArray(100000) { 42 } }
        val packed = files.document(empty.info.id); val before = packed.file.length()
        val two = one.copy(strokes = listOf(a, b), nextId = 3)
        files.save(two) { id -> assertEquals(b.storageId, id); byteArrayOf(7) }
        assertTrue(packed.file.length() - before < 20000)
        val reopened = NoteFiles(packed.file.parentFile!!)
        assertEquals(two, reopened.read(empty.info.id).manifest)
        assertArrayEquals(ByteArray(100000) { 42 }, reopened.stroke(empty.info.id, a.storageId))
        assertArrayEquals(byteArrayOf(7), reopened.stroke(empty.info.id, b.storageId))
        assertEquals(1, packed.file.parentFile!!.listFiles()!!.count { it.isFile })
    }
    @Test fun incompleteAppendAndFailedEncodingLeaveLastCommitReadable() {
        val (files, empty) = note(); val a = ref(1); val b = ref(2)
        try { files.save(empty.copy(strokes = listOf(a, b), nextId = 3)) {
            if (it == b.storageId) throw IOException("full") else byteArrayOf(1)
        }; fail() } catch (_: IOException) { }
        files.document(empty.info.id).file.appendBytes(ByteArray(1000) { 99 })
        assertEquals(empty, files.read(empty.info.id).manifest)
        files.save(empty.copy(strokes = listOf(a), nextId = 2)) { byteArrayOf(8) }
        assertArrayEquals(byteArrayOf(8), files.stroke(empty.info.id, a.storageId))
    }
    @Test fun damagedLatestSlotFallsBackToPreviousCommittedGeneration() {
        val (files, empty) = note(); files.rename(empty.info.id, "new")
        val packed = files.document(empty.info.id)
        RandomAccessFile(packed.file, "rw").use { it.seek(8 + 20); it.write(ByteArray(32)) }
        val read = files.read(empty.info.id)
        assertTrue(read.recovered); assertEquals(empty, read.manifest)
        files.save(empty.copy(info = empty.info.copy(title = "repaired"))) { error("none") }
        assertFalse(files.read(empty.info.id).recovered)
    }
    @Test fun previewCanBeReadWithoutReadingOrDecodingCorruptStrokePayload() {
        val (files, empty) = note(); val a = ref(1)
        files.save(empty.copy(strokes = listOf(a), nextId = 2)) { byteArrayOf(1, 2, 3) }
        val packed = files.document(empty.info.id); val png = byteArrayOf(7, 8, 9)
        packed.saveThumbnail(png)
        val offset = packed.index().records.getValue(a.storageId).offset
        RandomAccessFile(packed.file, "rw").use { it.seek(offset); it.write(99) }
        assertArrayEquals(png, packed.thumbnail())
        assertEquals("ノート", files.list().single().title)
        assertThrows(IOException::class.java) { packed.stroke(a.storageId) }
    }
    @Test fun legacyMigrationKeepsOriginalAndEmbedsAllBytes() {
        val root = temp.newFolder(); val legacy = NoteFiles(root, false); val empty = legacy.create("old")
        val a = ref(1); val manifest = empty.copy(strokes = listOf(a), nextId = 2)
        legacy.save(manifest) { byteArrayOf(1, 2, 3) }
        val files = NoteFiles(root)
        files.save(manifest) { error("migration must read legacy data") }
        assertTrue(File(root, "${empty.info.id}/manifest.hwn").exists())
        assertEquals(manifest, files.read(empty.info.id).manifest)
        File(root, empty.info.id).deleteRecursively()
        assertArrayEquals(byteArrayOf(1, 2, 3), files.stroke(empty.info.id, a.storageId))
    }
    @Test fun simultaneousAdditionsWithSameNumericOrderBothSurviveMergeAndReload() {
        val (files, empty) = note(); val base = files.document(empty.info.id)
        val a = copy(base); val b = copy(base); val left = ref(1); val right = ref(1)
        a.save(empty.copy(strokes = listOf(left), nextId = 2)) { byteArrayOf(1) }
        b.save(empty.copy(strokes = listOf(right), nextId = 2)) { byteArrayOf(2) }
        a.rewrite(MisqFile.merge(base, a, b), b)
        a.validate()
        assertEquals(setOf(left.storageId, right.storageId), a.index().manifest.strokes.map { it.storageId }.toSet())
        assertEquals(listOf(1L, 2L), a.index().manifest.strokes.map { it.id })
        assertArrayEquals(byteArrayOf(2), a.stroke(right.storageId))
    }
    @Test fun deletionAndIndependentAdditionMergeWithoutResurrectingDeletedStroke() {
        val (files, empty) = note(); val old = ref(1)
        val initial = empty.copy(strokes = listOf(old), nextId = 2)
        files.save(initial) { byteArrayOf(1) }
        val base = files.document(empty.info.id); val a = copy(base); val b = copy(base); val added = ref(2)
        a.save(initial.copy(strokes = emptyList())) { error("none") }
        b.save(initial.copy(strokes = listOf(old, added), nextId = 3)) { byteArrayOf(2) }
        a.rewrite(MisqFile.merge(base, a, b), b)
        assertEquals(listOf(added.storageId), a.index().manifest.strokes.map { it.storageId })
        assertTrue(a.index().events.any { it.blob == null })
        a.rewrite(MisqFile.merge(base, a, b), b)
        assertEquals(listOf(added.storageId), a.index().manifest.strokes.map { it.storageId })
    }
    @Test fun sameStrokeMoveAgainstDeleteReportsConflictAndLeavesBothFilesIntact() {
        val (files, empty) = note(); val old = ref(1)
        val initial = empty.copy(strokes = listOf(old), nextId = 2); files.save(initial) { byteArrayOf(1) }
        val base = files.document(empty.info.id); val a = copy(base); val b = copy(base)
        val moved = ref(1)
        a.save(initial.copy(strokes = listOf(moved))) { byteArrayOf(2) }
        b.save(initial.copy(strokes = emptyList())) { error("none") }
        val left = a.file.readBytes(); val right = b.file.readBytes()
        assertThrows(ArchiveConflictException::class.java) { MisqFile.merge(base, a, b) }
        assertArrayEquals(left, a.file.readBytes()); assertArrayEquals(right, b.file.readBytes())
    }
    @Test fun folderMembershipAndNoteContentMergeIndependently() {
        val (files, empty) = note(); val base = files.document(empty.info.id)
        val a = copy(base); val b = copy(base); val stroke = ref(1)
        a.save(empty.copy(strokes = listOf(stroke), nextId = 2)) { byteArrayOf(1) }
        val original = LibraryPreferences().added("folder", null)
        val moved = original.moved(empty.info.id, original.folders.single().id)
        val path = "notes/${empty.info.id}.misq"
        fun snapshot(note: MisqFile, library: LibraryPreferences) = mapOf(path to note.file.readBytes(), "folders.hwp" to LibraryJson.envelope(NotebookFolders.encode(library)))
        val merged = MisqMerge.merge(snapshot(base, original), snapshot(a, original), snapshot(b, moved), temp.newFolder())
        val folders = NotebookFolders.decode(LibraryJson.unwrap(merged.getValue("folders.hwp").bytes))
        assertEquals(moved.locations, folders.locations)
        assertArrayEquals(a.file.readBytes(), merged.getValue(path).bytes)
    }
    @Test fun addingAfterMergePreservesDrawingOrderEvenWhenOriginalIdsHadGaps() {
        val (files, empty) = note(); val original = ref(100)
        val initial = empty.copy(strokes = listOf(original), nextId = 101)
        files.save(initial) { byteArrayOf(1) }
        val base = files.document(empty.info.id); val a = copy(base); val b = copy(base)
        val left = ref(101); val right = ref(101)
        a.save(initial.copy(strokes = initial.strokes + left, nextId = 102)) { byteArrayOf(2) }
        b.save(initial.copy(strokes = initial.strokes + right, nextId = 102)) { byteArrayOf(3) }
        a.rewrite(MisqFile.merge(base, a, b), b)
        val merged = a.index().manifest; val newest = ref(merged.nextId)
        a.save(merged.copy(strokes = merged.strokes + newest, nextId = merged.nextId + 1)) { byteArrayOf(4) }
        a.rewrite(MisqFile.merge(base, a, b), b)
        assertEquals(newest.storageId, a.index().manifest.strokes.last().storageId)
    }
    @Test fun summaryAndPreviewDoNotParseTheLargeMainIndex() {
        val (files, empty) = note(); val packed = files.document(empty.info.id)
        val png = byteArrayOf(1, 2, 3); packed.saveThumbnail(png)
        val generation = packed.index().generation
        java.io.RandomAccessFile(packed.file, "rw").use { input ->
            input.seek(8L + (generation % 2) * 192 + 8)
            val indexOffset = input.readLong()
            input.seek(indexOffset); input.write(0)
        }
        assertArrayEquals(png, packed.thumbnail())
        assertEquals(empty.info.title, packed.summary().title)
        assertEquals(empty.info.title, files.list().single().title)
    }
    @Test fun damagedHeaderCannotBeReplacedByANewEmptyCommit() {
        val (files, empty) = note(); val packed = files.document(empty.info.id)
        packed.file.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { files.save(empty) { error("none") } }
        assertArrayEquals(byteArrayOf(1, 2, 3), packed.file.readBytes())
    }
    @Test fun compactionPreservesPreviousContentEvenAfterThumbnailUpdate() {
        val (files, empty) = note(); val a = ref(1); val b = ref(2)
        val first = empty.copy(strokes = listOf(a), nextId = 2)
        files.save(first) { byteArrayOf(1) }
        val second = first.copy(strokes = listOf(a, b), nextId = 3)
        files.save(second) { byteArrayOf(2) }
        val packed = files.document(empty.info.id); packed.saveThumbnail(byteArrayOf(5))
        packed.rewrite(packed.index()); packed.validate()
        assertEquals(second, packed.readManifest().manifest)
        assertEquals(first, packed.previous())
        assertArrayEquals(byteArrayOf(1), packed.stroke(a.storageId))
        assertArrayEquals(byteArrayOf(2), packed.stroke(b.storageId))
    }
    @Test fun initialMisqFormatUpgradesWithoutLosingNoteMetadata() {
        val root = temp.newFolder(); val codec = NoteFiles(root, false)
        val empty = codec.create("version one", 1234)
        val file = File(root, "${empty.info.id}.misq")
        val json = org.json.JSONObject().put("format", 1).put("generation", 1).put("id", empty.info.id)
            .put("manifest", java.util.Base64.getEncoder().encodeToString(codec.encodeManifest(empty)))
            .put("records", org.json.JSONObject()).put("entities", org.json.JSONObject())
            .put("events", org.json.JSONArray()).put("thumbnail", org.json.JSONObject.NULL).put("thumbnailKey", org.json.JSONObject.NULL)
        val bytes = json.toString().toByteArray()
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(136); output.writeLong(0x4D49535130303031L)
            output.seek(136); output.write(bytes)
            output.seek(72); output.writeLong(1); output.writeLong(136); output.writeInt(bytes.size)
            output.write(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        }
        val files = NoteFiles(root); assertEquals(empty, files.read(empty.info.id).manifest)
        files.rename(empty.info.id, "upgraded")
        assertEquals("upgraded", files.read(empty.info.id).manifest.info.title)
        assertEquals(empty, files.document(empty.info.id).previous())
        RandomAccessFile(file, "r").use { assertEquals(0x4D49535130303032L, it.readLong()) }
    }
}
