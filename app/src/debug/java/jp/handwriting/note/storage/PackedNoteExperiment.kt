package jp.handwriting.note.storage

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Experimental immutable packs, grouped by note and capped at roughly 1 MiB per pack. */
internal object PackedNoteExperiment {
    fun write(files: Map<String, File>, output: (String) -> OutputStream): List<String> {
        val names = mutableListOf<String>()
        val groups = files.entries.groupBy { if (it.key.startsWith("notes/")) it.key.split('/')[1] else "settings" }
        for ((group, entries) in groups) {
            val chunks = mutableListOf<MutableList<Map.Entry<String, File>>>()
            var size = 0L
            for (entry in entries.sortedBy { it.key }) {
                if (chunks.isEmpty() || size + entry.value.length() > 1024 * 1024) {
                    chunks.add(mutableListOf()); size = 0
                }
                chunks.last().add(entry); size += entry.value.length()
            }
            chunks.forEachIndexed { index, chunk ->
                val name = "$group-$index.hnp"
                DataOutputStream(output(name).buffered()).use { out ->
                    out.writeInt(0x484E5031); out.writeInt(1); out.writeInt(chunk.size)
                    for ((path, file) in chunk) {
                        val bytes = file.readBytes()
                        out.writeUTF(path); out.writeInt(bytes.size)
                        out.write(MessageDigest.getInstance("SHA-256").digest(bytes)); out.write(bytes)
                    }
                }
                names += name
            }
        }
        return names
    }

    fun verify(names: List<String>, expected: Map<String, String>, input: (String) -> InputStream) {
        val seen = mutableSetOf<String>()
        for (name in names) DataInputStream(input(name).buffered()).use { stream ->
            check(stream.readInt() == 0x484E5031 && stream.readInt() == 1)
            val count = stream.readInt(); check(count in 1..1_000_000)
            repeat(count) {
                val path = stream.readUTF(); check(path in expected && seen.add(path))
                val size = stream.readInt(); check(size in 0..128 * 1024 * 1024)
                val hash = ByteArray(32).also(stream::readFully)
                val bytes = ByteArray(size).also(stream::readFully)
                check(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(hash))
                check(NotebookArchive.digest(bytes) == expected.getValue(path))
            }
            check(stream.read() == -1)
        }
        check(seen == expected.keys)
    }
}
