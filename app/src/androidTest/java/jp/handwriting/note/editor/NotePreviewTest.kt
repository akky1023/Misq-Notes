package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import jp.handwriting.note.AppMessages
import jp.handwriting.note.MainActivity
import jp.handwriting.note.GeneralSettingsScreen
import jp.handwriting.note.NotebookTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import jp.handwriting.note.R
import jp.handwriting.note.storage.NotePaper
import jp.handwriting.note.storage.ImageData
import jp.handwriting.note.storage.ImageRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NotePreviewTest {
    private fun line(y: Float, color: Int): EditorStroke {
        val input = MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, y, 0, pressure = 1f)
            .add(InputToolType.STYLUS, 900f, y, 100, pressure = 1f)
        return EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), color, 30f, 0.1f), input))
    }

    @Test fun previewEnlargesOnlyTheRequestedStrokeWithoutChangingSavedInk() = runBlocking {
        val red = line(250f, 0xFFFF0000.toInt())
        val blue = line(750f, 0xFF0000FF.toInt())
        val before = jp.handwriting.note.storage.NoteRepository.encodeStroke(red)
        val strokes = listOf(red, blue)
        val normal = NoteExport.preview(strokes, NotePaper(color = 0xFFFFFFFF.toInt()))
        val enlarged = NoteExport.preview(strokes, NotePaper(color = 0xFFFFFFFF.toInt()), enlargedStrokes = setOf(red.storageId))
        try {
            fun pixels(bitmap: Bitmap, red: Boolean): Int {
                var count = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.green(color) < 80 &&
                        (if (red) android.graphics.Color.red(color) > 180 && android.graphics.Color.blue(color) < 80
                         else android.graphics.Color.blue(color) > 180 && android.graphics.Color.red(color) < 80)) count++
                }
                return count
            }
            assertTrue(pixels(enlarged, true) > pixels(normal, true) * 1.3f)
            assertEquals(pixels(normal, false), pixels(enlarged, false))
            assertArrayEquals(before, jp.handwriting.note.storage.NoteRepository.encodeStroke(red))
        } finally { normal.recycle(); enlarged.recycle() }
    }

    @Test fun fullPreviewIncludesDistantInkAndBoundsAllocationForVeryLongNotes() = runBlocking {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00FF00.toInt()) }
        val png = java.io.ByteArrayOutputStream()
        try { source.compress(Bitmap.CompressFormat.PNG, 100, png) } finally { source.recycle() }
        val strokes = listOf(line(500f, 0xFFFF0000.toInt()), line(4500f, 0xFF0000FF.toInt()),
            EditorStroke(ImageRecord(ImageData(png.toByteArray()), 100f, 3000f, 800f, 500f)))
        val bitmap = NoteExport.preview(strokes, NotePaper(color = 0xFFFFFFFF.toInt()))
        try {
            val scale = bitmap.width / Viewport.WIDTH
            assertEquals(0xFFFF0000.toInt(), bitmap.getPixel(bitmap.width / 2, (500f * scale).toInt()))
            assertEquals(0xFF0000FF.toInt(), bitmap.getPixel(bitmap.width / 2, (4500f * scale).toInt()))
            assertEquals(0xFF00FF00.toInt(), bitmap.getPixel(bitmap.width / 2, (3200f * scale).toInt()))
            assertTrue(bitmap.width <= 360 && bitmap.height <= 1200)
        } finally { bitmap.recycle() }
        val long = NoteExport.preview(listOf(line(1_000_000f, 0xFF000000.toInt())), NotePaper())
        try { assertTrue(long.width <= 360 && long.height <= 1200) } finally { long.recycle() }
        val band = NoteExport.preview(listOf(line(1_000_500f, 0xFF0000FF.toInt())), NotePaper(),
            android.graphics.RectF(0f, 1_000_000f, Viewport.WIDTH, 1_001_000f))
        try {
            assertEquals(360, band.width)
            assertEquals(360, band.height)
            assertEquals(0xFF0000FF.toInt(), band.getPixel(180, 180))
        } finally { band.recycle() }
    }

    @Test fun editorToggleShowsWholePreviewOnEitherSideAndClosesIt(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "preview-ui-${UUID.randomUUID()}")
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also { it.start(root) }
        }
        try {
            withTimeout(15000) { model.workspace.first { !it.working } }
            val settings = model.library.value.value
            val systemDark = instrumentation.targetContext.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val frameColor = jp.handwriting.note.notebookColors(settings.theme, systemDark, settings.amoled).primary.toArgb()
            fun isFramePixel(pixel: Int) = kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(frameColor)) < 20 &&
                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(frameColor)) < 20 &&
                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(frameColor)) < 20
            withContext(Dispatchers.Main) { model.createNote("Preview test") }
            withTimeout(15000) { model.workspace.first { it.current != null && !it.working } }
            withContext(Dispatchers.Main) {
                model.edits.addAll(listOf(line(500f, 0xFFFF0000.toInt()), line(4500f, 0xFF0000FF.toInt())))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val canvas = java.util.concurrent.atomic.AtomicReference<InkCanvasView>()
                scenario.onActivity { activity -> activity.setContent {
                    var generalSettings by remember { mutableStateOf(false) }
                    val library by model.library.collectAsState()
                    if (generalSettings) NotebookTheme(library.value.theme, library.value.amoled) {
                        Surface(Modifier.fillMaxSize()) { GeneralSettingsScreen(model) { generalSettings = false } }
                    }
                    else EditorScreen(model, { canvas.set(it) }, {}, { generalSettings = true })
                } }
                val automation = instrumentation.uiAutomation
                fun find(node: AccessibilityNodeInfo?, label: String, toggle: Boolean): AccessibilityNodeInfo? {
                    if (node == null || !node.refresh()) return null
                    if ((node.contentDescription?.toString() == label || node.text?.toString() == label) &&
                        node.isCheckable == toggle) return node
                    return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label, toggle) }
                }
                fun get(label: String, toggle: Boolean = false): AccessibilityNodeInfo {
                    val deadline = SystemClock.uptimeMillis() + 8000
                    do {
                        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                        find(automation.rootInActiveWindow, label, toggle)?.let { return it }
                        SystemClock.sleep(50)
                    } while (SystemClock.uptimeMillis() < deadline)
                    error("Missing $label (toggle=$toggle)")
                }
                val label = AppMessages.text(R.string.note_preview)
                fun click(label: String, toggle: Boolean = false) {
                    fun named(node: AccessibilityNodeInfo?, descriptionOnly: Boolean): AccessibilityNodeInfo? {
                        if (node == null || !node.refresh()) return null
                        if ((!toggle || node.isCheckable) && (node.contentDescription?.toString() == label ||
                            (!descriptionOnly && node.text?.toString() == label))) return node
                        return (0 until node.childCount).firstNotNullOfOrNull { named(node.getChild(it), descriptionOnly) }
                    }
                    val deadline = SystemClock.uptimeMillis() + 8000
                    var found: AccessibilityNodeInfo? = null
                    do {
                        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                        val root = automation.rootInActiveWindow
                        found = named(root, true) ?: named(root, false)
                        if (found == null) SystemClock.sleep(50)
                    } while (found == null && SystemClock.uptimeMillis() < deadline)
                    var node = checkNotNull(found) { "Missing clickable $label" }
                    while (!node.isClickable && node.parent != null) node = node.parent
                    assertTrue("Cannot click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(50)
                }
                assertFalse(get(label, true).isChecked)
                fun canvasBounds(): Rect {
                    val bounds = Rect()
                    instrumentation.runOnMainSync {
                        val location = IntArray(2)
                        canvas.get().getLocationOnScreen(location)
                        bounds.set(location[0], location[1], location[0] + canvas.get().width, location[1] + canvas.get().height)
                    }
                    return bounds
                }
                val fullWidth = canvasBounds().width()
                val animatedWidths = java.util.Collections.synchronizedList(mutableListOf<Int>())
                val recording = java.util.concurrent.atomic.AtomicBoolean(true)
                val frameCallback = object : android.view.Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        animatedWidths.add(canvas.get().width)
                        if (recording.get()) android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                instrumentation.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback(frameCallback) }
                assertTrue(get(label, true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(400)
                recording.set(false)
                instrumentation.runOnMainSync { android.view.Choreographer.getInstance().removeFrameCallback(frameCallback) }
                if (android.provider.Settings.Global.getFloat(instrumentation.targetContext.contentResolver,
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
                    assertTrue("Opening the preview should animate the canvas width", animatedWidths.distinct().size > 2)
                }
                val left = Rect().also(get(label)::getBoundsInScreen)
                assertTrue(canvasBounds().width() < fullWidth)
                assertTrue("Left preview must be outside the canvas", left.right <= canvasBounds().left)
                val filterLabel = AppMessages.text(R.string.preview_star_filter)
                val leftFilter = Rect().also(get(filterLabel, true)::getBoundsInScreen)
                assertTrue("Star button must be at the lower left of the preview", leftFilter.centerX() < left.centerX() &&
                    leftFilter.centerY() > left.top + left.height() * 0.75f && left.contains(leftFilter))
                assertFalse(get(filterLabel, true).isChecked)
                assertTrue(get(label, true).isChecked)
                val previewDeadline = SystemClock.uptimeMillis() + 8000
                while (!get(label).isScrollable && SystemClock.uptimeMillis() < previewDeadline) SystemClock.sleep(50)
                assertTrue(get(label).isScrollable)
                fun checkViewportFrame(position: ViewPosition) {
                    instrumentation.runOnMainSync {
                        model.viewport.restore(position)
                        model.onCanvasChange?.invoke()
                        model.viewportChanged()
                    }
                    val region = model.visibleRegion.value
                    assertEquals(position.left, region.left, 0.01f)
                    assertEquals(position.top, region.top, 0.01f)
                    assertEquals(Viewport.WIDTH / position.zoom, region.right - region.left, 0.01f)
                    val deadline = SystemClock.uptimeMillis() + 8000
                    var matches = false
                    do {
                        automation.takeScreenshot()?.let { shot ->
                            try {
                                val frame = Rect(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0)
                                for (y in left.top until minOf(left.bottom, shot.height)) for (x in left.left until left.right) {
                                    val pixel = shot.getPixel(x, y)
                                    if (isFramePixel(pixel)) {
                                        frame.left = minOf(frame.left, x); frame.right = maxOf(frame.right, x)
                                        frame.top = minOf(frame.top, y); frame.bottom = maxOf(frame.bottom, y)
                                    }
                                }
                                val scale = left.width() / Viewport.WIDTH
                                matches = kotlin.math.abs(frame.left - (left.left + region.left * scale)) < 8 &&
                                    kotlin.math.abs(frame.right - (left.left + region.right * scale)) < 8 &&
                                    kotlin.math.abs(frame.top - (left.top + region.top * scale)) < 8 &&
                                    kotlin.math.abs(frame.bottom - (left.top + region.bottom * scale)) < 8
                            } finally { shot.recycle() }
                        }
                        if (!matches) SystemClock.sleep(100)
                    } while (!matches && SystemClock.uptimeMillis() < deadline)
                    assertTrue("Preview rectangle must follow viewport $position, including tile boundaries", matches)
                }
                checkViewportFrame(ViewPosition(2f, 200f, 900f))
                checkViewportFrame(ViewPosition(4f, 300f, 200f))
                val dragStart = model.viewport.position
                val dragRegion = model.visibleRegion.value
                val previewScale = left.width() / Viewport.WIDTH
                val dragX = left.left + (dragRegion.left + dragRegion.right) / 2f * previewScale
                val dragY = left.top + (dragRegion.top + dragRegion.bottom) / 2f * previewScale
                val dragTime = SystemClock.uptimeMillis()
                for (step in 0..12) {
                    val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                    val event = android.view.MotionEvent.obtain(dragTime, dragTime + step * 20L, action,
                        dragX + 100f * previewScale * step / 12f, dragY + 500f * previewScale * step / 12f, 0)
                    try { automation.injectInputEvent(event, true) } finally { event.recycle() }
                }
                SystemClock.sleep(400)
                assertEquals(dragStart.zoom, model.viewport.position.zoom, 0.01f)
                assertTrue("Dragging the frame should move the main note horizontally", model.viewport.position.left > dragStart.left + 70f)
                assertTrue("Dragging the frame should move the main note vertically", model.viewport.position.top > dragStart.top + 400f)
                val editRevision = model.edits.revision
                instrumentation.runOnMainSync {
                    model.moveViewportFromPreview(400f, 8000f)
                    model.viewportChanged()
                }
                var followed = false
                val followDeadline = SystemClock.uptimeMillis() + 8000
                do {
                    automation.takeScreenshot()?.let { shot ->
                        try {
                            var frameVisible = false
                            var topStrokeVisible = false
                            for (y in left.top until minOf(left.bottom, shot.height)) {
                                val x = left.left + (model.visibleRegion.value.left * previewScale).toInt() + 4
                                val pixel = shot.getPixel(x, y)
                                if (isFramePixel(pixel)) frameVisible = true
                                val centerPixel = shot.getPixel(left.centerX(), y)
                                if (android.graphics.Color.red(centerPixel) > 240 && android.graphics.Color.green(centerPixel) < 50 && android.graphics.Color.blue(centerPixel) < 50) topStrokeVisible = true
                            }
                            followed = frameVisible && !topStrokeVisible
                            if (followed) File(instrumentation.targetContext.cacheDir, "codex-preview-follow.png").outputStream().use {
                                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally { shot.recycle() }
                    }
                    if (!followed) SystemClock.sleep(100)
                } while (!followed && SystemClock.uptimeMillis() < followDeadline)
                assertTrue("Preview must automatically scroll to the frame after a distant main-note move", followed)
                assertEquals(editRevision, model.edits.revision)
                instrumentation.runOnMainSync {
                    model.moveViewportFromPreview(300f, 200f)
                    model.viewportChanged()
                }
                SystemClock.sleep(300)
                val originalPosition = model.viewport.position
                val originalCount = model.edits.entries.size
                // Send a real finger swipe inside the preview, then scroll to its end.
                val downTime = SystemClock.uptimeMillis()
                for (step in 0..12) {
                    val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                    val event = android.view.MotionEvent.obtain(downTime, downTime + step * 20L, action,
                        left.exactCenterX(), left.top + left.height() * (0.8f - 0.6f * step / 12f), 0)
                    try { automation.injectInputEvent(event, true) } finally { event.recycle() }
                }
                SystemClock.sleep(300)
                repeat(3) {
                    get(label).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(300)
                }
                assertEquals(originalPosition, model.viewport.position)
                assertEquals(originalCount, model.edits.entries.size)
                assertEquals(left.width(), Rect().also(get(label)::getBoundsInScreen).width())
                var sawBottom = false
                val renderDeadline = SystemClock.uptimeMillis() + 8000
                do {
                    automation.takeScreenshot()?.let { shot ->
                        try {
                            for (y in left.top.coerceAtLeast(0) until minOf(left.bottom, shot.height)) {
                                val pixel = shot.getPixel(left.centerX(), y)
                                if (android.graphics.Color.blue(pixel) > 180 && android.graphics.Color.red(pixel) < 80) sawBottom = true
                            }
                        } finally { shot.recycle() }
                    }
                    if (!sawBottom) SystemClock.sleep(100)
                } while (!sawBottom && SystemClock.uptimeMillis() < renderDeadline)
                assertTrue("Scrolling should reveal the blue stroke at the end of the preview", sawBottom)
                val settings = get(AppMessages.text(R.string.note_settings))
                assertTrue(settings.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                get(AppMessages.text(R.string.export_pdf))
                assertNull(find(automation.rootInActiveWindow, AppMessages.text(R.string.preview_position), false))
                click(AppMessages.text(R.string.close))
                SystemClock.sleep(300)
                click(AppMessages.text(R.string.back_to_notes))
                click(AppMessages.text(R.string.note_defaults))
                click(AppMessages.text(R.string.preview_position))
                SystemClock.sleep(250)
                automation.takeScreenshot()?.let { shot ->
                    try { File(instrumentation.targetContext.cacheDir, "codex-preview-settings.png").outputStream().use {
                        shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    } } finally { shot.recycle() }
                }
                click(AppMessages.text(R.string.position_right))
                instrumentation.waitForIdleSync()
                assertTrue(model.library.value.value.previewOnRight)
                click(AppMessages.text(R.string.preview_position))
                click(AppMessages.text(R.string.position_left))
                instrumentation.waitForIdleSync()
                assertFalse(model.library.value.value.previewOnRight)
                click(AppMessages.text(R.string.preview_position))
                click(AppMessages.text(R.string.position_right))
                click(AppMessages.text(R.string.back))
                click(AppMessages.text(R.string.back))
                click(label, true)
                SystemClock.sleep(500)
                val right = Rect().also(get(label)::getBoundsInScreen)
                assertTrue("Preview should move to the right: $left -> $right", right.left > left.right)
                assertTrue("Right preview must be outside the canvas", canvasBounds().right <= right.left)
                val rightFilter = Rect().also(get(filterLabel, true)::getBoundsInScreen)
                assertTrue("Star button must be at the lower right of the preview", rightFilter.centerX() > right.centerX() &&
                    rightFilter.centerY() > right.top + right.height() * 0.75f && right.contains(rightFilter))
                repeat(3) {
                    get(label).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    SystemClock.sleep(300)
                }
                automation.takeScreenshot()?.let { shot ->
                    try { File(instrumentation.targetContext.cacheDir, "codex-note-preview.png").outputStream().use {
                        shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    } } finally { shot.recycle() }
                }
                assertTrue(get(label, true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(250)
                assertFalse(get(label, true).isChecked)
                assertNull(find(automation.rootInActiveWindow, label, false))
                assertEquals(fullWidth, canvasBounds().width())
            }
            withContext(Dispatchers.Main) { model.closeNote() }
            withTimeout(15000) { model.workspace.first { it.current == null && !it.working } }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            root.deleteRecursively()
        }
    }
}
