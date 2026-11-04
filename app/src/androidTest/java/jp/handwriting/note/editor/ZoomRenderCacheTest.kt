package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.PerformanceActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Synthetic strokes only; does not open the user's notebooks. */
class ZoomRenderCacheTest {
    private fun stroke(x: Float, y: Float, color: Int = Color.BLACK): EditorStroke {
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), color, 4f, 0.01f)
        val inputs = MutableStrokeInputBatch()
        repeat(24) { i -> inputs.add(InputToolType.STYLUS, x + i * 3f, y + (i % 6) * 2f, i * 10L) }
        return EditorStroke(Stroke(brush, inputs))
    }

    @Test fun zoomedDisplayListsMatchFreshInkIncludingMixedScaleTailsAndOffscreenStrokes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = StrokeRenderCache()
        val renderer = InkStrokeRenderer.create()
        var position = ViewPosition()
        var entries = listOf(1L to stroke(230f, 5000f), 2L to stroke(260f, 5004f, 0x803366ff.toInt()))
        lateinit var view: View
        ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    view = object : View(activity) {
                        override fun onDraw(canvas: Canvas) {
                            canvas.drawColor(Color.WHITE)
                            val scale = 400f / Viewport.WIDTH * position.zoom
                            val matrix = Matrix().apply { setValues(floatArrayOf(scale, 0f, -position.left * scale,
                                0f, scale, -position.top * scale, 0f, 0f, 1f)) }
                            fun drawStroke(target: Canvas, entry: EditorStroke) {
                                target.save(); target.concat(matrix)
                                renderer.draw(target, entry.ink, matrix)
                                target.restore()
                            }
                            canvas.save(); canvas.clipRect(0, 0, 400, 600)
                            cache.draw(canvas, entries, position, 400, 600, WorldPoint(0f, 0f), { false }) {
                                target, entry, _ -> drawStroke(target, entry)
                            }
                            canvas.restore()
                            canvas.save(); canvas.translate(400f, 0f); canvas.clipRect(0, 0, 400, 600)
                            entries.forEach { drawStroke(canvas, it.second) }
                            canvas.restore()
                        }
                    }
                    activity.setContentView(view, FrameLayout.LayoutParams(800, 600))
                }
                for ((index, next) in listOf(ViewPosition(), ViewPosition(6f, 200f, 4950f),
                    ViewPosition(2f, 100f, 4800f), ViewPosition(1f, 0f, 4900f)).withIndex()) {
                    val ready = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        position = next
                        if (index == 2) entries = entries + (3L to stroke(280f, 5020f))
                        view.invalidate()
                        view.postOnAnimation { view.postOnAnimation { ready.countDown() } }
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                    try {
                        val copied = CountDownLatch(1); var code = -1
                        scenario.onActivity { activity ->
                            val location = IntArray(2); view.getLocationInWindow(location)
                            PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + 800, location[1] + 600),
                                bitmap, { code = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                        }
                        assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, code)
                        var inkPixels = 0; var different = 0
                        for (y in 0 until 600) for (x in 0 until 400) {
                            val actual = bitmap.getPixel(x, y); val expected = bitmap.getPixel(x + 400, y)
                            if (expected != Color.WHITE) inkPixels++
                            if (listOf(Color.red(actual) - Color.red(expected), Color.green(actual) - Color.green(expected),
                                Color.blue(actual) - Color.blue(expected)).any { kotlin.math.abs(it) > 12 }) different++
                        }
                        if (index > 0) assertTrue("Zoomed ink must remain visible", inkPixels > 100)
                        assertTrue("Cached/fresh mismatch at $next: $different of $inkPixels ink pixels",
                            different <= maxOf(10, inkPixels / 100))
                    } finally { bitmap.recycle() }
                }
                assertEquals("Only the base and appended tail should be recorded", 2, cache.recordedGroups)
            } finally { instrumentation.runOnMainSync { cache.clear() } }
        }
    }

    @Test fun compareZoomRecordingCostWithPreviousFullInvalidation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val entries = (1L..512L).map { it to stroke((it % 12) * 75f, (it / 12) * 20f) }
        for (clearEveryStep in listOf(true, false)) {
            val samples = mutableListOf<Long>()
            val cache = StrokeRenderCache(); val target = RenderNode("zoom-cost")
            val renderer = InkStrokeRenderer.create()
            var drawCalls = 0
            try {
                instrumentation.runOnMainSync {
                    repeat(65) { step ->
                        val start = System.nanoTime()
                        if (clearEveryStep) cache.clear()
                        val position = ViewPosition(1f + step % 60 / 12f)
                        val scale = position.zoom
                        val matrix = Matrix().apply { setScale(scale, scale) }
                        val canvas = target.beginRecording(1000, 1000)
                        try {
                            cache.draw(canvas, entries, position, 1000, 1000, WorldPoint(0f, 0f), { false }) { output, entry, _ ->
                                drawCalls++
                                output.save(); output.concat(matrix); renderer.draw(output, entry.ink, matrix); output.restore()
                            }
                        } finally { target.endRecording() }
                        if (step >= 5) samples += System.nanoTime() - start
                    }
                }
                if (!clearEveryStep) assertEquals(entries.size, drawCalls)
                val sorted = samples.sorted()
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "\nZOOM clearEveryStep=$clearEveryStep strokes=${entries.size} samples=${samples.size} " +
                        "recordedGroups=${cache.recordedGroups} p50us=${sorted[30] / 1000} p95us=${sorted[57] / 1000}\n") })
            } finally { cache.clear(); target.discardDisplayList() }
        }
    }
}
