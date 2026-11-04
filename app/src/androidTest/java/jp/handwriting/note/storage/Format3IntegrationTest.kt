package jp.handwriting.note.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.editor.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class Format3IntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = Uri.parse("content://net.neomenia.misqnotes.probe.tests.working-set")
    private fun provider(method: String, extras: Bundle? = null) = context.contentResolver.call(provider, method, null, extras)
    private fun stroke(id: Long, y: Float): OrderedEdits.Entry<EditorStroke> {
        val brush = androidx.ink.brush.Brush.createWithColorIntArgb(androidx.ink.brush.StockBrushes.marker(), 0xff202b33.toInt(), 3f, .1f)
        val inputs = androidx.ink.strokes.MutableStrokeInputBatch()
            .add(androidx.ink.brush.InputToolType.STYLUS, 100f, y, 0, pressure = .5f)
            .add(androidx.ink.brush.InputToolType.STYLUS, 120f, y + 20, 20, pressure = .5f)
        return OrderedEdits.Entry(id, EditorStroke(androidx.ink.strokes.Stroke(brush, inputs)))
    }
    private suspend fun fixture(external: Boolean = false, test: suspend (File, NotebookStorage, NoteManifest, List<OrderedEdits.Entry<EditorStroke>>) -> Unit) {
        check(context.packageName == "net.neomenia.misqnotes.probe"); AppMessages.configure(context)
        val preferences = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
        val saved = preferences.all.toMap(); preferences.edit().clear().commit()
        val folder = File(context.cacheDir, "format3-test-${UUID.randomUUID()}").also { it.mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var tree: Uri? = null
        try {
            val storage = NotebookStorage(context, folder, scope); assertFalse(storage.initialize())
            val files = NoteFiles(File(folder, "notes")); val empty = files.create("three regions")
            val entries = listOf(stroke(1, 100f), stroke(2, 10000f), stroke(3, 20000f))
            val manifest = empty.copy(strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 4)
            NoteRepository(files).save(NoteSnapshot(manifest, entries))
            if (external) {
                @Suppress("DEPRECATION")
                val selected = provider("test-new-tree")!!.getParcelable<Uri>("tree")!!
                tree = selected
                context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                storage.change(selected); assertTrue(storage.awaitSync())
            }
            test(folder, storage, manifest, entries)
        } finally {
            provider("test-hold-reads", Bundle().apply { putBoolean("hold", false) })
            provider("test-hold-writes", Bundle().apply { putBoolean("hold", false) })
            provider("test-fail-reads", Bundle().apply { putBoolean("fail", false) })
            provider("test-fail-writes", Bundle().apply { putBoolean("fail", false) })
            scope.coroutineContext[Job]!!.cancelAndJoin()
            tree?.let { selected ->
                DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(selected, DocumentsContract.getTreeDocumentId(selected)))
                runCatching { context.contentResolver.releasePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            val edit = preferences.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") val strings = value as Set<String>; edit.putStringSet(key, strings) }
            } }
            edit.commit(); folder.deleteRecursively()
        }
    }
    @Test fun editorMigratesSavesReopensAndExportsWithoutDailyMisqReconstruction() = runBlocking {
        fixture { folder, storage, manifest, _ ->
            val before = NotebookArchive.digest(File(folder, "notes/${manifest.info.id}.misq"))
            val models = ViewModelStore(); val model = withContext(Dispatchers.Main) {
                ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also {
                    it.viewport.size(1000f, 1000f); it.configureStorage(context, folder, File(folder, "ui-cache")); it.start(folder)
                }
            }
            suspend fun waitFor(check: () -> Boolean) { withTimeout(30000) { while (!check()) delay(10) } }
            try {
                waitFor { !model.workspace.value.working }
                withContext(Dispatchers.Main) { model.migrateStorageFormat() }
                waitFor { !model.workspace.value.working && model.storageLocation.value.format3 }
                assertEquals(before, NotebookArchive.digest(File(folder, "notes/${manifest.info.id}.misq")))
                withContext(Dispatchers.Main) { model.openNote(manifest.info.id) }
                waitFor { model.workspace.value.current?.id == manifest.info.id && !model.ui.value.busy && !model.workspace.value.working && !model.ui.value.loadingRest }
                assertEquals(listOf(1L), model.edits.snapshot().map { it.id })
                withContext(Dispatchers.Main) { model.edits.addAll(listOf(stroke(99, 150f).value)) }
                waitFor { !model.workspace.value.saving }
                withContext(Dispatchers.Main) { model.edits.undo() }
                waitFor { !model.workspace.value.saving }
                assertEquals(listOf(1L), model.edits.snapshot().map { it.id })
                withContext(Dispatchers.Main) { model.edits.redo() }
                waitFor { !model.workspace.value.saving }
                withContext(Dispatchers.Main) { model.closeNote() }
                waitFor { model.workspace.value.current == null && !model.workspace.value.working }
                assertFalse(File(folder, "format3-working/notes/${manifest.info.id}.misq").exists())
                withContext(Dispatchers.Main) { model.openNote(manifest.info.id) }
                waitFor { model.workspace.value.current != null && !model.ui.value.busy && !model.workspace.value.working }
                assertEquals(listOf(1L, 4L), model.edits.snapshot().map { it.id })
                assertEquals(4, model.ui.value.strokes)
                val exported = withContext(Dispatchers.Main) { model.exportSnapshot(manifest.info.id) }
                assertEquals(listOf(1L, 2L, 3L, 4L), exported.entries.map { it.id })
                val standalone = File(folder, "standalone.tmp")
                withContext(Dispatchers.Main) { model.exportNotebookFile(manifest.info.id, standalone) }
                MisqFile(standalone).validate()
                assertEquals(listOf(1L, 2L, 3L, 4L), MisqFile(standalone).index().manifest.strokes.map { it.id })
                withContext(Dispatchers.Main) { model.closeNote() }
                waitFor { model.workspace.value.current == null && !model.workspace.value.working }
            } finally { val job = model.viewModelScope.coroutineContext[Job]!!; withContext(Dispatchers.Main) { models.clear() }; job.join() }
            assertFalse(storage.usesImmutable) // The separate, idle fixture instance was not mutated.
        }
    }
    @Test fun externalMigrationRetainsLegacyAndRoundTripsPartialEditsAcrossRestart() = runBlocking {
        fixture(external = true) { folder, storage, manifest, entries ->
            storage.migrateFormat3(); assertTrue(storage.usesImmutable); assertTrue(withTimeout(30000) { storage.awaitSync() })
            val old = File(folder, "notes/${manifest.info.id}.misq"); val expected = NotebookArchive.digest(old)
            val repository = NoteRepository(storage.noteFiles(), persist = { storage.write(it) })
            val visible = repository.loadVisible(manifest.info.id, 1f); assertTrue(visible.partial); assertEquals(listOf(1L), visible.entries.map { it.id })
            val moved = OrderedEdits.Entry(1L, entries[0].value.translated(0f, 6000f))
            val next = NoteManifest(manifest.info.copy(title = "edited in packs"), listOf(StrokeRef(1, moved.value.storageId)), 4)
            provider("test-fail-reads", Bundle().apply { putBoolean("fail", true) })
            repository.save(NoteSnapshot(next, listOf(moved), loadedIds = setOf(1), addedSince = 4))
            assertFalse(File(folder, "format3-working/notes/${manifest.info.id}.misq").exists())
            provider("test-fail-reads", Bundle().apply { putBoolean("fail", false) })
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
            val restarted = NotebookStorage(context, folder, restartedScope)
            assertTrue(restarted.initialize()); restarted.initializeRemote()
            val loaded = NoteRepository(restarted.noteFiles()).load(manifest.info.id)
            assertEquals("edited in packs", loaded.manifest.info.title)
            assertEquals(listOf(moved.value.storageId, entries[1].value.storageId, entries[2].value.storageId), loaded.entries.map { it.value.storageId })
            assertEquals(expected, NotebookArchive.digest(old))
            val exported = restarted.noteFiles().document(manifest.info.id)
            exported.validate(); assertEquals(3, exported.index().manifest.strokes.size)
            assertTrue(exported.index().events.size > manifest.strokes.size)
            } finally { restartedScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
    @Test fun cancelledMigrationLeavesLegacyUsableAndActivationAbsent() = runBlocking {
        fixture { folder, storage, manifest, _ ->
            val original = File(folder, "notes/${manifest.info.id}.misq"); val expected = NotebookArchive.digest(original)
            var checkpoints = 0
            try { storage.migrateFormat3 { if (++checkpoints == 6) throw CancellationException("simulated interruption") }; fail("must interrupt") }
            catch (_: CancellationException) { }
            assertFalse(Format3Migration(folder).active); assertFalse(storage.usesImmutable)
            assertEquals(expected, NotebookArchive.digest(original))
            assertEquals(3, NoteRepository(storage.noteFiles()).load(manifest.info.id).entries.size)
        }
    }
    @Test fun concurrentLibrarySettingsAreRetainedAndCanBeExplicitlyResolved() = runBlocking {
        fixture(external = true) { folder, storage, _, _ ->
            storage.migrateFormat3(); assertTrue(storage.awaitSync())
            val library = storage.noteFiles().format3!!; val store = library.store
            val base = store.current(Format3Library.SETTINGS_ID)!!
            val other = ImmutableNoteStore(File(folder, "another-device"))
            Format3Migration(folder).backup(store, other)
            val json = org.json.JSONObject(String(store.thumbnail(base)!!, Charsets.UTF_8))
            val original = NotebookFolders.decode(LibraryJson.unwrap(java.util.Base64.getDecoder().decode(json.getString("folders.hwp"))))
            val remoteFolders = original.added("from other device", null)
            json.put("folders.hwp", java.util.Base64.getEncoder().encodeToString(LibraryJson.envelope(NotebookFolders.encode(remoteFolders))))
            val foreign = other.saveThumbnail(other.root(base.id), json.toString().toByteArray(Charsets.UTF_8))
            other.install(foreign, validate = false)
            val transport = ImmutableNoteTransport(other, PackedDocumentFiles(context.contentResolver, storage.uri!!, "misq-v3"))
            transport.publish(foreign)
            storage.write {
                val preferences = LibraryPreferencesFile(File(folder, "library.hwp")); preferences.write(preferences.read().added("this device", null))
            }
            assertTrue(storage.awaitSync())
            storage.retryPending(); assertFalse(storage.awaitSync())
            assertEquals(2, storage.settingsVersions.size)
            storage.resolveSettingsVersion(foreign.id); assertTrue(storage.awaitSync())
            assertTrue(storage.settingsVersions.isEmpty())
            assertEquals(listOf("from other device"), LibraryPreferencesFile(File(folder, "library.hwp")).read().folders.map { it.name })
            assertEquals(1, transport.heads(Format3Library.SETTINGS_ID).size)
        }
    }
    @Test fun blockedPreviewDownloadDoesNotBlockDurableEditingOrPublishAnOlderPreview() = runBlocking {
        fixture(external = true) { folder, storage, manifest, entries ->
            storage.migrateFormat3(); assertTrue(storage.awaitSync())
            val files = storage.noteFiles(); val library = files.format3!!; val root = library.store.current(manifest.info.id)!!
            val firstPack = library.store.find(root, setOf(1)).getValue(1).body.objectId
            assertTrue(File(library.store.directory, "objects/$firstPack").delete())
            val repository = NoteRepository(files, persist = { storage.write(it) })
            provider("test-hold-reads", Bundle().apply { putBoolean("hold", true) })
            val preview = async(Dispatchers.IO) { repository.preview(manifest.info.id) }
            withTimeout(10000) { while (!provider("test-read-waiting")!!.getBoolean("waiting")) delay(10) }
            val changed = OrderedEdits.Entry(2L, entries[1].value.translated(0f, 1000f))
            withTimeout(2000) { withContext(Dispatchers.Default) {
                repository.save(NoteSnapshot(NoteManifest(manifest.info.copy(title = "saved while preview waits"), listOf(StrokeRef(2, changed.value.storageId)), 4), listOf(changed), loadedIds = setOf(2)))
            } }
            assertEquals("saved while preview waits", library.manifest(manifest.info.id).info.title)
            provider("test-hold-reads", Bundle().apply { putBoolean("hold", false) })
            preview.await(); assertTrue(storage.awaitSync())
            assertEquals(changed.value.storageId, library.manifest(manifest.info.id).strokes.first { it.id == 2L }.storageId)
            assertFalse(library.thumbnailCurrent(manifest.info.id))
        }
    }
    @Test fun divergentAppendIsResolvedThroughExistingConflictHandlingWithoutLosingEitherParent() = runBlocking {
        fixture(external = true) { folder, storage, manifest, _ ->
            storage.migrateFormat3(); assertTrue(storage.awaitSync())
            val files = storage.noteFiles(); val library = files.format3!!; val store = library.store
            val base = store.current(manifest.info.id)!!
            val other = ImmutableNoteStore(File(folder, "another-device")); Format3Migration(folder).backup(store, other)
            val appended = stroke(4, 300f)
            val foreign = other.edit(other.root(base.id), base.info, 5, emptySet(), listOf(
                StrokeRef(4, appended.value.storageId) to (appended.value.placement to NoteRepository.encodeStroke(appended.value))))
            other.install(foreign, validate = false)
            val transport = ImmutableNoteTransport(other, PackedDocumentFiles(context.contentResolver, storage.uri!!, "misq-v3"))
            transport.publish(foreign)
            storage.write { store.install(store.edit(base, base.info.copy(color = 0xff445566.toInt()), base.nextId, emptySet(), emptyList()), validate = false) }
            assertTrue(storage.awaitSync()); storage.retryPending(); assertTrue(storage.awaitSync())
            val conflicts = NoteConflicts(files.directory, File(folder, "conflicts"), storage::contains, storage::ensure, includeHistory = true)
            val conflict = conflicts.scan().single()
            val repository = NoteRepository(files, persist = { storage.write(it) })
            assertNotNull(repository.autoMergeConflict(conflicts, conflict))
            assertEquals(listOf(1L, 2L, 3L, 4L), repository.load(manifest.info.id).entries.map { it.id })
            assertTrue(storage.awaitSync()); assertEquals(1, transport.heads(manifest.info.id).size)
            assertTrue(conflicts.scan().isEmpty())
        }
    }
    @Test fun cancelledViewportDownloadDoesNotHoldTheDurableSaveLock() = runBlocking {
        fixture(external = true) { folder, storage, manifest, entries ->
            storage.migrateFormat3(); assertTrue(storage.awaitSync())
            val files = storage.noteFiles(); val library = files.format3!!; val root = library.store.current(manifest.info.id)!!
            val missing = library.store.find(root, setOf(2)).getValue(2).body.objectId
            val body = File(library.store.directory, "objects/$missing"); assertTrue(body.delete())
            val repository = NoteRepository(files, persist = { storage.write(it) })
            repository.loadVisible(manifest.info.id, 1f)
            provider("test-hold-reads", Bundle().apply { putBoolean("hold", true) })
            val download = async(Dispatchers.IO) {
                val context = currentCoroutineContext()
                repository.loadNextRegion(manifest.info.id, 10000f, 11000f, setOf(1)) { context.ensureActive() }
            }
            withTimeout(10000) { while (!provider("test-read-waiting")!!.getBoolean("waiting")) delay(10) }
            download.cancel()
            val changed = OrderedEdits.Entry(1L, entries[0].value.translated(0f, 50f))
            withTimeout(2000) { withContext(Dispatchers.Default) {
                repository.save(NoteSnapshot(NoteManifest(manifest.info, listOf(StrokeRef(1, changed.value.storageId)), 4), listOf(changed), loadedIds = setOf(1)))
            } }
            provider("test-hold-reads", Bundle().apply { putBoolean("hold", false) })
            download.join(); assertTrue(storage.awaitSync())
            assertFalse(body.exists())
            assertEquals(changed.value.storageId, library.manifest(manifest.info.id).strokes.first { it.id == 1L }.storageId)
        }
    }
}
