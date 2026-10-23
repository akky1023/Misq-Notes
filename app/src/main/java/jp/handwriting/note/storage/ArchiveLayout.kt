package jp.handwriting.note.storage

import java.nio.ByteBuffer

/** Physical organization leaves the immutable names in revision records unchanged. */
internal object ArchiveLayout {
    val folders = listOf("notes/manifests", "notes/strokes", "settings/folders", "settings/presets", "history", "data")
    private val blob = Regex("blob-[a-f0-9]{64}")
    private val revision = Regex("revision-[0-9]{20}-[a-f0-9-]{36}")
    fun isRecord(name: String) = name.matches(blob) || name.matches(revision)
    fun complete(name: String, bytes: ByteArray): Boolean = when {
        name.matches(blob) -> NotebookArchive.digest(bytes) == name.removePrefix("blob-")
        name.matches(revision) && bytes.size >= 40 -> ByteBuffer.wrap(bytes).int == 0x484E4131 &&
            NotebookArchive.digest(bytes.copyOf(bytes.size - 32)) == bytes.takeLast(32).joinToString("") { "%02x".format(it) }
        else -> false
    }
    fun path(name: String, bytes: ByteArray): String {
        require(isRecord(name))
        if (name.matches(revision)) return "history/$name"
        val data = ByteBuffer.wrap(bytes)
        val folder = if (bytes.size >= 16 && data.getInt(0) == 0x484E4231) when (data.getInt(8)) {
            0x484E4D31 -> "notes/manifests"
            0x484E5331, ImageRecord.MAGIC -> "notes/strokes"
            1 -> if (data.getInt(12) < 0) "settings/presets" else "settings/folders"
            2 -> "settings/presets"
            else -> "data"
        } else "data"
        return "$folder/$name"
    }
}
