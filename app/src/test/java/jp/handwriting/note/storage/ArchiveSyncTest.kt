package jp.handwriting.note.storage

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveSyncTest {
    @get:Rule val temp = TemporaryFolder()
    private class Directory : ArchiveDirectory {
        val records = ConcurrentHashMap<String, ByteArray>()
        var beforeRevision: () -> Unit = {}
        @Volatile var fail = false
        override fun names() = records.keys.toSet()
        override fun read(name: String) = records.getValue(name)
        override fun write(name: String, bytes: ByteArray) {
            if (fail) throw IOException("Storage unavailable")
            if (name.startsWith("revision-")) beforeRevision()
            records[name] = bytes.copyOf()
        }
        override fun delete(name: String) { records.remove(name) }
    }
    private fun files(): Map<String, File> {
        val folders = temp.newFile("folders.hwp")
        NotebookFolders.write(folders, LibraryPreferences().added("first", null))
        return mapOf("folders.hwp" to folders)
    }
    @Test fun editsBeforeAConnectionStillLeaveADurablePendingMarker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val files = files(); val pending = File(temp.root, "pending")
            val sync = ArchiveSync(scope, pending, Any(), { files }, batchMillis = 0)
            sync.dirty(true)
            assertTrue(pending.exists())
            sync.attach(NotebookArchive(Directory())); sync.request()
            assertTrue(withTimeout(5000) { sync.await() }); assertFalse(pending.exists())
        } finally { scope.cancel() }
    }
    @Test fun slowExternalWriteDoesNotBlockLocalEditsAndCannotAcknowledgeANewerEdit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        val firstRelease = CountDownLatch(1); val secondRelease = CountDownLatch(1)
        try {
            val files = files(); val pending = File(temp.root, "pending"); val lock = Any()
            val directory = Directory(); val sequence = AtomicInteger()
            directory.beforeRevision = {
                val first = sequence.incrementAndGet() == 1
                (if (first) firstStarted else secondStarted).countDown()
                check((if (first) firstRelease else secondRelease).await(10, TimeUnit.SECONDS))
            }
            val archive = NotebookArchive(directory)
            val sync = ArchiveSync(scope, pending, lock, { files }, batchMillis = 10)
            sync.attach(archive); sync.dirty(); sync.request()
            assertTrue(withContext(Dispatchers.IO) { firstStarted.await(5, TimeUnit.SECONDS) })
            withTimeout(1000) { withContext(Dispatchers.IO) { synchronized(lock) {
                sync.dirty()
                NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("second", null))
                sync.request()
            } } }
            assertTrue(pending.exists())
            firstRelease.countDown()
            assertTrue(withContext(Dispatchers.IO) { secondStarted.await(5, TimeUnit.SECONDS) })
            assertTrue("The first successful sync must leave the newer edit pending", pending.exists())
            secondRelease.countDown()
            assertTrue(withTimeout(5000) { sync.await() })
            assertFalse(pending.exists())
            val restored = temp.newFolder()
            val reader = NotebookArchive(directory); reader.restore(reader.latest()!!, restored)
            assertEquals("second", NotebookFolders.read(File(restored, "folders.hwp"), LibraryPreferences()).folders.single().name)
        } finally { firstRelease.countDown(); secondRelease.countDown(); scope.cancel() }
    }
    @Test fun unavailableExternalStorageKeepsLocalFilesAndPendingMarkerUntilRetrySucceeds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val files = files(); val pending = File(temp.root, "pending")
            val directory = Directory().apply { fail = true }
            val sync = ArchiveSync(scope, pending, Any(), { files }, batchMillis = 0)
            sync.attach(NotebookArchive(directory)); sync.dirty(); sync.request()
            assertFalse(withTimeout(5000) { sync.await() })
            assertTrue(pending.exists())
            assertEquals("first", NotebookFolders.read(files.getValue("folders.hwp"), LibraryPreferences()).folders.single().name)
            directory.fail = false; sync.request()
            assertTrue(withTimeout(5000) { sync.await() })
            assertFalse(pending.exists())
        } finally { scope.cancel() }
    }
}
