package jp.handwriting.note.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** SAF object transport confined to the explicitly selected subdirectory. */
internal class PackedDocumentFiles(private val resolver: ContentResolver, tree: Uri, private val directoryName: String) : PackedFiles {
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private var folder: Uri? = null
    private data class Document(val name: String, val id: String)
    @Volatile private var known = java.util.concurrent.ConcurrentHashMap<String, Document>()
    private fun children(parent: Uri, previous: Map<String, Document> = emptyMap()): java.util.concurrent.ConcurrentHashMap<String, Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return checkNotNull(resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)).use { cursor ->
            // Keep only raw document IDs. Uri eagerly retains encoded paths and tree prefixes per
            // entry; building several complete maps during every sync can exhaust a 256 MiB heap.
            java.util.concurrent.ConcurrentHashMap<String, Document>().apply {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1); val id = cursor.getString(0)
                    val document = previous[name]?.takeIf { it.id == id } ?: Document(name, id)
                    put(document.name, document)
                }
            }
        }
    }
    override fun names(): Set<String> {
        if (folder == null) folder = locate(false)
        known = folder?.let { children(it, known) } ?: java.util.concurrent.ConcurrentHashMap()
        return known.keys.toSet()
    }
    private fun locate(create: Boolean): Uri? {
        var parent = root
        for (name in directoryName.split('/')) {
            require(name.isNotBlank() && name != "." && name != "..")
            parent = children(parent)[name]?.let { DocumentsContract.buildDocumentUriUsingTree(parent, it.id) } ?: if (create) checkNotNull(DocumentsContract.createDocument(
                resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)) else return null
        }
        return parent
    }
    private fun uri(name: String) = DocumentsContract.buildDocumentUriUsingTree(root, known.getValue(name).id)
    override fun openRead(name: String): InputStream = checkNotNull(resolver.openInputStream(uri(name)))
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
        if (known.containsKey(name)) { check(digest(name) == ContentHash.digest(bytes)); return }
        bytes.inputStream().use { writeStream(name, it) }
    }
    override fun writeStream(name: String, input: InputStream) {
        check(!known.containsKey(name))
        if (folder == null) folder = checkNotNull(locate(true))
        val uri = checkNotNull(DocumentsContract.createDocument(resolver, checkNotNull(folder), "application/octet-stream", name))
        checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> input.copyTo(output, 8192); output.flush() }
        known[name] = Document(name, DocumentsContract.getDocumentId(uri))
    }
    override fun delete(name: String) { known[name]?.let { check(DocumentsContract.deleteDocument(resolver,
        DocumentsContract.buildDocumentUriUsingTree(root, it.id))); known.remove(name) } }
}
