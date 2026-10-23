package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors

internal class DocumentArchiveDirectory(private val resolver: ContentResolver, tree: Uri, legacy: Boolean = false) : ArchiveDirectory {
    companion object {
        private val readers = Executors.newFixedThreadPool(4) { task -> Thread(task, "note-archive-read").apply { isDaemon = true } }
    }
    private val selected = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val inventories = mutableMapOf<Uri, MutableMap<String, Uri>>()
    private val sizes = mutableMapOf<Uri, Long>()
    private val root = if (legacy) checkNotNull(children(selected, foldersOnly = true)["Handwriting Notes"]) else selected
    private val directories = mutableMapOf("" to root)
    // One child inventory per directory per commit, including writes and legacy migration.
    private var documents = mutableMapOf<String, List<Uri>>()
    fun legacyArchive(): NotebookArchive? = if ("Handwriting Notes" in children(selected, foldersOnly = true))
        runCatching { NotebookArchive(DocumentArchiveDirectory(resolver, selected, legacy = true)) }.getOrNull() else null
    private fun children(parent: Uri, foldersOnly: Boolean = false): Map<String, Uri> {
        if (!foldersOnly) inventories[parent]?.let { return it }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return checkNotNull(resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)).use { cursor ->
            buildMap { while (cursor.moveToNext()) {
                if (!foldersOnly || cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val child = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                    put(cursor.getString(1), child)
                    sizes[child] = if (cursor.isNull(3)) Long.MAX_VALUE else cursor.getLong(3)
                }
            } }.also { if (!foldersOnly) inventories[parent] = it.toMutableMap() }
        }
    }
    private fun directory(path: String, create: Boolean): Uri? {
        directories[path]?.let { return it }
        val parent = directory(path.substringBeforeLast('/', ""), create) ?: return null
        val name = path.substringAfterLast('/')
        val uri = children(parent, foldersOnly = true)[name] ?: if (create) {
            check(name !in children(parent)) { AppMessages.text(R.string.storage_name_conflict, name) }
            checkNotNull(DocumentsContract.createDocument(resolver, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name)) { AppMessages.text(R.string.storage_create_failed) }
        } else return null
        directories[path] = uri
        return uri
    }
    private fun scan(): MutableMap<String, List<Uri>> {
        val found = mutableMapOf<String, List<Uri>>()
        for (path in ArchiveLayout.folders) directory(path, false)?.let { parent ->
            children(parent).filterKeys(ArchiveLayout::isRecord).forEach { (name, uri) -> found[name] = found[name].orEmpty() + uri }
        }
        // Until a structured copy is verified and the original removed, prefer the old location.
        children(root).filterKeys(ArchiveLayout::isRecord).forEach { (name, uri) -> found[name] = listOf(uri) + found[name].orEmpty() }
        return found
    }
    override fun names(): Set<String> { inventories.clear(); documents = scan(); return documents.keys.toSet() }
    private fun readUri(uri: Uri): ByteArray = checkNotNull(resolver.openInputStream(uri)).use { stream ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer); if (count == -1) break
            check(output.size().toLong() + count <= 128L * 1024 * 1024 + 16) { AppMessages.text(R.string.storage_file_too_large) }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    override fun read(name: String): ByteArray {
        val candidates = checkNotNull(documents[name])
        var first: ByteArray? = null
        var failure: Exception? = null
        for (uri in candidates) try {
            val bytes = readUri(uri)
            if (candidates.size == 1 || ArchiveLayout.complete(name, bytes)) return bytes
            if (first == null) first = bytes
        } catch (e: Exception) { failure = e }
        return first ?: throw IOException(AppMessages.text(R.string.storage_file_read_failed), failure)
    }
    override fun readBatch(names: List<String>): List<ByteArray> {
        // Large/unknown records remain sequential so parallelism cannot multiply their memory use.
        val tasks = names.filter { name -> documents[name].orEmpty().all { (sizes[it] ?: Long.MAX_VALUE) in 0..1_048_576 } }
            .associateWith { name -> readers.submit<ByteArray> { read(name) } }
        try { return names.map { name -> tasks[name]?.get() ?: read(name) } }
        finally { tasks.values.forEach { if (!it.isDone) it.cancel(true) } }
    }
    override fun write(name: String, bytes: ByteArray) {
        check(ArchiveLayout.complete(name, bytes)) { AppMessages.text(R.string.storage_data_invalid) }
        val path = ArchiveLayout.path(name, bytes)
        val parent = checkNotNull(directory(path.substringBeforeLast('/'), true))
        val uri = children(parent)[name] ?: checkNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name))
        checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
        check(readUri(uri).contentEquals(bytes)) { AppMessages.text(R.string.storage_write_unverified) }
        inventories[parent]?.set(name, uri)
        documents[name] = listOf(uri) + documents[name].orEmpty().filter { it != uri }
    }
    override fun delete(name: String) {
        documents[name].orEmpty().forEach { check(DocumentsContract.deleteDocument(resolver, it)) }
        inventories.values.forEach { it.remove(name) }
        documents.remove(name)
    }
    override fun organize() {
        val flat = children(root).filterKeys(ArchiveLayout::isRecord)
        for ((name, original) in flat) {
            val bytes = read(name)
            if (!ArchiveLayout.complete(name, bytes)) continue
            write(name, bytes)
            check(DocumentsContract.deleteDocument(resolver, original)) { AppMessages.text(R.string.storage_organize_failed) }
            documents[name] = documents[name].orEmpty().filter { it != original }
            inventories[root]?.remove(name)
        }
    }
}
