package jp.handwriting.note.storage

import java.io.File

/** Three-way library merge; each stroke uses its causal event graph rather than file timestamps. */
internal object MisqMerge {
    fun merge(base: Map<String, ByteArray>, local: Map<String, ByteArray>, remote: Map<String, ByteArray>, work: File): Map<String, ArchiveBlob> {
        work.mkdirs()
        fun same(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
        val result = linkedMapOf<String, ArchiveBlob>()
        for (path in (base.keys + local.keys + remote.keys).sorted()) {
            val old = base[path]; val left = local[path]; val right = remote[path]
            val bytes = when {
                same(left, right) || same(right, old) -> left
                same(left, old) -> right
                left == null || right == null -> throw ArchiveConflictException()
                path.endsWith(".misq") -> {
                    val name = path.substringAfterLast('/')
                    val a = MisqFile(File(work, "local/$name").also { it.parentFile!!.mkdirs(); it.writeBytes(left) })
                    val b = MisqFile(File(work, "remote/$name").also { it.parentFile!!.mkdirs(); it.writeBytes(right) })
                    val original = old?.let { MisqFile(File(work, "base/$name").also { file -> file.parentFile!!.mkdirs(); file.writeBytes(it) }) }
                    val merged = MisqFile.merge(original, a, b)
                    a.rewrite(merged, b); a.file.readBytes()
                }
                path == "folders.hwp" -> {
                    val a = NotebookFolders.decode(LibraryJson.unwrap(left))
                    val b = NotebookFolders.decode(LibraryJson.unwrap(right))
                    val original = old?.let { NotebookFolders.decode(LibraryJson.unwrap(it)) } ?: LibraryPreferences()
                    fun <K, V> mergeMap(old: Map<K, V>, left: Map<K, V>, right: Map<K, V>): Map<K, V> =
                        (old.keys + left.keys + right.keys).mapNotNull { key ->
                            val value = when {
                                left[key] == right[key] || right[key] == old[key] -> left[key]
                                left[key] == old[key] -> right[key]
                                else -> throw ArchiveConflictException()
                            }
                            value?.let { key to it }
                        }.toMap()
                    val folders = mergeMap(original.folders.associateBy { it.id }, a.folders.associateBy { it.id }, b.folders.associateBy { it.id })
                    val merged = a.copy(folders = folders.values.sortedBy { it.id },
                        locations = mergeMap(original.locations, a.locations, b.locations),
                        sorts = mergeMap(original.sorts, a.sorts, b.sorts), orders = mergeMap(original.orders, a.orders, b.orders))
                    LibraryJson.envelope(NotebookFolders.encode(merged))
                }
                else -> throw ArchiveConflictException()
            }
            if (bytes != null) result[path] = ArchiveBlob(bytes)
        }
        return result
    }
}
