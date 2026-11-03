package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import jp.handwriting.note.editor.ViewPosition
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.UUID
import java.util.zip.CRC32

data class NoteInfo(
    val id: String, val title: String, val createdAt: Long, val updatedAt: Long,
    val position: ViewPosition = ViewPosition(), val color: Int = 0xFF202B33.toInt(), val size: Float = 3f,
    val paper: NotePaper = NotePaper(),
)
data class StrokeRef(val id: Long, val storageId: String)
data class NoteManifest(val info: NoteInfo, val strokes: List<StrokeRef>, val nextId: Long = 1)
data class ReadManifest(val manifest: NoteManifest, val recovered: Boolean)
data class NoteSummary(val id: String, val title: String, val updatedAt: Long, val strokes: Int, val problem: String? = null,
    val createdAt: Long = updatedAt, val thumbnail: String? = null, val paper: NotePaper = NotePaper(), val provisional: Boolean = false)

internal interface NoteBacking {
    fun contains(id: String): Boolean
    fun ensure(id: String)
    fun summaries(): List<NoteSummary>
    fun deleted(id: String)
    fun preview(id: String): File
    fun spatial(id: String): SpatialNoteCache.Source? = null
}

/** IO-thread confined. Each manifest is committed only after all referenced stroke blobs exist. */
class NoteFiles(directory: File, private val singleFile: Boolean = true) {
    internal fun spatial(id: String): SpatialNoteCache.Source? =
        if (File(root, "${uuid(id)}.misq").exists()) null else backing?.spatial(id)
    internal fun needsPlacements(id: String, values: Map<String, StrokePlacement>): Boolean = values.isNotEmpty() &&
        (!packed(id) || values.any { document(id).index().placements[it.key] != it.value })
    internal fun hasLocalBody(id: String) = File(root, "${uuid(id)}.misq").exists() || manifestFile(id).exists()
    internal fun hasExternal(id: String) = backing?.contains(id) == true
    internal var backing: NoteBacking? = null
    private val root = directory.absoluteFile.normalize().also { check(it.isDirectory || it.mkdirs()) }
    internal val directory get() = root
    private val known = mutableMapOf<String, MutableSet<String>>()
    private val documents = mutableMapOf<String, MisqFile>()
    private data class ContentKey(val stamp: NoteFileStamp, val key: String)
    private val contentKeys = mutableMapOf<File, ContentKey>()
    private fun contentKey(file: File): String {
        val stamp = file.noteStamp()
        return contentKeys[file]?.takeIf { stamp.identity != null && it.stamp == stamp }?.key
            ?: MisqFile(file, readOnlyAlias = true).contentKey().also { contentKeys[file] = ContentKey(stamp, it) }
    }
    private fun uuid(id: String): String { require(UUID.fromString(id).toString() == id); return id }
    private fun folder(id: String) = File(root, uuid(id))
    private fun manifestFile(id: String) = File(folder(id), "manifest.hwn")
    private fun strokeFile(note: String, stroke: String) = File(folder(note), "${uuid(stroke)}.ink")
    internal fun document(id: String): MisqFile {
        uuid(id)
        if (!File(root, "$id.misq").exists()) {
            documents.remove(id)
            backing?.ensure(id)
        }
        return documents.getOrPut(id) { MisqFile(File(root, "$id.misq")) }
    }
    private fun packed(id: String) = singleFile && document(id).file.exists()
    fun thumbnail(id: String) = File(folder(id), "thumbnail-v2.png")
    fun thumbnailIsCurrent(id: String): Boolean {
        if (packed(id)) {
            val note = document(id)
            if (!note.thumbnailCurrent()) return false
            val target = thumbnail(id)
            if (!target.exists() || target.lastModified() < note.file.lastModified()) {
                target.parentFile!!.mkdirs()
                target.writeBytes(note.thumbnail() ?: return false)
                target.setLastModified(note.file.lastModified())
            }
            return true
        }
        return thumbnail(id).let { it.exists() && it.lastModified() >= manifestFile(id).lastModified() }
    }
    fun markThumbnailCurrent(id: String) {
        if (packed(id) && !document(id).thumbnailCurrent()) document(id).saveThumbnail(thumbnail(id).readBytes())
        thumbnail(id).setLastModified(if (packed(id)) document(id).file.lastModified() else manifestFile(id).lastModified())
        if (packed(id)) runCatching { BlockChanges.update(document(id).file, document(id).index()) }
    }
    fun invalidateThumbnail(id: String) { thumbnail(id).takeIf { it.exists() }?.setLastModified(0) }
    internal fun storedThumbnail(id: String): File? {
        if (backing?.contains(id) == true && !File(root, "$id.misq").exists()) return backing!!.preview(id)
        if (!packed(id)) return thumbnail(id).takeIf { it.exists() }
        val bytes = document(id).thumbnail() ?: return null
        val target = thumbnail(id)
        if (!target.exists() || target.lastModified() < document(id).file.lastModified()) {
            target.parentFile!!.mkdirs(); target.writeBytes(bytes)
            target.setLastModified(document(id).file.lastModified())
        }
        return target
    }

    fun create(title: String, now: Long = System.currentTimeMillis(), paper: NotePaper = NotePaper()): NoteManifest {
        val info = NoteInfo(UUID.randomUUID().toString(), validTitle(title), now, now, paper = paper)
        val note = NoteManifest(info, emptyList())
        save(note) { error("Empty note cannot need a stroke") }
        return note
    }
    fun list(): List<NoteSummary> = (backing?.summaries().orEmpty() + root.listFiles().orEmpty().mapNotNull { file ->
        val id = if (file.isFile && file.extension == "misq") file.nameWithoutExtension else if (file.isDirectory && File(file, "manifest.hwn").exists()) file.name else return@mapNotNull null
        id.takeIf { runCatching { uuid(it) }.isSuccess }
    }.distinct().mapNotNull { id ->
        try {
            if (packed(id)) return@mapNotNull document(id).summary().copy(thumbnail = thumbnail(id).takeIf { it.exists() }?.path)
            val m = read(id).manifest
            NoteSummary(m.info.id, m.info.title, m.info.updatedAt, m.strokes.size, createdAt = m.info.createdAt,
                thumbnail = thumbnail(m.info.id).takeIf { it.exists() }?.path, paper = m.info.paper)
        } catch (_: IOException) {
            val alias = runCatching { MisqFile(document(id).file, readOnlyAlias = true).summary() }.getOrNull()
            if (alias != null && alias.id != id) return@mapNotNull null
            NoteSummary(id, AppMessages.text(R.string.unreadable_note), document(id).file.lastModified(), 0, AppMessages.text(R.string.note_data_read_failed))
        }
    }).associateBy { it.id }.values.sortedByDescending { it.updatedAt }

    /** Missing working files are represented by the newest remaining version, without publishing it. */
    internal fun hasWorkingCopy(id: String) = backing?.contains(id) == true || document(id).file.exists() || manifestFile(id).exists()
    internal fun provisionalVersions(): Map<String, Pair<File, NoteSummary>> {
        val candidates = root.listFiles().orEmpty().filter { it.isFile && it.extension.equals("misq", true) }
        contentKeys.keys.retainAll(candidates.toSet())
        return candidates.mapNotNull { file ->
            runCatching {
                val summary = MisqFile(file, readOnlyAlias = true).summary()
                if (hasWorkingCopy(summary.id)) null
                else Triple(file, summary.copy(provisional = true,
                    thumbnail = thumbnail(summary.id).takeIf { it.exists() }?.path), contentKey(file))
            }.getOrNull()
        }.sortedWith(compareByDescending<Triple<File, NoteSummary, String>> { it.second.updatedAt }.thenBy { it.third })
            .distinctBy { it.second.id }.associate { it.second.id to (it.first to it.second) }
    }

    fun read(id: String): ReadManifest {
        if (packed(id)) return document(id).readManifest()
        val file = manifestFile(id)
        val (bytes, recovered) = readWithBackup(file)
        try {
            return ReadManifest(decodeManifest(id, bytes), recovered)
        } catch (e: Exception) { throw IOException(AppMessages.text(R.string.note_format_invalid), e) }
    }
    fun previous(id: String): NoteManifest? = runCatching {
        if (packed(id)) return document(id).previous()
        val file = manifestFile(id)
        val backup = File(file.path + ".bak")
        if (!backup.exists()) return null
        // Decode through an isolated reader; never replace the live manifest during recovery.
        decodeManifest(id, readEnvelope(backup))
    }.getOrNull()

    internal fun decodeManifest(id: String, bytes: ByteArray): NoteManifest = NoteManifestCodec.decodeManifest(id, bytes)
    fun save(note: NoteManifest, placements: Map<String, StrokePlacement> = emptyMap(), encode: (String) -> ByteArray) {
        if (singleFile) {
            val target = document(note.info.id)
            if (!target.file.exists() && manifestFile(note.info.id).exists()) {
                val old = NoteFiles(root, false)
                val original = old.read(note.info.id).manifest
                target.save(original) { old.stroke(note.info.id, it) }
                thumbnail(note.info.id).takeIf { it.exists() }?.let { target.saveThumbnail(it.readBytes()) }
            }
            target.save(note, placements, encode)
            runCatching { BlockChanges.update(target.file, target.index()) }
            return
        }
        val dir = folder(note.info.id); check(dir.isDirectory || dir.mkdirs())
        require(note.nextId > (note.strokes.maxOfOrNull { it.id } ?: 0))
        val verified = known.getOrPut(note.info.id) { mutableSetOf() }
        for (ref in note.strokes) {
            if (ref.storageId !in verified) {
                val file = strokeFile(note.info.id, ref.storageId)
                if (!file.exists() || runCatching { readEnvelope(file) }.isFailure) atomicWrite(file, encode(ref.storageId))
                verified.add(ref.storageId)
            }
        }
        atomicWrite(manifestFile(note.info.id), encodeManifest(note))
    }
    internal fun encodeManifest(note: NoteManifest): ByteArray = NoteManifestCodec.encodeManifest(note)
    fun stroke(note: String, storageId: String): ByteArray {
        if (packed(note)) return document(note).stroke(uuid(storageId))
        val bytes = readWithBackup(strokeFile(note, storageId)).first
        known.getOrPut(note) { mutableSetOf() }.add(storageId)
        return bytes
    }
    internal fun <T> readStrokes(id: String, refs: List<StrokeRef>, decode: (StrokeRef, ByteArray) -> T): List<T> =
        if (packed(id)) document(id).readStrokes(refs, decode)
        else refs.map { decode(it, stroke(id, it.storageId)) }
    fun rename(id: String, title: String) {
        val note = read(id).manifest
        save(note.copy(info = note.info.copy(title = validTitle(title), updatedAt = System.currentTimeMillis()))) { error("Existing stroke missing") }
    }
    /** User deletion removes aliases too, otherwise a missing original reappears provisionally. */
    internal fun deleteNotebook(id: String) {
        uuid(id)
        val versions = root.listFiles().orEmpty().filter { file ->
            file.isFile && file.extension.equals("misq", true) &&
                runCatching {
                    val source = MisqFile(file, readOnlyAlias = true)
                    runCatching { source.summary().id }.getOrElse { source.readManifest().manifest.info.id } == id
                }.getOrDefault(false)
        }
        delete(id)
        versions.filter { it.exists() }.forEach { file ->
            check(file.delete()) { "Unable to delete notebook version: ${file.name}" }
            contentKeys.remove(file)
        }
    }
    fun delete(id: String) {
        backing?.deleted(uuid(id))
        BlockChanges.forget(File(root, "$id.misq"))
        if (PartialNoteJournal.exists(root, id)) PartialNoteJournal.clear(root, id)
        // A deletion does not need to download the external notebook first.
        documents.remove(id)
        val packedFile = File(root, "$id.misq")
        if (singleFile && packedFile.exists()) {
            val file = packedFile
            val trash = File(root, ".trash-${uuid(id)}-${UUID.randomUUID()}")
            Files.move(file.toPath(), trash.toPath(), ATOMIC_MOVE)
            trash.delete()
        }
        val dir = folder(id)
        if (!dir.exists()) return
        val trash = File(root, ".trash-${uuid(id)}-${UUID.randomUUID()}")
        Files.move(dir.toPath(), trash.toPath(), ATOMIC_MOVE)
        check(trash.parentFile == root && trash.name.startsWith(".trash-"))
        trash.deleteRecursively()
        known.remove(id)
    }
    fun collectOrphans(id: String) {
        if (packed(id)) { document(id).compactIfNeeded(); return }
        val current = read(id).manifest
        val previous = previous(id)
        val keep = (current.strokes + previous?.strokes.orEmpty()).map { it.storageId }.toSet()
        folder(id).listFiles().orEmpty().filter { it.name.endsWith(".ink") }.forEach { file ->
            val key = file.name.removeSuffix(".ink")
            if (runCatching { uuid(key) }.isSuccess && key !in keep) {
                if (file.delete()) { File(file.path + ".bak").delete(); known[id]?.remove(key) }
            }
        }
    }
    companion object {
        private const val MAX_BYTES = 128 * 1024 * 1024
        fun validTitle(title: String): String = title.trim().also { require(it.isNotEmpty() && it.length <= 120) }
        internal fun atomicWrite(file: File, bytes: ByteArray) {
            require(bytes.size <= MAX_BYTES)
            val temp = File(file.path + ".tmp")
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(stream)
                out.writeInt(0x484E4231); out.writeInt(bytes.size); out.write(bytes)
                out.writeLong(CRC32().also { it.update(bytes) }.value); out.flush(); stream.fd.sync()
            }
            // Keep a previous committed generation; interrupted .tmp files are never loaded.
            if (file.exists() && runCatching { readEnvelope(file) }.isSuccess) {
                Files.move(file.toPath(), File(file.path + ".bak").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        internal fun readEnvelope(file: File): ByteArray = DataInputStream(FileInputStream(file).buffered()).use { input ->
            if (input.readInt() != 0x484E4231) throw IOException("Invalid data header")
            val length = input.readInt()
            if (length !in 0..MAX_BYTES || file.length() != length.toLong() + 16) throw IOException("Invalid data length")
            val bytes = ByteArray(length); input.readFully(bytes)
            if (input.readLong() != CRC32().also { it.update(bytes) }.value) throw IOException("Checksum mismatch")
            bytes
        }
        private fun readWithBackup(file: File): Pair<ByteArray, Boolean> {
            try { return readEnvelope(file) to false } catch (e: IOException) {
                try { return readEnvelope(File(file.path + ".bak")) to true } catch (_: IOException) { throw e }
            }
        }
    }
}
