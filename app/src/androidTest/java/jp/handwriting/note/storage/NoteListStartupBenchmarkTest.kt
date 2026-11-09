package jp.handwriting.note.storage

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.editor.EditorViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in benchmark of the installed probe library. Never clears or deletes library data. */
class NoteListStartupBenchmarkTest {
    @Test fun installedLibraryStartup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureInstalledLibrary") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe")
        AppMessages.configure(context)
        val models = ViewModelStore()
        val start = System.nanoTime()
        val model = withContext(Dispatchers.Main) {
            ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also {
                it.configureStorage(context); it.start(context.filesDir)
            }
        }
        try {
            withTimeout(240_000) { while (model.workspace.value.working) delay(5) }
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            val state = model.workspace.value
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nNOTE_LIST startupMs=$elapsed notes=${state.notes.size} error=${state.error} syncError=${state.syncError}\n")
            })
            assertNull(state.error)
            assertNull(state.syncError)
            assertTrue(state.notes.isNotEmpty())
        } finally {
            val job = model.viewModelScope.coroutineContext[Job]!!
            withContext(Dispatchers.Main) { models.clear() }
            job.join()
        }
    }
}
