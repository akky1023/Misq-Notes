package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.json.JSONObject
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

internal data class ConflictSource(val file: File, val name: String = file.name, val primary: Boolean = false)
internal data class NoteConflict(val key: String, val noteId: String, val title: String, val filename: String,
    val updatedAt: Long, val strokes: Int, internal val file: File, val provisional: Boolean = false)

internal val conflictVersionOrder = compareByDescending<NoteConflict> { it.updatedAt }.thenBy { it.key }

/** Different contents with the same embedded ID; names and sync vendor conventions are irrelevant. */
internal class NoteConflicts(private val notes: File, private val state: File) {
    private data class Version(val source: ConflictSource, val index: MisqFile.Index, val key: String)
    private data class Cached(val stamp: NoteFileStamp, val version: Version)
    private val cache = mutableMapOf<String, Cached>()
    private val imports = File(state, "opened.hwp")
    private fun version(source: ConflictSource): Version {
        val stamp = source.file.noteStamp()
        val path = source.file.absolutePath
        cache[path]?.takeIf { stamp.identity != null && it.stamp == stamp }?.let { return it.version.copy(source = source) }
        val reader = MisqFile(source.file, readOnlyAlias = true)
        val index = reader.index()
        val key = reader.contentKey()
        return Version(source, index, key).also { cache[path] = Cached(stamp, it) }
    }
    private fun opened(): JSONObject = if (!imports.exists()) JSONObject() else JSONObject(String(NoteFiles.readEnvelope(imports), Charsets.UTF_8))

    @Synchronized fun scan(additional: List<ConflictSource> = emptyList()): List<NoteConflict> {
        val sources = notes.listFiles().orEmpty().filter { it.isFile && it.extension.equals("misq", true) }
            .map { ConflictSource(it, primary = runCatching { UUID.fromString(it.nameWithoutExtension).toString() == it.nameWithoutExtension }.getOrDefault(false)) } + additional
        cache.keys.retainAll(sources.map { it.file.absolutePath }.toSet())
        val versions = sources.mapNotNull { source -> runCatching { version(source) }.getOrNull() }
        val handled = opened()
        return versions.groupBy { it.index.manifest.info.id }.values.flatMap { group ->
            val distinct = group.distinctBy { it.key }
            val original = group.firstOrNull { it.source.primary && it.source.file.nameWithoutExtension == it.index.manifest.info.id }
            val provisional = original == null && !File(notes, "${group.first().index.manifest.info.id}.misq").exists()
            if (!provisional && distinct.size < 2) return@flatMap emptyList()
            distinct.filter { it.key != original?.key }.filter { item ->
                val id = handled.optString(item.key)
                provisional || id.isEmpty() || !File(notes, "$id.misq").isFile
            }.map { item ->
                val manifest = item.index.manifest
                NoteConflict(item.key, manifest.info.id, manifest.info.title, item.source.name,
                    manifest.info.updatedAt, manifest.strokes.size, item.source.file, provisional)
            }
        }.sortedWith(conflictVersionOrder)
    }

    fun missingWorkingCopy(noteId: String) = !File(notes, "$noteId.misq").exists()

    /** Recheck the selected version and choose the current working file or newest provisional version. */
    @Synchronized fun mergeSources(conflict: NoteConflict, provisionalBase: NoteConflict? = null): Pair<File, File> {
        cache.remove(conflict.file.absolutePath)
        check(version(ConflictSource(conflict.file, conflict.filename)).key == conflict.key) { AppMessages.text(R.string.conflict_version_changed) }
        val original = File(notes, "${conflict.noteId}.misq")
        if (conflict.provisional && !original.exists()) {
            val latest = provisionalBase ?: scan().filter { it.noteId == conflict.noteId }
                .minWithOrNull(conflictVersionOrder) ?: conflict
            require(latest.noteId == conflict.noteId)
            cache.remove(latest.file.absolutePath)
            check(version(ConflictSource(latest.file, latest.filename)).key == latest.key) { AppMessages.text(R.string.conflict_version_changed) }
            return latest.file to conflict.file
        }
        check(original.isFile && MisqFile(original).readManifest().manifest.info.id == conflict.noteId) {
            AppMessages.text(R.string.merge_original_missing)
        }
        return original to conflict.file
    }

    @Synchronized fun canAutoMerge(conflict: NoteConflict): Boolean = !conflict.provisional && runCatching {
        val (a, b) = mergeSources(conflict)
        AppendOnlyMerge.canMerge(MisqFile(a).index(), MisqFile(b, readOnlyAlias = true).index())
    }.getOrDefault(false)

    /** Preserve the alias and the previous committed manifest, and keep local entry IDs stable. */
    @Synchronized fun autoMerge(conflict: NoteConflict): NoteManifest? {
        if (conflict.provisional) return null
        val (original, alias) = mergeSources(conflict)
        val local = MisqFile(original)
        val a = local.index()
        if (!AppendOnlyMerge.canMerge(a, MisqFile(alias, readOnlyAlias = true).index())) return null
        check(state.isDirectory || state.mkdirs())
        val snapshot = File(state, "auto-${UUID.randomUUID()}.misq")
        try {
            alias.inputStream().use { input -> snapshot.outputStream().use { input.copyTo(it, 65536) } }
            val remote = MisqFile(snapshot, readOnlyAlias = true)
            val b = remote.index()
            check(version(ConflictSource(snapshot)).key == conflict.key) { AppMessages.text(R.string.conflict_version_changed) }
            cache.remove(alias.absolutePath)
            check(version(ConflictSource(alias, conflict.filename)).key == conflict.key) { AppMessages.text(R.string.conflict_version_changed) }
            if (!AppendOnlyMerge.canMerge(a, b)) return null
            if (b.manifest.strokes.size <= a.manifest.strokes.size) {
                markOpened(conflict, a.manifest.info.id)
                return a.manifest
            }
            val merged = try { MisqFile.merge(null, local, remote) } catch (_: ArchiveConflictException) { return null }
            val oldIds = a.entities.entries.associate { it.value to it.key }
            val oldRefs = a.manifest.strokes.associateBy { it.id }
            var next = a.manifest.nextId
            val entities = linkedMapOf<Long, String>()
            val refs = merged.manifest.strokes.map { ref ->
                val entity = merged.entities.getValue(ref.id)
                val id = oldIds[entity] ?: next++
                entities[id] = entity
                StrokeRef(id, oldRefs[id]?.storageId ?: ref.storageId)
            }
            val index = merged.copy(manifest = merged.manifest.copy(strokes = refs, nextId = next), entities = entities)
            local.rewrite(index, remote)
            markOpened(conflict, index.manifest.info.id)
            return index.manifest
        } finally { cache.remove(snapshot.absolutePath); snapshot.delete() }
    }

    @Synchronized fun copy(conflict: NoteConflict, title: String): NoteManifest {
        cache.remove(conflict.file.absolutePath)
        check(version(ConflictSource(conflict.file, conflict.filename)).key == conflict.key) { AppMessages.text(R.string.conflict_version_changed) }
        val source = MisqFile(conflict.file, readOnlyAlias = true)
        val manifest = source.readManifest().manifest
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val copy = manifest.copy(info = manifest.info.copy(id = id, title = NoteFiles.validTitle(title.take(120)), createdAt = now, updatedAt = now))
        val temporary = File(state, "$id.misq")
        check(state.isDirectory || state.mkdirs())
        try {
            val target = MisqFile(temporary)
            target.save(copy) { source.stroke(it) }
            target.validate()
            // Detect a writer replacing the alias while it was read. Never publish mixed inputs.
            cache.remove(conflict.file.absolutePath)
            check(version(ConflictSource(conflict.file, conflict.filename)).key == conflict.key) { AppMessages.text(R.string.conflict_version_changed) }
            java.nio.file.Files.move(temporary.toPath(), File(notes, "$id.misq").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return copy
        } finally { temporary.delete() }
    }
    @Synchronized fun markOpened(conflict: NoteConflict, id: String) {
        val json = opened().put(conflict.key, id)
        check(state.isDirectory || state.mkdirs())
        NoteFiles.atomicWrite(imports, json.toString().toByteArray())
    }
}
