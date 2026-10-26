package jp.handwriting.note.storage

import android.content.ContentResolver
import android.net.Uri

/** Keep recovery generations separate from the user's current one-file-per-note library. */
internal class MisqDocumentFiles(resolver: ContentResolver, tree: Uri) : PackedFiles {
    private val current = PackedDocumentFiles(resolver, tree, "inkdata")
    private val recovery = PackedDocumentFiles(resolver, tree, "inkdata/.recovery")
    private fun owner(name: String) = if (name.startsWith("blob-") || name.startsWith("revision-")) recovery else current
    override fun names() = current.names() + recovery.names()
    override fun read(name: String) = owner(name).read(name)
    override fun write(name: String, bytes: ByteArray) = owner(name).write(name, bytes)
    override fun delete(name: String) = owner(name).delete(name)
}
