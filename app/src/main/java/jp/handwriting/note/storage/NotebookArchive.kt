package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Immutable blobs are written before the revision that references them. A partial write is never current. */
internal interface ArchiveDirectory {
    val recoveredIndex: Boolean get() = false
    fun names(): Set<String>
    fun read(name: String): ByteArray
    fun matches(name: String, hash: String): Boolean = NotebookArchive.digest(read(name)) == hash
    fun readBatch(names: List<String>): List<ByteArray> = names.map(::read)
    fun visit(names: List<String>, accept: (String, ByteArray) -> Unit) {
        for (batch in names.chunked(4)) {
            val contents = readBatch(batch); check(contents.size == batch.size)
            batch.zip(contents).forEach { (name, bytes) -> accept(name, bytes) }
        }
    }
    fun write(name: String, bytes: ByteArray)
    fun writeFile(name: String, file: File) = write(name, file.readBytes())
    fun copyTo(name: String, file: File) { file.writeBytes(read(name)) }
    fun delete(name: String)
    fun commit() {}
    fun organize() {}
}

/** Snapshots can own disk copies instead of retaining whole notebooks in the Android heap. */
internal class ArchiveBlob private constructor(private val memory: ByteArray?, private val file: File?,
    private val remoteHash: String? = null, private val fetch: ((File) -> Unit)? = null, private val temporary: File? = null,
    val changes: BlockChanges.Capture? = null) {
    constructor(bytes: ByteArray) : this(bytes, null)
    constructor(file: File) : this(null, file)
    constructor(file: File, hash: String) : this(null, file, hash)
    constructor(file: File, hash: String, changes: BlockChanges.Capture?) : this(null, file, hash, changes = changes)
    constructor(hash: String, temporary: File, fetch: (File) -> Unit) : this(null, null, hash, fetch, temporary)
    private fun <T> materialize(block: (File) -> T): T {
        file?.let { return block(it) }
        val copy = File(checkNotNull(temporary), ".notes-fetch-${UUID.randomUUID()}.misq")
        try { copyTo(copy); return block(copy) } finally { copy.delete() }
    }
    val bytes: ByteArray get() = memory ?: materialize { it.readBytes() }
    val size: Long get() = memory?.size?.toLong() ?: file?.length() ?: Long.MAX_VALUE
    val localFile: File? get() = file
    val hash = remoteHash ?: memory?.let(NotebookArchive::digest) ?: NotebookArchive.digest(file!!)
    fun copyTo(target: File) {
        if (file != null) {
            if (file.canonicalFile != target.canonicalFile) file.copyTo(target, overwrite = true)
        } else if (memory != null) target.writeBytes(memory)
        else { checkNotNull(fetch)(target); check(NotebookArchive.digest(target) == hash) { "Invalid external notebook copy" } }
    }
    fun writeTo(directory: ArchiveDirectory, name: String) {
        if (memory != null) directory.write(name, memory) else materialize { directory.writeFile(name, it) }
    }
}

/** A private disk copy stays immutable while editing continues; the owner removes it after sync. */
internal class ArchiveSnapshot(private val root: File, private val blobs: Map<String, ArchiveBlob>,
    val sources: Map<String, Pair<File, NoteFileStamp>>) :
    AbstractMap<String, ArchiveBlob>(), Closeable {
    override val entries get() = blobs.entries
    override fun close() { root.deleteRecursively() }
    companion object {
        fun capture(files: Map<String, File>, references: Map<String, ArchiveBlob> = emptyMap()): ArchiveSnapshot {
            val root = File(files.values.first().parentFile, ".notes-snapshot-${UUID.randomUUID()}")
            check(root.mkdirs())
            try {
                val sources = files.mapValues { (_, source) -> source to source.noteStamp() }
                return ArchiveSnapshot(root, references + files.mapValues { (path, source) ->
                    val changes = if (path.endsWith(".misq")) BlockChanges.capture(source) else null
                    val copy = File(root, path).also { it.parentFile!!.mkdirs() }
                    val digest = MessageDigest.getInstance("SHA-256")
                    java.security.DigestInputStream(source.inputStream(), digest).use { input ->
                        copy.outputStream().use { input.copyTo(it, 65536) }
                    }
                    ArchiveBlob(copy, NotebookArchive.hex(digest.digest()), changes)
                }, sources)
            } catch (error: Throwable) { root.deleteRecursively(); throw error }
        }
    }
}

internal class NotebookArchive(private val directory: ArchiveDirectory) {
    var beforeFirstCommit: (() -> Unit)? = null
    data class Revision(val name: String, val files: Map<String, String>)
    private val verified = mutableSetOf<String>()
    private var complete: Revision? = null
    private var observed = false
    private var observedHead: String? = null
    private var attemptedHead: String? = null
    var recovered = false
        private set
    private val readCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {}
    private var readCacheBytes = 0
    var localMatches = false
        private set
    private fun remember(hash: String, bytes: ByteArray): ByteArray {
        readCache.remove(hash)?.let { readCacheBytes -= it.size }
        if (bytes.size <= 8 * 1024 * 1024) {
            readCache[hash] = bytes; readCacheBytes += bytes.size
            while (readCacheBytes > 8 * 1024 * 1024) {
                val oldest = readCache.entries.first(); readCacheBytes -= oldest.value.size; readCache.remove(oldest.key)
            }
        }
        return bytes
    }
    private fun readBlob(hash: String): ByteArray = readCache[hash] ?: directory.read("blob-$hash").also {
        check(digest(it) == hash) { AppMessages.text(R.string.storage_corrupt) }
        verified += hash; remember(hash, it)
    }
    private fun revisions(names: Set<String>) = names.filter { it.matches(REVISION) }.sortedDescending()
    fun latest(localFiles: Map<String, File> = emptyMap()): Revision? {
        readCache.clear(); readCacheBytes = 0
        val candidates = revisions(directory.names())
        observed = true; observedHead = candidates.firstOrNull()
        return latest(candidates, localFiles)
    }
    fun latestCaptured(local: Map<String, ArchiveBlob>): Revision? {
        readCache.clear(); readCacheBytes = 0
        val candidates = revisions(directory.names())
        observed = true; observedHead = candidates.firstOrNull()
        return latest(candidates, captured = local)
    }
    private fun latest(candidates: List<String>, localFiles: Map<String, File> = emptyMap(), captured: Map<String, ArchiveBlob>? = null): Revision? {
        localMatches = false
        for (name in candidates) {
            val revision = runCatching { Revision(name, decode(directory.read(name))) }.getOrNull() ?: continue
            var allLocal = revision.files.keys == (captured?.keys ?: localFiles.keys)
            val valid = runCatching {
                val matched = mutableSetOf<String>()
                for ((path, hash) in revision.files) {
                    val file = localFiles[path]
                    val matches = captured?.get(path)?.hash == hash ||
                        (file != null && runCatching { digest(file) == hash }.getOrDefault(false))
                    if (!matches) allLocal = false
                    else {
                        matched += hash
                        if (!path.endsWith(".misq")) {
                            captured?.get(path)?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { remember(hash, it.bytes) }
                            if (file != null && file.length() <= 8 * 1024 * 1024) remember(hash, file.readBytes())
                        }
                    }
                }
                val missing = revision.files.values.distinct().filter { it !in matched }
                // Verify notebook bodies without allocating a byte array for the entire file.
                missing.forEach { hash ->
                        if (revision.files.any { it.value == hash && it.key.endsWith(".misq") }) {
                            check(directory.matches("blob-$hash", hash)) { AppMessages.text(R.string.storage_corrupt) }
                        } else readBlob(hash)
                        verified += hash
                }
                true
            }.getOrDefault(false)
            if (valid) {
                complete = revision; localMatches = allLocal
                recovered = name != candidates.first() || directory.recoveredIndex; return revision
            }
        }
        check(candidates.isEmpty()) { AppMessages.text(R.string.storage_read_retained) }
        return null
    }
    fun restore(revision: Revision, target: File) {
        val byHash = revision.files.entries.groupBy { it.value }
        fun save(hash: String, bytes: ByteArray) {
            byHash.getValue(hash).forEach { (path, _) -> File(target, path).also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) } }
        }
        val missing = mutableListOf<String>()
        byHash.keys.forEach { hash -> readCache[hash]?.let { save(hash, it) } ?: missing.add("blob-$hash") }
        missing.forEach { name ->
            val hash = name.removePrefix("blob-")
            val paths = byHash.getValue(hash)
            val first = File(target, paths.first().key).also { it.parentFile!!.mkdirs() }
            directory.copyTo(name, first)
            check(digest(first) == hash) { AppMessages.text(R.string.storage_corrupt) }
            paths.drop(1).forEach { (path, _) -> first.copyTo(File(target, path).also { it.parentFile!!.mkdirs() }, overwrite = true) }
            verified += hash
        }
    }
    fun commit(files: Map<String, File>) {
        capture(files).use { commitSnapshot(it) }
    }
    fun capture(files: Map<String, File>): ArchiveSnapshot {
        require("folders.hwp" in files && files.keys.all(::validPath))
        return ArchiveSnapshot.capture(files)
    }
    fun commitSnapshot(files: Map<String, ArchiveBlob>) {
        require("folders.hwp" in files && files.keys.all(::validPath))
        beforeFirstCommit?.invoke()
        val names = directory.names()
        val actualHead = revisions(names).firstOrNull()
        val ownIncomplete = actualHead != null && actualHead == attemptedHead && runCatching { decode(directory.read(actualHead)) }.isFailure
        val ownComplete = actualHead != null && actualHead == attemptedHead &&
            runCatching { decode(directory.read(actualHead)) == files.mapValues { it.value.hash } }.getOrDefault(false)
        if (observed && actualHead != observedHead && !ownIncomplete && !ownComplete) throw ArchiveConflictException()
        if (ownComplete) { observedHead = actualHead; attemptedHead = null; complete = Revision(checkNotNull(actualHead), files.mapValues { it.value.hash }) }
        val refs = files.toSortedMap().mapValues { (_, blob) ->
            val hash = blob.hash
            val name = "blob-$hash"
            if (name !in names || (hash !in verified && !runCatching { directory.matches(name, hash) }.getOrDefault(false))) {
                blob.writeTo(directory, name)
                check(directory.matches(name, hash)) { AppMessages.text(R.string.storage_write_unverified) }
            }
            verified += hash
            hash
        }
        val previous = revisions(names)
        val previousComplete = complete?.name?.takeIf { it in previous } ?: runCatching { latest(previous)?.name }.getOrNull()
        val current = previous.firstOrNull()?.let { runCatching { decode(directory.read(it)) }.getOrNull() }
        if (current == refs) { directory.organize(); return }
        val sequence = (previous.firstOrNull()?.substring(9, 29)?.toLongOrNull() ?: 0L) + 1
        val name = "revision-${sequence.toString().padStart(20, '0')}-${UUID.randomUUID()}"
        val bytes = encode(refs)
        attemptedHead = name
        directory.write(name, bytes)
        check(directory.read(name).contentEquals(bytes)) { AppMessages.text(R.string.storage_commit_failed) }
        directory.commit()
        complete = Revision(name, refs)
        beforeFirstCommit = null
        observed = true; observedHead = name
        attemptedHead = null
        // Keep the preceding complete revision for recovery; unrelated files are never touched.
        runCatching {
            val keep = listOfNotNull(name, previousComplete)
            val blobs = keep.flatMap { decode(directory.read(it)).values }.map { "blob-$it" }.toSet()
            previous.filter { it !in keep }.forEach(directory::delete)
            // The initial inventory includes every pre-existing orphan. No second provider scan
            // is needed: new blobs belong to the revision we just verified.
            names.filter { it.matches(BLOB) && it !in blobs }.forEach(directory::delete)
        }
        directory.organize()
    }
    companion object {
        private val REVISION = Regex("revision-[0-9]{20}-[a-f0-9-]{36}")
        private val BLOB = Regex("blob-[a-f0-9]{64}")
        private const val HEX = "0123456789abcdef"
        fun digest(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            hex(digest.digest())
        }
        fun digest(bytes: ByteArray): String {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        }
        internal fun hex(hash: ByteArray): String {
            return CharArray(hash.size * 2).also { chars -> hash.forEachIndexed { i, byte ->
                val value = byte.toInt() and 255
                chars[i * 2] = HEX[value ushr 4]; chars[i * 2 + 1] = HEX[value and 15]
            } }.concatToString()
        }
        private fun validPath(path: String) = path in listOf("folders.hwp", "presets.hwp") ||
            path.matches(Regex("notes/[a-f0-9-]{36}\\.misq")) ||
            path.matches(Regex("notes/[a-f0-9-]{36}/(manifest\\.hwn(\\.bak)?|[a-f0-9-]{36}\\.ink(\\.bak)?)"))
        internal fun encode(files: Map<String, String>): ByteArray {
            val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
                out.writeInt(0x484E4131); out.writeInt(files.size)
                files.forEach { (path, hash) -> out.writeUTF(path); out.writeUTF(hash) }
            } }.toByteArray()
            return body + MessageDigest.getInstance("SHA-256").digest(body)
        }
        internal fun decode(bytes: ByteArray): Map<String, String> {
            require(bytes.size >= 40 && bytes.size <= 128 * 1024 * 1024)
            val body = bytes.copyOf(bytes.size - 32)
            require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(bytes.takeLast(32).toByteArray()))
            return DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == 0x484E4131)
                val count = input.readInt(); require(count in 1..1_000_000)
                val pairs = List(count) {
                    val path = input.readUTF()
                    val hash = input.readUTF()
                    require(validPath(path) && hash.matches(Regex("[a-f0-9]{64}")))
                    path to hash
                }
                require(input.available() == 0 && pairs.map { it.first }.distinct().size == count && pairs.any { it.first == "folders.hwp" })
                pairs.toMap()
            }
        }
    }
}
