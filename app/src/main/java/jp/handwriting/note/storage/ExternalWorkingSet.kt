package jp.handwriting.note.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Only hashes, deletion intents and rebuildable list metadata survive a successful external save. */
internal class ExternalWorkingSet(private val directory: File, val tree: String,
    private val fetch: (String, File) -> Unit) : NoteBacking {
    private val state = File(directory, ".notes-external-state")
    private val catalog = File(directory, ".notes-external-catalog")
    private val root = File(directory, "notes").also { check(it.isDirectory || it.mkdirs()) }
    private val codec = NoteFiles(root, false)
    var baseline: Map<String, String> = emptyMap()
        private set
    private val deleted = mutableSetOf<String>()
    private val cached = mutableMapOf<String, Pair<String, NoteSummary>>()
    private val checkedPreviews = mutableSetOf<String>()
    var available = false

    init {
        if (state.exists()) {
            val json = JSONObject(String(NoteFiles.readEnvelope(state), Charsets.UTF_8))
            check(json.getInt("version") == 1 && json.getString("tree") == tree) { "External storage selection does not match working data" }
            baseline = NotebookArchive.decode(Base64.getDecoder().decode(json.getString("baseline")))
            val ids = json.getJSONArray("deleted")
            repeat(ids.length()) { deleted += validId(ids.getString(it)) }
        }
        runCatching {
            val json = JSONObject(String(NoteFiles.readEnvelope(catalog), Charsets.UTF_8))
            json.keys().forEach { id ->
                val item = json.getJSONObject(id)
                val manifest = codec.decodeManifest(id, Base64.getDecoder().decode(item.getString("manifest")))
                val info = manifest.info
                cached[id] = item.getString("hash") to NoteSummary(id, info.title, info.updatedAt, item.getInt("strokes"),
                    createdAt = info.createdAt, paper = info.paper, thumbnail = thumbnail(id).takeIf { it.exists() }?.path)
            }
        }.onFailure { cached.clear() }
    }
    private fun validId(id: String) = id.also { require(UUID.fromString(it).toString() == it) }
    private fun path(id: String) = "notes/${validId(id)}.misq"
    private fun noteId(path: String): String? = if (path.startsWith("notes/") && path.endsWith(".misq"))
        validId(path.removePrefix("notes/").removeSuffix(".misq")) else null
    private fun thumbnail(id: String) = File(root, "${validId(id)}/thumbnail-v2.png")
    private fun save() {
        val json = JSONObject().put("version", 1).put("tree", tree)
            .put("baseline", Base64.getEncoder().encodeToString(NotebookArchive.encode(baseline)))
            .put("deleted", JSONArray(deleted.sorted()))
        NoteFiles.atomicWrite(state, json.toString().toByteArray())
    }
    private fun saveCatalog() {
        val json = JSONObject()
        cached.forEach { (id, entry) ->
            val note = entry.second
            val manifest = NoteManifest(NoteInfo(id, note.title, note.createdAt, note.updatedAt, paper = note.paper), emptyList())
            json.put(id, JSONObject().put("hash", entry.first).put("strokes", note.strokes)
                .put("manifest", Base64.getEncoder().encodeToString(codec.encodeManifest(manifest))))
        }
        NoteFiles.atomicWrite(catalog, json.toString().toByteArray())
    }
    override fun contains(id: String) = path(id) in baseline && id !in deleted
    fun isDeleted(path: String) = noteId(path)?.let { it in deleted } == true
    override fun ensure(id: String) {
        val target = File(root, "${validId(id)}.misq")
        if (target.exists() || !contains(id)) return
        check(available) { "External storage is unavailable; notebook was not downloaded" }
        val hash = baseline.getValue(path(id))
        val temporary = File(root, ".download-${UUID.randomUUID()}")
        try {
            fetch(hash, temporary)
            check(NotebookArchive.digest(temporary) == hash) { "External notebook verification failed" }
            check(MisqFile(temporary, readOnlyAlias = true).summary().id == id)
            java.io.RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    override fun deleted(id: String) { deleted += validId(id); save() }
    override fun preview(id: String): File {
        val target = thumbnail(id)
        val hash = baseline.getValue(path(id))
        if (target.exists() || "$id:$hash" in checkedPreviews) return target
        check(available) { "External storage is unavailable; preview was not downloaded" }
        val temporary = File(directory, ".notes-catalog-${UUID.randomUUID()}.misq")
        try {
            fetch(hash, temporary); check(NotebookArchive.digest(temporary) == hash)
            MisqFile(temporary, readOnlyAlias = true).thumbnail()?.let {
                target.parentFile!!.mkdirs(); target.writeBytes(it)
            }
            checkedPreviews += "$id:$hash"
        } finally { temporary.delete() }
        return target
    }
    override fun summaries(): List<NoteSummary> = baseline.keys.mapNotNull { path ->
        val id = noteId(path) ?: return@mapNotNull null
        if (id in deleted || File(root, "$id.misq").exists()) return@mapNotNull null
        checkNotNull(cached[id]?.takeIf { it.first == baseline[path] }) { "External notebook list needs rebuilding" }.second
    }
    fun references(): Map<String, ArchiveBlob> = baseline.filterKeys { path -> noteId(path)?.let { it !in deleted } == true }
        .mapValues { (_, hash) -> blob(hash) }
    fun blob(hash: String) = ArchiveBlob(hash, directory) { fetch(hash, it) }
    fun baseBlobs(): Map<String, ArchiveBlob> = baseline.mapValues { (_, hash) -> blob(hash) }

    /** Called only after the external revision and its bodies have been verified. */
    fun accept(files: Map<String, String>) {
        files.keys.forEach { path -> noteId(path) }
        // A deletion made after the captured save must remain pending.
        deleted.removeAll { path(it) !in files }
        baseline = files.toMap()
        save()
    }
    fun rebuildCatalog() {
        baseline.forEach { (path, hash) ->
            val id = noteId(path) ?: return@forEach
            if (id in deleted || cached[id]?.first == hash) return@forEach
            val local = File(root, "$id.misq")
            val temporary = File(directory, ".notes-catalog-${UUID.randomUUID()}.misq")
            try {
                val source = if (local.exists() && NotebookArchive.digest(local) == hash) local else temporary.also {
                    fetch(hash, it); check(NotebookArchive.digest(it) == hash)
                }
                val document = MisqFile(source, readOnlyAlias = true)
                val summary = document.summary(); check(summary.id == id)
                document.thumbnail()?.let { bytes -> thumbnail(id).also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) } }
                cached[id] = hash to summary.copy(thumbnail = thumbnail(id).takeIf { it.exists() }?.path)
            } finally { temporary.delete() }
        }
        cached.keys.retainAll(baseline.keys.mapNotNull(::noteId).toSet())
        saveCatalog()
    }
    /** Old per-stroke originals are backups, never missing working notes to migrate again. */
    fun archiveLegacyOriginals() {
        baseline.keys.mapNotNull(::noteId).forEach { id ->
            val folder = File(root, id)
            if (!File(folder, "manifest.hwn").exists()) return@forEach
            val backups = File(directory, ".notes-legacy-originals").also { check(it.isDirectory || it.mkdirs()) }
            val target = File(backups, "$id-${UUID.randomUUID()}")
            Files.move(folder.toPath(), target.toPath(), ATOMIC_MOVE)
            File(target, "thumbnail-v2.png").takeIf { it.isFile }?.let { source ->
                val preview = thumbnail(id); preview.parentFile!!.mkdirs(); source.copyTo(preview)
            }
        }
    }
    /** Never evicts a modified body, a legacy/unknown file or the active editor's working file. */
    fun evict(active: String? = null) {
        baseline.forEach { (path, hash) ->
            val id = noteId(path) ?: return@forEach
            val file = File(root, "$id.misq")
            if (id != active && id !in deleted && cached[id]?.first == hash && file.isFile && NotebookArchive.digest(file) == hash)
                check(file.delete()) { "Unable to release saved notebook working copy" }
        }
    }
    fun materializeAll() { baseline.keys.mapNotNull(::noteId).filter { it !in deleted }.forEach(::ensure) }
    fun forget() = forget(directory)
    companion object {
        fun exists(directory: File) = File(directory, ".notes-external-state").exists()
        fun forget(directory: File) {
            listOf(".notes-external-state", ".notes-external-state.bak", ".notes-external-catalog", ".notes-external-catalog.bak").forEach {
                val file = File(directory, it); check(!file.exists() || file.delete()) { "Unable to finish storage switch" }
            }
        }
    }
}
