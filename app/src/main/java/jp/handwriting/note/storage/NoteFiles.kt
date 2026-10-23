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
    val createdAt: Long = updatedAt, val thumbnail: String? = null, val paper: NotePaper = NotePaper())

/** IO-thread confined. Each manifest is committed only after all referenced stroke blobs exist. */
class NoteFiles(directory: File) {
    private val root = directory.absoluteFile.normalize().also { check(it.isDirectory || it.mkdirs()) }
    private val known = mutableMapOf<String, MutableSet<String>>()
    private fun uuid(id: String): String { require(UUID.fromString(id).toString() == id); return id }
    private fun folder(id: String) = File(root, uuid(id))
    private fun manifestFile(id: String) = File(folder(id), "manifest.hwn")
    private fun strokeFile(note: String, stroke: String) = File(folder(note), "${uuid(stroke)}.ink")
    fun thumbnail(id: String) = File(folder(id), "thumbnail-v2.png")
    fun thumbnailIsCurrent(id: String) = thumbnail(id).let { it.exists() && it.lastModified() >= manifestFile(id).lastModified() }
    fun markThumbnailCurrent(id: String) { thumbnail(id).setLastModified(manifestFile(id).lastModified()) }
    fun invalidateThumbnail(id: String) { thumbnail(id).takeIf { it.exists() }?.setLastModified(0) }

    fun create(title: String, now: Long = System.currentTimeMillis(), paper: NotePaper = NotePaper()): NoteManifest {
        val info = NoteInfo(UUID.randomUUID().toString(), validTitle(title), now, now, paper = paper)
        val note = NoteManifest(info, emptyList())
        save(note) { error("Empty note cannot need a stroke") }
        return note
    }
    fun list(): List<NoteSummary> = root.listFiles().orEmpty().filter { it.isDirectory && runCatching { uuid(it.name) }.isSuccess }.map { dir ->
        try {
            val m = read(dir.name).manifest
            NoteSummary(m.info.id, m.info.title, m.info.updatedAt, m.strokes.size, createdAt = m.info.createdAt,
                thumbnail = thumbnail(m.info.id).takeIf { it.exists() }?.path, paper = m.info.paper)
        } catch (_: IOException) {
            NoteSummary(dir.name, AppMessages.text(R.string.unreadable_note), dir.lastModified(), 0, AppMessages.text(R.string.note_data_read_failed))
        }
    }.sortedByDescending { it.updatedAt }

    fun read(id: String): ReadManifest {
        val file = manifestFile(id)
        val (bytes, recovered) = readWithBackup(file)
        try {
            return ReadManifest(decodeManifest(id, bytes), recovered)
        } catch (e: Exception) { throw IOException(AppMessages.text(R.string.note_format_invalid), e) }
    }
    fun previous(id: String): NoteManifest? = runCatching {
        val file = manifestFile(id)
        val backup = File(file.path + ".bak")
        if (!backup.exists()) return null
        // Decode through an isolated reader; never replace the live manifest during recovery.
        decodeManifest(id, readEnvelope(backup))
    }.getOrNull()

    private fun decodeManifest(id: String, bytes: ByteArray): NoteManifest {
        // Share the parser without altering either live generation.
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x484E4D31)
        val version = input.readInt(); require(version in 1..4)
        val storedId = uuid(input.readUTF()); require(storedId == id)
        val title = validTitle(input.readUTF()); val created = input.readLong(); val updated = input.readLong()
        val view = ViewPosition(input.readFloat(), input.readFloat(), input.readFloat())
        val color = input.readInt(); val size = input.readFloat(); require(size.isFinite() && size in 0.1f..100f)
        val paper = if (version >= 2) NotePaper(input.readInt(), PaperPattern.entries[input.readInt()], input.readInt(), input.readInt(),
            if (version >= 3) input.readFloat() else 1f).let { if (version >= 4) it.readMargin(input) else it } else NotePaper()
        val next = input.readLong(); val count = input.readInt(); require(next > 0 && count in 0..1_000_000)
        val refs = List(count) { StrokeRef(input.readLong().also { require(it > 0 && it < next) }, uuid(input.readUTF())) }
        require(refs.map { it.id }.distinct().size == count && refs.map { it.storageId }.distinct().size == count && input.available() == 0)
        return NoteManifest(NoteInfo(id, title, created, updated, view, color, size, paper), refs, next)
    }
    fun save(note: NoteManifest, encode: (String) -> ByteArray) {
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
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x484E4D31); out.writeInt(4)
            out.writeUTF(uuid(note.info.id)); out.writeUTF(validTitle(note.info.title))
            out.writeLong(note.info.createdAt); out.writeLong(note.info.updatedAt)
            out.writeFloat(note.info.position.zoom); out.writeFloat(note.info.position.left); out.writeFloat(note.info.position.top)
            out.writeInt(note.info.color); out.writeFloat(note.info.size)
            out.writeInt(note.info.paper.color); out.writeInt(note.info.paper.pattern.ordinal); out.writeInt(note.info.paper.lineColor); out.writeInt(note.info.paper.spacing)
            out.writeFloat(note.info.paper.lineWidth); note.info.paper.writeMargin(out)
            out.writeLong(note.nextId)
            out.writeInt(note.strokes.size)
            // New IDs were validated by strokeFile; loaded IDs by stroke(). Revalidating every
            // unchanged UUID and constructing its File on each pen lift adds no integrity check.
            note.strokes.forEach { out.writeLong(it.id); out.writeUTF(it.storageId) }
        } }.toByteArray()
        atomicWrite(manifestFile(note.info.id), bytes)
    }
    fun stroke(note: String, storageId: String): ByteArray {
        val bytes = readWithBackup(strokeFile(note, storageId)).first
        known.getOrPut(note) { mutableSetOf() }.add(storageId)
        return bytes
    }
    fun rename(id: String, title: String) {
        val note = read(id).manifest
        save(note.copy(info = note.info.copy(title = validTitle(title), updatedAt = System.currentTimeMillis()))) { error("Existing stroke missing") }
    }
    fun delete(id: String) {
        val dir = folder(id)
        if (!dir.exists()) return
        val trash = File(root, ".trash-${uuid(id)}-${UUID.randomUUID()}")
        Files.move(dir.toPath(), trash.toPath(), ATOMIC_MOVE)
        check(trash.parentFile == root && trash.name.startsWith(".trash-"))
        trash.deleteRecursively()
        known.remove(id)
    }
    fun collectOrphans(id: String) {
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
