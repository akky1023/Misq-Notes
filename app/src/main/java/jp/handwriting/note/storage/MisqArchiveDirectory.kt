package jp.handwriting.note.storage

import java.util.Base64
import org.json.JSONObject

/** One visible .misq per notebook; immutable recovery records make interrupted SAF writes safe. */
internal class MisqArchiveDirectory(private val files: PackedFiles) : ArchiveDirectory {
    private val staged = linkedMapOf<String, ByteArray>()
    private var inventory = emptySet<String>()
    private var revisions = emptyMap<String, JSONObject>()
    private var observedHead: String? = null
    override var recoveredIndex = false
        private set
    override fun names(): Set<String> {
        // Unpublished in-memory writes must not look like a committed generation on retry.
        staged.clear()
        inventory = files.names()
        val names = inventory.filter { it.matches(REVISION_FILE) }.sortedDescending()
        if (names.size > 1 && names[0].substring(9, 29) == names[1].substring(9, 29)) throw ArchiveConflictException()
        revisions = names.mapNotNull { name -> runCatching { name.removeSuffix(".json") to parse(files.read(name)) }.getOrNull() }.toMap()
        check(names.isEmpty() || revisions.isNotEmpty()) { "No readable Misq library generation" }
        recoveredIndex = names.firstOrNull()?.removeSuffix(".json") != revisions.keys.firstOrNull()
        observedHead = names.firstOrNull()
        val blobs = inventory.filter { it.matches(BLOB) }
        return names.map { it.removeSuffix(".json") }.toSet() + blobs + staged.keys
    }
    override fun read(name: String): ByteArray = staged[name] ?: if (name.startsWith("revision-")) {
        NotebookArchive.encode(refs(revisions.getValue(name)))
    } else files.read(name).also { require(NotebookArchive.digest(it) == name.removePrefix("blob-")) }
    override fun matches(name: String, hash: String): Boolean = staged[name]?.let { NotebookArchive.digest(it) == hash }
        ?: if (name.startsWith("blob-")) files.digest(name) == hash else super.matches(name, hash)
    override fun write(name: String, bytes: ByteArray) {
        require(ArchiveLayout.complete(name, bytes)); staged[name] = bytes
    }
    override fun delete(name: String) {
        // Cleanup follows verified publication. Existing revisions remain usable until then.
        staged.remove(name)
    }
    override fun commit() {
        val revision = staged.keys.filter { it.startsWith("revision-") }.maxOrNull() ?: return
        checkHead()
        val refs = NotebookArchive.decode(staged.getValue(revision))
        val folders = NotebookFolders.decode(LibraryJson.unwrap(read("blob-${refs.getValue("folders.hwp")}")))
        val json = LibraryJson.encode(folders).put("revision", revision).put("files", JSONObject(refs))
        refs["presets.hwp"]?.let { json.put("presets", Base64.getEncoder().encodeToString(read("blob-$it"))) }
        // Recovery blobs are immutable. They also preserve both sides of a simultaneous writer.
        staged.filterKeys { it.startsWith("blob-") }.forEach { (name, bytes) ->
            files.write(name, bytes); require(files.digest(name) == NotebookArchive.digest(bytes))
        }
        checkHead()
        val bytes = checked(json)
        files.write("$revision.json", bytes)
        require(files.read("$revision.json").contentEquals(bytes))
        val peers = files.names().filter { it.matches(REVISION_FILE) && it.substring(9, 29) == revision.substring(9, 29) }
        if (peers.size != 1) throw ArchiveConflictException()
        // These names are convenient current views. The immutable JSON above is the commit point.
        refs.filterKeys { it.endsWith(".misq") }.forEach { (path, hash) ->
            replaceNoteView(path.substringAfterLast('/'), hash)
        }
        replaceView("library.json", json.toString(2).toByteArray())
        staged.clear()
    }
    private fun replaceView(name: String, bytes: ByteArray) {
        // Never overwrite the committed recovery record; it remains authoritative if this fails.
        runCatching {
            val names = files.names()
            if (name in names && files.digest(name) == NotebookArchive.digest(bytes)) return
            if (name in names) files.delete(name)
            files.write(name, bytes)
            require(files.digest(name) == NotebookArchive.digest(bytes))
        }.getOrThrow()
    }
    private fun replaceNoteView(name: String, hash: String) {
        // A notebook can exceed the remaining Android heap. Compare, copy and verify
        // using bounded streams while the immutable recovery blob remains authoritative.
        val names = files.names()
        if (name in names && files.digest(name) == hash) return
        val source = "blob-$hash"
        require(files.digest(source) == hash)
        if (name in names) files.delete(name)
        files.openRead(source).use { files.writeStream(name, it) }
        require(files.digest(name) == hash)
    }
    private fun checkHead() {
        if (files.names().filter { it.matches(REVISION_FILE) }.maxOrNull() != observedHead) throw ArchiveConflictException()
    }
    override fun organize() {
        val names = files.names()
        val commits = names.filter { it.matches(REVISION_FILE) }.sortedDescending()
        // Keep two readable complete revisions. Branches and uncommitted blobs are retained.
        if (commits.groupBy { it.substring(9, 29) }.any { it.value.size > 1 }) return
        val keep = commits.filter { runCatching { parse(files.read(it)) }.isSuccess }.take(2)
        if (keep.isEmpty()) return
        val current = parse(files.read(keep.first()))
        refs(current).filterKeys { it.endsWith(".misq") }.forEach { (path, hash) ->
            replaceNoteView(path.substringAfterLast('/'), hash)
        }
        replaceView("library.json", current.toString(2).toByteArray())
        commits.filter { it !in keep }.forEach { files.delete(it) }
        // Old blob copies are deliberately retained in the test format, pending safe compaction.
        val active = refs(parse(files.read(keep.first()))).keys.filter { it.endsWith(".misq") }.map { it.substringAfterLast('/') }.toSet()
        names.filter { it.matches(NOTE) && it !in active }.forEach { files.delete(it) }
    }
    private fun refs(json: JSONObject) = json.getJSONObject("files").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
    companion object {
        private val REVISION_FILE = Regex("revision-[0-9]{20}-[a-f0-9-]{36}\\.json")
        private val BLOB = Regex("blob-[a-f0-9]{64}")
        private val NOTE = Regex("[a-f0-9-]{36}\\.misq")
        private fun checked(json: JSONObject): ByteArray {
            // Store the exact hashed JSON body, avoiding dependence on JSON key ordering.
            val body = json.toString()
            return JSONObject().put("body", body).put("sha256", NotebookArchive.digest(body.toByteArray())).toString().toByteArray()
        }
        private fun parse(bytes: ByteArray): JSONObject {
            val envelope = JSONObject(String(bytes, Charsets.UTF_8)); val body = envelope.getString("body")
            require(NotebookArchive.digest(body.toByteArray()) == envelope.getString("sha256"))
            return JSONObject(body).also { require(it.getInt("format") == 1); NotebookArchive.decode(NotebookArchive.encode(
                it.getJSONObject("files").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } })) }
        }
    }
}
