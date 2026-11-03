package jp.handwriting.note.storage

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Uses disposable private copies in the probe; never reads or writes the production tree. */
class CompactionIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun normalSaveCompactsAndPreservesDeletedStrokeHistory() {
        check(context.packageName == "net.neomenia.misqnotes.probe")
        val root = File(context.cacheDir, "compaction-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        try {
            val notes = NoteFiles(root)
            val empty = notes.create("Compaction verification")
            val ref = StrokeRef(1, UUID.randomUUID().toString())
            val written = empty.copy(strokes = listOf(ref), nextId = 2)
            notes.save(written) { byteArrayOf(1, 2, 3) }
            val document = notes.document(empty.info.id)
            val deleted = written.copy(strokes = emptyList())
            notes.save(deleted) { error("No new strokes") }
            document.saveThumbnail(byteArrayOf(4, 5))
            val before = document.indexes()
            document.file.appendBytes(ByteArray(9 * 1024 * 1024))
            // Same-content saves also compact already bloated notes.
            notes.save(deleted) { error("No new strokes") }
            assertTrue(document.file.length() < 1024 * 1024)
            verifyIndexes(before, MisqFile(document.file).indexes())
            assertArrayEquals(byteArrayOf(1, 2, 3), document.stroke(ref.storageId))
            assertArrayEquals(byteArrayOf(4, 5), document.thumbnail())
            assertTrue(document.index().events.any { it.blob == null })
            assertEquals(deleted, MisqFile(document.file).readManifest().manifest)
        } finally { root.deleteRecursively() }
    }

    @Test fun suppliedPrivateCopyCompactsWithoutChangingStrokeBytes() {
        check(context.packageName == "net.neomenia.misqnotes.probe")
        val sourcePath = InstrumentationRegistry.getArguments().getString("compactionSource")
        assumeNotNull(sourcePath)
        val source = File(checkNotNull(sourcePath)).canonicalFile
        require(source.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        require(UUID.fromString(source.nameWithoutExtension).toString() == source.nameWithoutExtension)
        val sourceHash = NotebookArchive.digest(source)
        val root = File(context.cacheDir, "compaction-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        try {
            val target = source.copyTo(File(root, source.name))
            val document = MisqFile(target)
            val before = document.indexes()
            val start = System.nanoTime()
            document.save(before.first().manifest) { error("Must reuse saved strokes") }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val after = MisqFile(target).indexes()
            verifyIndexes(before, after)
            assertTrue(target.length() < source.length())
            assertEquals(sourceHash, NotebookArchive.digest(source))
            val refs = after.first().manifest.strokes
            assertTrue(refs.isNotEmpty())
            listOf(refs.first(), refs.last()).forEach { ref ->
                val decoded = NoteRepository.decodeStroke(ref.storageId, document.stroke(ref.storageId))
                assertEquals(ref.storageId, decoded.storageId)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nCOMPACTION_DEVICE before=${source.length()} after=${target.length()} ms=$elapsedMs records=${after.first().records.size} events=${after.first().events.size}\n")
            })
        } finally { root.deleteRecursively() }
    }

    private fun verifyIndexes(before: List<MisqFile.Index>, after: List<MisqFile.Index>) {
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (old, current) ->
            assertEquals(old.copy(generation = current.generation, records = current.records, thumbnail = current.thumbnail), current)
            old.records.forEach { (id, ref) ->
                assertEquals(ref.hash, current.records.getValue(id).hash)
                assertEquals(ref.size, current.records.getValue(id).size)
            }
            assertEquals(old.thumbnail?.hash, current.thumbnail?.hash)
        }
    }
}
