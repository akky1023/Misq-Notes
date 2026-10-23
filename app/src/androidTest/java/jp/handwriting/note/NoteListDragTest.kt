package jp.handwriting.note

import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.editor.EditorViewModel
import jp.handwriting.note.storage.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NoteListDragTest {
    @Test fun longPressReordersAndDropsIntoFoldersInGridAndList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val directory = File(instrumentation.targetContext.cacheDir, "library-drag-${UUID.randomUUID()}")
        val files = NoteFiles(File(directory, "notes"))
        val a = files.create("Drag A"); val b = files.create("Drag B")
        val folder = UUID.randomUUID().toString()
        LibraryPreferencesFile(File(directory, "library.hwp")).write(LibraryPreferences(folders = listOf(NoteFolder(folder, "Drop target"))))
        val store = ViewModelStore()
        lateinit var model: EditorViewModel
        fun waitUntil(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30)
            assertTrue(label, condition())
        }
        fun find(node: AccessibilityNodeInfo?, label: String): Rect? {
            if (node == null || !node.refresh()) return null
            if (node.contentDescription?.toString() == label) return Rect().also(node::getBoundsInScreen)
            return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label) }
        }
        fun bounds(label: String): Rect {
            var result: Rect? = null
            waitUntil(label) {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                result = find(automation.rootInActiveWindow, label)
                result != null
            }
            return result!!
        }
        fun drag(from: Rect, to: Rect) {
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
                    it.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
                }
            }
            val x = from.exactCenterX(); val y = from.exactCenterY()
            send(MotionEvent.ACTION_DOWN, x, y)
            try {
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                for (step in 1..12) {
                    send(MotionEvent.ACTION_MOVE, x + (to.exactCenterX() - x) * step / 12, y + (to.exactCenterY() - y) * step / 12)
                    SystemClock.sleep(25)
                }
            } finally { send(MotionEvent.ACTION_UP, to.exactCenterX(), to.exactCenterY()) }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    // All files and edits belong to a disposable fixture, never the user's library.
                    model = ViewModelProvider(object : ViewModelStoreOwner { override val viewModelStore = store })[EditorViewModel::class.java]
                    model.start(directory)
                    activity.setContent {
                        val workspace by model.workspace.collectAsState()
                        MaterialTheme { NoteList(workspace.notes, workspace.working, model, null, {}, {}) }
                    }
                }
                waitUntil("fixture loaded") { model.workspace.value.notes.size == 2 && !model.workspace.value.working }
                for (grid in listOf(true, false)) {
                    scenario.onActivity {
                        model.moveNote(a.info.id, null); model.listGrid(grid)
                        model.reorderNotes(null, listOf(a.info.id, b.info.id))
                    }
                    SystemClock.sleep(250)
                    if (grid) automation.takeScreenshot()?.let { screenshot ->
                        try { File(instrumentation.targetContext.cacheDir, "codex-library-grid.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        finally { screenshot.recycle() }
                    }
                    val from = bounds("Drag Aの先頭1000×1000"); val to = bounds("Drag Bの先頭1000×1000")
                    drag(from, to)
                    try {
                        waitUntil("reorder in grid=$grid") { model.library.value.value.orders[""] == listOf(b.info.id, a.info.id) }
                    } catch (failure: AssertionError) {
                        throw AssertionError("grid=$grid from=$from to=$to actual=${model.library.value.value} open=${model.workspace.value.current?.id}", failure)
                    }
                    SystemClock.sleep(150)
                    drag(bounds("Drag Aの先頭1000×1000"), bounds("フォルダ：Drop target"))
                    waitUntil("folder drop in grid=$grid") { model.library.value.value.locations[a.info.id] == folder }
                    assertNull("Dragging must not open a note", model.workspace.value.current)
                }
                waitUntil("library saved") { LibraryPreferencesFile(File(directory, "library.hwp")).read().locations[a.info.id] == folder }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            directory.deleteRecursively()
        }
    }
}
