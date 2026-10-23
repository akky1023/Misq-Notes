package jp.handwriting.note.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream

/** Only enumerates packed-v1; the old per-stroke directories remain untouched for rollback. */
internal class PackedDocumentFiles(private val resolver: ContentResolver, tree: Uri) : PackedFiles {
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private var folder: Uri? = null
    private var known = mutableMapOf<String, Uri>()
    private fun children(parent: Uri): MutableMap<String, Uri> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return checkNotNull(resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(1), DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))) }.toMutableMap()
        }
    }
    override fun names(): Set<String> {
        if (folder == null) folder = children(root)["packed-v1"]
        known = folder?.let(::children) ?: mutableMapOf()
        return known.keys.toSet()
    }
    override fun read(name: String): ByteArray = checkNotNull(resolver.openInputStream(known.getValue(name))).use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            check(output.size().toLong() + count <= PackedArchiveDirectory.MAX_BYTES)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    override fun write(name: String, bytes: ByteArray) {
        if (name in known) { check(read(name).contentEquals(bytes)); return }
        if (folder == null) folder = checkNotNull(DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "packed-v1"))
        val uri = checkNotNull(DocumentsContract.createDocument(resolver, checkNotNull(folder), "application/octet-stream", name))
        checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes); it.flush() }
        known[name] = uri
    }
    override fun delete(name: String) { known[name]?.let { check(DocumentsContract.deleteDocument(resolver, it)); known.remove(name) } }
}
