package jp.handwriting.note.storage

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual production storage coordinator on copies only. */
class PackedStorageIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPACKED_STORAGE $value\n") })
    @Test fun migrateEditDuringConnectionRestartImportAndConflictsOnRealCopy() = runBlocking {
        AppMessages.configure(context)
        val parent = File(context.cacheDir, "packed-storage-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val work = File(parent, "work")
        check(RealDataExperiment.seed(context).copyRecursively(work))
        val prefs = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
        val oldTree = prefs.getString("tree", null); val oldLabel = prefs.getString("label", null)
        prefs.edit().putString("tree", RealDataExperiment.tree.toString()).putString("label", "Experiment copy").commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val notes = NoteFiles(File(work, "notes")).list()
            val edited = notes.minBy { it.strokes }
            val storage = NotebookStorage(context, work, scope)
            val start = System.nanoTime()
            assertFalse(storage.initialize())
            report("localInitializeMs=${(System.nanoTime() - start) / 1_000_000}")
            storage.startBackground()
            val editStart = System.nanoTime()
            storage.write { NoteFiles(File(work, "notes")).rename(edited.id, "copy edit during connection") }
            report("editDuringConnectionMs=${(System.nanoTime() - editStart) / 1_000_000}")
            assertTrue(File(work, ".notes-pending").exists())
            assertTrue("Migration/sync must succeed: ${storage.syncState.first().error}", withTimeout(240000) { storage.awaitSync() })
            assertFalse(File(work, ".notes-pending").exists())
            assertEquals("copy edit during connection", NoteFiles(File(work, "notes")).list().single { it.id == edited.id }.title)
            val physical = PackedDocumentFiles(context.contentResolver, RealDataExperiment.tree)
            val remoteReader = NotebookArchive(PackedArchiveDirectory(physical))
            val remote = checkNotNull(remoteReader.latest())
            val restored = File(parent, "restored")
            remoteReader.restore(remote, restored)
            assertEquals(NoteFiles(File(work, "notes")).list().map { it.id to it.title }, NoteFiles(File(restored, "notes")).list().map { it.id to it.title })
            report("migrated files=${remote.files.size} physicalObjects=${physical.names().size}")

            val restarted = NotebookStorage(context, work, scope)
            val restartStart = System.nanoTime(); assertFalse(restarted.initialize())
            restarted.startBackground(); assertTrue(withTimeout(30000) { restarted.awaitSync() })
            report("packedRestartAndVerifyMs=${(System.nanoTime() - restartStart) / 1_000_000}")

            // A clean local library may stage a remote update, but must not swap live files.
            val beforeRemote = File(parent, "before-remote")
            check(work.copyRecursively(beforeRemote))
            NoteFiles(File(restored, "notes")).rename(edited.id, "remote update")
            val sourceFiles = remote.files.keys.associateWith { File(restored, it) }
            remoteReader.commit(sourceFiles)
            val downloader = NotebookStorage(context, work, scope)
            assertFalse(downloader.initialize()); downloader.startBackground()
            assertFalse(withTimeout(30000) { downloader.awaitSync() })
            assertTrue(File(work, ".notes-incoming").isDirectory)
            assertEquals("copy edit during connection", NoteFiles(File(work, "notes")).list().single { it.id == edited.id }.title)
            val afterRestart = NotebookStorage(context, work, scope)
            assertFalse(afterRestart.initialize())
            assertEquals("remote update", NoteFiles(File(work, "notes")).list().single { it.id == edited.id }.title)
            report("remoteUpdateStagedAndAppliedOnRestart=true")

            // Local and remote edits from the same base must both survive, without overwrite.
            val conflicted = NotebookStorage(context, beforeRemote, scope)
            assertFalse(conflicted.initialize())
            conflicted.write { NoteFiles(File(beforeRemote, "notes")).rename(edited.id, "local conflict copy") }
            conflicted.startBackground()
            assertFalse(withTimeout(30000) { conflicted.awaitSync() })
            assertTrue(conflicted.blockedByRemoteChange)
            assertEquals("local conflict copy", NoteFiles(File(beforeRemote, "notes")).list().single { it.id == edited.id }.title)
            assertTrue(beforeRemote.listFiles().orEmpty().any { it.name.startsWith(".notes-incoming-") })
            val remoteAgain = NotebookArchive(PackedArchiveDirectory(PackedDocumentFiles(context.contentResolver, RealDataExperiment.tree)))
            val finalRestore = File(parent, "remote-again")
            remoteAgain.restore(checkNotNull(remoteAgain.latest()), finalRestore)
            assertEquals("remote update", NoteFiles(File(finalRestore, "notes")).list().single { it.id == edited.id }.title)
            report("concurrentLocalAndRemoteVersionsBothPreserved=true")
        } finally {
            scope.cancel()
            prefs.edit().putString("tree", oldTree).putString("label", oldLabel).commit()
            // Preserve copies for inspection if an assertion failed; never touch production data.
        }
    }
}
