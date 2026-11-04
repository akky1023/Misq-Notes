package jp.handwriting.note.storage

import java.util.UUID
import jp.handwriting.note.editor.StrokeIndex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic, local-only workloads. Timings are reported, never used as flaky pass/fail limits. */
class OptimizationBenchmarkTest {
    @get:Rule val temp = TemporaryFolder()

    private fun measure(name: String, action: () -> Unit) {
        repeat(3) { action() }
        val samples = List(9) {
            val start = System.nanoTime()
            action()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println("OPTIMIZATION $name medianMs=${samples[4]} samplesMs=$samples")
    }

    @Test fun storedNoteReadAndManifestTraversal() {
        val files = NoteFiles(temp.newFolder())
        val empty = files.create("synthetic benchmark")
        val refs = (1L..4096L).map { StrokeRef(it, UUID.randomUUID().toString()) }
        val placements = refs.associate { it.storageId to StrokePlacement(0f, 0f, 20f) }
        val body = ByteArray(128) { it.toByte() }
        files.save(empty.copy(strokes = refs, nextId = 4097), placements) { body }
        val store = ImmutableNoteStore(temp.newFolder())
        val root = store.importNote(files.document(empty.info.id))
        measure("visible-4096") {
            val result = store.visible(root, 0f, 100f, emptySet())
            assertEquals(refs, result.map { it.first })
            assertTrue(result.all { it.second.contentEquals(body) })
        }
        val expected = root.pages.flatMap { store.rows(it).map { row -> row.ref } }
        measure("manifest-4096") {
            val strokes = store.manifest(root).strokes
            assertEquals(expected, strokes.toList())
            assertEquals(expected.last(), strokes.last())
        }
    }

    @Test fun viewportQueries() {
        val index = StrokeIndex<Int>()
        repeat(100_000) { i ->
            val y = (i / 20) * 20f
            index.add(i.toLong(), i, StrokeIndex.Bounds((i % 20) * 30f, y, (i % 20) * 30f + 25f, y + 25f))
        }
        measure("viewport-100000-500queries") {
            repeat(500) { i ->
                val y = i * 100f
                val result = index.query(StrokeIndex.Bounds(0f, y, 1000f, y + 1000f))
                assertEquals(if (i == 0) 1020 else 1040, result.size)
                assertTrue(result.zipWithNext().all { (a, b) -> a.first < b.first })
            }
        }
    }
}
