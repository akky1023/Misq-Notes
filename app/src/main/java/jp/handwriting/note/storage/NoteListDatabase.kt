package jp.handwriting.note.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable
import java.io.File

/** Rebuildable local list index. Never used as the source of note content. */
internal class NoteListDatabase(file: File) : Closeable {
    private val db = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        try {
            if (version !in 0..2) throw SQLiteException("Unsupported note list index version")
            if (version == 0) {
                execSQL("""CREATE TABLE IF NOT EXISTS notes (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL,
                    strokes INTEGER NOT NULL, problem TEXT, created INTEGER NOT NULL, thumbnail TEXT,
                    color INTEGER NOT NULL, pattern INTEGER NOT NULL, line_color INTEGER NOT NULL,
                    spacing INTEGER NOT NULL, line_width REAL NOT NULL)""")
                execSQL("CREATE INDEX IF NOT EXISTS notes_updated ON notes(updated DESC)")
                execSQL("CREATE TABLE cache_state (ready INTEGER NOT NULL)")
                execSQL("INSERT INTO cache_state VALUES (0)")
                version = 1
            }
            if (version == 1) {
                execSQL("ALTER TABLE notes ADD COLUMN margin_color INTEGER NOT NULL DEFAULT -1795430")
                execSQL("ALTER TABLE notes ADD COLUMN margin_width REAL NOT NULL DEFAULT 1")
                execSQL("ALTER TABLE notes ADD COLUMN margin_offset REAL NOT NULL DEFAULT 100")
                execSQL("UPDATE cache_state SET ready = 0")
                version = 2
            }
            rawQuery("PRAGMA quick_check(1)", null).use { check ->
                if (!check.moveToFirst() || check.getString(0) != "ok") throw SQLiteException("Invalid note list index")
            }
        } catch (error: Exception) { close(); throw error }
    }

    val ready: Boolean get() = db.rawQuery("SELECT ready FROM cache_state", null).use { it.moveToFirst() && it.getInt(0) == 1 }

    fun replaceAll(notes: List<NoteSummary>) = transaction {
        db.delete("notes", null, null)
        notes.forEach(::upsert)
        db.execSQL("UPDATE cache_state SET ready = 1")
    }

    fun upsert(note: NoteSummary) {
        val values = ContentValues().apply {
            put("id", note.id); put("title", note.title); put("updated", note.updatedAt)
            put("strokes", note.strokes); put("problem", note.problem); put("created", note.createdAt)
            put("thumbnail", note.thumbnail); put("color", note.paper.color)
            put("pattern", note.paper.pattern.ordinal); put("line_color", note.paper.lineColor)
            put("spacing", note.paper.spacing); put("line_width", note.paper.lineWidth)
            put("margin_color", note.paper.marginColor); put("margin_width", note.paper.marginWidth); put("margin_offset", note.paper.marginOffset)
        }
        check(db.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    fun remove(id: String) { db.delete("notes", "id = ?", arrayOf(id)) }

    fun thumbnail(id: String, path: String) {
        db.update("notes", ContentValues().apply { put("thumbnail", path) }, "id = ?", arrayOf(id))
    }

    fun list(): List<NoteSummary> = db.rawQuery("SELECT * FROM notes ORDER BY updated DESC", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(NoteSummary(
                id = cursor.getString(0), title = cursor.getString(1), updatedAt = cursor.getLong(2),
                strokes = cursor.getInt(3), problem = cursor.getString(4), createdAt = cursor.getLong(5),
                thumbnail = cursor.getString(6), paper = NotePaper(cursor.getInt(7),
                    PaperPattern.entries[cursor.getInt(8)], cursor.getInt(9), cursor.getInt(10), cursor.getFloat(11), cursor.getInt(12), cursor.getFloat(13), cursor.getFloat(14)),
            ))
        }
    }

    private inline fun transaction(action: () -> Unit) {
        db.beginTransaction()
        try { action(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    override fun close() = db.close()
}
