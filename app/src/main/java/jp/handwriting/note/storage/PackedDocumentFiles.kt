package jp.handwriting.note.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Only enumerates packed-v1; the old per-stroke directories remain untouched for rollback. */
internal class PackedDocumentFiles(private val resolver: ContentResolver, tree: Uri, private val directoryName: String = "packed-v1") : PackedFiles {
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
        if (folder == null) folder = locate(false)
        known = folder?.let(::children) ?: mutableMapOf()
        return known.keys.toSet()
    }
    private fun locate(create: Boolean): Uri? {
        var parent = root
        for (name in directoryName.split('/')) {
            require(name.isNotBlank() && name != "." && name != "..")
            parent = children(parent)[name] ?: if (create) checkNotNull(DocumentsContract.createDocument(
                resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)) else return null
        }
        return parent
    }
    override fun openRead(name: String): InputStream = checkNotNull(resolver.openInputStream(known.getValue(name)))
    override fun read(name: String): ByteArray = openRead(name).use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            check(output.size().toLong() + count <= 512L * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    override fun write(name: String, bytes: ByteArray) {
        if (name in known) { check(digest(name) == NotebookArchive.digest(bytes)); return }
        bytes.inputStream().use { writeStream(name, it) }
    }
    override fun writeStream(name: String, input: InputStream) {
        check(name !in known)
        if (folder == null) folder = checkNotNull(locate(true))
        val uri = checkNotNull(DocumentsContract.createDocument(resolver, checkNotNull(folder), "application/octet-stream", name))
        checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> input.copyTo(output, 8192); output.flush() }
        known[name] = uri
    }
    override fun delete(name: String) { known[name]?.let { check(DocumentsContract.deleteDocument(resolver, it)); known.remove(name) } }
}
