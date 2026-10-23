package jp.handwriting.note.editor

import java.util.TreeMap
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PersistentEntriesTest {
    @Test fun randomEditsMatchSortedMapAndRetainedSnapshotsNeverChange() {
        val random = Random(419)
        var entries = PersistentEntries<String>()
        val expected = TreeMap<Long, String>()
        val snapshots = mutableListOf<Pair<List<OrderedEdits.Entry<String>>, List<OrderedEdits.Entry<String>>>>()
        repeat(15000) { step ->
            val id = random.nextLong(1, 1200)
            if (random.nextInt(3) == 0) { entries = entries.without(id); expected.remove(id) }
            else { val value = "$step"; entries = entries.with(id, value); expected[id] = value }
            if (step % 100 == 0) {
                val reference = expected.map { OrderedEdits.Entry(it.key, it.value) }
                assertEquals(reference, entries)
                assertEquals(reference, entries.indices.map { entries[it] })
                expected.forEach { (key, value) -> assertEquals(value, entries.value(key)) }
                snapshots += entries to reference
            }
        }
        snapshots.forEach { (captured, reference) -> assertEquals(reference, captured) }
    }
    @Test fun deletingAllEntriesAndExtremeIdsRetainsCorrectOrdering() {
        var entries = PersistentEntries.from(listOf(OrderedEdits.Entry(Long.MAX_VALUE, "last"), OrderedEdits.Entry(Long.MIN_VALUE, "first")))
        assertEquals(listOf("first", "last"), entries.map { it.value })
        entries = entries.without(Long.MIN_VALUE).without(Long.MAX_VALUE).without(0)
        assertTrue(entries.isEmpty())
        assertThrows(IndexOutOfBoundsException::class.java) { entries[0] }
        assertThrows(NoSuchElementException::class.java) { entries.iterator().next() }
    }
    @Test fun saveSnapshotsSurviveUndoRedoReplaceAndCanceledErase() {
        val edits = OrderedEdits<String>()
        edits.addAll(listOf("a", "b", "c"))
        val saved = edits.snapshot()
        assertSame(saved, edits.snapshot())
        edits.replace(mapOf(2L to "moved")); val moved = edits.snapshot()
        edits.undo(); edits.redo()
        val eraser = edits.beginErase(); edits.erase(eraser) { true }; edits.endErase(eraser, canceled = true)
        assertEquals(listOf("a", "b", "c"), saved.map { it.value })
        assertEquals(listOf("a", "moved", "c"), moved.map { it.value })
        assertEquals(moved, edits.snapshot())
    }
    @Test fun longNotesCaptureWithoutTraversingTheWholeDocument() {
        for (size in listOf(1_000, 10_000, 100_000)) {
            val edits = OrderedEdits<Int>()
            edits.restore((1..size).map { OrderedEdits.Entry(it.toLong(), it) })
            val captured = edits.snapshot()
            val samples = LongArray(300) { value ->
                val start = System.nanoTime()
                val id = edits.reserveStroke(); edits.resolveStroke(id, value)
                val save = edits.snapshot()
                assertSame(save, edits.snapshot())
                System.nanoTime() - start
            }.sorted()
            assertEquals(size, captured.size)
            assertEquals(size + 300, edits.snapshot().size)
            println("EDIT_PERF initial=$size p50us=${samples[150] / 1000} p95us=${samples[285] / 1000} maxus=${samples.last() / 1000}")
        }
    }
}
