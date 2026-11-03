package jp.handwriting.note.storage

import android.content.ContentResolver
import android.net.Uri

/** Keep recovery generations separate from the user's current one-file-per-note library. */
internal class MisqDocumentFiles(resolver: ContentResolver, tree: Uri) : PackedFiles {
    private val current = PackedDocumentFiles(resolver, tree, "inkdata")
    private val recovery = PackedDocumentFiles(resolver, tree, "inkdata/.recovery")
    private val blocks = PackedDocumentFiles(resolver, tree, "inkdata/.blocks")
    private val blockName = Regex("block-[a-f0-9]{64}")
    private val recoveryName = Regex("blob-[a-f0-9]{64}|revision-[0-9]{20}-[a-f0-9-]{36}\\.json")
    private fun owner(name: String) = when {
        name.matches(blockName) -> blocks
        name.matches(recoveryName) -> recovery
        else -> current
    }
    private val transport = BlockPackedFiles(object : PackedFiles {
        override fun names() = current.names() + recovery.names() + blocks.names()
        override fun read(name: String) = owner(name).read(name)
        override fun openRead(name: String) = owner(name).openRead(name)
        override fun writeStream(name: String, input: java.io.InputStream) = owner(name).writeStream(name, input)
        override fun write(name: String, bytes: ByteArray) = owner(name).write(name, bytes)
        override fun delete(name: String) = owner(name).delete(name)
    })
    override fun names() = transport.names()
    override fun read(name: String) = transport.read(name)
    override fun openRead(name: String) = transport.openRead(name)
    override fun writeStream(name: String, input: java.io.InputStream) = transport.writeStream(name, input)
    override fun writeNote(name: String, file: java.io.File) = transport.writeNote(name, file)
    override fun matchesNote(name: String, hash: String) = transport.matchesNote(name, hash)
    override fun publishNote(source: String, target: String, hash: String) = transport.publishNote(source, target, hash)
    override fun write(name: String, bytes: ByteArray) = transport.write(name, bytes)
    override fun delete(name: String) = transport.delete(name)
}
