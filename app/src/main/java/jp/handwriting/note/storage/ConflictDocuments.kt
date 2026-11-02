package jp.handwriting.note.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Inspects headers through seekable SAF descriptors; copies only aliases, using bounded streams. */
internal class ConflictDocuments(private val resolver: ContentResolver, private val cache: File) {
    private data class Document(val name: String, val uri: Uri, val directory: Boolean)
    private fun children(parent: Uri): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return checkNotNull(resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Document(cursor.getString(1), DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)),
                cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)) }
        }
    }
    private data class Identity(val id: String, val revision: String)
    private fun identity(input: FileInputStream): Identity {
        fun read(offset: Long, size: Int): ByteArray {
            require(offset >= 0 && size in 0..16 * 1024 * 1024)
            input.channel.position(offset)
            val buffer = ByteBuffer.allocate(size)
            while (buffer.hasRemaining()) check(input.channel.read(buffer) > 0)
            return buffer.array()
        }
        val magic = String(read(0, 8), Charsets.US_ASCII)
        require(magic == "MISQ0002" || magic == "MISQ0001")
        val size = if (magic == "MISQ0002") 192 else 64
        return (0..1).mapNotNull { slot -> runCatching {
            val bytes = read(8L + slot * size, size)
            if (size == 192) require(MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(size - 32)).contentEquals(bytes.takeLast(32).toByteArray()))
            val data = ByteBuffer.wrap(bytes)
            val generation = data.long; require(generation > 0)
            val indexHash = bytes.copyOfRange(20, 52).joinToString("") { "%02x".format(it) }
            // Current summaries are small; old formats fall back to the committed index body.
            val start = if (size == 192 && ByteBuffer.wrap(bytes).getLong(96) != 0L) 96 else 8
            data.position(start)
            val offset = data.long; val length = data.int; val hash = ByteArray(32).also(data::get)
            val body = read(offset, length)
            require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(hash))
            val id = JSONObject(String(body, Charsets.UTF_8)).getString("id")
            require(UUID.fromString(id).toString() == id)
            generation to Identity(id, indexHash)
        }.getOrNull() }.maxByOrNull { it.first }?.second ?: error("No valid note commit")
    }

    fun scan(tree: Uri): List<ConflictSource> {
        val root = runCatching { DocumentsContract.getDocumentId(tree); tree }.getOrElse {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }
        val documents = children(root)
        val folder = documents.firstOrNull { it.directory && it.name == "inkdata" }?.uri ?: root
        check(cache.isDirectory || cache.mkdirs())
        val entries = children(folder).filter { !it.directory && it.name.endsWith(".misq", true) }
        val retained = entries.map { NotebookArchive.digest(it.uri.toString().toByteArray()) + ".misq" }.toSet()
        cache.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.misq")) && it.name !in retained }.forEach { it.delete() }
        return entries.mapNotNull { document ->
            runCatching {
                val header = runCatching { resolver.openFileDescriptor(document.uri, "r")!!.use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(::identity)
                } }.getOrNull()
                if (header != null && document.name == "${header.id}.misq") return@runCatching null
                val key = NotebookArchive.digest(document.uri.toString().toByteArray())
                val file = File(cache, "$key.misq")
                val cached = if (file.exists()) runCatching { FileInputStream(file).use(::identity) }.getOrNull() else null
                if (header == null || cached != header) {
                    val temporary = File(cache, "$key.tmp")
                    try {
                        resolver.openInputStream(document.uri)!!.use { input -> temporary.outputStream().use { input.copyTo(it, 65536) } }
                        val actual = FileInputStream(temporary).use(::identity)
                        check(header == null || header == actual)
                        if (document.name == "${actual.id}.misq") return@runCatching null
                        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
                }
                ConflictSource(file, document.name)
            }.getOrNull()
        }
    }
}
