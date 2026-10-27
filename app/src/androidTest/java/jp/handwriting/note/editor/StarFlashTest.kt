package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.PerformanceActivity
import jp.handwriting.note.storage.NoteRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

class StarFlashTest {
    @Test fun newlyWrittenStarFlashesOrangeThenReturnsToOriginalInkWithoutFlashingOnUndoRedo(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "star-flash-${UUID.randomUUID()}")
        val store = androidx.lifecycle.ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                .get(EditorViewModel::class.java).also { it.start(root) }
        }
        try {
            withTimeout(15000) { model.workspace.first { !it.working } }
            withContext(Dispatchers.Main) { model.createNote("Synthetic star flash test") }
            withTimeout(15000) { model.workspace.first { it.current != null && !it.working } }
            ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
                lateinit var view: InkCanvasView
                scenario.onActivity { activity ->
                    view = InkCanvasView(activity, model)
                    activity.setContentView(view, FrameLayout.LayoutParams(1000, 1000))
                }
                instrumentation.waitForIdleSync()
                fun capture(): Bitmap {
                    val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                    val copied = CountDownLatch(1)
                    var result = -1
                    scenario.onActivity { activity ->
                        val location = IntArray(2); view.getLocationInWindow(location)
                        PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + 1000, location[1] + 1000),
                            bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    assertTrue(copied.await(5, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, result)
                    return bitmap
                }
                fun orangePixels(bitmap: Bitmap): Int {
                    var count = 0
                    for (y in 300 until 700) for (x in 300 until 700) {
                        val color = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(color) > 180 && android.graphics.Color.green(color) in 70..190 &&
                            android.graphics.Color.blue(color) < 90) count++
                    }
                    return count
                }
                fun draw(points: List<Pair<Float, Float>>) {
                    instrumentation.runOnMainSync {
                        val down = SystemClock.uptimeMillis()
                        points.forEachIndexed { i, (x, y) ->
                            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; points.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                            val event = MotionEvent.obtain(down, down + i * 8L, action, 1,
                                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
                                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f }),
                                0, 0, 1f, 1f, 14, 0, InputDevice.SOURCE_STYLUS, 0)
                            try { view.onTouchEvent(event) } finally { event.recycle() }
                        }
                    }
                }
                // A normal pen stroke must never trigger the orange effect.
                draw((0..20).map { 350f + it * 15f to 650f })
                val noFlashDeadline = SystemClock.uptimeMillis() + 600
                do {
                    val shot = capture()
                    try { assertEquals(0, orangePixels(shot)) } finally { shot.recycle() }
                } while (SystemClock.uptimeMillis() < noFlashDeadline)

                val vertices = (0..5).map {
                    val angle = -PI / 2 + (it % 5) * 4 * PI / 5
                    500f + 120f * cos(angle).toFloat() to 450f + 120f * sin(angle).toFloat()
                }
                val points = vertices.zipWithNext().flatMap { (a, b) -> (0 until 8).map { i ->
                    a.first + (b.first - a.first) * i / 8f to a.second + (b.second - a.second) * i / 8f
                } } + vertices.last()
                draw(points)
                var flashed = false
                val deadline = SystemClock.uptimeMillis() + 5000
                do {
                    val shot = capture()
                    try {
                        flashed = orangePixels(shot) > 20
                        if (flashed) File(instrumentation.targetContext.cacheDir, "codex-star-flash.png").outputStream().use {
                            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { shot.recycle() }
                } while (!flashed && SystemClock.uptimeMillis() < deadline)
                assertTrue("A newly drawn single-stroke star should briefly turn orange", flashed)
                val source = withContext(Dispatchers.Main) { model.edits.snapshot().last().value }
                val saved = NoteRepository.encodeStroke(source)
                assertTrue("Stored ink keeps its original color", source.ink.brush.colorIntArgb != 0xFFFF9800.toInt())
                SystemClock.sleep(550)
                val normal = capture()
                try { assertEquals("Flash ends automatically", 0, orangePixels(normal)) } finally { normal.recycle() }
                assertArrayEquals(saved, NoteRepository.encodeStroke(source))
                withContext(Dispatchers.Main) { model.edits.undo(); model.edits.redo() }
                val redo = capture()
                try { assertEquals("Redo must not flash old stars", 0, orangePixels(redo)) } finally { redo.recycle() }
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            root.deleteRecursively()
        }
    }
}
