package jp.handwriting.note.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NoteListDatabaseTest {
    @Test fun collegePaperSurvivesIndexRestart() {
        val file = File.createTempFile("college-index", ".db", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val note = NoteSummary("note", "College", 1234, 0, paper = NotePaper(
                pattern = PaperPattern.COLLEGE_RULED, marginColor = 0x80445566.toInt(), marginWidth = 5f, marginOffset = 145f))
            NoteListDatabase(file).use { it.replaceAll(listOf(note)) }
            NoteListDatabase(file).use { assertTrue(it.ready); assertEquals(listOf(note), it.list()) }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun oldIndexIsMigratedAndMarkedForRebuild() {
        val file = File.createTempFile("old-paper-index", ".db", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("""CREATE TABLE notes (
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL,
                    strokes INTEGER NOT NULL, problem TEXT, created INTEGER NOT NULL, thumbnail TEXT,
                    color INTEGER NOT NULL, pattern INTEGER NOT NULL, line_color INTEGER NOT NULL,
                    spacing INTEGER NOT NULL, line_width REAL NOT NULL)""")
                db.execSQL("CREATE TABLE cache_state (ready INTEGER NOT NULL)")
                db.execSQL("INSERT INTO cache_state VALUES (1)")
                db.execSQL("INSERT INTO notes VALUES ('old', 'Old', 1234, 0, NULL, 1234, NULL, -1, 1, -16777216, 32, 1)")
                db.version = 1
            }
            NoteListDatabase(file).use {
                assertFalse(it.ready)
                assertEquals(NotePaper(color = -1, pattern = PaperPattern.RULED, lineColor = -16777216), it.list().single().paper)
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
}
