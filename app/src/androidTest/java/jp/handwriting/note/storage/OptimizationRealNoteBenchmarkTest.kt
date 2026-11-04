package jp.handwriting.note.storage

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import jp.handwriting.note.editor.StrokeIndex
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in: sourceTree/sourceNote/sourceTitle identify a user-authorized, read-only source. */
class OptimizationRealNoteBenchmarkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
    private fun digest(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val count = it.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        NotebookArchive.hex(digest.digest())
    }
    private fun measure(name: String, action: () -> Unit) {
        repeat(3) { action() }
        val samples = List(9) {
            val start = System.nanoTime(); action(); (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        report("OPTIMIZATION $name medianMs=${samples[4]} samplesMs=$samples")
    }

    @Test fun benchmarkAuthorizedSourceCopy() {
        val args = InstrumentationRegistry.getArguments()
        val tree = args.getString("sourceTree"); val note = args.getString("sourceNote"); val title = args.getString("sourceTitle")
        assumeNotNull(tree, note, title)
        require(UUID.fromString(note).toString() == note)
        val context = instrumentation.targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe")
        val uri = Uri.parse(tree)
        check(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        val directory = File(context.cacheDir, "optimization-real-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        try {
            // This facade is used exclusively for names/openRead: no writes to the source tree.
            val remote = MisqDocumentFiles(context.contentResolver, uri).also { it.names() }
            val source = File(directory, "$note.misq")
            remote.openRead(source.name).use { input -> source.outputStream().use { input.copyTo(it, 65536) } }
            val sourceHash = NotebookArchive.digest(source)
            val summary = MisqFile(source).summary()
            assertEquals(title, summary.title)
            val store = ImmutableNoteStore(File(directory, "store"))
            Format3Library(store, File(directory, "exports").also { it.mkdirs() }).imported(source)
            val root = store.current(note!!)!!
            val top = root.info.position.top; val bottom = top + 2000f
            val rows = root.pages.flatMap(store::rows)
            val expected = rows.filter { it.placement.bottom >= top && it.placement.top <= bottom }.sortedBy { it.ref.id }
            assertTrue(expected.isNotEmpty())
            val hashes = expected.associate { it.ref.storageId to it.body.hash }
            measure("real-visible-bytes") {
                val result = ImmutableNoteStore(store.directory).visible(root, top, bottom, emptySet())
                assertEquals(expected.map { it.ref }, result.map { it.first })
                result.forEach { (ref, bytes) -> assertEquals(hashes.getValue(ref.storageId), NotebookArchive.digest(bytes)) }
            }
            measure("real-visible-decode") {
                val result = ImmutableNoteStore(store.directory).visible(root, top, bottom, emptySet())
                    .map { (ref, bytes) -> NoteRepository.decodeStroke(ref.storageId, bytes) }
                assertEquals(expected.map { it.ref.storageId }, result.map { it.storageId })
            }
            val index = StrokeIndex<Long>()
            val original = MisqFile(source, readOnlyAlias = true)
            // Decode outside the timer and retain only bounds, not all Ink shapes at once.
            val bounds = rows.mapNotNull { row ->
                val stroke = NoteRepository.decodeStroke(row.ref.storageId, original.stroke(row.ref.storageId))
                val box = stroke.bounds ?: return@mapNotNull null
                row.ref.id to StrokeIndex.Bounds(box.xMin, box.yMin, box.xMax, box.yMax)
            }.toMap()
            bounds.forEach { (id, box) -> index.add(id, id, box) }
            val regions = List(500) { i ->
                val y = i * root.bottom / 500f
                StrokeIndex.Bounds(0f, y, 1000f, y + 2000f)
            }
            val matches = regions.map { rect -> bounds.filterValues { box ->
                box.left <= rect.right && box.right >= rect.left && box.bottom >= rect.top && box.top <= rect.bottom
            }.keys.sorted() }
            measure("real-viewport-500queries") {
                regions.forEachIndexed { i, rect -> assertEquals(matches[i], index.query(rect).map { it.first }) }
            }
            assertEquals(sourceHash, digest(remote.openRead(source.name)))
            report("REAL_NOTE strokes=${root.count} visible=${expected.size} bytes=${source.length()} sourceHash=$sourceHash sourceUnchanged=true")
        } finally { directory.deleteRecursively() }
    }
}
