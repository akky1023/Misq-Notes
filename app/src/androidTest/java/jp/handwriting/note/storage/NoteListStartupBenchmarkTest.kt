package jp.handwriting.note.storage

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.NotebookApp
import jp.handwriting.note.PerformanceActivity
import jp.handwriting.note.editor.EditorViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject

/** Opt-in benchmark of the installed probe library. Never clears or deletes library data. */
class NoteListStartupBenchmarkTest {
    @Test fun installedLibraryStartup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureInstalledLibrary") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        measure(context, context.filesDir, "installed")
    }

    @Test fun productionSnapshotStartup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureProductionSnapshot") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "net.neomenia.misqnotes.probe")
        // The source is an explicitly authorized on-device copy, never the production app's files.
        val source = File(base.getExternalFilesDir(null), "note-list-production-snapshot.zip")
        val directory = File(base.filesDir, "note-list-benchmark-${UUID.randomUUID()}").also { check(it.mkdir()) }
        ZipFile(source).use { zip ->
            val hashes = zip.getInputStream(zip.getEntry("manifest.sha256")).bufferedReader().useLines { lines ->
                lines.associate { it.substring(66) to it.substring(0, 64) }
            }
            fun copy(name: String) {
                val entry = zip.getEntry("files/$name") ?: return
                val target = File(directory, name)
                check(target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                target.parentFile!!.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                check(ContentHash.digest(target) == hashes[entry.name])
            }
            copy(".format3-active")
            val active = JSONObject(String(NoteFiles.readEnvelope(File(directory, ".format3-active")), Charsets.UTF_8)).getString("directory")
            require(active.matches(Regex("format3-[a-f0-9-]{36}")))
            zip.entries().asSequence().filter { !it.isDirectory && (it.name.startsWith("files/$active/") ||
                (it.name.startsWith("files/format3-working/notes/") && it.name.endsWith("/thumbnail-v2.png"))) }
                .forEach { copy(it.name.removePrefix("files/")) }
            listOf("library.hwp", "library.hwp.bak", "palette.hwp", "palette.hwp.bak", "tools.hwp", "tools.hwp.bak").forEach(::copy)
        }
        val prefix = directory.name + "-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
            override fun getApplicationContext(): Context = this
        }
        measure(context, directory, "production-copy")
        // Preserve both the source archive and extracted test notes for follow-up measurements.
    }

    private suspend fun measure(context: Context, directory: File, label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(context.packageName == "net.neomenia.misqnotes.probe")
        AppMessages.configure(context)
        val models = ViewModelStore()
        val start = System.nanoTime()
        val activity = instrumentation.startActivitySync(Intent(context, PerformanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PerformanceActivity
        val rendered = CompletableDeferred<Double>()
        val model = withContext(Dispatchers.Main) {
            ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also {
                val editor = it
                activity.window.decorView.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                    private var requested = false
                    override fun onDraw() {
                        if (!requested && !editor.workspace.value.working && editor.workspace.value.notes.isNotEmpty()) {
                            requested = true
                            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback {
                                rendered.complete((System.nanoTime() - start) / 1_000_000.0)
                            }
                        }
                    }
                })
                activity.setContent { NotebookApp(editor, {}, {}, {}) }
                it.configureStorage(context, directory); it.start(directory)
            }
        }
        try {
            withTimeout(240_000) { while (model.workspace.value.working) delay(5) }
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            val state = model.workspace.value
            val frameMs = withTimeout(10000) { rendered.await() }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nNOTE_LIST source=$label startupMs=$elapsed frameCommitMs=$frameMs notes=${state.notes.size} error=${state.error} syncError=${state.syncError}\n")
            })
            assertNull(state.error)
            assertNull(state.syncError)
            assertTrue(state.notes.isNotEmpty())
            InstrumentationRegistry.getArguments().getString("maxListStartupMs")?.toDouble()?.let { maximum ->
                assertTrue("List frame took $frameMs ms (limit $maximum ms)", frameMs <= maximum)
            }
            val expected = NoteFiles(directory, Format3Library(Format3Storage(directory).open(), File(directory, "format3-working/notes"))).list()
            assertEquals(expected.map { it.id }.toSet(), state.notes.map { it.id }.toSet())
            assertEquals(expected.associate { it.id to Triple(it.title, it.updatedAt, it.strokes) },
                state.notes.associate { it.id to Triple(it.title, it.updatedAt, it.strokes) })
        } finally {
            val job = model.viewModelScope.coroutineContext[Job]!!
            withContext(Dispatchers.Main) { models.clear(); activity.finish() }
            job.join()
        }
    }
}
