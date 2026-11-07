package jp.handwriting.note.storage

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

/** Daily note IO uses Format 3 exclusively; .misq files here are disposable review/export copies. */
class NoteFiles internal constructor(directory: File, internal val format3: Format3Library) {
    private val root = directory.absoluteFile.normalize().also { check(it.isDirectory || it.mkdirs()) }
    internal val directory get() = root
    internal fun spatial(id: String) = format3.source(id)
    private fun folder(id: String): File { require(UUID.fromString(id).toString() == id); return File(root, id) }
    internal fun document(id: String): MisqFile {
        folder(id)
        val file = File(root, "$id.misq")
        format3.export(id, file)
        return MisqFile(file)
    }
    fun thumbnail(id: String) = File(folder(id), "thumbnail-v2.png")
    fun thumbnailIsCurrent(id: String) = format3.thumbnailCurrent(id) && thumbnail(id).exists()
    fun markThumbnailCurrent(id: String, expectedRevision: String? = null) =
        format3.saveThumbnail(id, thumbnail(id).readBytes(), expectedRevision)
    fun invalidateThumbnail(id: String) { thumbnail(id).takeIf { it.exists() }?.setLastModified(0) }
    internal fun storedThumbnail(id: String): File? {
        val bytes = format3.thumbnail(id) ?: return null
        val target = thumbnail(id); target.parentFile!!.mkdirs()
        if (!target.exists() || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        return target
    }
    fun create(title: String, now: Long = System.currentTimeMillis(), paper: NotePaper = NotePaper()) = format3.create(title, paper, now)
    fun list() = format3.summaries()
    fun read(id: String) = ReadManifest(format3.manifest(id), format3.store.recovered(id))
    fun save(note: NoteManifest, placements: Map<String, StrokePlacement> = emptyMap(), encode: (String) -> ByteArray) =
        format3.save(note, placements, encode = encode)
    fun stroke(note: String, storageId: String) = format3.stroke(note, storageId)
    internal fun <T> readStrokes(id: String, refs: List<StrokeRef>, decode: (StrokeRef, ByteArray) -> T) = format3.read(id, refs, decode)
    fun rename(id: String, title: String) = format3.rename(id, title)
    internal fun deleteNotebook(id: String) {
        delete(id)
        root.listFiles().orEmpty().filter { it.isFile && it.extension == "misq" }.forEach { file ->
            if (runCatching { MisqFile(file, readOnlyAlias = true).summary().id == id }.getOrDefault(false)) check(file.delete())
        }
    }
    fun delete(id: String) = format3.delete(id)
    companion object {
        /** Opens isolated libraries used by host workflows and editor fixtures; never imports old files. */
        internal fun open(directory: File): NoteFiles {
            check(directory.isDirectory || directory.mkdirs())
            val storage = Format3Storage(directory)
            val store = if (storage.active) storage.open() else {
                check(directory.listFiles().orEmpty().none { it.extension == "misq" ||
                    (it.isDirectory && (File(it, "manifest.hwn").exists() || it.name.startsWith("format3-"))) }) {
                    "Legacy or incomplete notebook storage is unsupported"
                }
                storage.create()
            }
            return NoteFiles(directory, Format3Library(store, directory))
        }
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

    }
}
