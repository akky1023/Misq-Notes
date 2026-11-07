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
    @Test fun batchReadPreservesOrderAndRejectsCorruptPayload() {
        val (files, empty) = note(); val a = ref(1); val b = ref(2)
        files.save(empty.copy(strokes = listOf(a, b), nextId = 3)) {
            if (it == a.storageId) byteArrayOf(1) else byteArrayOf(2)
        }
        val document = files.document(empty.info.id)
        assertEquals(listOf(b.id to 2, a.id to 1), document.readStrokes(listOf(b, a)) { ref, bytes -> ref.id to bytes.single().toInt() })
        RandomAccessFile(document.file, "rw").use { output ->
            output.seek(document.index().records.getValue(b.storageId).offset); output.write(3)
        }
        assertThrows(IOException::class.java) { document.readStrokes(listOf(a, b)) { _, bytes -> bytes } }
    }
    @Test fun damagedLatestIndexFallsBackAndPreviousGenerationRemainsAvailable() {
        val (files, empty) = note(); files.rename(empty.info.id, "latest")
        val document = files.document(empty.info.id)
        val latest = MisqFile(document.file)
        assertEquals("latest", latest.readManifest().manifest.info.title)
        assertEquals(empty, latest.previous())
        assertEquals(2, latest.indexes().size)
        val generation = latest.index().generation
        RandomAccessFile(document.file, "rw").use { output ->
            output.seek(8L + generation % 2 * 192 + 8)
            val offset = output.readLong(); output.seek(offset); output.write(0)
        }
        val recovered = MisqFile(document.file).readManifest()
        assertTrue(recovered.recovered)
        assertEquals(empty, recovered.manifest)
    }
    @Test fun clockSkewFixturePreservesSharedAncestorsAndCanBeResolved() {
        val (files, empty) = note()
        val old = ref(1)
        val base = empty.copy(strokes = listOf(old), nextId = 2)
        files.save(base) { byteArrayOf(1) }
        val a = copy(files.document(empty.info.id))
        val b = copy(a)
        val originalEvents = a.index().events
        a.save(base.copy(strokes = emptyList())) { error("deleted") }
        val moved = ref(1)
        b.save(base.copy(strokes = listOf(moved))) { byteArrayOf(2) }
        a.preserveEventTimes(mapOf(old.storageId to 1L))
        b.preserveEventTimes(mapOf(old.storageId to 4102444800000L))
        for (branch in listOf(a, b)) {
            assertEquals(originalEvents, branch.index().events.filter { event -> originalEvents.any { it.version == event.version } })
        }
        assertEquals(1L, a.index().events.last().at)
        assertEquals(4102444800000L, b.index().events.last().at)
        val target = copy(a)
        target.resolve(b.readManifest().manifest, listOf(a, b), b.index().entities)
        target.validate()
        assertEquals(listOf(moved), target.readManifest().manifest.strokes)
        assertArrayEquals(byteArrayOf(2), target.stroke(moved.storageId))
    }
    private fun ref(id: Long) = StrokeRef(id, UUID.randomUUID().toString())
    private fun note(): Pair<MisqTestFiles, NoteManifest> {
        val files = MisqTestFiles(temp.newFolder())
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
        val reopened = MisqTestFiles(packed.file.parentFile!!)
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
    @Test fun obsoleteMisqHeaderIsRejectedWithoutChangingTheFile() {
        val file = File(temp.newFolder(), "old.misq")
        RandomAccessFile(file, "rw").use { it.setLength(136); it.writeLong(0x4D49535130303031L) }
        val before = file.readBytes()
        assertThrows(Exception::class.java) { MisqFile(file).readManifest() }
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun appendOnlySavesBoundIndexGrowthWithoutAnOrphanSweep() {
        val (files, empty) = note()
        var manifest = empty
        val packed = files.document(empty.info.id)
        var shrank = false
        repeat(360) { number ->
            val added = ref(number + 1L)
            manifest = manifest.copy(strokes = manifest.strokes + added, nextId = number + 2L)
            val before = packed.file.length()
            packed.save(manifest) { byteArrayOf(1, 2, 3) }
            if (packed.file.length() < before) shrank = true
        }
        assertTrue("Ordinary additions must reclaim accumulated indexes", shrank)
        val reopened = MisqFile(packed.file)
        assertEquals(manifest, reopened.readManifest().manifest)
        assertEquals(360, reopened.index().events.size)
        assertEquals(360, reopened.index().records.size)
        manifest.strokes.forEach { assertArrayEquals(byteArrayOf(1, 2, 3), reopened.stroke(it.storageId)) }
    }
    @Test fun compactionPreservesDeletedPayloadsBothCommitsAndTheirPreviews() {
        val (files, empty) = note(); val a = ref(1); val b = ref(2)
        val original = empty.copy(strokes = listOf(a, b), nextId = 3)
        files.save(original) { if (it == a.storageId) byteArrayOf(1) else byteArrayOf(2) }
        val packed = files.document(empty.info.id)
        packed.saveThumbnail(byteArrayOf(4))
        packed.save(original.copy(strokes = listOf(b))) { error("already stored") }
        packed.saveThumbnail(byteArrayOf(5))
        val before = packed.indexes()
        packed.file.appendBytes(ByteArray(9 * 1024 * 1024))
        packed.compactIfNeeded()
        val after = MisqFile(packed.file).indexes()
        assertEquals(2, after.size)
        before.zip(after).forEach { (old, current) ->
            assertEquals(old.copy(generation = current.generation, records = current.records, thumbnail = current.thumbnail), current)
            assertEquals(old.records.mapValues { it.value.hash }, current.records.mapValues { it.value.hash })
            assertEquals(old.thumbnail?.hash, current.thumbnail?.hash)
        }
        assertArrayEquals(byteArrayOf(1), packed.stroke(a.storageId))
        assertTrue(packed.index().events.any { it.blob == null })
        // Damage the newest commit to exercise recovery through the retained slot.
        val newest = after.first()
        RandomAccessFile(packed.file, "rw").use { it.seek(8L + newest.generation % 2 * 192 + 8); it.writeLong(0) }
        val fallback = MisqFile(packed.file)
        assertTrue(fallback.readManifest().recovered)
        assertEquals(before[1].manifest, fallback.readManifest().manifest)
        assertArrayEquals(byteArrayOf(4), fallback.thumbnail())
    }
    @Test fun corruptHistoricalPayloadPreventsReplacementButDoesNotFailADurableSave() {
        val (files, empty) = note(); val a = ref(1)
        files.save(empty.copy(strokes = listOf(a), nextId = 2)) { byteArrayOf(1) }
        val packed = files.document(empty.info.id)
        packed.save(empty.copy(nextId = 2)) { error("deleted") }
        RandomAccessFile(packed.file, "rw").use {
            it.seek(packed.index().records.getValue(a.storageId).offset); it.write(2)
        }
        packed.file.appendBytes(ByteArray(9 * 1024 * 1024))
        val before = ContentHash.digest(packed.file)
        assertThrows(IllegalArgumentException::class.java) { packed.compactIfNeeded() }
        assertEquals(before, ContentHash.digest(packed.file))
        assertFalse(File(packed.file.path + ".compact").exists())
        val saved = empty.copy(info = empty.info.copy(title = "saved"), nextId = 2)
        packed.save(saved) { error("deleted") }
        assertEquals(saved, MisqFile(packed.file).readManifest().manifest)
    }
    @Test fun unchangedSaveAndPreviewUpdateAlsoReclaimObsoleteAppends() {
        val (files, empty) = note(); val a = ref(1)
        val manifest = empty.copy(strokes = listOf(a), nextId = 2)
        files.save(manifest) { byteArrayOf(1) }
        val packed = files.document(empty.info.id)
        val events = packed.index().events
        packed.file.appendBytes(ByteArray(9 * 1024 * 1024))
        packed.save(manifest) { error("unchanged") }
        assertTrue(packed.file.length() < 1024 * 1024)
        packed.file.appendBytes(ByteArray(9 * 1024 * 1024))
        packed.saveThumbnail(byteArrayOf(5))
        val reopened = MisqFile(packed.file)
        assertTrue(packed.file.length() < 1024 * 1024)
        assertEquals(manifest, reopened.readManifest().manifest)
        assertEquals(events, reopened.index().events)
        assertArrayEquals(byteArrayOf(1), reopened.stroke(a.storageId))
        assertArrayEquals(byteArrayOf(5), reopened.thumbnail())
    }
}
