package jp.handwriting.note.storage

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shares the short local-file lock, but releases it before any external provider operation. */
internal class ArchiveSync(scope: CoroutineScope, private val pending: File, private val localLock: Any,
    private val files: () -> Map<String, File>, batchMillis: Long = 750,
    private val committed: (Map<String, ArchiveBlob>) -> Unit = {}, private val conflicted: () -> Unit = {}) {
    private var target: NotebookArchive? = null
    private var version = 0L
    private val queue = SaveQueue<NotebookArchive>(scope, batchMillis) { requested -> withContext(Dispatchers.IO) {
        val snapshot = synchronized(localLock) {
            if (target !== requested) null else version to requested.capture(files())
        }
        if (snapshot != null) {
            try { synchronized(requested) { requested.commitSnapshot(snapshot.second) } }
            catch (error: ArchiveConflictException) { conflicted(); throw error }
            synchronized(localLock) {
                if (target === requested) committed(snapshot.second)
                // An older successful sync must never acknowledge a later unsynchronized edit.
                if (target === requested && version == snapshot.first) pending.delete()
            }
        }
    } }
    val state get() = queue.state
    fun attach(archive: NotebookArchive?) = synchronized(localLock) { target = archive; version++ }
    fun dirty(track: Boolean = target != null) = synchronized(localLock) {
        version++
        if (track && !pending.exists()) FileOutputStream(pending).use { out -> out.write(1); out.fd.sync() }
    }
    fun request() = synchronized(localLock) { target?.let { queue.request(it) } }
    suspend fun await(): Boolean = queue.await(queue.state.value.latest)
}
