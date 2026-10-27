package jp.handwriting.note.editor

import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
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
import jp.handwriting.note.MainActivity
import jp.handwriting.note.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.*

class StarRowsPreviewTest {
    private fun star(x: Float, y: Float): EditorStroke {
        val inputs = MutableStrokeInputBatch()
        for (i in 0..5) {
            val angle = -PI / 2 + (i % 5) * 4 * PI / 5
            inputs.add(InputToolType.STYLUS, x + 45f * cos(angle).toFloat(),
                y + 45f * sin(angle).toFloat(), i * 30L, pressure = 1f)
        }
        return EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xFF202B33.toInt(), 3f, 0.1f), inputs))
    }

    @Test fun tabGroupsStarsJumpsToOriginalRowAndUpdatesAfterDeletion(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "star-rows-${UUID.randomUUID()}")
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also { it.start(root) }
        }
        try {
            withTimeout(15000) { model.workspace.first { !it.working } }
            withContext(Dispatchers.Main) { model.createNote("Star rows test") }
            withTimeout(15000) { model.workspace.first { it.current != null && !it.working } }
            withContext(Dispatchers.Main) { model.edits.addAll(listOf(star(100f, 500f), star(700f, 510f), star(100f, 4500f))) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val canvas = java.util.concurrent.atomic.AtomicReference<InkCanvasView>()
                scenario.onActivity { it.setContent { EditorScreen(model, { view -> canvas.set(view) }, {}, {}) } }
                val automation = instrumentation.uiAutomation
                fun find(node: AccessibilityNodeInfo?, label: String, checkbox: Boolean? = null): AccessibilityNodeInfo? {
                    if (node == null || !node.refresh()) return null
                    if ((node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                        (checkbox == null || node.isCheckable == checkbox)) return node
                    return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label, checkbox) }
                }
                fun get(label: String, checkbox: Boolean? = null): AccessibilityNodeInfo {
                    val deadline = SystemClock.uptimeMillis() + 8000
                    do {
                        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                        find(automation.rootInActiveWindow, label, checkbox)?.let { return it }
                        SystemClock.sleep(50)
                    } while (SystemClock.uptimeMillis() < deadline)
                    error("Missing $label")
                }
                fun click(label: String, checkbox: Boolean? = null) {
                    var node = get(label, checkbox)
                    while (!node.isClickable && node.parent != null) node = node.parent
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                }
                click(AppMessages.text(R.string.note_preview), true)
                click(AppMessages.text(R.string.preview_tab_stars))
                val first = AppMessages.text(R.string.preview_star_row, 1)
                val second = AppMessages.text(R.string.preview_star_row, 2)
                get(first)
                get(second)
                assertNull(find(automation.rootInActiveWindow, AppMessages.text(R.string.preview_star_row, 3)))
                fun assertFirstStarRendered() {
                    val bounds = Rect()
                    instrumentation.runOnMainSync {
                        val location = IntArray(2)
                        canvas.get().getLocationOnScreen(location)
                        bounds.set(location[0] + model.viewport.screenX(50f).toInt(),
                            location[1] + model.viewport.screenY(450f).toInt(),
                            location[0] + model.viewport.screenX(150f).toInt(),
                            location[1] + model.viewport.screenY(550f).toInt())
                    }
                    var pixels = 0
                    val deadline = SystemClock.uptimeMillis() + 3000
                    do {
                        automation.takeScreenshot()?.let { shot ->
                            try {
                                pixels = 0
                                for (y in bounds.top.coerceAtLeast(0) until minOf(bounds.bottom, shot.height)) {
                                    for (x in bounds.left.coerceAtLeast(0) until minOf(bounds.right, shot.width)) {
                                        val color = shot.getPixel(x, y)
                                        if (android.graphics.Color.red(color) < 100 && android.graphics.Color.green(color) < 120 &&
                                            android.graphics.Color.blue(color) < 130) pixels++
                                    }
                                }
                            } finally { shot.recycle() }
                        }
                        if (pixels < 40) SystemClock.sleep(50)
                    } while (pixels < 40 && SystemClock.uptimeMillis() < deadline)
                    assertTrue("The returned star must actually be drawn on the canvas (ink pixels=$pixels)", pixels >= 40)
                }
                click(first)
                assertFirstStarRendered()
                val originalPosition = model.viewport.position
                repeat(2) {
                    // Finger panning takes the canvas's direct invalidation path, unlike model navigation.
                    instrumentation.runOnMainSync {
                        val view = canvas.get()
                        repeat(3) {
                            val down = SystemClock.uptimeMillis()
                            for (step in 0..12) {
                                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                                val coordinates = MotionEvent.PointerCoords().apply {
                                    x = view.width * 0.7f; y = view.height * (0.85f - 0.7f * step / 12f)
                                    pressure = 1f; size = 1f
                                }
                                val event = MotionEvent.obtain(down, down + step * 16L, action, 1,
                                    arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(300)
                    assertTrue("Finger scroll must leave the row: ${model.viewport.position}", model.viewport.position.top > originalPosition.top + 1000f)
                    click(first)
                    assertEquals(originalPosition.top, model.viewport.position.top, 0.01f)
                    assertFirstStarRendered()
                }
                val revision = model.edits.revision
                val zoom = model.viewport.position.zoom
                click(second)
                assertTrue("Tap jumps to the original distant row", model.viewport.position.top > 3000f)
                assertEquals(zoom, model.viewport.position.zoom, 0f)
                assertEquals(revision, model.edits.revision)
                click(AppMessages.text(R.string.preview_tab_all))
                get(AppMessages.text(R.string.note_preview), false)
                click(AppMessages.text(R.string.preview_tab_stars))
                get(second)
                withContext(Dispatchers.Main) { model.edits.delete(model.edits.snapshot().map { it.id }.toSet()) }
                get(AppMessages.text(R.string.preview_no_stars))
                assertNull(find(automation.rootInActiveWindow, first))
                withContext(Dispatchers.Main) { model.edits.undo() }
                get(first)
                get(second)
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            root.deleteRecursively()
        }
    }
}
