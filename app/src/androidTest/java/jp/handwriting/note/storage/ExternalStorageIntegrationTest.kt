package jp.handwriting.note.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
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
            scope.cancel(); failWrites(false)
            DocumentsContract.deleteDocument(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            prefs.edit().putString("tree", oldTree).putString("label", oldLabel).commit()
            directory.deleteRecursively()
        }
    }
    private fun bodies(directory: File) = File(directory, "notes").listFiles().orEmpty().filter { it.extension == "misq" }
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
