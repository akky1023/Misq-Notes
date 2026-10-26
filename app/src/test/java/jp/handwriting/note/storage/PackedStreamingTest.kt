package jp.handwriting.note.storage

import org.junit.Assert.*
import org.junit.Test

class PackedStreamingTest {
    @Test fun scatteredRecordsStreamEachPackOnceBeyondTheReadCacheBudget() {
        val contents = linkedMapOf<String, ByteArray>()
        var reads = 0
        val files = object : PackedFiles {
            override fun names() = contents.keys.toSet()
            override fun read(name: String): ByteArray { if (name.startsWith("pack-")) reads++; return contents.getValue(name) }
            override fun write(name: String, bytes: ByteArray) { contents[name] = bytes }
            override fun delete(name: String) { contents.remove(name) }
        }
        val writer = PackedArchiveDirectory(files); writer.names()
        val expected = (0 until 12).associate { i ->
            val bytes = ByteArray(1024 * 1024) { i.toByte() }
            "blob-${NotebookArchive.digest(bytes)}" to bytes
        }
        expected.forEach { (name, bytes) -> writer.write(name, bytes) }; writer.commit()
        val reader = PackedArchiveDirectory(files); reader.names(); reads = 0
        val names = expected.keys.toList(); var visited = 0
        reader.visit(names + names.reversed()) { name, bytes -> assertArrayEquals(expected.getValue(name), bytes); visited++ }
        assertEquals(24, visited)
        assertEquals(12, reads)
    }
}
