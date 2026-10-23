package jp.handwriting.note.storage

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Lives inside the notes directory, so an archive restore/rollback replaces the index too.
 * No handles survive an operation. The archive includes only .hwn/.ink files, never this cache.
 * Callers serialize reads and writes through NoteRepository's lock.
 */
internal class NoteListCache(root: File) {
    private val file = File(root, ".list.db")
    private val dirty = File(root, ".list-dirty")

    fun list(scan: () -> List<NoteSummary>): List<NoteSummary> {
        fun read(): List<NoteSummary> {
            val notes = NoteListDatabase(file).use { db ->
                if (dirty.exists() || !db.ready) db.replaceAll(scan())
                db.list()
            }
            dirty.delete()
            return notes
        }
        try { return read() } catch (error: Exception) {
            Log.w("NoteListCache", "Rebuilding list index", error)
        }
        // Only derived files are discarded. A full/readonly disk must still permit reading notes.
        try {
            SQLiteDatabase.deleteDatabase(file)
            return read()
        } catch (error: Exception) {
            Log.w("NoteListCache", "Using note files without list index", error)
            return scan()
        }
    }

    /** Sync before changing the authoritative files, so process death cannot leave a stale index. */
    fun changing(): Boolean {
        val clean = !dirty.exists()
        if (clean) FileOutputStream(dirty).use { it.write(1); it.fd.sync() }
        return clean
    }

    fun saved(note: NoteSummary, wasClean: Boolean) = complete(wasClean) { it.upsert(note) }
    fun deleted(id: String, wasClean: Boolean) = complete(wasClean) { it.remove(id) }

    private fun complete(wasClean: Boolean, update: (NoteListDatabase) -> Unit) {
        try {
            NoteListDatabase(file).use(update)
            // A prior failed write may concern a different note; only a full rebuild clears it.
            if (wasClean) dirty.delete()
        } catch (error: Exception) {
            Log.w("NoteListCache", "List index will be rebuilt after note write", error)
        }
    }

    fun thumbnail(id: String, path: String) {
        try { NoteListDatabase(file).use { it.thumbnail(id, path) } }
        catch (error: Exception) { Log.w("NoteListCache", "Preview index update skipped", error) }
    }
}
