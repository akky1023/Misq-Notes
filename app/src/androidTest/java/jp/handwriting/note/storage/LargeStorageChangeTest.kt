package jp.handwriting.note.storage

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Uses only a dedicated disposable SAF tree and a private cache library, never production data. */
class LargeStorageChangeTest {
    @Test fun selectSyncRestartAndImportLibraryLargerThanHeap() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe")
        AppMessages.configure(context)
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMisqStorageStress-20261102")
        check(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission })
        val prefs = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
        val oldTree = prefs.getString("tree", null); val oldLabel = prefs.getString("label", null)
        val parent = File(context.cacheDir, "storage-stress-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val work = File(parent, "local").also { it.mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs.edit().remove("tree").remove("label").commit()
        try {
            val notes = NoteFiles(File(work, "notes"))
            val repository = NoteRepository(notes)
            repeat(2) { number ->
                val note = repository.create("large-$number")
                RandomAccessFile(notes.document(note.info.id).file, "rw").use { it.setLength(160L * 1024 * 1024) }
            }
            val expected = notes.list().associate { it.id to NotebookArchive.digest(notes.document(it.id).file) }
            val heapLimit = Runtime.getRuntime().maxMemory()
            assertTrue("Fixture must exceed heap: $heapLimit", 320L * 1024 * 1024 > heapLimit)
            val storage = NotebookStorage(context, work, scope)
            val abandoned = File(work, ".notes-snapshot-${UUID.randomUUID()}").also { it.mkdirs(); File(it, "partial").writeText("interrupted") }
            assertFalse(storage.initialize())
            assertFalse(abandoned.exists())
            storage.change(tree)
            assertEquals(tree, storage.uri)
            assertTrue(withTimeout(300000) { storage.awaitSync() })
            assertFalse(File(work, ".notes-pending").exists())
            assertTrue(work.listFiles().orEmpty().none { it.name.startsWith(".notes-snapshot-") })

            val restarted = NotebookStorage(context, work, scope)
            assertFalse(restarted.initialize())
            assertTrue(withTimeout(300000) { restarted.awaitSync() })
            val imported = File(parent, "imported").also { it.mkdirs() }
            val importer = NotebookStorage(context, imported, scope)
            assertTrue(importer.initialize())
            importer.initializeRemote()
            assertTrue(withTimeout(300000) { importer.awaitSync() })
            val restored = NoteFiles(File(imported, "notes"))
            assertEquals(expected, restored.list().associate { it.id to NotebookArchive.digest(restored.document(it.id).file) })
            assertEquals(setOf("large-0", "large-1"), restored.list().map { it.title }.toSet())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nLARGE_STORAGE selected/synced/restarted/imported bytes=335544320 heapLimit=$heapLimit allHashesMatch=true\n")
            })
        } finally {
            scope.cancel()
            prefs.edit().putString("tree", oldTree).putString("label", oldLabel).commit()
            parent.deleteRecursively()
        }
    }
}
