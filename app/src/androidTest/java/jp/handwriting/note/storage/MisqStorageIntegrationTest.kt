package jp.handwriting.note.storage

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.editor.OrderedEdits
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Reads the copied production data only through the fixed Download test tree. */
class MisqStorageIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nMISQ_STORAGE $value\n") })
    @Test fun productionCopyMigratesPreviewsReloadsAndMergesIndependentStrokeEdits() = runBlocking {
        AppMessages.configure(context)
        val parent = File(context.cacheDir, "misq-integration-${UUID.randomUUID()}").also { it.mkdirs() }
        val work = File(parent, "work").also { it.mkdirs() }
        val prefs = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
        val previous = prefs.all
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs.edit().putString("tree", MisqTestCopy.tree.toString()).putString("label", "Misq storage copy").commit()
        try {
            val oldReader = NotebookArchive(PackedArchiveDirectory(PackedDocumentFiles(context.contentResolver, MisqTestCopy.tree)))
            val old = checkNotNull(oldReader.latest())
            val original = File(parent, "original"); oldReader.restore(old, original)
            val oldNotes = NoteFiles(File(original, "notes"), false)
            val expected = oldNotes.list().associate { it.id to oldNotes.read(it.id).manifest }
            val storage = NotebookStorage(context, work, scope)
            assertTrue(storage.initialize())
            val migrationStart = System.nanoTime()
            storage.initializeRemote()
            assertTrue(withTimeout(240000) { storage.awaitSync() })
            report("migrationMs=${(System.nanoTime() - migrationStart) / 1_000_000} notes=${expected.size}")
            val notes = NoteFiles(File(work, "notes"))
            assertEquals(expected.keys, notes.list().map { it.id }.toSet())
            expected.forEach { (id, manifest) ->
                assertEquals(manifest, notes.read(id).manifest)
                manifest.strokes.forEach { ref -> assertArrayEquals(oldNotes.stroke(id, ref.storageId), notes.stroke(id, ref.storageId)) }
            }
            val physical = MisqDocumentFiles(context.contentResolver, MisqTestCopy.tree)
            assertEquals(expected.size, physical.names().count { it.endsWith(".misq") })
            report("allOriginalStrokeBytesMatch=true")

            val largest = notes.list().maxBy { it.strokes }
            val repository = NoteRepository(notes)
            val decodeStart = System.nanoTime(); val loaded = repository.load(largest.id)
            report("largestNoteLoadMs=${(System.nanoTime() - decodeStart) / 1_000_000} strokes=${loaded.entries.size}")
            assertTrue(loaded.entries.isNotEmpty())
            repository.updatePreview(NoteSnapshot(loaded.manifest, loaded.entries))
            val cold = NoteRepository(NoteFiles(File(work, "notes")))
            val previewStart = System.nanoTime(); val preview = cold.preview(largest.id)
            assertNotNull(android.graphics.BitmapFactory.decodeFile(preview.path))
            report("coldEmbeddedPreviewMs=${(System.nanoTime() - previewStart) / 1_000_000}")
            storage.write { /* Include the generated preview in the next external snapshot. */ }
            assertTrue(withTimeout(240000) { storage.awaitSync() })

            val reader = NotebookArchive(MisqArchiveDirectory(MisqDocumentFiles(context.contentResolver, MisqTestCopy.tree)))
            val revision = checkNotNull(reader.latest())
            val remoteWork = File(parent, "remote"); reader.restore(revision, remoteWork)
            val remotePresets = NotebookPresets.read(File(remoteWork, "presets.hwp"))
            val remoteLibrary = NotebookFolders.read(File(remoteWork, "folders.hwp"), LibraryPreferences())
            LibraryPreferencesFile(File(remoteWork, "library.hwp")).write(remoteLibrary.copy(paper = remotePresets.paper))
            SavedColors(File(remoteWork, "palette.hwp")).write(remotePresets.palettes)
            ToolPreferencesFile(File(remoteWork, "tools.hwp")).write(remotePresets.applyTo(ToolPreferences()))
            val branchStorage = NotebookStorage(context, remoteWork, scope)
            assertFalse(branchStorage.initialize())
            branchStorage.startBackground()
            assertTrue(withTimeout(240000) { branchStorage.awaitSync() })
            val remoteNotes = NoteFiles(File(remoteWork, "notes"))
            val target = notes.list().first { it.strokes > 1 }
            val initial = notes.read(target.id).manifest
            // Both writers add an independent stroke using the same numeric draw-order ID.
            val a = StrokeRef(initial.nextId, UUID.randomUUID().toString())
            val b = StrokeRef(initial.nextId, UUID.randomUUID().toString())
            val payload = notes.stroke(target.id, initial.strokes.first().storageId)
            storage.write { notes.save(initial.copy(strokes = initial.strokes + a, nextId = initial.nextId + 1)) { payload } }
            // Finish local publication, then edit a detached older branch to simulate a second device.
            assertTrue(withTimeout(240000) { storage.awaitSync() })
            branchStorage.write { remoteNotes.save(initial.copy(strokes = initial.strokes + b, nextId = initial.nextId + 1)) { payload } }
            // A separate library starting from that branch merges against the actual newer remote.
            assertFalse(withTimeout(240000) { branchStorage.awaitSync() })
            withTimeout(240000) { while (!File(remoteWork, ".notes-incoming").isDirectory) delay(50) }
            val restarted = NotebookStorage(context, remoteWork, scope)
            assertFalse(restarted.initialize())
            val merged = NoteFiles(File(remoteWork, "notes")).read(target.id).manifest
            assertEquals(initial.strokes.size + 2, merged.strokes.size)
            assertTrue(merged.strokes.any { it.storageId == a.storageId })
            assertTrue(merged.strokes.any { it.storageId == b.storageId })
            val mergedLoad = NoteRepository(NoteFiles(File(remoteWork, "notes"))).load(target.id)
            assertEquals(initial.strokes.size + 2, mergedLoad.entries.size)
            report("sameNoteIndependentEditsMergedAndReloaded=true")

            // A local-only restart must retain all migrated notes and hierarchy.
            val start = System.nanoTime(); val localRestart = NotebookStorage(context, work, scope)
            assertFalse(localRestart.initialize())
            assertEquals(expected.size, NoteFiles(File(work, "notes")).list().size)
            report("localRestartAndListMs=${(System.nanoTime() - start) / 1_000_000}")
        } finally {
            scope.cancel()
            val edit = prefs.edit().clear()
            previous.forEach { (key, value) -> when (value) { is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value) } }
            edit.commit()
            // Keep test copies and diagnostics for inspection. Never touch the production tree.
        }
    }
}
