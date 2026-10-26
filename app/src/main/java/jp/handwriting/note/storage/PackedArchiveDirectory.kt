package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import java.io.*
import java.util.UUID

internal class ArchiveConflictException : IOException(AppMessages.text(R.string.storage_conflict))

/** Physical, immutable objects in a dedicated directory. Other files are never removed. */
internal interface PackedFiles {
    fun names(): Set<String>
    fun read(name: String): ByteArray
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

/** Logical blob/revision API over immutable packs and checksummed commit indexes. */
internal class PackedArchiveDirectory(private val files: PackedFiles) : ArchiveDirectory {
    override var recoveredIndex = false
        private set
    private var head: String? = null
    private var records = mutableMapOf<String, String>()
    private val staged = linkedMapOf<String, ByteArray>()
    private var dirty = false
    private val cache = LinkedHashMap<String, Map<String, ByteArray>>(16, 0.75f, true)
    private var cacheBytes = 0L
    override fun names(): Set<String> {
        val indexes = files.names().filter { it.matches(INDEX) }.sortedDescending()
        if (indexes.size > 1 && indexes[0].substring(6, 26) == indexes[1].substring(6, 26)) throw ArchiveConflictException()
        head = indexes.firstOrNull(); staged.clear(); cache.clear(); cacheBytes = 0; dirty = false; recoveredIndex = false
        if (indexes.isEmpty()) { records.clear(); return emptySet() }
        for (name in indexes) {
            val loaded = runCatching { decodeIndex(files.read(name)) }.getOrNull() ?: continue
            records = loaded.toMutableMap(); recoveredIndex = name != indexes.first(); return records.keys.toSet()
        }
        throw IOException(AppMessages.text(R.string.storage_read_retained))
    }
    override fun read(name: String): ByteArray {
        staged[name]?.let { return it }
        val pack = records.getValue(name)
        var decoded = cache[pack]
        if (decoded == null) {
            val bytes = files.read(pack)
            check(NotebookArchive.digest(bytes) == pack.removePrefix("pack-"))
            decoded = decodePack(bytes)
            val size = decoded.values.sumOf { it.size.toLong() }
            if (size <= CACHE_BYTES) {
                cache[pack] = decoded; cacheBytes += size
                while (cacheBytes > CACHE_BYTES) {
                    val first = cache.entries.first(); cacheBytes -= first.value.values.sumOf { it.size.toLong() }; cache.remove(first.key)
                }
            }
        }
        return decoded.getValue(name)
    }
    override fun visit(names: List<String>, accept: (String, ByteArray) -> Unit) {
        // Logical UUID order scatters nearby records across hundreds of old packs. Stream each
        // physical pack once, keeping memory bounded even when the whole archive exceeds cache.
        names.groupBy { records.getValue(it) }.forEach { (pack, members) ->
            val decoded = cache[pack] ?: files.read(pack).let { bytes ->
                check(NotebookArchive.digest(bytes) == pack.removePrefix("pack-")); decodePack(bytes)
            }
            members.forEach { accept(it, decoded.getValue(it)) }
        }
    }
    override fun write(name: String, bytes: ByteArray) {
        check(ArchiveLayout.complete(name, bytes))
        staged[name] = bytes; dirty = true
    }
    override fun delete(name: String) { staged.remove(name); if (records.remove(name) != null) dirty = true }
    override fun commit() {
        if (!dirty) return
        checkHead()
        val groups = mutableListOf<MutableMap<String, ByteArray>>()
        var size = 0L
        for ((name, bytes) in staged) {
            if (groups.isEmpty() || size + bytes.size > 1024 * 1024) { groups += linkedMapOf<String, ByteArray>(); size = 0 }
            groups.last()[name] = bytes; size += bytes.size
        }
        for (group in groups) {
            val bytes = encodePack(group)
            val name = "pack-${NotebookArchive.digest(bytes)}"
            files.write(name, bytes)
            check(files.read(name).contentEquals(bytes))
            group.keys.forEach { records[it] = name }
        }
        publish()
        staged.clear(); dirty = false
    }
    override fun organize() {
        if (recoveredIndex) dirty = true
        commit()
        recoveredIndex = false
        // Keep two verified index generations and every pack either one still references.
        checkHead()
        val names = files.names()
        val indexes = names.filter { it.matches(INDEX) }.sortedDescending()
        val keep = indexes.filter { runCatching { decodeIndex(files.read(it)) }.isSuccess }.take(2)
        check(keep.firstOrNull() == head)
        // Do not collect immutable packs automatically: another device may be preparing an index
        // that references one. Keeping them also permits recovery of an interrupted/forked writer.
        indexes.filter { it !in keep }.forEach { name -> runCatching { files.delete(name) } }
    }
    private fun checkHead() {
        if (files.names().filter { it.matches(INDEX) }.maxOrNull() != head) throw ArchiveConflictException()
    }
    private fun publish() {
        checkHead()
        val sequence = (head?.substring(6, 26)?.toLongOrNull() ?: 0L) + 1
        val name = "index-${sequence.toString().padStart(20, '0')}-${UUID.randomUUID()}"
        val bytes = encodeIndex(records)
        files.write(name, bytes)
        check(files.read(name).contentEquals(bytes))
        val peers = files.names().filter { it.matches(INDEX) && it.substring(6, 26) == name.substring(6, 26) }
        if (peers.size != 1) throw ArchiveConflictException()
        head = name
    }
    companion object {
        private val INDEX = Regex("index-[0-9]{20}-[a-f0-9-]{36}")
        private val PACK = Regex("pack-[a-f0-9]{64}")
        private const val CACHE_BYTES = 8L * 1024 * 1024
        const val MAX_BYTES = 128 * 1024 * 1024 + 1024
        private fun checkedBody(bytes: ByteArray): ByteArray {
            require(bytes.size in 44..MAX_BYTES)
            val body = bytes.copyOf(bytes.size - 32)
            require(java.security.MessageDigest.getInstance("SHA-256").digest(body).contentEquals(bytes.takeLast(32).toByteArray()))
            return body
        }
        private fun encode(magic: Int, count: Int, block: (DataOutputStream) -> Unit): ByteArray {
            val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
                out.writeInt(magic); out.writeInt(1); out.writeInt(count); block(out)
            } }.toByteArray()
            require(body.size <= MAX_BYTES - 32)
            return body + java.security.MessageDigest.getInstance("SHA-256").digest(body)
        }
        private fun encodePack(values: Map<String, ByteArray>) = encode(0x484E5032, values.size) { out ->
            values.forEach { (name, bytes) -> out.writeUTF(name); out.writeInt(bytes.size); out.write(bytes) }
        }
        private fun decodePack(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(checkedBody(bytes))).use { input ->
            require(input.readInt() == 0x484E5032 && input.readInt() == 1)
            val count = input.readInt(); require(count in 1..1_000_000)
            val values = linkedMapOf<String, ByteArray>()
            repeat(count) {
                val name = input.readUTF(); require(ArchiveLayout.isRecord(name) && name !in values)
                val size = input.readInt(); require(size in 0..minOf(MAX_BYTES, input.available()))
                val value = ByteArray(size).also(input::readFully); require(ArchiveLayout.complete(name, value))
                values[name] = value
            }
            require(input.available() == 0); values
        }
        private fun encodeIndex(values: Map<String, String>) = encode(0x484E4931, values.size) { out ->
            values.toSortedMap().forEach { (name, pack) -> out.writeUTF(name); out.writeUTF(pack) }
        }
        private fun decodeIndex(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(checkedBody(bytes))).use { input ->
            require(input.readInt() == 0x484E4931 && input.readInt() == 1)
            val count = input.readInt(); require(count in 1..1_000_000)
            val values = linkedMapOf<String, String>()
            repeat(count) {
                val name = input.readUTF(); val pack = input.readUTF()
                require(ArchiveLayout.isRecord(name) && name !in values && pack.matches(PACK)); values[name] = pack
            }
            require(input.available() == 0); values
        }
    }
}
