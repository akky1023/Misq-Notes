package jp.handwriting.note.storage

import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** External transport only. The editor/export/merge still receive an ordinary, exact .misq. */
internal class BlockPackedFiles(private val files: PackedFiles) : PackedFiles {
    internal data class Part(val hash: String, val size: Int)
    internal data class Manifest(val hash: String, val size: Long, val noteId: String?, val revision: String?, val parts: List<Part>)
    private var inventory = emptySet<String>()
    private val verifiedParts = mutableSetOf<String>()
    private val verifiedNotes = mutableSetOf<String>()
    private fun verificationKey(manifest: Manifest) = NotebookArchive.digest(encode(manifest))

    override fun names(): Set<String> {
        inventory = files.names()
        verifiedParts.retainAll(inventory)
        return inventory.filterNot { it.matches(BLOCK_NAME) }.toSet()
    }
    private fun readPart(part: Part): ByteArray {
        val name = "block-${part.hash}"
        try {
            val bytes = files.openRead(name).use { input ->
                ByteArray(part.size).also { DataInputStream(input).readFully(it); check(input.read() == -1) }
            }
            check(NotebookArchive.digest(bytes) == part.hash)
            verifiedParts += name
            return bytes
        } catch (error: Exception) { throw IOException("Invalid notebook block: $name", error) }
    }
    private fun verify(manifest: Manifest) {
        if (manifest.parts.any { "block-${it.hash}" !in inventory }) throw IOException("Notebook block is missing")
        if (verificationKey(manifest) in verifiedNotes) return
        BlockInput(manifest).use { input ->
            val buffer = ByteArray(65536)
            while (input.read(buffer) >= 0) { /* Verify exact assembled content without retaining it. */ }
        }
        verifiedNotes += verificationKey(manifest)
    }
    private inner class BlockInput(private val manifest: Manifest) : InputStream() {
        private var next = 0
        private var current = ByteArrayInputStream(byteArrayOf())
        private val hash = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        private var finished = false
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            while (true) {
                val read = current.read(bytes, offset, length)
                if (read > 0) { hash.update(bytes, offset, read); count += read; return read }
                if (next < manifest.parts.size) {
                    current = ByteArrayInputStream(readPart(manifest.parts[next++]))
                } else {
                    if (!finished) {
                        if (count != manifest.size || NotebookArchive.hex(hash.digest()) != manifest.hash) throw IOException("Invalid assembled notebook")
                        finished = true
                        verifiedNotes += verificationKey(manifest)
                    }
                    return -1
                }
            }
        }
    }
    override fun openRead(name: String): InputStream {
        val source = PushbackInputStream(files.openRead(name), MAGIC.size)
        try {
            val prefix = readPrefix(source)
            if (!prefix.contentEquals(MAGIC)) { source.unread(prefix); return source }
            val manifest = decodeBody(source)
            source.close()
            return BlockInput(manifest)
        } catch (error: Exception) { source.close(); throw error }
    }
    override fun read(name: String): ByteArray = openRead(name).use { it.readBytes() }
    private fun manifest(name: String): Manifest? = files.openRead(name).use(::inspect)
    override fun matchesNote(name: String, hash: String): Boolean {
        val manifest = manifest(name) ?: return files.digest(name) == hash
        if (manifest.hash != hash) return false
        verify(manifest)
        return true
    }
    override fun write(name: String, bytes: ByteArray) { files.write(name, bytes); inventory = inventory + name }
    override fun writeStream(name: String, input: InputStream) = writeBlocks(name, input, null)
    override fun writeNote(name: String, file: File) {
        val identity = runCatching { MisqFile(file, readOnlyAlias = true).externalIdentity() }.getOrNull()
        file.inputStream().use { writeBlocks(name, it, identity) }
    }
    private fun writeBlocks(name: String, input: InputStream, identity: Pair<String, String>?) {
        val hash = MessageDigest.getInstance("SHA-256")
        val parts = mutableListOf<Part>()
        var size = 0L
        val buffer = ByteArray(BLOCK_SIZE)
        while (true) {
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) { val byte = input.read(); if (byte < 0) break; buffer[count++] = byte.toByte() }
                else count += read
            }
            if (count == 0) break
            hash.update(buffer, 0, count); size += count
            val bytes = if (count == buffer.size) buffer else buffer.copyOf(count)
            val part = Part(NotebookArchive.digest(bytes), count)
            val block = "block-${part.hash}"
            if (block !in verifiedParts || block !in inventory) {
                val intact = block in inventory && runCatching { readPart(part); true }.getOrDefault(false)
                if (!intact) {
                    // Repair only an interrupted/corrupt object with this exact content hash.
                    if (block in inventory) files.delete(block)
                    files.write(block, bytes)
                    readPart(part) // Verify provider contents before publishing any reference.
                    inventory = inventory + block
                }
            }
            parts += part
            check(parts.size <= MAX_PARTS) { "Notebook has too many blocks" }
        }
        val manifest = Manifest(NotebookArchive.hex(hash.digest()), size, identity?.first, identity?.second, parts)
        check(!name.startsWith("blob-") || name == "blob-${manifest.hash}") { "Notebook snapshot changed" }
        val descriptor = encode(manifest)
        files.write(name, descriptor)
        check(files.read(name).contentEquals(descriptor)) { "Notebook block index was not saved" }
        inventory = inventory + name
        verifiedNotes += verificationKey(manifest)
    }
    override fun publishNote(source: String, target: String, hash: String): Boolean {
        val manifest = manifest(source) ?: return false
        check(manifest.hash == hash)
        verify(manifest)
        val bytes = encode(manifest)
        if (target in inventory) files.delete(target)
        files.write(target, bytes)
        check(files.read(target).contentEquals(bytes)) { "Notebook view was not saved" }
        inventory = inventory + target
        return true
    }
    override fun delete(name: String) {
        check(!name.matches(BLOCK_NAME)) { "Block retention is managed separately" }
        files.delete(name); inventory = inventory - name
    }

    companion object {
        const val BLOCK_SIZE = 1024 * 1024
        private const val MAX_PARTS = 100_000
        private const val MAX_DESCRIPTOR = 16 * 1024 * 1024
        private val MAGIC = "MISQBLK1".toByteArray(Charsets.US_ASCII)
        private val BLOCK_NAME = Regex("block-[a-f0-9]{64}")
        private val HASH = Regex("[a-f0-9]{64}")
        private fun readPrefix(input: InputStream): ByteArray {
            val prefix = ByteArray(MAGIC.size)
            var count = 0
            while (count < prefix.size) {
                val value = input.read(); if (value < 0) break
                prefix[count++] = value.toByte()
            }
            return prefix.copyOf(count)
        }
        fun inspect(input: InputStream): Manifest? = if (readPrefix(input).contentEquals(MAGIC)) decodeBody(input) else null
        private fun decodeBody(input: InputStream): Manifest {
            val data = DataInputStream(input)
            val length = data.readInt(); require(length in 0..MAX_DESCRIPTOR)
            val body = ByteArray(length).also(data::readFully)
            val expected = ByteArray(32).also(data::readFully)
            require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(expected) && data.read() == -1)
            return DataInputStream(ByteArrayInputStream(body)).use { source ->
                require(source.readInt() == 1)
                val hash = source.readUTF(); require(hash.matches(HASH))
                val size = source.readLong(); require(size >= 0)
                val id = source.readUTF().takeIf { it.isNotEmpty() }?.also { require(UUID.fromString(it).toString() == it) }
                val revision = source.readUTF().takeIf { it.isNotEmpty() }?.also { require(it.matches(HASH)) }
                val count = source.readInt(); require(count in 0..MAX_PARTS)
                val parts = List(count) {
                    Part(source.readUTF(), source.readInt()).also { require(it.hash.matches(HASH) && it.size in 1..BLOCK_SIZE) }
                }
                require(parts.sumOf { it.size.toLong() } == size && source.read() == -1)
                Manifest(hash, size, id, revision, parts)
            }
        }
        private fun encode(manifest: Manifest): ByteArray {
            val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
                output.writeInt(1); output.writeUTF(manifest.hash); output.writeLong(manifest.size)
                output.writeUTF(manifest.noteId.orEmpty()); output.writeUTF(manifest.revision.orEmpty()); output.writeInt(manifest.parts.size)
                manifest.parts.forEach { output.writeUTF(it.hash); output.writeInt(it.size) }
            } }.toByteArray()
            require(body.size <= MAX_DESCRIPTOR)
            return ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
                output.write(MAGIC); output.writeInt(body.size); output.write(body)
                output.write(MessageDigest.getInstance("SHA-256").digest(body))
            } }.toByteArray()
        }
    }
}
