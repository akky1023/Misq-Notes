package jp.handwriting.note.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookFlowTest {
    @Test fun autosaveCloseReopenAndNotebookIsolation() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "workspace-${UUID.randomUUID()}")
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also { it.start(root) }
            }
            suspend fun ready(predicate: (WorkspaceUi) -> Boolean) = withTimeout(15000) { model.workspace.first { !it.working && predicate(it) } }
            ready { it.current == null }
            withContext(Dispatchers.Main) { model.createNote("A") }
            val first = ready { it.current != null }.current!!.id
            withContext(Dispatchers.Main) {
                val inputs = MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 200f, 0, pressure = 0.5f)
                    .add(InputToolType.STYLUS, 200f, 300f, 20, pressure = 0.7f)
                val stroke = EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.pressurePen(), 0xFF205EB8.toInt(), 3f, 0.1f), inputs))
                val id = model.edits.reserveStroke(); model.edits.resolveStroke(id, stroke)
                model.closeNote()
                model.viewportChanged() // Simulate an additional lifecycle callback while closing.
            }
            assertEquals(1, ready { it.current == null }.notes.single().strokes)
            withContext(Dispatchers.Main) { model.createNote("B") }
            ready { it.current != null }
            assertEquals(0, withContext(Dispatchers.Main) { model.edits.entries.size })
            withContext(Dispatchers.Main) { model.closeNote() }
            ready { it.current == null }
            withContext(Dispatchers.Main) { model.openNote(first) }
            ready { it.current?.id == first }
            assertEquals(1, withContext(Dispatchers.Main) { model.edits.entries.size })
            assertFalse(withContext(Dispatchers.Main) { model.edits.canUndo })
            assertFalse(model.workspace.value.saveFailed)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            root.deleteRecursively()
        }
    }
}
