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
    fun readBatch(names: List<String>): List<ByteArray> = names.map(::read)
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
    fun commit() {}
    fun organize() {}
}

/** A detached snapshot owns immutable bytes, so later local edits cannot change an in-flight sync. */
internal class ArchiveBlob(val bytes: ByteArray) {
    val hash = NotebookArchive.digest(bytes)
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
    private data class LocalHash(val modified: Long, val size: Long, val blob: ArchiveBlob)
    private val localHashes = LinkedHashMap<File, LocalHash>(16, 0.75f, true)
    private var localCacheBytes = 0
    private fun rememberLocal(file: File, blob: ArchiveBlob) {
        localHashes.remove(file)?.let { localCacheBytes -= it.blob.bytes.size }
        if (blob.bytes.size > 8 * 1024 * 1024) return
        localHashes[file] = LocalHash(file.lastModified(), file.length(), blob)
        localCacheBytes += blob.bytes.size
        while (localCacheBytes > 8 * 1024 * 1024) {
            val first = localHashes.entries.first(); localCacheBytes -= first.value.blob.bytes.size; localHashes.remove(first.key)
        }
    }
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
                    val local = (captured?.get(path)?.bytes ?: file?.let { runCatching { it.readBytes() }.getOrNull() })?.takeIf { digest(it) == hash }
                    if (local == null) allLocal = false
                    else {
                        matched += hash
                        remember(hash, local)
                        if (file != null && file.name.contains(".ink")) rememberLocal(file, ArchiveBlob(local))
                    }
                }
                val missing = revision.files.values.distinct().filter { it !in matched }
                // A few small SAF streams in parallel amortize per-document provider round trips.
                for (batch in missing.chunked(4)) {
                    val contents = directory.readBatch(batch.map { "blob-$it" })
                    check(contents.size == batch.size)
                    batch.zip(contents).forEach { (hash, bytes) ->
                        check(digest(bytes) == hash) { AppMessages.text(R.string.storage_corrupt) }
                        verified += hash; remember(hash, bytes)
                    }
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
        revision.files.forEach { (path, hash) ->
            val bytes = readBlob(hash)
            File(target, path).also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }
        }
    }
    fun commit(files: Map<String, File>) {
        commitSnapshot(capture(files))
    }
    fun capture(files: Map<String, File>): Map<String, ArchiveBlob> {
        require("folders.hwp" in files && files.keys.all(::validPath))
        val result = files.mapValues { (_, file) ->
            val cached = localHashes[file]?.takeIf { file.name.contains(".ink") && it.modified == file.lastModified() && it.size == file.length() }
            cached?.blob ?: ArchiveBlob(file.readBytes()).also { rememberLocal(file, it) }
        }
        val current = files.values.toSet()
        val entries = localHashes.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key !in current) { localCacheBytes -= entry.value.blob.bytes.size; entries.remove() }
        }
        return result
    }
    fun commitSnapshot(files: Map<String, ArchiveBlob>) {
        require("folders.hwp" in files && files.keys.all(::validPath))
        beforeFirstCommit?.invoke()
        val names = directory.names()
        val actualHead = revisions(names).firstOrNull()
        val ownIncomplete = actualHead != null && actualHead == attemptedHead && runCatching { decode(directory.read(actualHead)) }.isFailure
        if (observed && actualHead != observedHead && !ownIncomplete) throw ArchiveConflictException()
        val refs = files.toSortedMap().mapValues { (_, blob) ->
            val hash = blob.hash
            val name = "blob-$hash"
            if (name !in names || (hash !in verified && runCatching { digest(directory.read(name)) != hash }.getOrDefault(true))) {
                val bytes = blob.bytes
                directory.write(name, bytes)
                check(digest(directory.read(name)) == hash) { AppMessages.text(R.string.storage_write_unverified) }
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
        fun digest(bytes: ByteArray): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            return CharArray(hash.size * 2).also { chars -> hash.forEachIndexed { i, byte ->
                val value = byte.toInt() and 255
                chars[i * 2] = HEX[value ushr 4]; chars[i * 2 + 1] = HEX[value and 15]
            } }.concatToString()
        }
        private fun validPath(path: String) = path in listOf("folders.hwp", "presets.hwp") || path.matches(Regex("notes/[a-f0-9-]{36}/(manifest\\.hwn(\\.bak)?|[a-f0-9-]{36}\\.ink(\\.bak)?)"))
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
                    val path = input.readUTF(); val hash = input.readUTF()
                    require(validPath(path) && hash.matches(Regex("[a-f0-9]{64}")))
                    path to hash
                }
                require(input.available() == 0 && pairs.map { it.first }.distinct().size == count && pairs.any { it.first == "folders.hwp" })
                pairs.toMap()
            }
        }
    }
}
