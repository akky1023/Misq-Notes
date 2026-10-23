package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import jp.handwriting.note.PerformanceActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** GPU readback of synthetic rectangles; never opens or edits a saved notebook. */
class StrokePanPixelsTest {
    @Test fun panningKeepsOffscreenGeometryAndTailsRecordedAtDifferentPositionsAligned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), Color.BLACK, 3f, 0.1f)
        fun stroke() = EditorStroke(Stroke(brush, MutableStrokeInputBatch().add(InputToolType.STYLUS, 10f, 10f, 0)))
        val red = stroke(); val blue = stroke()
        val cache = StrokeRenderCache()
        var entries = listOf(1L to red)
        var position = ViewPosition()
        var compact = false
        val paint = Paint()
        lateinit var view: View
        ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = object : View(activity) {
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.WHITE)
                        cache.draw(canvas, entries, position, width, height, WorldPoint(0f, 0f), { false }, compact) { target, entry, _ ->
                            target.save()
                            target.translate(-position.left, -position.top)
                            paint.color = if (entry === red) Color.RED else Color.BLUE
                            if (entry === red) target.drawRect(10f, 900f, 50f, 1100f, paint)
                            else target.drawRect(100f, 1000f, 140f, 1200f, paint)
                            target.restore()
                        }
                    }
                }
                activity.setContentView(view, FrameLayout.LayoutParams(1000, 1000))
            }
            fun capture(): Bitmap {
                val drawn = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    view.invalidate()
                    view.postOnAnimation { view.postOnAnimation { drawn.countDown() } }
                }
                assertTrue("Frame was not drawn", drawn.await(5, TimeUnit.SECONDS))
                val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                var result = -1
                scenario.onActivity { activity ->
                    val location = IntArray(2); view.getLocationInWindow(location)
                    PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + 1000, location[1] + 1000),
                        bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue("GPU readback timed out", copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result)
                return bitmap
            }
            fun checkPixel(x: Int, y: Int, color: Int) {
                val bitmap = capture()
                try { assertEquals("Pixel ($x,$y) at $position", color, bitmap.getPixel(x, y)) }
                finally { bitmap.recycle() }
            }
            try {
                checkPixel(30, 950, Color.RED)
                val initial = cache.recordedGroups
                scenario.onActivity { position = ViewPosition(top = 100f) }
                checkPixel(30, 950, Color.RED) // Originally below the recording viewport.
                assertEquals(initial, cache.recordedGroups)
                scenario.onActivity { entries = entries + (2L to blue) }
                checkPixel(120, 950, Color.BLUE)
                scenario.onActivity { position = ViewPosition(top = 200f) }
                checkPixel(120, 950, Color.BLUE) // Tail and base have different recording origins.
                checkPixel(30, 850, Color.RED)
                scenario.onActivity { compact = true }
                checkPixel(120, 950, Color.BLUE)
                scenario.onActivity { compact = false; position = ViewPosition(top = 300f) }
                checkPixel(120, 850, Color.BLUE)
                checkPixel(30, 750, Color.RED)
                checkPixel(30, 850, Color.WHITE)
            } finally { instrumentation.runOnMainSync { cache.clear() } }
        }
    }
}
