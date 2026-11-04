package jp.handwriting.note.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal data class SettingsVersion(val id: String, val local: Boolean, val folders: Int, val locations: Int)

/** External revisions are authoritative; local .misq files hold durable, temporary editing work. */
internal class NotebookStorage(private val context: Context, private val directory: File, private val scope: CoroutineScope) {
    private val legacy = LegacyNotebookStorage(context, directory, scope)
    private var format3: Format3Library? = null
    private var format3Uri: Uri? = null
    private var format3Transport: ImmutableNoteTransport? = null
    internal val usesImmutable get() = format3 != null
    @Synchronized internal fun deletedNoteIds(): Set<String> = format3?.store?.let { store -> store.ids().filter { id -> runCatching { store.current(id)?.deleted == true }.getOrDefault(false) }.toSet() }.orEmpty()
    @Volatile var settingsVersions: List<SettingsVersion> = emptyList()
        private set
    private val pending = File(directory, ".notes-pending")
    private val switching = File(directory, ".notes-external-switch")
    private var working: ExternalWorkingSet? = null
    private var archive: NotebookArchive? = null
    private var regions: SpatialNoteCache? = null
    private val publishedBodies = mutableMapOf<String, Pair<String, NoteFileStamp>>()
    internal var lastSnapshotNoteBytes = 0L
        private set
    private var needsLegacyRestore = false
    @Volatile private var active: String? = null
    private var version = 0L
    private var connection: Job? = null
    private val externalMode = MutableStateFlow(false)
    private val connectionState = MutableStateFlow(SaveState())
    var beforeExternalSave: suspend () -> Unit = {}
    var afterExternalSave: suspend () -> Unit = {}
    private val queue = SaveQueue(scope, batchMillis = 750L) { _: Unit ->
        beforeExternalSave()
        withContext(Dispatchers.IO) { saveExternal() }
        afterExternalSave()
    }
    val syncState = combine(legacy.syncState, queue.state, connectionState, externalMode) { old, save, connect, external ->
        if (!external) old else save.copy(saving = save.saving || connect.saving,
            error = connect.error ?: save.error)
    }
    val uri: Uri? get() = if (usesImmutable) format3Uri else legacy.uri
    val label: String get() = if (usesImmutable) {
        if (uri == null) AppMessages.text(R.string.storage_default) else context.getSharedPreferences("note-storage", Context.MODE_PRIVATE).getString("format3-label", null) ?: AppMessages.text(R.string.storage_selected)
    } else legacy.label
    var recovered = false
        private set
    var blockedByRemoteChange = false
        private set

    fun initialize(): Boolean {
        if (Format3Migration(directory).active) {
            val preferences = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
            format3Uri = preferences.getString("format3-tree", null)?.let(Uri::parse)
            val selected = format3Uri
            val store = Format3Migration(directory).open()
            if (selected != null) format3Transport = ImmutableNoteTransport(store, PackedDocumentFiles(context.contentResolver, selected, "misq-v3"))
            format3 = Format3Library(store, File(directory, "format3-working/notes").also { it.mkdirs() })
            externalMode.value = selected != null
            return selected != null
        }
        recoverSwitch()
        if (ExternalWorkingSet.exists(directory)) {
            cleanupTemporaryFiles()
            val tree = checkNotNull(uri) { "External working data has no selected folder" }
            working = newWorkingSet(tree)
            externalMode.value = true
            connectionState.value = SaveState(saving = true)
            return true
        }
        needsLegacyRestore = legacy.initialize()
        return uri != null
    }
    private fun recoverSwitch() {
        if (!switching.exists()) return
        val json = JSONObject(String(NoteFiles.readEnvelope(switching), Charsets.UTF_8))
        val current = uri?.toString().orEmpty()
        val expected = json.getJSONObject("files").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
        val actual = localFiles().mapValues { NotebookArchive.digest(it.value) }
        when (current) {
            json.getString("to") -> {
                // Preference commit succeeded. All old notebooks were materialized beforehand.
                // Selecting a populated target may also have imported new notes/metadata.
                check(expected.filterKeys { it.startsWith("notes/") }.all { actual[it.key] == it.value }) {
                    AppMessages.text(R.string.storage_change_incomplete)
                }
                ExternalWorkingSet.forget(directory)
            }
            json.getString("from") -> check(expected == actual) { AppMessages.text(R.string.storage_change_incomplete) }
            else -> error(AppMessages.text(R.string.storage_change_incomplete))
        }
        clearSwitch()
    }
    private fun clearSwitch() {
        check(!switching.exists() || switching.delete())
        File(switching.path + ".bak").delete()
    }
    suspend fun initializeRemote() = withContext(Dispatchers.IO) {
        if (usesImmutable) {
            try { syncFormat3(incoming = true); connectionState.value = SaveState() }
            catch (error: Exception) { connectionState.value = SaveState(error = error.localizedMessage) }
            return@withContext
        }
        try {
            if (working == null) migrate() else connectExternal()
            connectionState.value = SaveState()
        } catch (error: Exception) {
            connectionState.value = SaveState(error = error.localizedMessage)
            throw error
        }
    }
    private fun regionStore(tree: Uri) = SpatialNoteCache(PackedDocumentFiles(context.contentResolver, tree, "inkdata/.regions"))
    private fun newWorkingSet(tree: Uri) = ExternalWorkingSet(directory, tree.toString(), spatialReader = { hash -> regionStore(tree).open(hash) }) { hash, target ->
        // Each reader owns its provider inventory; uploads and lazy downloads never share it.
        val source = MisqDocumentFiles(context.contentResolver, tree)
        source.names()
        source.openRead("blob-$hash").use { input -> target.outputStream().use { input.copyTo(it, 65536) } }
    }
    private fun openArchive(tree: Uri = checkNotNull(uri)) =
        NotebookArchive(MisqArchiveDirectory(MisqDocumentFiles(context.contentResolver, tree)))

    /** The old code is used only to finish an existing migration/sync before releasing its copies. */
    private suspend fun migrate() {
        if (needsLegacyRestore) legacy.initializeRemote() else legacy.startBackground()
        check(legacy.awaitSync()) { legacy.syncState.first().error ?: AppMessages.text(R.string.storage_incoming) }
        check(!legacy.blockedByRemoteChange) { AppMessages.text(R.string.storage_incoming) }
        val files = localFiles()
        val reader = openArchive()
        val revision = checkNotNull(reader.latest(files)) { "External library was not saved" }
        check(revision.files == files.mapValues { NotebookArchive.digest(it.value) }) { "External library does not match local data" }
        val next = newWorkingSet(checkNotNull(uri))
        next.available = true
        next.accept(revision.files)
        next.archiveLegacyOriginals()
        next.rebuildCatalog()
        synchronized(this) { working = next; archive = reader; recovered = legacy.recovered; externalMode.value = true }
        next.evict()
        releaseLegacyBase()
        if (queue.state.value.error != null) queue.request(Unit)
    }
    private fun cleanupTemporaryFiles() {
        val uuid = "[a-f0-9-]{36}"
        val rootNames = Regex("\\.notes-(snapshot|fetch|catalog|external-merge)-$uuid(\\.misq)?")
        directory.listFiles().orEmpty().filter { rootNames.matches(it.name) }.forEach { it.deleteRecursively() }
        val noteNames = Regex("\\.(download|replace)-$uuid")
        File(directory, "notes").listFiles().orEmpty().filter { it.isFile && noteNames.matches(it.name) }.forEach { it.delete() }
    }
    private fun releaseLegacyBase() {
        // These exact app-owned paths contain only old derived merge copies.
        listOf(".notes-merge-base", ".notes-merge-base-next").forEach { File(directory, it).deleteRecursively() }
    }
    private fun localFiles(): Map<String, File> {
        val library = LibraryPreferencesFile(File(directory, "library.hwp")).read()
        val folders = File(directory, "folders.hwp").also { NotebookFolders.write(it, library) }
        val presets = File(directory, "presets.hwp").also {
            NotebookPresets.write(it, library.paper, SavedColors(File(directory, "palette.hwp")).read(),
                ToolPreferencesFile(File(directory, "tools.hwp")).read())
        }
        val files = linkedMapOf("folders.hwp" to folders, "presets.hwp" to presets)
        val notes = NoteFiles(File(directory, "notes"))
        notes.list().forEach { summary ->
            check(summary.problem == null) { AppMessages.text(R.string.storage_unreadable_notes) }
            val file = notes.document(summary.id).file
            if (!file.exists()) {
                val manifest = notes.read(summary.id).manifest
                notes.save(manifest) { notes.stroke(summary.id, it) }
            }
            files["notes/${summary.id}.misq"] = file
        }
        return files
    }
    private fun snapshot(): ArchiveSnapshot {
        check(PartialNoteJournal.ids(File(directory, "notes")).isEmpty()) { "Local edits must be materialized before taking an upload snapshot" }
        val local = localFiles().filterKeys { working?.isDeleted(it) != true }
        val references = working?.references().orEmpty().toMutableMap()
        val copy = local.filter { (path, file) ->
            val published = publishedBodies[path]
            val unchanged = path.endsWith(".misq") && published != null && references[path]?.hash == published.first &&
                published.second.identity != null && runCatching { file.noteStamp() == published.second }.getOrDefault(false) &&
                BlockChanges.capture(file)?.let { it.baseline == published.first && it.dirty.isEmpty() && it.metadataDirty == 0L } == true
            if (unchanged) {
                val expected = checkNotNull(published)
                // If a remote object disappeared, recreate it from a checked, immutable copy
                // of the still-unchanged local body rather than depending on a missing blob.
                references[path] = ArchiveBlob(expected.first, directory) { target -> synchronized(this) {
                    check(file.noteStamp() == expected.second) { "Notebook changed during snapshot repair" }
                    file.copyTo(target, overwrite = true)
                } }
            }
            !unchanged
        }
        return ArchiveSnapshot.capture(copy, references)
    }
    private fun acknowledgeBlocks(snapshot: ArchiveSnapshot, published: Set<String>) {
        val baseline = working?.baseline.orEmpty()
        publishedBodies.entries.removeAll { (path, value) -> baseline[path] != value.first }
        snapshot.sources.filterKeys { it.endsWith(".misq") }.forEach { (path, source) ->
            val blob = snapshot[path] ?: return@forEach
            if (path in published && baseline[path] == blob.hash) blob.changes?.let { captured -> runCatching { BlockChanges.acknowledge(source.first, captured, blob.hash) } }
            if (path in published && baseline[path] == blob.hash && source.second.identity != null &&
                runCatching { source.first.noteStamp() == source.second }.getOrDefault(false))
                publishedBodies[path] = blob.hash to source.second
        }
    }
    private fun recoverPartial(set: ExternalWorkingSet) = PartialNoteJournal.recover(File(directory, "notes"), NoteFiles(File(directory, "notes")),
        deleted = { set.isDeleted("notes/$it.misq") }) { set.ensure(it) }

    /** Runs before constructing the editor, so a merged body can never replace live edits. */
    private fun connectExternal() {
        val set = checkNotNull(working)
        set.available = true
        try { recoverPartial(set) }
        finally { set.available = false }
        set.archiveLegacyOriginals()
        val reader = openArchive()
        snapshot().use { local ->
            val remote = checkNotNull(reader.latestCaptured(local)) { "External library is missing; working data has been retained" }
            val remoteBlobs = remote.files.mapValues { (_, hash) -> set.blob(hash) }
            val work = File(directory, ".notes-external-merge-${UUID.randomUUID()}")
            try {
                val selected = MisqMerge.mergeFiles(set.baseBlobs(), local, remoteBlobs, work)
                if (selected.mapValues { it.value.hash } != remote.files) reader.commitSnapshot(selected)
                val published = publishRegions(selected)
                // Keep interrupted imports recoverable: publish the new baseline only after all
                // changed working files and metadata have been installed successfully.
                applySelected(selected, local)
                set.accept(selected.mapValues { it.value.hash })
                set.archiveLegacyOriginals()
                set.available = true
                set.rebuildCatalog()
                acknowledgeBlocks(local, published)
                archive = reader; recovered = reader.recovered; blockedByRemoteChange = false
                pending.delete()
                set.evict()
                releaseLegacyBase()
            } finally { work.deleteRecursively() }
        }
    }
    private fun applySelected(selected: Map<String, ArchiveBlob>, previous: Map<String, ArchiveBlob>) {
        val libraryFile = LibraryPreferencesFile(File(directory, "library.hwp"))
        val toolsFile = ToolPreferencesFile(File(directory, "tools.hwp"))
        val settings = libraryFile.read()
        val folders = NotebookFolders.decode(LibraryJson.unwrap(selected.getValue("folders.hwp").bytes), settings)
        val presets = selected["presets.hwp"]?.let { NotebookPresets.decode(LibraryJson.unwrap(it.bytes)) }
        libraryFile.write(if (presets == null) folders else folders.copy(paper = presets.paper))
        if (presets != null) {
            SavedColors(File(directory, "palette.hwp")).write(presets.palettes)
            toolsFile.write(presets.applyTo(toolsFile.read()))
        }
        previous.keys.filter { it.startsWith("notes/") && it.endsWith(".misq") }.forEach { path ->
            val file = File(directory, path)
            if (!file.exists()) return@forEach
            val next = selected[path]
            if (next == null) check(file.delete())
            else if (NotebookArchive.digest(file) != next.hash) {
                val temporary = File(file.parentFile, ".replace-${UUID.randomUUID()}")
                try {
                    next.copyTo(temporary)
                    Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
        }
        File(directory, "notes/.list-dirty").writeText("rebuild")
    }
    private fun saveExternal() {
        if (usesImmutable) { syncFormat3(incoming = false); return }
        // Fetch unopened bodies without the persistence monitor: closing another note and
        // fsync'ing its edits must not wait for a provider download.
        if (!materializePartial()) { queue.request(Unit); return }
        val set: ExternalWorkingSet
        val target: NotebookArchive
        val captured: ArchiveSnapshot
        val capturedVersion: Long
        synchronized(this) {
            if (!pending.exists()) return
            if (PartialNoteJournal.ids(File(directory, "notes")).isNotEmpty()) {
                queue.request(Unit)
                return
            }
            set = checkNotNull(working)
            check(set.available) { AppMessages.text(R.string.storage_unavailable) }
            check(!blockedByRemoteChange) { AppMessages.text(R.string.storage_incoming) }
            target = checkNotNull(archive)
            captured = snapshot()
            lastSnapshotNoteBytes = captured.sources.filterKeys { it.endsWith(".misq") }.values.sumOf { it.second.size }
            capturedVersion = version
        }
        captured.use {
            try { target.commitSnapshot(captured) }
            catch (error: ArchiveConflictException) {
                synchronized(this) { blockedByRemoteChange = true }
                throw java.io.IOException(AppMessages.text(R.string.storage_incoming), error)
            }
            val published = publishRegions(captured)
            synchronized(this) {
                set.accept(captured.mapValues { it.value.hash })
                set.archiveLegacyOriginals()
                set.rebuildCatalog(captured)
                acknowledgeBlocks(captured, published)
                // Local writes may have happened during provider IO. Acknowledge only this snapshot.
                if (version == capturedVersion) pending.delete()
            }
        }
    }
    private fun materializePartial(): Boolean {
        val root = File(directory, "notes")
        val ids = synchronized(this) { PartialNoteJournal.ids(root) }
        for (id in ids) {
            val prepared = synchronized(this) {
                val set = checkNotNull(working)
                check(set.available) { AppMessages.text(R.string.storage_unavailable) }
                if (set.isDeleted("notes/$id.misq")) {
                    PartialNoteJournal.clear(root, id)
                    null
                } else PartialNoteJournal.read(root, id)?.let { journal ->
                    journal to set.blob(set.baseline.getValue("notes/$id.misq"))
                }
            } ?: continue
            val (journal, base) = prepared
            val work = File(directory, ".notes-fetch-${UUID.randomUUID()}").also { check(it.mkdir()) }
            val temporary = File(work, "$id.misq")
            try {
                base.copyTo(temporary)
                val target = MisqFile(temporary)
                check(target.readManifest().manifest.info.id == id)
                journal.apply(target)
                target.validate()
                synchronized(this) {
                    val set = checkNotNull(working)
                    if (set.isDeleted("notes/$id.misq")) return@synchronized
                    // An edit or deletion made during IO wins over the old download.
                    if (PartialNoteJournal.read(root, id)?.token != journal.token) return false
                    Files.move(temporary.toPath(), File(root, "$id.misq").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    PartialNoteJournal.clear(root, id)
                }
            } finally { temporary.delete(); work.delete() }
        }
        return synchronized(this) { PartialNoteJournal.ids(root).isEmpty() }
    }
    private fun publishRegions(snapshot: Map<String, ArchiveBlob>): Set<String> {
        val store = regions ?: regionStore(checkNotNull(uri)).also { regions = it }
        val published = mutableSetOf<String>()
        snapshot.filterKeys { it.endsWith(".misq") }.forEach { (path, blob) -> blob.localFile?.let { file ->
            runCatching { store.publish(file, blob.hash, blob.changes) }.onSuccess { published += path }
                .onFailure { android.util.Log.w("MisqStorage", "Spatial cache could not be published", it) }
        } }
        return published
    }
    @Synchronized fun <T> write(block: () -> T): T {
        if (usesImmutable) {
            if (uri != null && !pending.exists()) java.io.FileOutputStream(pending).use { it.write(1); it.fd.sync() }
            version++
            val result = block()
            if (uri != null) queue.request(Unit)
            return result
        }
        val set = working ?: return legacy.write(block)
        check(set.available) { AppMessages.text(R.string.storage_unavailable) }
        if (!pending.exists()) java.io.FileOutputStream(pending).use { it.write(1); it.fd.sync() }
        version++
        val result = block()
        queue.request(Unit)
        return result
    }
    fun noteFiles(): NoteFiles = NoteFiles(File(directory, if (usesImmutable) "format3-working/notes" else "notes")).also { files ->
        files.format3 = format3
        files.backing = object : NoteBacking {
            override fun contains(id: String) = synchronized(this@NotebookStorage) { working?.contains(id) == true }
            override fun ensure(id: String) = synchronized(this@NotebookStorage) { working?.ensure(id); Unit }
            override fun summaries() = synchronized(this@NotebookStorage) { working?.summaries().orEmpty() }
            override fun deleted(id: String) = synchronized(this@NotebookStorage) { working?.deleted(id); Unit }
            override fun preview(id: String) = synchronized(this@NotebookStorage) { checkNotNull(working).preview(id) }
            override fun spatial(id: String) = synchronized(this@NotebookStorage) { working?.spatial(id) }
        }
    }
    @Synchronized fun contains(id: String) = format3?.contains(id) ?: (working?.contains(id) == true || File(directory, "notes/$id.misq").exists())
    @Synchronized fun ensure(id: String) {
        format3?.let { library -> library.export(id, File(directory, "format3-working/notes/$id.misq")); return }
        working?.ensure(id)
    }
    @Synchronized fun keepOpen(id: String?) {
        active = id
        if (id == null && usesImmutable && uri != null) retryPending()
    }
    /** The repository serializes this with reads/previews; provider writes own immutable snapshots. */
    @Synchronized fun releaseWorkingCopies() { if (!usesImmutable) working?.evict(active) }
    @Synchronized fun startBackground() {
        if (usesImmutable) { if (uri != null && pending.exists()) queue.request(Unit); return }
        if (working == null) { legacy.startBackground(); return }
        if (archive != null || connection?.isActive == true) return
        connection = scope.launch(Dispatchers.IO) { runCatching { initializeRemote() } }
    }
    suspend fun awaitSync(): Boolean {
        if (usesImmutable && uri == null) return true
        if (!usesImmutable && working == null) return legacy.awaitSync()
        connection?.join()
        if (connectionState.value.error != null) return false
        while (true) {
            val generation = queue.state.value.latest
            if (!queue.flush(generation)) return false
            if (!pending.exists()) return true
            if (queue.state.value.latest <= generation) return false
        }
    }
    @Synchronized fun retryPending() {
        if (usesImmutable) {
            if (uri != null && connection?.isActive != true) {
                connectionState.value = SaveState(saving = true)
                val generation = queue.state.value.latest
                connection = scope.launch(Dispatchers.IO) { queue.await(generation); initializeRemote() }
            }
            return
        }
        if (working == null) { legacy.retryPending(); return }
        if (archive == null) startBackground() else if (pending.exists()) queue.request(Unit)
    }
    @android.annotation.SuppressLint("UseKtx") // The checked commit result is part of the storage switch.
    suspend fun change(tree: Uri?) {
        if (usesImmutable) {
            connection?.join()
            queue.await(queue.state.value.latest)
            val library = checkNotNull(format3)
            // Once external permission can change, every retained generation must be local.
            withContext(Dispatchers.IO) { library.store.allRoots().forEach { root ->
                library.store.closure(root).forEach { id -> library.store.openObject(id).use { input ->
                    val buffer = ByteArray(8192); while (input.read(buffer) >= 0) currentCoroutineContext().ensureActive()
                } }
                library.store.verify(root)
            } }
            val preferences = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
            check(preferences.edit().putString("format3-tree", tree?.toString()).putString("format3-label", tree?.toString()).commit())
            format3Uri = tree; format3Transport = null; externalMode.value = tree != null
            if (tree != null) {
                write {}; check(awaitSync()) { AppMessages.text(R.string.wait_note) }
                initializeRemote(); check(connectionState.value.error == null) { checkNotNull(connectionState.value.error) }
            }
            return
        }
        if (tree == uri) { retryPending(); check(awaitSync()) { AppMessages.text(R.string.wait_note) }; return }
        val set = working
        if (set != null) {
            connection?.join()
            // Even an explicit recovery to internal storage must wait for a running upload.
            // A failed upload is allowed here; its unsaved local work is retained below.
            queue.await(queue.state.value.latest)
            if (tree != null) check(awaitSync()) { AppMessages.text(R.string.wait_note) }
            // A deliberate switch to internal storage must first acquire every remaining body.
            synchronized(this) { recoverPartial(set); set.materializeAll() }
        }
        if (set != null) {
            val json = JSONObject().put("from", uri.toString()).put("to", tree?.toString().orEmpty())
                .put("files", JSONObject(localFiles().mapValues { NotebookArchive.digest(it.value) }))
            NoteFiles.atomicWrite(switching, json.toString().toByteArray())
        }
        try { legacy.change(tree) }
        catch (error: Exception) { clearSwitch(); throw error }
        synchronized(this) {
            set?.forget(); working = null; archive = null; regions = null; publishedBodies.clear(); externalMode.value = false
            blockedByRemoteChange = false; connectionState.value = SaveState()
            clearSwitch()
        }
        if (tree != null) { needsLegacyRestore = false; migrate() }
    }

    @android.annotation.SuppressLint("UseKtx") // Do not discard the synchronous preference commit result.
    suspend fun migrateFormat3(checkpoint: () -> Unit = {}) = withContext(Dispatchers.IO) {
        check(!usesImmutable && active == null) { "Close the notebook before migrating" }
        check(awaitSync()) { AppMessages.text(R.string.wait_note) }
        working?.let { set -> synchronized(this@NotebookStorage) { recoverPartial(set); set.materializeAll() } }
        val all = synchronized(this@NotebookStorage) { localFiles() }
        val aliases = uri?.let { ConflictDocuments(context.contentResolver, File(directory, "format3-migration-aliases")).scan(it) }.orEmpty()
        check(NoteConflicts(File(directory, "notes"), File(directory, "conflicts")).scan(aliases).isEmpty()) { "Resolve notebook conflicts before migrating; all original data has been retained" }
        val sources = all.filterKeys { it.endsWith(".misq") }.values.map { MisqFile(it) }
        val metadata = listOf("library.hwp", "palette.hwp", "tools.hwp", "folders.hwp", "presets.hwp").associateWith { File(directory, it) }
        val selected = uri
        // Save the future selection before activation; old-format code ignores these keys.
        check(context.getSharedPreferences("note-storage", Context.MODE_PRIVATE).edit()
            .putString("format3-tree", selected?.toString()).putString("format3-label", label).commit())
        val store = Format3Migration(directory).migrate(sources, metadata, checkpoint) { source ->
            val refs = source.index().manifest.strokes
            source.readStrokes(refs) { ref, bytes -> checkpoint(); ref.storageId to NoteRepository.decodeStroke(ref.storageId, bytes).placement }.toMap()
        }
        synchronized(this@NotebookStorage) {
            format3Uri = selected
            format3 = Format3Library(store, File(directory, "format3-working/notes").also { it.mkdirs() })
            externalMode.value = selected != null
            working = null; archive = null; regions = null
        }
        write {}
    }
    private fun format3Settings(): ByteArray {
        val library = LibraryPreferencesFile(File(directory, "library.hwp")).read()
        val folders = LibraryJson.envelope(NotebookFolders.encode(library))
        val file = File(directory, ".format3-presets.tmp")
        try {
            NotebookPresets.write(file, library.paper, SavedColors(File(directory, "palette.hwp")).read(), ToolPreferencesFile(File(directory, "tools.hwp")).read())
            return org.json.JSONObject().put("folders.hwp", java.util.Base64.getEncoder().encodeToString(folders))
                .put("presets.hwp", java.util.Base64.getEncoder().encodeToString(file.readBytes())).toString().toByteArray(Charsets.UTF_8)
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
    private fun applyFormat3Settings(bytes: ByteArray) {
        val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
        require(json.keys().asSequence().toSet() == setOf("folders.hwp", "presets.hwp"))
        val file = LibraryPreferencesFile(File(directory, "library.hwp")); val current = file.read()
        val folders = NotebookFolders.decode(LibraryJson.unwrap(java.util.Base64.getDecoder().decode(json.getString("folders.hwp"))), current)
        val presets = NotebookPresets.decode(LibraryJson.unwrap(java.util.Base64.getDecoder().decode(json.getString("presets.hwp"))))
        file.write(folders.copy(paper = presets.paper))
        SavedColors(File(directory, "palette.hwp")).write(presets.palettes)
        val tools = ToolPreferencesFile(File(directory, "tools.hwp")); tools.write(presets.applyTo(tools.read()))
    }
    private fun syncFormat3(incoming: Boolean) {
        val library: Format3Library; val store: ImmutableNoteStore; val roots: List<ImmutableNoteStore.Root>; val captured: Long
        synchronized(this) {
            library = checkNotNull(format3); store = library.store
            val settings = format3Settings()
            val previous = store.current(Format3Library.SETTINGS_ID)
            if (previous == null || store.thumbnail(previous)?.contentEquals(settings) != true) {
                val base = previous ?: store.create(NoteInfo(Format3Library.SETTINGS_ID, "Settings", 0, 0))
                if (previous == null) store.install(base, validate = false)
                store.install(store.saveThumbnail(base, settings), validate = false)
            }
            roots = store.allRoots(); captured = version
        }
        val tree = uri ?: return
        val remote = PackedDocumentFiles(context.contentResolver, tree, "misq-v3")
        val transport = format3Transport ?: ImmutableNoteTransport(store, remote).also { format3Transport = it }
        roots.forEach { transport.publish(it) }
        if (incoming && active == null) {
            val noteIds = transport.noteIds()
            val received = noteIds.associateWith(transport::heads)
            for ((id, heads) in received) {
                if (heads.size == 1) {
                    val next = heads.single()
                    synchronized(this) {
                        if (version == captured && store.current(id)?.id == roots.firstOrNull { it.info.id == id }?.id) {
                            if (active != null) return@synchronized
                            if (id == Format3Library.SETTINGS_ID) applyFormat3Settings(checkNotNull(store.thumbnail(next)))
                            store.install(next, validate = false)
                        }
                    }
                } else if (heads.size > 1) {
                    val base = heads.minBy { it.id }
                    val same = if (id == Format3Library.SETTINGS_ID) heads.all { store.thumbnail(it)!!.contentEquals(store.thumbnail(base)!!) }
                        else heads.all { store.equivalent(base, it) }
                    if (same) {
                        synchronized(this) { if (version == captured) write { store.install(store.resolve(base, heads.map { it.id }), validate = false) } }
                        continue
                    }
                    // Preserve the active branch and expose the alternatives to the existing review UI.
                    if (id == Format3Library.SETTINGS_ID) {
                        val current = store.current(id)?.id
                        settingsVersions = heads.map { head ->
                            val json = JSONObject(String(checkNotNull(store.thumbnail(head)), Charsets.UTF_8))
                            val value = NotebookFolders.decode(LibraryJson.unwrap(java.util.Base64.getDecoder().decode(json.getString("folders.hwp"))))
                            SettingsVersion(head.id, head.id == current, value.folders.size, value.locations.size)
                        }.sortedWith(compareByDescending<SettingsVersion> { it.local }.thenBy { it.id })
                        throw java.io.IOException(AppMessages.text(R.string.storage_settings_conflict))
                    }
                    val folder = File(directory, "format3-working/notes").also { it.mkdirs() }
                    val current = store.current(id)
                    if (current != null) library.export(id, File(folder, "$id.misq"))
                    heads.filter { it.id != current?.id }.forEach { branch ->
                        val alias = File(folder, "branch-${branch.id.removePrefix("o-")}.misq")
                        if (!alias.exists()) store.export(branch, alias)
                    }
                    blockedByRemoteChange = true
                }
            }
        }
        synchronized(this) { if (captured == version) pending.delete() }
    }
    @Synchronized fun resolveSettingsVersion(id: String) {
        val library = checkNotNull(format3); val store = library.store
        require(settingsVersions.any { it.id == id })
        check(store.current(Format3Library.SETTINGS_ID)?.id == settingsVersions.firstOrNull { it.local }?.id) { AppMessages.text(R.string.conflict_version_changed) }
        val selected = store.root(id); val resolved = store.resolve(selected, settingsVersions.map { it.id })
        write { applyFormat3Settings(checkNotNull(store.thumbnail(selected))); store.install(resolved, validate = false) }
        settingsVersions = emptyList(); connectionState.value = SaveState()
    }
}
