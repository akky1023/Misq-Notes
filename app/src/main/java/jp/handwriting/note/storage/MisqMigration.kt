package jp.handwriting.note.storage

import java.io.File

/** Format-only conversion may carry newer local viewport/tool preferences without a content fork. */
internal object MisqMigration {
    fun sameContent(local: Map<String, ArchiveBlob>, legacy: File, detached: File): Boolean {
        val folders = local["folders.hwp"] ?: return false
        if (!folders.bytes.contentEquals(File(legacy, "folders.hwp").readBytes())) return false
        local.filterKeys { it.endsWith(".misq") }.forEach { (path, blob) ->
            File(detached, path).also { it.parentFile!!.mkdirs(); it.writeBytes(blob.bytes) }
        }
        val left = NoteFiles(File(detached, "notes"))
        val right = NoteFiles(File(legacy, "notes"), false)
        val a = left.list(); val b = right.list()
        if (a.any { it.problem != null } || b.any { it.problem != null } || a.map { it.id }.toSet() != b.map { it.id }.toSet()) return false
        for (summary in a) {
            val current = left.read(summary.id).manifest
            val previous = right.read(summary.id).manifest
            // Keep this device's viewing position and tool choices. They do not change ink/media.
            if (current.info.copy(updatedAt = previous.info.updatedAt, position = previous.info.position,
                    color = previous.info.color, size = previous.info.size) != previous.info ||
                current.strokes != previous.strokes || current.nextId != previous.nextId) return false
            for (ref in current.strokes) if (!left.stroke(summary.id, ref.storageId).contentEquals(right.stroke(summary.id, ref.storageId))) return false
        }
        return true
    }
}
