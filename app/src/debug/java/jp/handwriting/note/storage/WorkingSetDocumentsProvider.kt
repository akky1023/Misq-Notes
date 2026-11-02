package jp.handwriting.note.storage

import android.database.Cursor
import android.content.Intent
import android.provider.DocumentsContract
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Disposable debug-only provider. No access to the user's selected folder or production files. */
class WorkingSetDocumentsProvider : DocumentsProvider() {
    private val root get() = File(context!!.cacheDir, "working-set-provider").canonicalFile.also { it.mkdirs() }
    private var failWrites = false
    override fun onCreate() = true
    private fun file(id: String): File {
        val file = File(root, id).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()))
        return file
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).also { cursor ->
        cursor.addRow(cursor.columnNames.map<String, Any?> { when (it) {
            Root.COLUMN_ROOT_ID -> "test"; Root.COLUMN_DOCUMENT_ID -> "."; Root.COLUMN_TITLE -> "Working set test"
            Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE; else -> null
        } }.toTypedArray())
    }
    private fun row(cursor: MatrixCursor, file: File) {
        cursor.addRow(cursor.columnNames.map<String, Any?> { when (it) {
            Document.COLUMN_DOCUMENT_ID -> file.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
            Document.COLUMN_DISPLAY_NAME -> file.name
            Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_SIZE -> file.length()
            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            Document.COLUMN_FLAGS -> if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
            else -> null
        } }.toTypedArray())
    }
    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection).also { row(it, file(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        cursor(projection).also { result -> file(parentDocumentId).listFiles().orEmpty().forEach { row(result, it) } }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).toPath().startsWith(file(parentDocumentId).toPath())
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (failWrites) throw FileNotFoundException("Simulated unavailable external folder")
        require(displayName.isNotEmpty() && displayName !in listOf(".", "..") && !displayName.contains('/') && !displayName.contains('\\'))
        val target = File(file(parentDocumentId), displayName)
        check(!target.exists())
        check(if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile())
        return target.relativeTo(root).invariantSeparatorsPath
    }
    override fun deleteDocument(documentId: String) {
        if (failWrites) throw FileNotFoundException("Simulated unavailable external folder")
        val target = file(documentId); check(target != root.canonicalFile); check(target.deleteRecursively())
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (failWrites && mode != "r") throw FileNotFoundException("Simulated unavailable external folder")
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "test-fail-writes") { failWrites = extras!!.getBoolean("fail"); return Bundle() }
        if (method == "test-new-tree") {
            val id = java.util.UUID.randomUUID().toString(); check(File(root, id).mkdir())
            val tree = DocumentsContract.buildTreeDocumentUri("net.neomenia.misqnotes.probe.tests.working-set", id)
            context!!.grantUriPermission("net.neomenia.misqnotes.probe", tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            return Bundle().apply { putParcelable("tree", tree) }
        }
        return super.call(method, arg, extras)
    }
}
