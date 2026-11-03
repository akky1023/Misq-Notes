package jp.handwriting.note.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import jp.handwriting.note.AppMessages
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/** Real ContentResolver IO through a disposable debug-only DocumentsProvider, never the user's tree. */
class ExternalStorageIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = Uri.parse("content://net.neomenia.misqnotes.probe.tests.working-set")
    private fun callProvider(method: String, extras: Bundle? = null): Bundle? {
        return context.contentResolver.call(provider, method, null, extras)
    }
    private fun failWrites(fail: Boolean) {
        callProvider("test-fail-writes", Bundle().apply { putBoolean("fail", fail) })
    }
    private suspend fun fixture(test: suspend (File, Uri, CoroutineScope, NotebookStorage, List<NoteManifest>) -> Unit) {
        check(context.packageName == "net.neomenia.misqnotes.probe")
        AppMessages.configure(context)
        val prefs = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
        val oldTree = prefs.getString("tree", null); val oldLabel = prefs.getString("label", null)
        @Suppress("DEPRECATION")
        val tree = callProvider("test-new-tree")!!.getParcelable<Uri>("tree")!!
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val directory = File(context.cacheDir, "external-working-test-${UUID.randomUUID()}").also { it.mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs.edit().remove("tree").remove("label").commit()
        try {
            val notes = NoteFiles(File(directory, "notes"))
            val manifests = listOf(notes.create("one"), notes.create("two"))
            val storage = NotebookStorage(context, directory, scope)
            assertFalse(storage.initialize())
            storage.change(tree)
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            test(directory, tree, scope, storage, manifests)
        } finally {
            callProvider("test-hold-writes", Bundle().apply { putBoolean("hold", false) })
            scope.coroutineContext[Job]!!.cancelAndJoin(); failWrites(false)
            callProvider("test-fail-reads", Bundle().apply { putBoolean("fail", false) })
            DocumentsContract.deleteDocument(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            prefs.edit().putString("tree", oldTree).putString("label", oldLabel).commit()
            directory.deleteRecursively()
        }
    }
    private fun bodies(directory: File) = File(directory, "notes").listFiles().orEmpty().filter { it.extension == "misq" }
    private suspend fun clearModel(store: androidx.lifecycle.ViewModelStore, model: jp.handwriting.note.editor.EditorViewModel) {
        val job = model.viewModelScope.coroutineContext[Job]!!
        withContext(Dispatchers.Main) { store.clear() }
        // Closing a note no longer waits for uploads; stop all IO before deleting the test tree.
        job.join()
    }
    @Test fun editorAndLibraryRemainUsableDuringExternalUploadAndFailure() = runBlocking {
        fixture { directory, _, scope, _, manifests ->
            val store = androidx.lifecycle.ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[
                    jp.handwriting.note.editor.EditorViewModel::class.java].also {
                    it.configureStorage(context, directory, File(directory, "ui-cache")); it.start(directory)
                }
            }
            suspend fun waitUntil(test: () -> Boolean) = withTimeout(15000) { while (!test()) delay(10) }
            val id = manifests.first().info.id
            suspend fun open() {
                withContext(Dispatchers.Main) { model.openNote(id) }
                waitUntil { model.workspace.value.current?.id == id && !model.workspace.value.working && !model.ui.value.busy }
            }
            suspend fun close() {
                withContext(Dispatchers.Main) { model.closeNote() }
                waitUntil { model.workspace.value.current == null && !model.workspace.value.working }
            }
            try {
                waitUntil { !model.workspace.value.working }
                open()
                callProvider("test-hold-writes", Bundle().apply { putBoolean("hold", true) })
                withContext(Dispatchers.Main) { model.edits.addAll(listOf(placedStroke(1, 100f).value)) }
                waitUntil { callProvider("test-write-waiting")!!.getBoolean("waiting") }
                close()
                assertTrue(model.workspace.value.syncing)
                assertTrue(File(directory, ".notes-pending").exists())
                assertTrue(File(directory, "notes/$id.misq").exists())
                // Editing the same note again must survive acknowledgement of the older upload.
                open()
                withContext(Dispatchers.Main) { model.edits.addAll(listOf(placedStroke(2, 200f).value)) }
                close()
                withContext(Dispatchers.Main) { model.renameNote(id, "renamed during upload") }
                waitUntil { !model.workspace.value.working }
                assertEquals("renamed during upload", model.workspace.value.notes.first { it.id == id }.title)
                withContext(Dispatchers.Main) { model.deleteNote(manifests[1].info.id) }
                waitUntil { !model.workspace.value.working }
                assertEquals(listOf(id), model.workspace.value.notes.map { it.id })
                assertTrue(callProvider("test-write-waiting")!!.getBoolean("waiting"))
                callProvider("test-hold-writes", Bundle().apply { putBoolean("hold", false) })
                waitUntil { !model.workspace.value.syncing && !File(directory, ".notes-pending").exists() }
                assertTrue("Published closed notes should release their working bodies", bodies(directory).isEmpty())

                open()
                assertEquals(2, model.ui.value.strokes)
                failWrites(true)
                withContext(Dispatchers.Main) { model.edits.addAll(listOf(placedStroke(3, 300f).value)) }
                close()
                waitUntil { model.workspace.value.syncError != null }
                assertTrue(File(directory, ".notes-pending").exists())
                assertEquals(3, NoteFiles(File(directory, "notes")).read(id).manifest.strokes.size)
                failWrites(false)
                withContext(Dispatchers.Main) { model.retry() }
                waitUntil { !model.workspace.value.working && !model.workspace.value.syncing &&
                    model.workspace.value.syncError == null && !File(directory, ".notes-pending").exists() }
                assertTrue(bodies(directory).isEmpty())
                val restarted = NotebookStorage(context, directory, scope)
                assertTrue(restarted.initialize()); restarted.initializeRemote()
                val saved = restarted.noteFiles().read(id).manifest
                assertEquals("renamed during upload", saved.info.title)
                assertEquals(3, saved.strokes.size)
                assertEquals(listOf(id), restarted.noteFiles().list().map { it.id })
            } finally {
                callProvider("test-hold-writes", Bundle().apply { putBoolean("hold", false) })
                clearModel(store, model)
            }
        }
    }
    @Test fun dirtyBandsSurviveFailureAndUnchangedBodyIsNotCopiedForSettingsSave() = runBlocking {
        fixture { directory, tree, _, storage, manifests ->
            val note = manifests.first(); val id = note.info.id; val files = storage.noteFiles()
            files.read(id); storage.keepOpen(id)
            val a = StrokeRef(1, UUID.randomUUID().toString()); val b = StrokeRef(2, UUID.randomUUID().toString())
            val manifest = note.copy(strokes = listOf(a, b), nextId = 3)
            failWrites(true)
            storage.write { files.save(manifest, mapOf(a.storageId to StrokePlacement(0f, 0f, 10f), b.storageId to StrokePlacement(5000f, 5000f, 5010f))) {
                if (it == b.storageId) ByteArray(8 * 1024 * 1024) { n -> (n % 251).toByte() } else byteArrayOf(1)
            } }
            assertFalse(withTimeout(30000) { storage.awaitSync() })
            val body = files.document(id).file
            assertEquals(setOf(0L, 5L), BlockChanges.capture(body)!!.dirty.keys)
            failWrites(false); storage.retryPending()
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            val saved = BlockChanges.capture(body)!!
            assertTrue(saved.dirty.isEmpty()); assertEquals(0L, saved.metadataDirty)
            assertEquals(NotebookArchive.digest(body), saved.baseline)
            val library = LibraryPreferencesFile(File(directory, "library.hwp"))
            storage.write { library.write(library.read().added("metadata only", null)) }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            assertEquals(0L, storage.lastSnapshotNoteBytes)
            assertEquals(saved.generation, BlockChanges.capture(body)!!.generation)
            // Reusing a remote reference must still repair a missing remote object from the
            // verified local body, without losing the note or clearing later changes.
            val recovery = PackedDocumentFiles(context.contentResolver, tree, "inkdata/.recovery")
            recovery.names(); recovery.delete("blob-${saved.baseline}")
            storage.write { library.write(library.read().added("repair reference", null)) }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            val physical = MisqDocumentFiles(context.contentResolver, tree); physical.names()
            assertTrue(physical.matchesNote("blob-${saved.baseline}", saved.baseline!!))
            val moved = a.copy(storageId = UUID.randomUUID().toString())
            storage.write { files.save(manifest.copy(strokes = listOf(moved, b)), mapOf(moved.storageId to StrokePlacement(2000f, 2000f, 2010f))) { byteArrayOf(2) } }
            assertEquals(setOf(0L, 2L), BlockChanges.capture(body)!!.dirty.keys)
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            assertTrue(BlockChanges.capture(body)!!.dirty.isEmpty())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nBLOCK_CHANGES noteBytes=${body.length()} settingsSnapshotNoteBytes=0 failureRetained=true missingRemoteRepaired=true\n")
            })
        }
    }
    private fun placedStroke(id: Long, y: Float, endY: Float = y + 20): jp.handwriting.note.editor.OrderedEdits.Entry<jp.handwriting.note.editor.EditorStroke> {
        val brush = androidx.ink.brush.Brush.createWithColorIntArgb(androidx.ink.brush.StockBrushes.marker(), 0xff202b33.toInt(), 3f, 0.1f)
        val inputs = androidx.ink.strokes.MutableStrokeInputBatch()
            .add(androidx.ink.brush.InputToolType.STYLUS, 100f, y, 0, pressure = 0.5f)
            .add(androidx.ink.brush.InputToolType.STYLUS, 120f, endY, 20, pressure = 0.5f)
        return jp.handwriting.note.editor.OrderedEdits.Entry(id, jp.handwriting.note.editor.EditorStroke(androidx.ink.strokes.Stroke(brush, inputs)))
    }
    @Test fun visibleOpenLoadsOnlySpatialBandsAndPartialSavePreservesUnreadInk() = runBlocking {
        fixture { directory, _, _, storage, manifests ->
            val original = manifests.first()
            val files = storage.noteFiles()
            val repository = NoteRepository(files, persist = { storage.write(it) })
            val entries = listOf(placedStroke(1, 100f, 2500f), placedStroke(2, 10000f), placedStroke(3, 12000f))
            val manifest = original.copy(strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 4)
            repository.save(NoteSnapshot(manifest, entries))
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            storage.releaseWorkingCopies()
            val visible = repository.loadVisible(original.info.id, 1f)
            assertTrue(visible.partial)
            assertEquals(listOf(1L), visible.entries.map { it.id })
            assertTrue(bodies(directory).isEmpty())
            val further = repository.loadRegion(original.info.id, 12000f, 13000f, setOf(1L))
            assertEquals(listOf(3L), further.map { it.id })
            assertTrue(bodies(directory).isEmpty())
            val moved = jp.handwriting.note.editor.OrderedEdits.Entry(1L, entries.first().value.translated(0f, 5000f))
            val partial = listOf(moved) + further
            val refs = listOf(StrokeRef(1, moved.value.storageId), manifest.strokes[1], manifest.strokes[2])
            repository.save(NoteSnapshot(manifest.copy(strokes = refs), partial))
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            storage.releaseWorkingCopies()
            val reloaded = repository.loadVisible(original.info.id, 1f)
            assertTrue(reloaded.entries.isEmpty())
            val crossed = repository.loadRegion(original.info.id, 6500f, 6800f, emptySet())
            assertEquals(listOf(1L), crossed.map { it.id })
            val full = repository.load(original.info.id)
            assertEquals(listOf(1L, 2L, 3L), full.entries.map { it.id })
            assertEquals(entries[1].value.storageId, full.entries[1].value.storageId)
        }
    }
    @Test fun editorSavesUnreadReferencesAndHydratesWithoutBreakingUndoOrExport() = runBlocking {
        fixture { directory, _, _, storage, manifests ->
            val original = manifests.first()
            val repository = NoteRepository(storage.noteFiles(), persist = { storage.write(it) })
            val entries = listOf(placedStroke(1, 100f), placedStroke(2, 10000f), placedStroke(3, 12000f))
            repository.save(NoteSnapshot(original.copy(strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 4), entries))
            assertTrue(storage.awaitSync()); storage.releaseWorkingCopies()
            val store = androidx.lifecycle.ViewModelStore()
            val model = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[
                jp.handwriting.note.editor.EditorViewModel::class.java]
            suspend fun waitUntil(test: () -> Boolean) { withTimeout(30000) { while (!test()) delay(10) } }
            try {
                withContext(Dispatchers.Main) {
                    model.viewport.size(1000f, 1000f)
                    model.configureStorage(context, directory, File(directory, "ui-cache"))
                    model.start(directory)
                }
                waitUntil { !model.workspace.value.working }
                withContext(Dispatchers.Main) { model.openNote(original.info.id) }
                waitUntil { model.workspace.value.current?.id == original.info.id && !model.workspace.value.working }
                assertTrue(model.edits.snapshot().any { it.id == 1L })
                withContext(Dispatchers.Main) {
                    model.viewport.restore(jp.handwriting.note.editor.ViewPosition(top = 12000f)); model.viewportChanged()
                }
                waitUntil { model.edits.snapshot().any { it.id == 3L } && !model.ui.value.busy }
                withContext(Dispatchers.Main) { assertEquals(listOf(4L), model.edits.addAll(listOf(placedStroke(99, 12050f).value))) }
                waitUntil { !model.workspace.value.saving && File(directory, "notes/${original.info.id}.misq").exists() }
                assertEquals(listOf(1L, 2L, 3L, 4L), NoteFiles(File(directory, "notes")).read(original.info.id).manifest.strokes.map { it.id })
                val exported = withContext(Dispatchers.Main) { model.exportSnapshot(original.info.id) }
                assertEquals(listOf(1L, 2L, 3L, 4L), exported.entries.map { it.id })
                withContext(Dispatchers.Main) {
                    assertTrue(model.edits.canUndo); model.edits.undo()
                    assertEquals(listOf(1L, 2L, 3L), model.edits.snapshot().map { it.id })
                    model.edits.redo(); model.closeNote()
                }
                waitUntil { model.workspace.value.current == null && !model.workspace.value.working }
                withContext(Dispatchers.Main) { model.openNote(original.info.id) }
                waitUntil { model.workspace.value.current?.id == original.info.id && !model.workspace.value.working }
                assertTrue(model.edits.snapshot().map { it.id }.containsAll(listOf(3L, 4L)))
                assertEquals(4, model.ui.value.strokes)
                withContext(Dispatchers.Main) { model.closeNote() }
                waitUntil { model.workspace.value.current == null && !model.workspace.value.working }
            } finally { clearModel(store, model) }
        }
    }
    @Test fun partialEditIsJournaledBeforeDownloadAndRecoveredAfterReadFailure() = runBlocking {
        fixture { directory, _, scope, storage, manifests ->
            val original = manifests.first(); val files = storage.noteFiles()
            val repository = NoteRepository(files, persist = { storage.write(it) })
            val entries = listOf(placedStroke(1, 100f), placedStroke(2, 10000f))
            val manifest = original.copy(strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 3)
            repository.save(NoteSnapshot(manifest, entries)); assertTrue(storage.awaitSync())
            storage.releaseWorkingCopies()
            assertEquals(listOf(1L), repository.loadVisible(original.info.id, 1f).entries.map { it.id })
            val changed = placedStroke(1, 300f)
            callProvider("test-fail-reads", Bundle().apply { putBoolean("fail", true) })
            assertThrows(Exception::class.java) { repository.save(NoteSnapshot(manifest.copy(strokes = listOf(
                StrokeRef(1, changed.value.storageId), manifest.strokes[1])), listOf(changed))) }
            assertTrue(PartialNoteJournal.exists(File(directory, "notes"), original.info.id))
            assertTrue(bodies(directory).isEmpty())
            callProvider("test-fail-reads", Bundle().apply { putBoolean("fail", false) })
            val restarted = NotebookStorage(context, directory, scope)
            assertTrue(restarted.initialize()); restarted.initializeRemote()
            val loaded = NoteRepository(restarted.noteFiles()).load(original.info.id)
            assertEquals(listOf(changed.value.storageId, entries[1].value.storageId), loaded.entries.map { it.value.storageId })
            assertFalse(PartialNoteJournal.exists(File(directory, "notes"), original.info.id))
        }
    }
    @Test fun initialViewportIsShownThenRestLoadsWithoutBlockingEditsAndStopsOnSwitchOrClose() = runBlocking {
        fixture { directory, _, _, storage, manifests ->
            val original = manifests.first()
            val repository = NoteRepository(storage.noteFiles(), persist = { storage.write(it) })
            val entries = (1L..30L).map { placedStroke(it, (it - 1) * 10000f + 100) }
            repository.save(NoteSnapshot(original.copy(strokes = entries.map { StrokeRef(it.id, it.value.storageId) }, nextId = 31), entries))
            assertTrue(storage.awaitSync()); storage.releaseWorkingCopies()
            val store = androidx.lifecycle.ViewModelStore()
            val model = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[
                jp.handwriting.note.editor.EditorViewModel::class.java]
            val firstFrame = java.util.concurrent.atomic.AtomicReference<List<Long>?>(null)
            val usablePrefetch = java.util.concurrent.atomic.AtomicBoolean(false)
            suspend fun waitUntil(test: () -> Boolean) { withTimeout(30000) { while (!test()) delay(10) } }
            try {
                withContext(Dispatchers.Main) {
                    model.viewport.size(1000f, 1000f)
                    model.configureStorage(context, directory, File(directory, "background-ui-cache")); model.start(directory)
                    model.onCanvasChange = {
                        if (model.workspace.value.current?.id == original.info.id && !model.workspace.value.working) {
                            firstFrame.compareAndSet(null, model.edits.snapshot().map { it.id })
                            if (model.ui.value.loadingRest && !model.ui.value.busy) usablePrefetch.set(true)
                        }
                    }
                }
                waitUntil { !model.workspace.value.working }
                withContext(Dispatchers.Main) { model.openNote(original.info.id) }
                waitUntil { firstFrame.get() != null && usablePrefetch.get() }
                assertEquals(listOf(1L), firstFrame.get())
                withContext(Dispatchers.Main) { assertEquals(listOf(31L), model.edits.addAll(listOf(placedStroke(99, 150f).value))) }
                waitUntil { model.edits.snapshot().size == 31 && !model.ui.value.loadingRest && !model.ui.value.busy }
                withContext(Dispatchers.Main) {
                    assertTrue(model.edits.canUndo); model.edits.undo()
                    assertEquals((1L..30L).toList(), model.edits.snapshot().map { it.id })
                    model.edits.redo(); model.closeNote()
                }
                waitUntil { model.workspace.value.current == null && !model.workspace.value.working }
                // This assertion exercises a published spatial cache; close now returns earlier.
                waitUntil { !model.workspace.value.syncing && !File(directory, ".notes-pending").exists() && bodies(directory).isEmpty() }
                firstFrame.set(null)
                withContext(Dispatchers.Main) { model.openNote(original.info.id) }
                waitUntil { firstFrame.get() != null }
                assertEquals(listOf(1L, 31L), firstFrame.get())
                withContext(Dispatchers.Main) { model.openNote(manifests.last().info.id) }
                waitUntil { model.workspace.value.current?.id == manifests.last().info.id && !model.workspace.value.working }
                delay(300)
                assertTrue(model.edits.snapshot().isEmpty())
                assertFalse(model.ui.value.loadingRest)
                withContext(Dispatchers.Main) { model.closeNote() }
                waitUntil { model.workspace.value.current == null && !model.workspace.value.working }
                firstFrame.set(null)
                withContext(Dispatchers.Main) { model.openNote(original.info.id) }
                waitUntil { firstFrame.get() != null }
                withContext(Dispatchers.Main) { model.closeNote() }
                waitUntil { model.workspace.value.current == null && !model.workspace.value.working }
                delay(300)
                assertTrue(model.edits.snapshot().isEmpty())
                assertFalse(model.ui.value.loadingRest)
            } finally { clearModel(store, model) }
        }
    }
    @Test fun largeNoteEditUploadsOnlyChangedBlocksAndAliasesRemainReadable() = runBlocking {
        fixture { directory, tree, _, storage, manifests ->
            val id = manifests.first().info.id
            val files = storage.noteFiles(); files.read(id)
            storage.write {
                java.io.FileOutputStream(files.document(id).file, true).use { output ->
                    val random = kotlin.random.Random(1234)
                    repeat(8) { output.write(random.nextBytes(BlockPackedFiles.BLOCK_SIZE)) }
                    output.fd.sync()
                }
            }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            val blocks = PackedDocumentFiles(context.contentResolver, tree, "inkdata/.blocks")
            val before = blocks.names()
            val start = System.nanoTime()
            storage.write { files.rename(id, "changed blocks") }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val added = blocks.names() - before
            val bytes = added.sumOf { blocks.read(it).size.toLong() }
            assertEquals(2, added.size)
            assertTrue(bytes < 2L * BlockPackedFiles.BLOCK_SIZE)
            val raw = PackedDocumentFiles(context.contentResolver, tree, "inkdata")
            raw.names()
            val descriptor = raw.read("$id.misq")
            assertNotNull(BlockPackedFiles.inspect(descriptor.inputStream()))
            assertTrue(descriptor.size < 4096)
            val alias = "${UUID.randomUUID()}.misq"
            raw.write(alias, descriptor)
            val conflicts = ConflictDocuments(context.contentResolver, File(directory, "alias-cache"))
            val found = conflicts.scan(tree).single()
            assertEquals(alias, found.name)
            assertEquals("changed blocks", MisqFile(found.file, readOnlyAlias = true).summary().title)
            assertEquals(1, conflicts.scan(tree).size)
            storage.releaseWorkingCopies()
            assertEquals("changed blocks", files.read(id).manifest.info.title)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nBLOCK_SAVE noteBytes=${files.document(id).file.length()} uploadedBlockBytes=$bytes blocks=${added.size} saveMs=$elapsedMs\n")
            })
        }
    }
    @Test fun previewAndBodyArePublishedTogetherWithoutAnExtraRevision() = runBlocking {
        fixture { _, tree, _, storage, manifests ->
            val physical = MisqDocumentFiles(context.contentResolver, tree)
            fun sequence(): Long = physical.names().filter { it.startsWith("revision-") && it.endsWith(".json") }
                .max().substring(9, 29).toLong()
            val before = sequence()
            val id = manifests.first().info.id
            val files = storage.noteFiles(); files.read(id)
            var prepared = false
            storage.beforeExternalSave = {
                if (!prepared) {
                    prepared = true
                    withContext(Dispatchers.IO) {
                        storage.write {
                            val thumbnail = files.thumbnail(id)
                            thumbnail.parentFile!!.mkdirs(); thumbnail.writeBytes(byteArrayOf(4, 5, 6))
                            files.markThumbnailCurrent(id)
                        }
                    }
                }
            }
            storage.write { files.rename(id, "body and preview") }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            // The preview requested another save while the first save was being prepared.
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            assertTrue(prepared)
            assertEquals(before + 1, sequence())
            storage.releaseWorkingCopies()
            assertEquals("body and preview", files.read(id).manifest.info.title)
            assertArrayEquals(byteArrayOf(4, 5, 6), files.document(id).thumbnail())
        }
    }
    @Test fun migrationLazyOpenSaveReleaseRestartAndSwitchBackToInternal() = runBlocking {
        fixture { directory, _, scope, storage, manifests ->
            assertTrue(bodies(directory).isEmpty())
            assertFalse(File(directory, ".notes-merge-base").exists())
            val files = storage.noteFiles()
            assertEquals(2, files.list().size)
            assertTrue(bodies(directory).isEmpty())
            val id = manifests.first().info.id
            storage.keepOpen(id)
            assertEquals(manifests.first(), files.read(id).manifest)
            assertEquals(listOf("$id.misq"), bodies(directory).map { it.name })
            storage.write { files.rename(id, "edited") }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            storage.releaseWorkingCopies()
            assertEquals(1, bodies(directory).size) // Active editor remains usable after publication.
            storage.keepOpen(null); storage.releaseWorkingCopies()
            assertTrue(bodies(directory).isEmpty())
            val restarted = NotebookStorage(context, directory, scope)
            assertTrue(restarted.initialize()); restarted.initializeRemote()
            assertTrue(bodies(directory).isEmpty())
            assertEquals(setOf("edited", "two"), restarted.noteFiles().list().map { it.title }.toSet())
            restarted.change(null)
            assertNull(restarted.uri)
            assertEquals(2, bodies(directory).size)
            assertFalse(ExternalWorkingSet.exists(directory))
            assertEquals(setOf("edited", "two"), NoteFiles(File(directory, "notes")).list().map { it.title }.toSet())
        }
    }
    @Test fun failedExternalWriteRetainsEditsAndRestartPublishesThem() = runBlocking {
        fixture { directory, _, scope, storage, manifests ->
            val id = manifests.first().info.id
            val files = storage.noteFiles(); files.read(id)
            failWrites(true)
            storage.write { files.rename(id, "unsaved work") }
            assertFalse(withTimeout(30000) { storage.awaitSync() })
            storage.releaseWorkingCopies()
            assertTrue(File(directory, ".notes-pending").exists())
            assertEquals("unsaved work", files.read(id).manifest.info.title)
            failWrites(false)
            val restarted = NotebookStorage(context, directory, scope)
            assertTrue(restarted.initialize()); restarted.initializeRemote()
            assertTrue(restarted.awaitSync())
            assertFalse(File(directory, ".notes-pending").exists())
            assertTrue(bodies(directory).isEmpty())
            assertEquals("unsaved work", restarted.noteFiles().list().single { it.id == id }.title)
        }
    }
    @Test fun deletionDoesNotDownloadOrReturnAfterRestart() = runBlocking {
        fixture { directory, _, scope, storage, manifests ->
            val id = manifests.first().info.id
            storage.write { storage.noteFiles().delete(id) }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            assertTrue(bodies(directory).isEmpty())
            val restarted = NotebookStorage(context, directory, scope)
            assertTrue(restarted.initialize()); restarted.initializeRemote()
            assertEquals(listOf(manifests.last().info.id), restarted.noteFiles().list().map { it.id })
        }
    }
    @Test fun metadataOnlySaveKeepsUnopenedNotesAndRetryReleasesSavedWork() = runBlocking {
        fixture { directory, _, _, storage, manifests ->
            val libraryFile = LibraryPreferencesFile(File(directory, "library.hwp"))
            storage.write { libraryFile.write(libraryFile.read().added("external folder", null)) }
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            assertTrue(bodies(directory).isEmpty())
            assertEquals(2, storage.noteFiles().list().size)
            val id = manifests.first().info.id
            val files = storage.noteFiles(); files.read(id)
            failWrites(true)
            storage.write { files.rename(id, "retry saved") }
            assertFalse(withTimeout(30000) { storage.awaitSync() })
            failWrites(false); storage.retryPending()
            assertTrue(withTimeout(30000) { storage.awaitSync() })
            storage.releaseWorkingCopies()
            assertTrue(bodies(directory).isEmpty())
            assertEquals("retry saved", files.list().single { it.id == id }.title)
        }
    }
    @Test fun libraryLargerThanHeapRemainsExternalAcrossRestart() = runBlocking {
        fixture { directory, _, scope, storage, manifests ->
            val files = storage.noteFiles()
            val size = Runtime.getRuntime().maxMemory() / 2 + 16L * 1024 * 1024
            manifests.forEach { manifest ->
                files.read(manifest.info.id)
                storage.write { java.io.RandomAccessFile(files.document(manifest.info.id).file, "rw").use { it.setLength(size) } }
            }
            assertTrue(withTimeout(180000) { storage.awaitSync() })
            storage.releaseWorkingCopies()
            assertTrue(bodies(directory).isEmpty())
            val restarted = NotebookStorage(context, directory, scope)
            assertTrue(restarted.initialize()); restarted.initializeRemote()
            assertEquals(2, restarted.noteFiles().list().size)
            assertTrue(bodies(directory).isEmpty())
            val id = manifests.first().info.id
            restarted.noteFiles().read(id)
            assertEquals(size, File(directory, "notes/$id.misq").length())
            assertEquals(1, bodies(directory).size)
        }
    }
    private fun switchJournal(directory: File, tree: Uri) {
        val files = listOf("folders.hwp", "presets.hwp").associateWith { NotebookArchive.digest(File(directory, it)) } +
            bodies(directory).associate { "notes/${it.name}" to NotebookArchive.digest(it) }
        NoteFiles.atomicWrite(File(directory, ".notes-external-switch"), JSONObject().put("from", tree.toString())
            .put("to", "").put("files", JSONObject(files)).toString().toByteArray())
    }
    @Test fun interruptedSwitchBeforePreferenceCommitRestoresExternalMode() = runBlocking {
        fixture { directory, tree, scope, storage, manifests ->
            manifests.forEach { storage.noteFiles().read(it.info.id) }
            switchJournal(directory, tree)
            val restarted = NotebookStorage(context, directory, scope)
            assertTrue(restarted.initialize()); restarted.initializeRemote()
            assertEquals(tree, restarted.uri)
            assertTrue(bodies(directory).isEmpty())
            assertFalse(File(directory, ".notes-external-switch").exists())
        }
    }
    @Test fun interruptedSwitchAfterPreferenceCommitKeepsCompleteInternalLibrary() = runBlocking {
        fixture { directory, tree, scope, storage, manifests ->
            manifests.forEach { storage.noteFiles().read(it.info.id) }
            switchJournal(directory, tree)
            context.getSharedPreferences("note-storage", Context.MODE_PRIVATE).edit().remove("tree").remove("label").commit()
            val restarted = NotebookStorage(context, directory, scope)
            assertFalse(restarted.initialize())
            assertNull(restarted.uri)
            assertFalse(ExternalWorkingSet.exists(directory))
            assertFalse(File(directory, ".notes-external-switch").exists())
            assertEquals(2, NoteFiles(File(directory, "notes")).list().size)
        }
    }
    @Test fun unsavedChangesCanBeExplicitlyRecoveredIntoInternalStorage() = runBlocking {
        fixture { directory, _, _, storage, manifests ->
            val id = manifests.first().info.id
            val files = storage.noteFiles(); files.read(id)
            failWrites(true)
            storage.write { files.rename(id, "recover internally") }
            assertFalse(withTimeout(30000) { storage.awaitSync() })
            storage.change(null)
            assertNull(storage.uri)
            assertFalse(ExternalWorkingSet.exists(directory))
            assertEquals(2, bodies(directory).size)
            assertEquals("recover internally", NoteFiles(File(directory, "notes")).read(id).manifest.info.title)
        }
    }
    @Test fun simultaneousIndependentStrokeEditsMergeOnRestartWithoutKeepingOtherBodies() = runBlocking {
        fixture { directory, _, scope, storage, manifests ->
            val branch = File(context.cacheDir, "external-branch-${UUID.randomUUID()}")
            try {
                check(directory.copyRecursively(branch))
                val second = NotebookStorage(context, branch, scope)
                assertTrue(second.initialize()); second.initializeRemote()
                val original = manifests.first()
                val a = StrokeRef(1, UUID.randomUUID().toString()); val b = StrokeRef(1, UUID.randomUUID().toString())
                val left = storage.noteFiles(); left.read(original.info.id)
                val right = second.noteFiles(); right.read(original.info.id)
                storage.write { left.save(original.copy(strokes = listOf(a), nextId = 2)) { byteArrayOf(1) } }
                assertTrue(withTimeout(30000) { storage.awaitSync() })
                second.write { right.save(original.copy(strokes = listOf(b), nextId = 2)) { byteArrayOf(2) } }
                assertFalse(withTimeout(30000) { second.awaitSync() })
                assertTrue(second.blockedByRemoteChange)
                val restarted = NotebookStorage(context, branch, scope)
                assertTrue(restarted.initialize()); restarted.initializeRemote()
                assertTrue(bodies(branch).isEmpty())
                val merged = restarted.noteFiles()
                val manifest = merged.read(original.info.id).manifest
                assertEquals(setOf(a.storageId, b.storageId), manifest.strokes.map { it.storageId }.toSet())
                assertArrayEquals(byteArrayOf(1), merged.stroke(original.info.id, a.storageId))
                assertArrayEquals(byteArrayOf(2), merged.stroke(original.info.id, b.storageId))
                assertEquals(1, bodies(branch).size)
            } finally { branch.deleteRecursively() }
        }
    }
}
