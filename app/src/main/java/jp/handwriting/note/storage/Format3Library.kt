package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import jp.handwriting.note.editor.ViewPosition

/** Application facade. It keeps daily edits on shared packs; MISQ0002 is an export boundary. */
internal class Format3Library(val store: ImmutableNoteStore, private val notes: File) {
    fun contains(id: String) = store.current(id)?.deleted == false
    fun manifest(id: String) = store.manifest(checkNotNull(store.current(id)).also { check(!it.deleted) })
    fun summaries() = store.ids().filter { it != SETTINGS_ID }.mapNotNull { id ->
        try {
        val root = checkNotNull(store.current(id)); if (root.deleted) return@mapNotNull null
        NoteSummary(id, root.info.title, root.info.updatedAt, root.count, createdAt = root.info.createdAt, paper = root.info.paper,
            thumbnail = File(notes, "${root.info.id}/thumbnail-v2.png").takeIf { it.exists() }?.path)
        } catch (_: Exception) { NoteSummary(id, jp.handwriting.note.AppMessages.text(jp.handwriting.note.R.string.unreadable_note), 0, 0,
            problem = jp.handwriting.note.AppMessages.text(jp.handwriting.note.R.string.note_data_read_failed)) }
    }.sortedByDescending { it.updatedAt }
    fun create(title: String, paper: NotePaper, now: Long): NoteManifest {
        val root = store.create(NoteInfo(UUID.randomUUID().toString(), NoteFiles.validTitle(title), now, now, paper = paper))
        store.install(root, validate = false)
        return store.manifest(root)
    }
    fun save(note: NoteManifest, placements: Map<String, StrokePlacement>, loaded: Set<Long>? = null,
        addedSince: Long = Long.MAX_VALUE, encode: (String) -> ByteArray) {
        val base = checkNotNull(store.current(note.info.id)); check(!base.deleted)
        val observed = loaded ?: store.manifest(base).strokes.map { it.id }.toSet()
        val old = store.find(base, note.strokes.map { it.id }.toSet())
        val entries = note.strokes.map { ref ->
            val prior = old[ref.id]
            val p = placements[ref.storageId] ?: prior?.takeIf { it.ref == ref }?.placement ?: error("Missing placement for changed stroke")
            ref to (p to if (prior?.ref == ref) ByteArray(0) else encode(ref.storageId))
        }
        store.installEdit { store.edit(base, note.info, note.nextId, observed, entries, addedSince) }
    }
    fun rename(id: String, title: String) {
        val root = checkNotNull(store.current(id))
        val next = store.edit(root, root.info.copy(title = NoteFiles.validTitle(title), updatedAt = System.currentTimeMillis()), root.nextId, emptySet(), emptyList())
        store.install(next, validate = false)
    }
    fun delete(id: String) { store.current(id)?.let { store.install(store.remove(it), validate = false) } }
    fun <T> read(id: String, refs: List<StrokeRef>, decode: (StrokeRef, ByteArray) -> T): List<T> =
        store.read(checkNotNull(store.current(id)), refs).map { (ref, bytes) -> decode(ref, bytes) }
    fun stroke(id: String, storageId: String): ByteArray {
        val root = checkNotNull(store.current(id))
        val ref = store.manifest(root).strokes.firstOrNull { it.storageId == storageId }
        if (ref != null) return store.read(root, listOf(ref)).single().second
        val file = File(notes, ".export-${UUID.randomUUID()}.tmp")
        try { store.export(root, file); return MisqFile(file).stroke(storageId) } finally { file.delete() }
    }
    fun export(id: String, target: File) {
        val root = checkNotNull(store.current(id))
        val temporary = File(target.parentFile, ".export-${UUID.randomUUID()}.tmp")
        try {
            store.export(root, temporary)
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun imported(file: File, parents: List<String> = emptyList()): NoteManifest {
        var source = MisqFile(file, readOnlyAlias = true)
        val id = source.index().manifest.info.id
        require(id != SETTINGS_ID) { "Reserved notebook identity" }
        val temporary = File(notes, ".import-${UUID.randomUUID()}.tmp")
        try {
            val index = source.index()
            val missing = index.manifest.strokes.filter { it.storageId !in index.placements }
            if (missing.isNotEmpty()) {
                source.file.copyTo(temporary)
                source = MisqFile(temporary)
                val placements = index.placements + source.readStrokes(missing) { ref, bytes -> ref.storageId to NoteRepository.decodeStroke(ref.storageId, bytes).placement }.toMap()
                source.save(index.manifest, placements, source::stroke)
            }
            val root = store.importNote(source, parents.ifEmpty { listOfNotNull(store.current(id)?.id) })
            store.install(root)
            return store.manifest(root)
        } finally { temporary.delete() }
    }
    fun contentKey(id: String): String {
        val root = checkNotNull(store.current(id)); val refs = store.manifest(root).strokes.sortedBy { it.id }
        val note = NoteManifest(root.info.copy(updatedAt = 0, position = ViewPosition(), color = 0xFF202B33.toInt(), size = 3f), refs, root.nextId)
        val digest = java.security.MessageDigest.getInstance("SHA-256"); digest.update(NoteManifestCodec.encodeManifest(note))
        val rows = store.find(root, refs.map { it.id }.toSet())
        refs.forEach { digest.update(rows.getValue(it.id).body.hash.toByteArray(Charsets.US_ASCII)) }
        return "$id:${ContentHash.hex(digest.digest())}"
    }
    fun requireCurrentExport(id: String) {
        val exported = MisqFile(File(notes, "$id.misq"))
        check(exported.contentKey() == contentKey(id) && exported.index().events == store.history(checkNotNull(store.current(id))).first) { "Notebook changed after merge review was opened" }
    }
    fun branchParent(file: File): String? {
        val hash = Regex("branch-([a-f0-9]{64})\\.misq").matchEntire(file.name)?.groupValues?.get(1) ?: return null
        val id = "o-$hash"
        return id.takeIf { runCatching { store.root(id).info.id == MisqFile(file, readOnlyAlias = true).summary().id }.getOrDefault(false) }
    }
    fun thumbnail(id: String) = store.current(id)?.let(store::thumbnail)
    fun thumbnailCurrent(id: String): Boolean = store.current(id)?.let { it.thumbnail != null && it.thumbnailKey == store.previewKey(it) } == true
    fun saveThumbnail(id: String, bytes: ByteArray, expectedRevision: String? = null) {
        val root = checkNotNull(store.current(id))
        if (store.recovered(id)) return // A disposable preview cannot acknowledge recovery of an older head.
        if (expectedRevision != null && root.id != expectedRevision) return
        if (root.thumbnail == "o-${ContentHash.digest(bytes)}" && root.thumbnailKey == store.previewKey(root)) return
        store.install(store.saveThumbnail(root, bytes), validate = false)
    }
    fun source(id: String): SpatialNoteSource? = store.current(id)?.takeIf { !it.deleted }?.let { SpatialNoteSource(store, it, store.recovered(id)) }
    companion object { const val SETTINGS_ID = "00000000-0000-0000-0000-000000000003" }
}
