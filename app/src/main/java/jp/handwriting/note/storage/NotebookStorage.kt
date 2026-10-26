package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Local durability releases the editor; external revisions are synchronized in the background. */
internal class NotebookStorage(private val context: Context, private val directory: File, private val scope: CoroutineScope) {
    private val choice = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
    private val pending = File(directory, ".notes-pending")
    private val libraryFile = LibraryPreferencesFile(File(directory, "library.hwp"))
    private var archive: NotebookArchive? = null
    private val checkpoint = File(directory, ".notes-sync-base")
    private val mergeBase = File(directory, ".notes-merge-base")
    private val syncs = ArchiveSync(scope, pending, this, ::snapshotFiles, committed = { snapshot ->
        NoteFiles.atomicWrite(checkpoint, NotebookArchive.encode(snapshot.mapValues { it.value.hash }))
        rememberBase(snapshot)
    }, conflicted = { reconnectAfterConflict() })
    private val connectionState = MutableStateFlow(SaveState())
    val syncState = combine(syncs.state, connectionState) { sync, connection ->
        sync.copy(saving = sync.saving || connection.saving, error = connection.error ?: sync.error)
    }
    private var connecting: Job? = null
    private var epoch = 0L
    private var localVersion = 0L
    var blockedByRemoteChange = false
        private set
    suspend fun awaitSync(): Boolean {
        startBackground(); connecting?.join()
        return connectionState.value.error == null && syncs.await()
    }
    private fun scheduleSync() {
        syncs.request()
    }
    @Synchronized private fun reconnectAfterConflict() {
        archive = null; syncs.attach(null); startBackground()
    }
    private fun rememberBase(snapshot: Map<String, ArchiveBlob>) {
        val stage = File(directory, ".notes-merge-base-next")
        stage.deleteRecursively(); stage.mkdirs()
        snapshot.forEach { (path, blob) -> File(stage, path).also { it.parentFile!!.mkdirs(); it.writeBytes(blob.bytes) } }
        // Losing a merge baseline only disables automatic merge; it never loses notebook content.
        mergeBase.deleteRecursively(); check(stage.renameTo(mergeBase))
    }
    var recovered = false
        private set
    var uri: Uri? = choice.getString("tree", null)?.let(Uri::parse)
        private set
    val label: String get() = if (uri == null) AppMessages.text(R.string.storage_default) else choice.getString("label", null) ?: AppMessages.text(R.string.storage_selected)
    /** Local-only recovery. Returns true only when no local library exists to display. */
    @Synchronized fun initialize(): Boolean {
        if (context.packageName == "net.neomenia.misqnotes") PreMigrationBackup.ensure(context, "before-misq-v2.zip")
        val backup = File(directory, ".notes-before-import")
        if (backup.exists()) {
            File(directory, "notes").deleteRecursively()
            check(backup.renameTo(File(directory, "notes"))) { AppMessages.text(R.string.storage_restore_failed) }
        }
        runCatching { applyIncoming() }.onFailure { error ->
            blockedByRemoteChange = true
            connectionState.value = SaveState(error = error.localizedMessage)
        }
        val hasLocal = File(directory, "library.hwp").exists() ||
            File(directory, "notes").listFiles().orEmpty().any { it.extension == "misq" || it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
        if (uri != null && hasLocal && !checkpoint.exists() && !pending.exists()) {
            NoteFiles.atomicWrite(checkpoint, NotebookArchive.encode(snapshotFiles().mapValues { NotebookArchive.digest(it.value.readBytes()) }))
        }
        return uri != null && !hasLocal
    }
    @Synchronized fun startBackground() {
        if (uri == null || archive != null || connecting?.isActive == true) return
        val token = epoch
        connectionState.value = SaveState(saving = true)
        connecting = scope.launch(Dispatchers.IO) {
            try {
                connect(token, false)
                synchronized(this@NotebookStorage) { if (epoch == token) connectionState.value = SaveState() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                android.util.Log.w("MisqStorage", "External connection failed", error)
                synchronized(this@NotebookStorage) {
                    if (epoch == token) connectionState.value = SaveState(error = error.localizedMessage)
                }
            }
        }
    }
    suspend fun initializeRemote() = withContext(Dispatchers.IO) { connect(epoch, true) }
    private data class Opened(val target: NotebookArchive, val source: NotebookArchive, val revision: NotebookArchive.Revision?, val migrating: Boolean)
    private fun open(tree: Uri, snapshot: Map<String, ArchiveBlob>): Opened {
        val target = NotebookArchive(MisqArchiveDirectory(MisqDocumentFiles(context.contentResolver, tree)))
        val current = target.latestCaptured(snapshot)
        if (current != null) return Opened(target, target, current, false)
        val packedSource = NotebookArchive(PackedArchiveDirectory(PackedDocumentFiles(context.contentResolver, tree)))
        val packed = packedSource.latestCaptured(snapshot)
        if (packed != null) {
            val expected = packed.name
            target.beforeFirstCommit = { if (packedSource.latestCaptured(snapshot)?.name != expected) throw ArchiveConflictException() }
            return Opened(target, packedSource, packed, true)
        }
        val documents = DocumentArchiveDirectory(context.contentResolver, tree)
        var source = NotebookArchive(documents)
        var revision = source.latestCaptured(snapshot)
        if (revision == null) documents.legacyArchive()?.let { legacy ->
            source = legacy; revision = legacy.latestCaptured(snapshot)
        }
        if (revision != null) {
            val expected = revision!!.name
            val legacy = source
            target.beforeFirstCommit = { if (legacy.latestCaptured(snapshot)?.name != expected) throw ArchiveConflictException() }
        }
        return Opened(target, source, revision, true)
    }
    private fun connect(token: Long, foreground: Boolean) {
        val tree: Uri
        val version: Long
        val local: Map<String, ArchiveBlob>
        val base: Map<String, String>?
        synchronized(this) {
            tree = uri ?: return
            version = localVersion
            local = NotebookArchive(object : ArchiveDirectory {
                override fun names() = emptySet<String>()
                override fun read(name: String): ByteArray = error("Local capture only")
                override fun write(name: String, bytes: ByteArray) = Unit
                override fun delete(name: String) = Unit
            }).capture(snapshotFiles())
            base = runCatching { NotebookArchive.decode(NoteFiles.readEnvelope(checkpoint)) }.getOrNull()
        }
        val opened = open(tree, local)
        val hashes = local.mapValues { it.value.hash }
        val remote = opened.revision
        val repairingFromKnownLocal = opened.source.recovered && base == hashes
        var matches = remote == null || remote.files == hashes || (base != null && remote.files == base) || repairingFromKnownLocal
        if (!matches && opened.migrating && remote != null) {
            val compare = File(directory, ".notes-migration-check-${UUID.randomUUID()}")
            try {
                val legacy = File(compare, "legacy")
                opened.source.restore(remote, legacy)
                matches = MisqMigration.sameContent(local, legacy, File(compare, "local"))
            } finally { compare.deleteRecursively() }
        }
        if (!matches && !foreground) {
            val incoming = File(directory, ".notes-incoming-${UUID.randomUUID()}")
            try {
                opened.source.restore(checkNotNull(remote), incoming)
                if (remote.files.keys.all { it in listOf("folders.hwp", "presets.hwp") || it.endsWith(".misq") } &&
                    hashes.keys.all { it in listOf("folders.hwp", "presets.hwp") || it.endsWith(".misq") } && (pending.exists() || base != hashes)) {
                    val original = mergeBase.walkTopDown().filter { it.isFile }.associate { it.relativeTo(mergeBase).invariantSeparatorsPath to it.readBytes() }
                    val external = remote.files.mapValues { (path, _) -> File(incoming, path).readBytes() }
                    val work = File(directory, ".notes-merge-work-${UUID.randomUUID()}")
                    try {
                        val merged = MisqMerge.merge(original, local.mapValues { it.value.bytes }, external, work)
                        synchronized(opened.target) { opened.target.commitSnapshot(merged) }
                        remote.files.keys.filter { it !in merged }.forEach { File(incoming, it).delete() }
                        merged.forEach { (path, blob) -> File(incoming, path).also { it.parentFile!!.mkdirs(); it.writeBytes(blob.bytes) } }
                        NoteFiles.atomicWrite(File(incoming, "remote.hna"), NotebookArchive.encode(merged.mapValues { it.value.hash }))
                        NoteFiles.atomicWrite(File(incoming, "base.hna"), NotebookArchive.encode(hashes))
                        File(incoming, "tree.txt").writeText(tree.toString())
                        synchronized(this) {
                            if (epoch != token || localVersion != version) throw ArchiveConflictException()
                            val previous = File(directory, ".notes-incoming")
                            if (previous.exists()) check(previous.renameTo(File(directory, ".notes-incoming-retained-${UUID.randomUUID()}")))
                            check(incoming.renameTo(previous))
                            pending.delete(); blockedByRemoteChange = true
                        }
                        throw java.io.IOException(AppMessages.text(R.string.storage_incoming))
                    } finally { work.deleteRecursively() }
                }
                NoteFiles.atomicWrite(File(incoming, "remote.hna"), NotebookArchive.encode(remote.files))
                NoteFiles.atomicWrite(File(incoming, "base.hna"), NotebookArchive.encode(hashes))
                File(incoming, "tree.txt").writeText(tree.toString())
                synchronized(this) {
                    if (epoch != token) return
                    blockedByRemoteChange = true
                    if (localVersion == version && !pending.exists() && base == hashes && !File(directory, ".notes-incoming").exists()) {
                        check(incoming.renameTo(File(directory, ".notes-incoming")))
                        throw java.io.IOException(AppMessages.text(R.string.storage_incoming))
                    }
                }
                throw ArchiveConflictException()
            } catch (error: Exception) {
                synchronized(this) { if (epoch == token) blockedByRemoteChange = true }
                throw error
            } finally { /* Incoming/conflict copies intentionally survive restart. */ }
        }
        synchronized(this) {
            if (epoch != token) return
            if (foreground && remote != null && remote.files != hashes) restore(opened.source, remote)
            archive = opened.target; syncs.attach(opened.target)
            recovered = opened.source.recovered
            blockedByRemoteChange = false
            if (remote != null && !opened.migrating) NoteFiles.atomicWrite(checkpoint, NotebookArchive.encode(remote.files))
            if (remote?.files == hashes && !mergeBase.exists()) rememberBase(local)
            if (opened.migrating || opened.source.recovered || pending.exists() || localVersion != version || remote?.files != hashes) {
                syncs.dirty(true); scheduleSync()
            }
        }
    }
    private fun applyIncoming() {
        val incoming = File(directory, ".notes-incoming")
        if (!incoming.isDirectory || pending.exists() || File(incoming, "tree.txt").readText() != uri?.toString()) return
        val base = NotebookArchive.decode(NoteFiles.readEnvelope(File(incoming, "base.hna")))
        val current = snapshotFiles().mapValues { NotebookArchive.digest(it.value.readBytes()) }
        if (current != base) { blockedByRemoteChange = true; return }
        val remote = NotebookArchive.decode(NoteFiles.readEnvelope(File(incoming, "remote.hna")))
        val revisionName = "revision-00000000000000000001-${UUID.randomUUID()}"
        val records = remote.entries.associate { (path, hash) -> "blob-$hash" to File(incoming, path) }
        val reader = NotebookArchive(object : ArchiveDirectory {
            override fun names() = records.keys + revisionName
            override fun read(name: String) = if (name == revisionName) NotebookArchive.encode(remote) else records.getValue(name).readBytes()
            override fun write(name: String, bytes: ByteArray): Unit = error("Incoming copy is read-only")
            override fun delete(name: String): Unit = error("Incoming copy is read-only")
        })
        restore(reader, checkNotNull(reader.latest()))
        NoteFiles.atomicWrite(checkpoint, NotebookArchive.encode(remote))
        rememberBase(remote.mapValues { (path, _) -> ArchiveBlob(File(incoming, path).readBytes()) })
        incoming.deleteRecursively()
    }
    @Synchronized fun <T> write(block: () -> T): T {
        localVersion++
        syncs.dirty(uri != null)
        val result = block()
        scheduleSync()
        return result
    }
    @Synchronized fun retryPending() {
        if (syncs.state.value.error != null) { archive = null; syncs.attach(null) }
        if (archive == null) startBackground() else if (pending.exists()) scheduleSync()
    }
    private fun snapshotFiles(): Map<String, File> {
        val folders = File(directory, "folders.hwp")
        NotebookFolders.write(folders, libraryFile.read())
        val files = mutableMapOf("folders.hwp" to folders)
        val presets = File(directory, "presets.hwp")
        NotebookPresets.write(presets, libraryFile.read().paper, SavedColors(File(directory, "palette.hwp")).read(), ToolPreferencesFile(File(directory, "tools.hwp")).read())
        files["presets.hwp"] = presets
        val notes = NoteFiles(File(directory, "notes"))
        notes.list().forEach { summary ->
            check(summary.problem == null) { AppMessages.text(R.string.storage_unreadable_notes) }
            val document = notes.document(summary.id)
            if (!document.file.exists()) {
                val manifest = notes.read(summary.id).manifest
                notes.save(manifest) { notes.stroke(summary.id, it) }
            }
            files["notes/${summary.id}.misq"] = document.file
        }
        return files
    }
    private fun commit() {
        val target = archive ?: throw java.io.IOException(AppMessages.text(R.string.storage_unavailable))
        pending.writeText("pending")
        val snapshot = target.capture(snapshotFiles())
        synchronized(target) { target.commitSnapshot(snapshot) }
        NoteFiles.atomicWrite(checkpoint, NotebookArchive.encode(snapshot.mapValues { it.value.hash }))
        pending.delete()
    }
    @android.annotation.SuppressLint("UseKtx") // The boolean result of synchronous commit is required before switching.
    @Synchronized fun change(tree: Uri?) {
        if (tree == uri) { if (pending.exists()) commit(); return }
        if (pending.exists() && tree != null && !blockedByRemoteChange) commit()
        val local = snapshotFiles()
        val captured = local.mapValues { ArchiveBlob(it.value.readBytes()) }
        val opened = tree?.let { open(it, captured) }
        val next = opened?.target
        val source = opened?.source
        val revision = opened?.revision
        if (revision != null) {
            val selectedRevision = revision
            val empty = NoteFiles(File(directory, "notes")).list().isEmpty() && libraryFile.read().folders.isEmpty()
            if (empty) restore(checkNotNull(source), selectedRevision)
            else check(selectedRevision.files.filterKeys { it != "presets.hwp" } == snapshotFiles()
                .filterKeys { it in selectedRevision.files && it != "presets.hwp" }.mapValues { NotebookArchive.digest(it.value.readBytes()) }) {
                AppMessages.text(R.string.storage_different_data)
            }
        }
        val name = tree?.let { value ->
            val document = DocumentsContract.buildDocumentUriUsingTree(value, DocumentsContract.getTreeDocumentId(value))
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
        check(choice.edit().putString("tree", tree?.toString()).putString("label", name).commit()) { AppMessages.text(R.string.storage_choice_save_failed) }
        val previousTree = uri
        epoch++; uri = tree; archive = next; syncs.attach(next); blockedByRemoteChange = false
        connectionState.value = SaveState()
        checkpoint.delete()
        if (tree == null) pending.delete()
        else { syncs.dirty(); scheduleSync() }
        previousTree?.let { previous -> runCatching {
            context.contentResolver.releasePersistableUriPermission(previous,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } }
    }
    private fun restore(source: NotebookArchive, revision: NotebookArchive.Revision) {
        val stage = File(directory, ".notes-import-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val notes = File(directory, "notes")
        val backup = File(directory, ".notes-before-import")
        val settings = libraryFile.read()
        val palettesFile = SavedColors(File(directory, "palette.hwp"))
        val toolsFile = ToolPreferencesFile(File(directory, "tools.hwp"))
        val palettes = palettesFile.read()
        val tools = toolsFile.read()
        try {
            source.restore(revision, stage)
            val presets = File(stage, "presets.hwp").takeIf { it.exists() }?.let(NotebookPresets::read)
            val imported = NotebookFolders.read(File(stage, "folders.hwp"), settings).let { if (presets != null) it.copy(paper = presets.paper) else it }
            val stagedNotes = File(stage, "notes").also { it.mkdirs() }
            check(NoteFiles(stagedNotes).list().none { it.problem != null }) { AppMessages.text(R.string.storage_unreadable_notes) }
            check(!backup.exists()) { AppMessages.text(R.string.storage_change_incomplete) }
            if (notes.exists()) check(notes.renameTo(backup))
            try {
                check(stagedNotes.renameTo(notes))
                libraryFile.write(imported)
                if (presets != null) { palettesFile.write(presets.palettes); toolsFile.write(presets.applyTo(tools)) }
            } catch (e: Exception) {
                notes.deleteRecursively()
                if (backup.exists()) check(backup.renameTo(notes))
                libraryFile.write(settings)
                palettesFile.write(palettes); toolsFile.write(tools)
                throw e
            }
            backup.deleteRecursively()
            recovered = source.recovered
        } finally { stage.deleteRecursively() }
    }
}
