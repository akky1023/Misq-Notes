package jp.handwriting.note.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.editor.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import androidx.lifecycle.viewModelScope
import org.junit.Assert.*
import org.junit.Test

class Format3OnlyStorageTest {
    private suspend fun slowRemoteStartup(editSettings: Boolean, openNote: Boolean = false) = isolated { context, folder, scope ->
        val provider = Uri.parse("content://net.neomenia.misqnotes.probe.tests.working-set")
        fun control(method: String, hold: Boolean = false) = context.contentResolver.call(provider, method, null,
            android.os.Bundle().apply { putBoolean("hold", hold) })
        @Suppress("DEPRECATION")
        val tree = control("test-new-tree")!!.getParcelable<Uri>("tree")!!
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, grant)
        val models = androidx.lifecycle.ViewModelStore()
        var model: EditorViewModel? = null
        try {
            val storage = NotebookStorage(context, folder, scope); storage.initialize()
            val local = storage.noteFiles().create("local note")
            storage.change(tree); assertTrue(storage.awaitSync())
            val store = storage.noteFiles().format3.store
            val incoming = store.create(NoteInfo(UUID.randomUUID().toString(), "remote note", 2, 2))
            ImmutableNoteTransport(store, PackedDocumentFiles(context.contentResolver, tree, "misq-v3")).publish(incoming)
            control("test-hold-reads", true)
            val started = android.os.SystemClock.elapsedRealtime()
            val editor = withContext(Dispatchers.Main) {
                androidx.lifecycle.ViewModelProvider(models, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also {
                    it.configureStorage(context, folder, File(folder, "ui-cache")); it.start(folder)
                }
            }
            model = editor
            withTimeout(3000) { while (editor.workspace.value.working) delay(5) }
            assertTrue(android.os.SystemClock.elapsedRealtime() - started < 3000)
            assertEquals(listOf(local.info.id), editor.workspace.value.notes.map { it.id })
            withTimeout(10000) { while (control("test-read-waiting")?.getBoolean("waiting") != true) delay(5) }
            if (editSettings) withContext(Dispatchers.Main) { editor.listGrid(false) }
            if (openNote) {
                withContext(Dispatchers.Main) { editor.openNote(local.info.id) }
                withTimeout(10000) { while (editor.workspace.value.working) delay(5) }
                assertEquals(local.info.id, editor.workspace.value.current?.id)
            }
            control("test-hold-reads", false)
            withTimeout(30000) { while (editor.workspace.value.syncing) delay(10) }
            assertNull(editor.workspace.value.syncError)
            assertFalse(editor.workspace.value.working)
            if (editSettings) {
                assertFalse(editor.library.value.value.grid)
                withTimeout(10000) { while (editor.library.value.saving) delay(10) }
                assertFalse(LibraryPreferencesFile(File(folder, "library.hwp")).read().grid)
            } else if (openNote) {
                assertEquals(local.info.id, editor.workspace.value.current?.id)
                assertEquals(listOf(local.info.id), editor.workspace.value.notes.map { it.id })
                withContext(Dispatchers.Main) { editor.closeNote() }
                withTimeout(30000) {
                    while (editor.workspace.value.working || editor.workspace.value.notes.none { it.id == incoming.info.id }) delay(10)
                }
                assertNull(editor.workspace.value.current)
                assertEquals(setOf(local.info.id, incoming.info.id), editor.workspace.value.notes.map { it.id }.toSet())
            } else {
                assertEquals(setOf(local.info.id, incoming.info.id), editor.workspace.value.notes.map { it.id }.toSet())
            }
        } finally {
            control("test-hold-reads", false)
            val job = model?.viewModelScope?.coroutineContext?.get(Job)
            withContext(Dispatchers.Main) { models.clear() }
            job?.join()
            DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, grant) }
        }
    }
    @Test fun localListIsReadyWhileRemoteReadsWaitAndRefreshesAfterSync() = runBlocking { slowRemoteStartup(false) }
    @Test fun settingsChangedDuringStartupSyncKeepTheirLatestValue() = runBlocking { slowRemoteStartup(true) }
    @Test fun noteOpenedDuringStartupSyncStaysOpenAndReceivesNewNotesAfterClosing() = runBlocking { slowRemoteStartup(false, true) }

    private suspend fun isolated(test: suspend (Context, File, CoroutineScope) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        AppMessages.configure(base)
        val prefix = "modern-only-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
            override fun getApplicationContext(): Context = this
        }
        val folder = File(base.cacheDir, "modern-only-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { test(context, folder, scope) }
        finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            base.deleteSharedPreferences(prefix + "note-storage")
            check(folder.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            folder.deleteRecursively()
        }
    }
    private fun entry(): OrderedEdits.Entry<EditorStroke> {
        val brush = androidx.ink.brush.Brush.createWithColorIntArgb(androidx.ink.brush.StockBrushes.marker(), 0xff202b33.toInt(), 3f, .1f)
        val inputs = androidx.ink.strokes.MutableStrokeInputBatch()
            .add(androidx.ink.brush.InputToolType.STYLUS, 100f, 100f, 0L, pressure = .5f)
            .add(androidx.ink.brush.InputToolType.STYLUS, 120f, 120f, 20L, pressure = .5f)
        return OrderedEdits.Entry(1L, EditorStroke(androidx.ink.strokes.Stroke(brush, inputs)))
    }
    @Test fun freshLibrarySavesRestartsExportsAndDeletesUsingFormat3Only() = runBlocking {
        isolated { context, folder, scope ->
            val storage = NotebookStorage(context, folder, scope)
            assertFalse(storage.initialize()); assertTrue(storage.usesImmutable)
            val files = storage.noteFiles()
            val repository = NoteRepository(files, persist = { storage.write(it) })
            val empty = repository.create("new format")
            val stroke = entry()
            val manifest = empty.copy(strokes = listOf(StrokeRef(1L, stroke.value.storageId)), nextId = 2L)
            repository.save(NoteSnapshot(manifest, listOf(stroke)))
            assertFalse(File(folder, "notes").exists())
            assertFalse(File(folder, "format3-working/notes/${manifest.info.id}.misq").exists())
            val restarted = NotebookStorage(context, folder, scope)
            assertFalse(restarted.initialize()); assertTrue(restarted.usesImmutable)
            val restored = NoteRepository(restarted.noteFiles(), persist = { restarted.write(it) })
            assertEquals(stroke.value.storageId, restored.load(manifest.info.id).entries.single().value.storageId)
            val exported = File(folder, "standalone.misq")
            restored.exportNotebook(manifest.info.id, exported) {}
            MisqFile(exported, readOnlyAlias = true).validate()
            assertEquals(manifest, MisqFile(exported, readOnlyAlias = true).index().manifest)
            restored.delete(manifest.info.id)
            assertFalse(restarted.contains(manifest.info.id))
            assertTrue(manifest.info.id in restarted.deletedNoteIds())
        }
    }
    @Test fun editorCanCreateAndCloseItsFirstNoteWithoutALegacyListCache() = runBlocking {
        isolated { context, folder, _ ->
            val models = androidx.lifecycle.ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                androidx.lifecycle.ViewModelProvider(models, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also {
                    it.viewport.size(1000f, 1000f)
                    it.configureStorage(context, folder, File(folder, "ui-cache")); it.start(folder)
                }
            }
            suspend fun ready() = withTimeout(30000L) { while (model.workspace.value.working || model.ui.value.busy) delay(10L) }
            try {
                ready(); assertNull(model.workspace.value.error)
                assertTrue(model.storageLocation.value.format3)
                withContext(Dispatchers.Main) { model.createNote("first note") }
                ready(); assertNull(model.workspace.value.error)
                assertNotNull(model.workspace.value.current)
                withContext(Dispatchers.Main) { model.closeNote() }
                ready(); assertNull(model.workspace.value.error)
                assertEquals(1, model.workspace.value.notes.size)
                assertFalse(File(folder, "notes").exists())
            } finally {
                val job = model.viewModelScope.coroutineContext[Job]!!
                withContext(Dispatchers.Main) { models.clear() }; job.join()
            }
        }
    }
    @Test fun legacyStateWithoutMaterializedBodiesCannotBecomeAnEmptyLibrary() = runBlocking {
        isolated { context, folder, scope ->
            val state = File(folder, ".notes-external-state")
            state.writeText("retained legacy state")
            val hash = ContentHash.digest(state)
            val storage = NotebookStorage(context, folder, scope)
            assertThrows(IllegalStateException::class.java) { storage.initialize() }
            assertFalse(Format3Storage(folder).active)
            assertEquals(hash, ContentHash.digest(state))
        }
    }
    @Test fun missingMarkerNeverReplacesAnExistingObjectStore() = runBlocking {
        isolated { context, folder, scope ->
            val store = ImmutableNoteStore(File(folder, "format3-${UUID.randomUUID()}"))
            val root = store.create(NoteInfo(UUID.randomUUID().toString(), "retained", 1L, 1L)); store.install(root)
            val storage = NotebookStorage(context, folder, scope)
            assertThrows(IllegalStateException::class.java) { storage.initialize() }
            assertFalse(Format3Storage(folder).active)
            assertEquals(root, store.current(root.info.id))
        }
    }
    @Test fun standaloneLegacyInputIsConvertedWithoutChangingTheOriginal() = runBlocking {
        isolated { context, folder, scope ->
            val storage = NotebookStorage(context, folder, scope); storage.initialize()
            val sourceFiles = NoteFiles.open(File(folder, "legacy-input"))
            val empty = sourceFiles.create("import source")
            val stroke = entry()
            val manifest = empty.copy(strokes = listOf(StrokeRef(1L, stroke.value.storageId)), nextId = 2L)
            NoteRepository(sourceFiles).save(NoteSnapshot(manifest, listOf(stroke)))
            val source = sourceFiles.document(manifest.info.id)
            val expected = ContentHash.digest(source.file)
            val library = checkNotNull(storage.noteFiles().format3)
            storage.write { library.imported(source.file) }
            val actual = NoteRepository(storage.noteFiles()).load(manifest.info.id)
            assertEquals(manifest, actual.manifest)
            assertEquals(stroke.value.storageId, actual.entries.single().value.storageId)
            assertEquals(expected, ContentHash.digest(source.file))
            assertTrue(storage.usesImmutable)
            assertFalse(File(folder, "format3-working/notes/${manifest.info.id}.misq").exists())
        }
    }
    @Test fun choosingLegacyExternalFolderFailsBeforePublishingOrChangingSelection() = runBlocking {
        isolated { context, folder, scope ->
            val provider = Uri.parse("content://net.neomenia.misqnotes.probe.tests.working-set")
            @Suppress("DEPRECATION")
            val tree = context.contentResolver.call(provider, "test-new-tree", null, null)!!.getParcelable<Uri>("tree")!!
            val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(tree, grant)
            try {
                val storage = NotebookStorage(context, folder, scope); storage.initialize()
                val name = "${UUID.randomUUID()}.misq"
                val old = PackedDocumentFiles(context.contentResolver, tree, "inkdata")
                old.names(); old.write(name, byteArrayOf(1, 2, 3))
                try { storage.change(tree); fail("legacy tree must be rejected") }
                catch (_: IllegalStateException) { }
                assertNull(storage.uri)
                assertArrayEquals(byteArrayOf(1, 2, 3), old.read(name))
                assertTrue(PackedDocumentFiles(context.contentResolver, tree, "misq-v3").names().isEmpty())
            } finally {
                DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
                // Deleting the disposable tree may already revoke its persisted grant.
                runCatching { context.contentResolver.releasePersistableUriPermission(tree, grant) }
            }
        }
    }
}
