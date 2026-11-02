package jp.handwriting.note.storage

/** Fast-forward only: one ordered document is an unchanged prefix of the other. */
internal object AppendOnlyMerge {
    fun canMerge(a: MisqFile.Index, b: MisqFile.Index): Boolean {
        if (a.manifest.info.id != b.manifest.info.id || a.manifest.info.title != b.manifest.info.title ||
            a.manifest.info.paper != b.manifest.info.paper) return false
        fun values(index: MisqFile.Index) = index.manifest.strokes.map {
            index.entities.getValue(it.id) to index.records.getValue(it.storageId).hash
        }
        val left = values(a); val right = values(b)
        val short = if (left.size <= right.size) a else b
        val prefix = if (left.size <= right.size) left else right
        val full = if (left.size <= right.size) right else left
        if (full.take(prefix.size) != prefix) return false
        // An absent stroke with known history may have been deleted: never resurrect it.
        val known = short.events.map { it.entity }.toSet()
        if (full.drop(prefix.size).any { it.first in known }) return false
        if ((a.events + b.events).groupBy { it.version }.values.any { it.distinct().size > 1 }) return false
        return true
    }
}
