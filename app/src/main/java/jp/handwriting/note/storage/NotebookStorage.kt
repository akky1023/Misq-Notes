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

/** External revisions are authoritative; local .misq files hold durable, temporary editing work. */
internal class NotebookStorage(private val context: Context, private val directory: File, private val scope: CoroutineScope) {
    private val legacy = LegacyNotebookStorage(context, directory, scope)
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
    private val queue = SaveQueue(scope, batchMillis = 750L) { _: Unit ->
        beforeExternalSave()
        withContext(Dispatchers.IO) { saveExternal() }
    }
    val syncState = combine(legacy.syncState, queue.state, connectionState, externalMode) { old, save, connect, external ->
        if (!external) old else save.copy(saving = save.saving || connect.saving,
            error = connect.error ?: save.error)
    }
    val uri: Uri? get() = legacy.uri
    val label: String get() = legacy.label
    var recovered = false
        private set
    var blockedByRemoteChange = false
        private set

    fun initialize(): Boolean {
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
        working?.takeIf { it.available }?.let { set -> recoverPartial(set) }
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
        val set: ExternalWorkingSet
        val target: NotebookArchive
        val captured: ArchiveSnapshot
        val capturedVersion: Long
        synchronized(this) {
            if (!pending.exists()) return
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
        val set = working ?: return legacy.write(block)
        check(set.available) { AppMessages.text(R.string.storage_unavailable) }
        if (!pending.exists()) java.io.FileOutputStream(pending).use { it.write(1); it.fd.sync() }
        version++
        val result = block()
        queue.request(Unit)
        return result
    }
    fun noteFiles(): NoteFiles = NoteFiles(File(directory, "notes")).also { files ->
        files.backing = object : NoteBacking {
            override fun contains(id: String) = synchronized(this@NotebookStorage) { working?.contains(id) == true }
            override fun ensure(id: String) = synchronized(this@NotebookStorage) { working?.ensure(id); Unit }
            override fun summaries() = synchronized(this@NotebookStorage) { working?.summaries().orEmpty() }
            override fun deleted(id: String) = synchronized(this@NotebookStorage) { working?.deleted(id); Unit }
            override fun preview(id: String) = synchronized(this@NotebookStorage) { checkNotNull(working).preview(id) }
            override fun spatial(id: String) = synchronized(this@NotebookStorage) { working?.spatial(id) }
        }
    }
    @Synchronized fun contains(id: String) = working?.contains(id) == true || File(directory, "notes/$id.misq").exists()
    @Synchronized fun ensure(id: String) { working?.ensure(id) }
    fun keepOpen(id: String?) { active = id }
    /** The repository serializes this with reads/previews; provider writes own immutable snapshots. */
    @Synchronized fun releaseWorkingCopies() { working?.evict(active) }
    @Synchronized fun startBackground() {
        if (working == null) { legacy.startBackground(); return }
        if (archive != null || connection?.isActive == true) return
        connection = scope.launch(Dispatchers.IO) { runCatching { initializeRemote() } }
    }
    suspend fun awaitSync(): Boolean {
        if (working == null) return legacy.awaitSync()
        connection?.join()
        return connectionState.value.error == null && queue.flush() && !pending.exists()
    }
    @Synchronized fun retryPending() {
        if (working == null) { legacy.retryPending(); return }
        if (archive == null) startBackground() else if (pending.exists()) queue.request(Unit)
    }
    suspend fun change(tree: Uri?) {
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
}
