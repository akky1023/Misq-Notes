package jp.handwriting.note.storage

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.ExperimentActivity
import jp.handwriting.note.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32

class RealDataExperimentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nREAL_DATA $message\n") })
    private fun ms(action: () -> Unit): Double { val t = System.nanoTime(); action(); return (System.nanoTime() - t) / 1_000_000.0 }
    private fun median(values: List<Double>) = values.sorted()[values.size / 2]

    @Test fun prepareCopiedNotebook() {
        AppMessages.configure(context)
        val revision = RealDataExperiment.prepare(context)
        val summaries = NoteFiles(File(RealDataExperiment.seed(context), "notes")).list()
        assertTrue(summaries.isNotEmpty()); assertTrue(summaries.none { it.problem != null })
        report("prepared notes=${summaries.size} strokes=${summaries.sumOf { it.strokes }} files=${revision.files.size} bytes=${RealDataExperiment.files(RealDataExperiment.seed(context)).values.sumOf { it.length() }}")
    }

    @Test fun compareCopiedFilesSqliteAndPackedArchive() {
        AppMessages.configure(context)
        val seed = RealDataExperiment.seed(context)
        val files = RealDataExperiment.files(seed)
        assertTrue(files.isNotEmpty())
        val expected = files.mapValues { NotebookArchive.digest(it.value.readBytes()) }
        val dbFile = File(RealDataExperiment.root(context), "stroke-copy-${UUID.randomUUID()}.db")
        val sqliteBuild = ms {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                db.execSQL("CREATE TABLE files (path TEXT PRIMARY KEY, hash TEXT NOT NULL, payload BLOB NOT NULL)")
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT INTO files VALUES (?, ?, ?)").use { insert ->
                        files.forEach { (path, file) ->
                            insert.clearBindings(); insert.bindString(1, path); insert.bindString(2, expected.getValue(path))
                            insert.bindBlob(3, file.readBytes()); insert.executeInsert()
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
        val fileReads = mutableListOf<Double>(); val dbReads = mutableListOf<Double>()
        repeat(3) {
            fileReads += ms { assertEquals(expected, files.mapValues { NotebookArchive.digest(it.value.readBytes()) }) }
            dbReads += ms {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val hashes = mutableMapOf<String, String>()
                    db.rawQuery("SELECT path, payload FROM files", null).use { cursor ->
                        while (cursor.moveToNext()) hashes[cursor.getString(0)] = NotebookArchive.digest(cursor.getBlob(1))
                    }
                    assertEquals(expected, hashes)
                }
            }
        }
        report("local fileVerifyMedianMs=${median(fileReads)} sqliteVerifyMedianMs=${median(dbReads)} sqliteBuildMs=$sqliteBuild sqliteBytes=${dbFile.length()} files=${files.size}")
        val noteFiles = NoteFiles(File(seed, "notes"))
        val largest = noteFiles.list().maxBy { it.strokes }
        val manifest = noteFiles.read(largest.id).manifest
        val openFiles = mutableListOf<Double>(); val openDb = mutableListOf<Double>()
        repeat(3) {
            openFiles += ms { assertEquals(largest.strokes, NoteRepository(NoteFiles(File(seed, "notes"))).load(largest.id).entries.size) }
            openDb += ms {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    manifest.strokes.forEach { ref ->
                        db.rawQuery("SELECT payload FROM files WHERE path = ?", arrayOf("notes/${largest.id}/${ref.storageId}.ink")).use { cursor ->
                            check(cursor.moveToFirst())
                            NoteRepository.decodeStroke(ref.storageId, envelope(cursor.getBlob(0)))
                        }
                    }
                }
            }
        }
        report("noteOpen strokes=${largest.strokes} fileMedianMs=${median(openFiles)} sqliteMedianMs=${median(openDb)} sqliteUsesExistingManifest=true")
        val resolver = context.contentResolver
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(RealDataExperiment.tree, DocumentsContract.getTreeDocumentId(RealDataExperiment.tree))
        val target = checkNotNull(DocumentsContract.createDocument(resolver, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, "experiment-packs-${UUID.randomUUID()}"))
        val uris = mutableMapOf<String, android.net.Uri>()
        lateinit var packs: List<String>
        val packBuild = ms {
            packs = PackedNoteExperiment.write(files) { name ->
                val uri = checkNotNull(DocumentsContract.createDocument(resolver, target, "application/octet-stream", name))
                uris[name] = uri
                checkNotNull(resolver.openOutputStream(uri, "w"))
            }
        }
        val packReads = (1..3).map {
            ms { PackedNoteExperiment.verify(packs, expected) { name -> checkNotNull(resolver.openInputStream(uris.getValue(name))) } }
        }
        report("safPacks count=${packs.size} buildMs=$packBuild verifyMedianMs=${median(packReads)} samplesMs=${packReads.joinToString(",")}")
    }

    @Test fun copiedNotebookIsUsableWhileExternalCheckRuns() = runBlocking {
        AppMessages.configure(context)
        val summaries = NoteFiles(File(RealDataExperiment.seed(context), "notes")).list()
        val largest = summaries.maxBy { it.strokes }
        val started = SystemClock.elapsedRealtime()
        val intent = Intent(context, ExperimentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ExperimentActivity>(intent).use { scenario ->
            lateinit var model: EditorViewModel; var verification: Job? = null
            scenario.onActivity { model = it.experimentModel; verification = it.verification }
            val ready = withTimeout(20000) { model.workspace.first { !it.working } }
            assertNull(ready.error); assertEquals(summaries.map { it.id }.toSet(), ready.notes.map { it.id }.toSet())
            val readyMs = SystemClock.elapsedRealtime() - started
            assertFalse("External validation must not gate the library", checkNotNull(verification).isCompleted)
            val openStarted = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { model.openNote(largest.id) }
            val opened = withTimeout(20000) { model.workspace.first { !it.working && it.current != null } }
            assertNull(opened.error)
            assertEquals(largest.strokes, withContext(Dispatchers.Main) { model.edits.entries.size })
            val openMs = SystemClock.elapsedRealtime() - openStarted
            withContext(Dispatchers.Main) { model.closeNote() }
            withTimeout(20000) { model.workspace.first { !it.working && it.current == null } }
            report("localFirst listReadyMs=$readyMs largestNoteOpenMs=$openMs strokes=${largest.strokes} externalStillRunning=${verification?.isActive}")
            withTimeout(180000) { checkNotNull(verification).join() }
            scenario.onActivity { assertNull(it.verificationFailure); assertTrue(it.verificationSucceeded) }
            report("externalCheckEndMs=${SystemClock.elapsedRealtime() - started}")
        }
    }

    private fun envelope(bytes: ByteArray): ByteArray = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        check(input.readInt() == 0x484E4231)
        val size = input.readInt(); check(size in 0..128 * 1024 * 1024 && bytes.size == size + 16)
        val data = ByteArray(size).also(input::readFully)
        check(input.readLong() == CRC32().also { it.update(data) }.value)
        data
    }
}
