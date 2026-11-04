package jp.handwriting.note.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in verification on a local snapshot; never changes the supplied files. */
class NotebookCompactionCopyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun compactSuppliedCopiesPreservingBothCommitsAndAllRecordHashes() {
        val input = System.getenv("MISQ_COMPACT_INPUT_DIR")
        assumeTrue("Set MISQ_COMPACT_INPUT_DIR to verify an existing snapshot", input != null)
        val source = File(checkNotNull(input)).canonicalFile
        val output = System.getenv("MISQ_COMPACT_OUTPUT_DIR")?.let { File(it).canonicalFile } ?: temp.newFolder()
        require(output != source && !output.toPath().startsWith(source.toPath()))
        check(output.isDirectory || output.mkdirs())
        val originals = source.listFiles().orEmpty().filter { it.name.endsWith(".misq") }
        assertTrue(originals.isNotEmpty())
        var totalBefore = 0L
        var totalAfter = 0L
        originals.forEach { original ->
            val beforeHash = NotebookArchive.digest(original)
            val before = MisqFile(original).indexes()
            val target = original.copyTo(File(output, original.name))
            val document = MisqFile(target)
            document.compactIfNeeded()
            val after = MisqFile(target).indexes()
            assertEquals(before.size, after.size)
            before.zip(after).forEach { (old, current) ->
                assertEquals(old.copy(generation = current.generation, records = current.records, thumbnail = current.thumbnail), current)
                old.records.forEach { (id, ref) ->
                    assertEquals(ref.hash, current.records.getValue(id).hash)
                    assertEquals(ref.size, current.records.getValue(id).size)
                }
                assertEquals(old.thumbnail?.hash, current.thumbnail?.hash)
            }
            assertEquals(beforeHash, NotebookArchive.digest(original))
            assertTrue(target.length() <= original.length())
            totalBefore += original.length(); totalAfter += target.length()
            println("COMPACTION ${original.name} ${original.length()} -> ${target.length()}")
        }
        println("COMPACTION TOTAL ${originals.size} notes: $totalBefore -> $totalAfter bytes")
        assertTrue(totalAfter < totalBefore)
    }
}
