package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpatialNoteCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private class Memory : PackedFiles {
        val data = mutableMapOf<String, ByteArray>(); val reads = mutableListOf<String>(); val writes = mutableListOf<String>()
        override fun names() = data.keys.toSet()
        override fun read(name: String): ByteArray { reads += name; return data.getValue(name).copyOf() }
        override fun write(name: String, bytes: ByteArray) { data[name] = bytes.copyOf(); writes += name }
        override fun delete(name: String) { data.remove(name) }
    }
    @Test fun spatialReadIgnoresCreationOrderAndIncludesCrossingStroke() {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("spatial")
        val refs = (1L..4L).map { StrokeRef(it, UUID.randomUUID().toString()) }
        val placements = listOf(StrokePlacement(12000f, 12000f, 12100f), StrokePlacement(100f, 100f, 200f),
            StrokePlacement(5000f, 5000f, 5100f), StrokePlacement(-100f, -100f, 1400f))
        val manifest = empty.copy(strokes = refs, nextId = 5)
        notes.save(manifest, refs.zip(placements).associate { it.first.storageId to it.second }) { byteArrayOf(1, 2, 3) }
        val memory = Memory(); val cache = SpatialNoteCache(memory); val file = notes.document(empty.info.id).file
        val hash = NotebookArchive.digest(file); cache.publish(file, hash)
        val source = cache.open(hash)!!; memory.reads.clear()
        val loaded = source.load(0f, 1000f, emptySet()) { ref, _ -> ref.id }
        assertEquals(setOf(2L, 4L), loaded.toSet())
        assertEquals(2, memory.reads.count { it.startsWith("region-") })
        assertEquals(12100f, source.bottom)
        assertFalse(source.pending(0f, 1000f, loaded.toSet()))
        assertEquals(listOf(1L), source.load(12000f, 13000f, loaded.toSet()) { ref, _ -> ref.id })
        memory.reads.clear()
        assertEquals(listOf(3L), source.next(5000f, 6000f, loaded.toSet(), {}) { ref, _ -> ref.id })
        assertEquals(1, memory.reads.count { it.startsWith("region-") })
        memory.reads.clear()
        assertTrue(source.next(20000f, 23000f, emptySet(), {}) { ref, _ -> ref.id }.isEmpty())
        assertTrue(memory.reads.isEmpty()) // Reaching the end of the neighbourhood must not fetch distant packs.
        assertTrue(source.next(0f, 1000f, setOf(1L, 2L, 3L, 4L), {}) { ref, _ -> ref.id }.isEmpty())
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            source.next(0f, 1000f, emptySet(), { throw java.util.concurrent.CancellationException() }) { _, _ -> error("cancelled before decoding") }
        }
    }
    @Test fun pendingJournalManifestSuppressesDeletedAndReplacedRecords() {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("overlay")
        val refs = (1L..3L).map { StrokeRef(it, UUID.randomUUID().toString()) }
        notes.save(empty.copy(strokes = refs, nextId = 4), refs.associate { it.storageId to StrokePlacement(it.id * 1000f, it.id * 1000f, it.id * 1000f + 20) }) { byteArrayOf(1) }
        val memory = Memory(); val cache = SpatialNoteCache(memory); val file = notes.document(empty.info.id).file
        val hash = NotebookArchive.digest(file); cache.publish(file, hash)
        val replacement = refs[1].copy(storageId = UUID.randomUUID().toString())
        val source = cache.open(hash)!!.withManifest(empty.copy(strokes = listOf(replacement, refs[2]), nextId = 4))
        assertEquals(listOf(3L), source.load(0f, 10000f, emptySet()) { ref, _ -> ref.id })
        assertFalse(source.pending(0f, 2500f, emptySet()))
        assertTrue(source.next(0f, 2500f, emptySet(), {}) { ref, _ -> ref.id }.isEmpty())
    }
    @Test fun changedBandDoesNotRewriteOtherBandsAndDamagedCacheIsRejected() {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("spatial")
        val a = StrokeRef(1, UUID.randomUUID().toString()); val b = StrokeRef(2, UUID.randomUUID().toString())
        val manifest = empty.copy(strokes = listOf(a, b), nextId = 3)
        notes.save(manifest, mapOf(a.storageId to StrokePlacement(0f, 0f, 10f), b.storageId to StrokePlacement(5000f, 5000f, 5010f))) { byteArrayOf(1) }
        val memory = Memory(); val cache = SpatialNoteCache(memory); val file = notes.document(empty.info.id).file
        cache.publish(file, NotebookArchive.digest(file)); memory.writes.clear()
        val moved = a.copy(storageId = UUID.randomUUID().toString())
        notes.save(manifest.copy(strokes = listOf(moved, b)), mapOf(moved.storageId to StrokePlacement(1000f, 1000f, 1010f))) { byteArrayOf(2) }
        val hash = NotebookArchive.digest(file); cache.publish(file, hash)
        assertEquals(1, memory.writes.count { it.startsWith("region-") })
        val source = cache.open(hash)!!
        val pack = source.packs.first { it.members.any { m -> m.ref.id == 1L } }
        memory.data[pack.name] = byteArrayOf(0)
        assertThrows(Exception::class.java) { source.load(1000f, 1100f, emptySet()) { ref, _ -> ref } }
        cache.publish(file, hash) // A new cache instance verifies and repairs the corrupt projection.
        SpatialNoteCache(memory).publish(file, hash)
        assertEquals(listOf(1L), cache.open(hash)!!.load(1000f, 1100f, emptySet()) { ref, _ -> ref.id })
    }
    @Test fun acknowledgedCleanBandIsReusedWithoutReadingItsBodyAfterRestart() {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("tracked")
        val a = StrokeRef(1, UUID.randomUUID().toString()); val b = StrokeRef(2, UUID.randomUUID().toString())
        val manifest = empty.copy(strokes = listOf(a, b), nextId = 3)
        notes.save(manifest, mapOf(a.storageId to StrokePlacement(0f, 0f, 10f), b.storageId to StrokePlacement(5000f, 5000f, 5010f))) { byteArrayOf(1) }
        val memory = Memory(); val original = SpatialNoteCache(memory); val file = notes.document(empty.info.id).file
        val hash = NotebookArchive.digest(file)
        original.publish(file, hash)
        val cleanPack = original.open(hash)!!.packs.single { it.members.single().ref.id == b.id }.name
        BlockChanges.acknowledge(file, BlockChanges.capture(file)!!, hash)
        val changed = a.copy(storageId = UUID.randomUUID().toString())
        notes.save(manifest.copy(strokes = listOf(changed, b)), mapOf(changed.storageId to StrokePlacement(0f, 0f, 20f))) { byteArrayOf(2) }
        memory.reads.clear(); memory.writes.clear()
        val restarted = SpatialNoteCache(memory)
        val updatedHash = NotebookArchive.digest(file)
        restarted.publish(file, updatedHash, BlockChanges.capture(file))
        assertFalse(memory.reads.contains(cleanPack))
        assertEquals(1, memory.writes.count { it.startsWith("region-") })
        assertEquals(listOf(1.toByte()), restarted.open(updatedHash)!!.load(5000f, 5100f, emptySet()) { _, bytes -> bytes.single() })
    }
}
