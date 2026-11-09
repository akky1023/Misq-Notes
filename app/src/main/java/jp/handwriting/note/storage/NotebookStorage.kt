package jp.handwriting.note.storage

import android.content.Context
import android.net.Uri
import java.io.File
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject

internal data class SettingsVersion(val id: String, val local: Boolean, val folders: Int, val locations: Int)

/** Daily persistence uses Format 3 only. Standalone MISQ0002 files are used only for exchange and conflict review. */
internal class NotebookStorage(private val context: Context, private val directory: File, private val scope: CoroutineScope) {
    private var format3: Format3Library? = null
    private var format3Uri: Uri? = null
    private var format3Transport: ImmutableNoteTransport? = null
    internal val usesImmutable get() = format3 != null
    @Synchronized internal fun deletedNoteIds(): Set<String> = format3?.store?.let { store ->
        store.ids().filter { id -> store.current(id)?.deleted == true }.toSet()
    }.orEmpty()
    @Volatile var settingsVersions: List<SettingsVersion> = emptyList()
        private set
    private val pending = File(directory, ".notes-pending")
    @Volatile private var active: String? = null
    private var version = 0L
    private var connection: Job? = null
    private val externalMode = MutableStateFlow(false)
    private val connectionState = MutableStateFlow(SaveState())
    var beforeExternalSave: suspend () -> Unit = {}
    var onRemoteUpdate: suspend () -> Unit = {}
    @Volatile var localSettingsEdited = false
        private set
    @Volatile var settingsRevision = 0L
        private set
    @Synchronized fun settingsEdited() { localSettingsEdited = true; settingsRevision++; version++ }
    @Synchronized fun settingsSaved() { localSettingsEdited = false }
    private val queue = SaveQueue(scope, batchMillis = 750L) { _: Unit ->
        beforeExternalSave()
        withContext(Dispatchers.IO) { syncFormat3(incoming = false) }
    }
    val syncState = combine(queue.state, connectionState, externalMode) { save, connect, external ->
        if (!external) SaveState() else save.copy(saving = save.saving || connect.saving, error = connect.error ?: save.error)
    }
    val uri: Uri? get() = format3Uri
    val label: String get() = if (uri == null) AppMessages.text(R.string.storage_default)
        else context.getSharedPreferences("note-storage", Context.MODE_PRIVATE).getString("format3-label", null) ?: AppMessages.text(R.string.storage_selected)
    val recovered get() = format3?.store?.let { store -> store.ids().any(store::recovered) } == true
    var blockedByRemoteChange = false
        private set

    fun initialize(): Boolean {
        val storage = Format3Storage(directory)
        val preferences = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
        if (!storage.active) {
            // A missing marker must never turn an existing library into an empty one.
            val legacy = preferences.getString("tree", null) != null ||
                listOf(".notes-external-state", ".notes-sync-base", ".notes-pending", ".notes-external-switch").any { File(directory, it).exists() } ||
                File(directory, "notes").listFiles().orEmpty().any {
                    (it.isFile && it.extension == "misq") || (it.isDirectory && File(it, "manifest.hwn").isFile)
                } || directory.listFiles().orEmpty().any { it.isDirectory && it.name.matches(Regex("format3-[a-f0-9-]{36}")) }
            check(!legacy) { AppMessages.text(R.string.storage_legacy_unsupported) }
            storage.create()
        }
        format3Uri = preferences.getString("format3-tree", null)?.let(Uri::parse)
        val store = storage.open()
        format3 = Format3Library(store, File(directory, "format3-working/notes").also { check(it.isDirectory || it.mkdirs()) })
        externalMode.value = uri != null
        return uri != null
    }
    suspend fun initializeRemote() {
        var failure: String? = null
        try {
            withContext(Dispatchers.IO) { syncFormat3(incoming = true) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = error.localizedMessage }
        try { onRemoteUpdate() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = failure ?: error.localizedMessage }
        connectionState.value = SaveState(error = failure)
    }
    @Synchronized fun <T> write(block: () -> T): T {
        checkNotNull(format3) { AppMessages.text(R.string.storage_legacy_unsupported) }
        if (uri != null && !pending.exists()) java.io.FileOutputStream(pending).use { it.write(1); it.fd.sync() }
        version++
        val result = block()
        if (uri != null) queue.request(Unit)
        return result
    }
    fun noteFiles(): NoteFiles {
        val library = checkNotNull(format3) { AppMessages.text(R.string.storage_legacy_unsupported) }
        return NoteFiles(File(directory, "format3-working/notes"), library)
    }
    @Synchronized fun contains(id: String) = format3?.contains(id) == true
    @Synchronized fun ensure(id: String) {
        checkNotNull(format3).export(id, File(directory, "format3-working/notes/$id.misq"))
    }
    @Synchronized fun keepOpen(id: String?) {
        active = id
        if (id == null && uri != null) retryPending()
    }
    @Synchronized fun startBackground() {
        // Local heads are usable immediately; remote discovery must not block the list.
        retryPending()
    }
    suspend fun awaitSync(): Boolean {
        if (uri == null) return true
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
        if (uri != null && connection?.isActive != true) {
            connectionState.value = SaveState(saving = true)
            val generation = queue.state.value.latest
            connection = scope.launch(Dispatchers.IO) { queue.await(generation); initializeRemote() }
        }
    }
    @android.annotation.SuppressLint("UseKtx")
    suspend fun change(tree: Uri?) {
        checkNotNull(format3) { AppMessages.text(R.string.storage_legacy_unsupported) }
        if (tree != null) {
            val modern = PackedDocumentFiles(context.contentResolver, tree, "misq-v3").names()
            val hasModernNotes = modern.any { it.startsWith("commit-") && !it.startsWith("commit-${Format3Library.SETTINGS_ID}-") }
            val oldNotes = PackedDocumentFiles(context.contentResolver, tree, "inkdata").names()
            val oldPacked = PackedDocumentFiles(context.contentResolver, tree, "packed-v1").names()
            check(hasModernNotes || (oldNotes.none { it.endsWith(".misq") } && oldPacked.isEmpty())) {
                AppMessages.text(R.string.storage_legacy_unsupported)
            }
        }
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
        transport.withInventorySnapshot {
            roots.forEach { transport.publish(it) }
            if (incoming && active == null) {
                val noteIds = transport.noteIds()
                val received = noteIds.associateWith(transport::heads)
                for ((id, heads) in received) {
                    // UI setting changes may still be waiting in their save queues.
                    if (id == Format3Library.SETTINGS_ID && localSettingsEdited) continue
                    if (heads.size == 1) {
                        val next = heads.single()
                        synchronized(this) {
                            if (version == captured && store.current(id)?.id == roots.firstOrNull { it.info.id == id }?.id) {
                                if (active != null || (id == Format3Library.SETTINGS_ID && localSettingsEdited)) return@synchronized
                                if (id == Format3Library.SETTINGS_ID) applyFormat3Settings(checkNotNull(store.thumbnail(next)))
                                store.install(next, validate = false)
                            }
                        }
                    } else if (heads.size > 1) {
                        val base = heads.minBy { it.id }
                        val same = if (id == Format3Library.SETTINGS_ID) {
                            heads.all { store.thumbnail(it)!!.contentEquals(store.thumbnail(base)!!) }
                        } else {
                            heads.all { store.equivalent(base, it) }
                        }
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
        }
        synchronized(this) { if (captured == version) pending.delete() }
    }
    @Synchronized fun resolveSettingsVersion(id: String) {
        val library = checkNotNull(format3); val store = library.store
        require(settingsVersions.any { it.id == id })
        check(store.current(Format3Library.SETTINGS_ID)?.id == settingsVersions.firstOrNull { it.local }?.id) { AppMessages.text(R.string.conflict_version_changed) }
        val selected = store.root(id)
        val resolved = store.resolve(selected, settingsVersions.map { it.id })
        write {
            applyFormat3Settings(checkNotNull(store.thumbnail(selected)))
            store.install(resolved, validate = false)
        }
        settingsVersions = emptyList()
        connectionState.value = SaveState()
    }
}
