package jp.handwriting.note.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import jp.handwriting.note.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SelectionContactTest {
    private fun importedImage() = EditorStroke(jp.handwriting.note.storage.ImageRecord(
        jp.handwriting.note.storage.ImageData(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAwAAAAICAYAAADN5B7xAAAAEUlEQVR4nGNgGBrgPxgNXwAAvNoB/z2zBvkAAAAASUVORK5CYII=")),
        200f, 200f, 400f, 300f))

    @Test fun lassoCanExcludeMediaWhileLongPressSelectionStillHidesCapture() = runBlocking {
        withCanvas { model, _ ->
            val enclosure = listOf(WorldPoint(50f, 50f), WorldPoint(700f, 50f), WorldPoint(700f, 600f), WorldPoint(50f, 600f))
            withContext(Dispatchers.Main) {
                model.clearSelection()
                model.edits.addAll(listOf(importedImage()))
                model.lassoIncludeMedia(false)
                model.selectLasso(enclosure)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                assertEquals(1, model.ui.value.selectionCount)
                assertTrue(model.selectionOverlay.value!!.canCapture)
                model.lassoIncludeMedia(true)
                model.selectLasso(enclosure)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                assertEquals(2, model.ui.value.selectionCount)
                assertFalse(model.selectionOverlay.value!!.canCapture)
                model.clearSelection()
                model.lassoIncludeMedia(false)
                assertTrue(model.selectImportedImage(300f, 300f))
                assertFalse(model.selectionOverlay.value!!.canCapture)
            }
        }
    }

    @Test fun mediaPinchDefaultsOnAndResizesAroundImageCenterOnlyWhenEnabled() = runBlocking {
        withCanvas { model, view ->
            withContext(Dispatchers.Main) {
                model.clearSelection()
                model.edits.addAll(listOf(importedImage()))
                assertTrue(model.selectImportedImage(300f, 300f))
                assertTrue(model.preferences.value.value.pinchResizeMedia)
                model.pinchResizeMedia(false)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 300f, 300f)
                sendPair(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f, 500f)
                sendPair(view, MotionEvent.ACTION_MOVE, 350f, 450f)
                assertEquals(1f, model.selectionScale, 0f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_CANCEL, 350f, 300f)
                model.pinchResizeMedia(true)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 300f, 300f)
                sendPair(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f, 500f)
                sendPair(view, MotionEvent.ACTION_MOVE, 350f, 450f)
                assertEquals(0.5f, model.selectionScale, 0.01f)
                assertEquals(WorldPoint(100f, 75f), model.selectionOffset)
                sendPair(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 350f, 450f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 350f, 300f)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                val image = model.resizableImage!!
                assertEquals(200f, image.width, 0.01f)
                assertEquals(400f, image.left + image.width / 2f, 0.01f)
                assertEquals(350f, image.top + image.height / 2f, 0.01f)
            }
        }
    }

    private fun sendPair(view: InkCanvasView, action: Int, firstX: Float, secondX: Float) {
        eventTime += 20
        val event = MotionEvent.obtain(downTime, eventTime, action, 2,
            Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } },
            Array(2) { index -> MotionEvent.PointerCoords().apply { x = if (index == 0) firstX else secondX; y = 300f; pressure = 0.5f } },
            0, 0, 1f, 1f, 14, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun importedImageLongPressMovesResizesCancelsAndSupportsUndo() = runBlocking {
        withCanvas { model, view ->
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAwAAAAICAYAAADN5B7xAAAAEUlEQVR4nGNgGBrgPxgNXwAAvNoB/z2zBvkAAAAASUVORK5CYII=")
            withContext(Dispatchers.Main) {
                model.clearSelection()
                model.edits.addAll(listOf(EditorStroke(jp.handwriting.note.storage.ImageRecord(
                    jp.handwriting.note.storage.ImageData(png), 200f, 200f, 400f, 300f))))
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 300f, 300f)
            }
            delay(ViewConfiguration.getLongPressTimeout().toLong() + 200)
            withContext(Dispatchers.Main) {
                assertNotNull(model.resizableImage)
                assertNotNull(model.selectionOverlay.value)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 350f, 340f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 350f, 340f)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                assertEquals(250f, model.resizableImage!!.left, 0.01f)
                assertEquals(240f, model.resizableImage!!.top, 0.01f)
                // Drag the bottom-right handle to half size, preserving the aspect ratio.
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 650f, 540f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 450f, 390f)
                assertEquals(0.5f, model.selectionScale, 0.01f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 450f, 390f)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                assertEquals(200f, model.resizableImage!!.width, 0.01f)
                assertEquals(150f, model.resizableImage!!.height, 0.01f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 450f, 390f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 550f, 465f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_CANCEL, 550f, 465f)
                assertEquals(1f, model.selectionScale, 0f)
                assertEquals(200f, model.resizableImage!!.width, 0.01f)
                model.edits.undo()
                assertEquals(400f, model.resizableImage!!.width, 0.01f)
                model.clearSelection()
                assertNull(model.selectionOverlay.value)
            }
        }
    }

    @Test fun rectangleLassoSelectsWithADiagonalDragWhileFreeLassoRequiresAnEnclosure() = runBlocking {
        withCanvas { model, view ->
            withContext(Dispatchers.Main) {
                model.clearSelection()
                model.chooseTool(EditorTool.LASSO)
                model.chooseLassoMode(LassoMode.RECTANGLE)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_DOWN, 80f, 80f)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_MOVE, 180f, 180f)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_UP, 180f, 180f)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                assertEquals(1, model.ui.value.selectionCount)
                model.clearSelection()
                model.chooseLassoMode(LassoMode.FREE)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_DOWN, 80f, 80f)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_MOVE, 180f, 180f)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_UP, 180f, 180f)
                assertEquals(0, model.ui.value.selectionCount)
                assertEquals(1, model.edits.entries.size)
            }
        }
    }

    @Test fun longPressMenuSurvivesActivityWindowFocusLossAndFingerRelease() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: EditorViewModel
            lateinit var view: InkCanvasView
            scenario.onActivity { model = ViewModelProvider(it)[EditorViewModel::class.java] }
            withTimeout(15000) { model.workspace.first { !it.working } }
            scenario.onActivity { model.createNote("Long press focus regression") }
            withTimeout(15000) { model.workspace.first { it.current != null && !it.working } }
            val noteId = model.workspace.value.current!!.id
            fun findCanvas(root: View): InkCanvasView? {
                if (root is InkCanvasView) return root
                if (root is ViewGroup) for (i in 0 until root.childCount) {
                    findCanvas(root.getChildAt(i))?.let { return it }
                }
                return null
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            try {
                withTimeout(15000) {
                    var found = false
                    while (!found) {
                        scenario.onActivity { activity ->
                            findCanvas(activity.window.decorView)?.takeIf { it.width > 0 }?.let {
                                view = it; found = true
                            }
                        }
                        if (!found) delay(50)
                    }
                }
                withContext(Dispatchers.Main) {
                    send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, view.width * 0.6f, view.height * 0.4f)
                }
                delay(ViewConfiguration.getLongPressTimeout().toLong() + 200)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse(activity.hasWindowFocus())
                    send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, view.width * 0.6f, view.height * 0.4f)
                }
                instrumentation.waitForIdleSync()
                val item = instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText(AppMessages.text(R.string.insert_space)).single()
                assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertTrue(instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText(AppMessages.text(R.string.insert_space)).isEmpty())
            } finally {
                scenario.onActivity { findCanvas(it.window.decorView)?.settleForPause(); model.closeNote() }
                withTimeout(15000) { model.workspace.first { it.current == null && !it.working } }
                scenario.onActivity { model.deleteNote(noteId) }
                withTimeout(15000) { model.workspace.first { !it.working } }
            }
        }
    }

    @Test fun fingerLongPressMenuInsertsSpaceWithVerticalDrag() = runBlocking {
        withCanvas { model, view ->
            startSpaceInsertion(model, view)
            withContext(Dispatchers.Main) {
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 500f, 50f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 600f, 170f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 600f, 170f)
                assertTrue(model.insertingSpace)
                assertEquals(WorldPoint(0f, 120f), model.selectionOffset)
                assertEquals(100f, model.edits.snapshot().single().value.ink.inputs[0].y, 0.01f)
                // Re-grab the circle at its new position and reduce the gap.
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 500f, 170f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 600f, 130f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 600f, 130f)
                assertEquals(WorldPoint(0f, 80f), model.selectionOffset)
                assertTrue(model.insertingSpace)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 600f, 500f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 600f, 500f)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                val input = model.edits.snapshot().single().value.ink.inputs[0]
                assertEquals(100f, input.x, 0.01f)
                assertEquals(180f, input.y, 0.01f)
                assertNull(model.selectionBounds)
                model.edits.undo()
                assertEquals(100f, model.edits.snapshot().single().value.ink.inputs[0].y, 0.01f)
            }
        }
    }

    @Test fun writingExitsSpaceInsertionAndKeepsTheFirstPenStroke() = runBlocking {
        withCanvas { model, view ->
            startSpaceInsertion(model, view)
            withContext(Dispatchers.Main) {
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 500f, 50f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 500f, 130f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 500f, 130f)
                assertTrue(model.insertingSpace)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_DOWN, 500f, 500f)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_MOVE, 520f, 520f)
                send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_UP, 540f, 540f)
            }
            withTimeout(15000) {
                while (withContext(Dispatchers.Main) { model.insertingSpace }) delay(10)
            }
            withContext(Dispatchers.Main) {
                view.settleForPause()
                val entries = model.edits.snapshot()
                assertEquals(2, entries.size)
                assertEquals(180f, entries.first().value.ink.inputs[0].y, 0.01f)
                assertEquals(500f, entries.last().value.ink.inputs[0].x, 0.01f)
                assertEquals(500f, entries.last().value.ink.inputs[0].y, 0.01f)
                model.edits.undo() // Writing and space insertion remain separate undo steps.
                model.edits.undo()
                assertEquals(100f, model.edits.snapshot().single().value.ink.inputs[0].y, 0.01f)
            }
        }
    }

    private suspend fun startSpaceInsertion(model: EditorViewModel, view: InkCanvasView) {
        withContext(Dispatchers.Main) {
            send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 600f, 50f)
        }
        delay(ViewConfiguration.getLongPressTimeout().toLong() + 200)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val item = instrumentation.uiAutomation.rootInActiveWindow
            .findAccessibilityNodeInfosByText(AppMessages.text(R.string.insert_space)).single()
        assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        withTimeout(15000) { model.ui.first { it.busy } }
    }

    @Test fun panningCancelsLongPressMenu() = runBlocking {
        withCanvas { model, view ->
            withContext(Dispatchers.Main) {
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 600f, 600f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 600f, 500f)
            }
            delay(ViewConfiguration.getLongPressTimeout().toLong() + 200)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow
                .findAccessibilityNodeInfosByText(AppMessages.text(R.string.insert_space)).isEmpty())
            withContext(Dispatchers.Main) {
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 600f, 500f)
                assertEquals(100f, model.viewport.position.top, 0.01f)
            }
        }
    }

    @Test fun fingerMovesTheSelectionAndAnOutsideDownStartsPanningWithoutAnotherTouch() = runBlocking {
        withCanvas { model, view ->
            withContext(Dispatchers.Main) {
                model.chooseTool(EditorTool.PEN)
                val viewport = model.viewport.position
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 110f, 110f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 210f, 160f)
                assertEquals(WorldPoint(100f, 50f), model.selectionOffset)
                assertEquals(viewport, model.viewport.position)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 210f, 160f)
            }
            withTimeout(15000) { model.ui.first { !it.busy } }
            withContext(Dispatchers.Main) {
                assertEquals(200f, model.edits.snapshot().single().value.ink.inputs[0].x, 0.01f)
                assertEquals(1, model.ui.value.selectionCount)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_DOWN, 600f, 600f)
                assertNull(model.selectionBounds)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_MOVE, 600f, 500f)
                assertEquals(100f, model.viewport.position.top, 0.01f)
                send(view, MotionEvent.TOOL_TYPE_FINGER, MotionEvent.ACTION_UP, 600f, 500f)
                assertEquals(1, model.edits.entries.size)
            }
        }
    }

    @Test fun outsideStylusDownStartsTheChosenBrushAtTheOriginalContactPoint() = runBlocking {
        withCanvas { model, view -> withContext(Dispatchers.Main) {
            model.chooseTool(EditorTool.HIGHLIGHTER)
            send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_DOWN, 500f, 500f)
            assertNull(model.selectionBounds)
            assertTrue(model.edits.busy)
            send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_MOVE, 520f, 520f)
            send(view, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.ACTION_UP, 540f, 540f)
            view.settleForPause()
            val drawn = model.edits.snapshot().last().value.ink
            assertEquals(2, model.edits.entries.size)
            assertEquals(InkBrushes.highlighter, drawn.brush.family)
            assertEquals(500f, drawn.inputs[0].x, 0.01f)
            assertEquals(500f, drawn.inputs[0].y, 0.01f)
        } }
    }

    private var downTime = 0L
    private var eventTime = 0L
    private fun send(view: InkCanvasView, tool: Int, action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) { downTime = SystemClock.uptimeMillis(); eventTime = downTime }
        else eventTime += 20
        val event = MotionEvent.obtain(downTime, eventTime, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f }),
            0, 0, 1f, 1f, 14, 0,
            if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private suspend fun withCanvas(block: suspend (EditorViewModel, InkCanvasView) -> Unit) {
        val store = ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: EditorViewModel
                lateinit var view: InkCanvasView
                scenario.onActivity { activity ->
                    model = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java]
                    val inputs = MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 100f, 0, pressure = 1f)
                        .add(InputToolType.STYLUS, 120f, 120f, 20, pressure = 1f)
                    model.edits.restore(listOf(OrderedEdits.Entry(1L, EditorStroke(Stroke(
                        Brush.createWithColorIntArgb(StockBrushes.marker(), 0xFF000000.toInt(), 4f, 0.1f), inputs)))))
                    view = InkCanvasView(activity, model)
                    activity.setContentView(view)
                    view.layout(0, 0, 1000, 800)
                    model.selectLasso(listOf(WorldPoint(80f, 80f), WorldPoint(180f, 80f), WorldPoint(180f, 180f), WorldPoint(80f, 180f)))
                }
                withTimeout(15000) { model.ui.first { !it.busy } }
                // Keep the coordinate system stable even if the Activity's window was laid out meanwhile.
                withContext(Dispatchers.Main) { view.layout(0, 0, 1000, 800) }
                block(model, view)
            }
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }
}
