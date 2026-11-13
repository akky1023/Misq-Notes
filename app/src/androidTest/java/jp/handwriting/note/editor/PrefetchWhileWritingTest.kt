package jp.handwriting.note.editor

import android.os.SystemClock
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.PerformanceActivity
import org.junit.Assert.*
import org.junit.Test

class PrefetchWhileWritingTest {
    @Test fun offscreenGeometryWaitsForPenReleaseAndTheQuietPeriod() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = EditorViewModel()
        lateinit var view: InkCanvasView
        ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                view = InkCanvasView(activity, model)
                activity.setContentView(view, ViewGroup.LayoutParams(1000, 1000))
            }
            fun await(condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (true) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = condition() }
                    if (ready) return
                    check(SystemClock.uptimeMillis() < deadline) { "Canvas/prefetch timeout" }
                    SystemClock.sleep(25)
                }
            }
            await { view.width == 1000 && view.height == 1000 }
            val far = EditorStroke(Stroke(model.brush, MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 100f, 1500f, 0)
                .add(InputToolType.STYLUS, 140f, 1520f, 30)))
            val down = SystemClock.uptimeMillis()
            fun pen(action: Int, screenX: Float, screenY: Float) {
                val dispatch = Runnable {
                    val properties = arrayOf(MotionEvent.PointerProperties().apply {
                        id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS
                    })
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                        x = screenX; y = screenY; pressure = .5f
                    })
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                        properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run()
                else instrumentation.runOnMainSync(dispatch)
            }
            instrumentation.runOnMainSync {
                model.edits.addAll(listOf(far))
                pen(MotionEvent.ACTION_DOWN, 200f, 200f)
                pen(MotionEvent.ACTION_MOVE, 240f, 230f)
            }
            // Inspect the actual geometry cache, without a production test-only counter.
            val renderer = InkCanvasView::class.java.getDeclaredField("renderer").let {
                it.isAccessible = true; it.get(view) as InkStrokeRenderer.LocalRenderer
            }
            SystemClock.sleep(1_300)
            instrumentation.runOnMainSync {
                assertTrue("Offscreen ink must remain unprepared while a stationary pen is held",
                    renderer.needsPreparation(far.ink))
            }
            pen(MotionEvent.ACTION_UP, 240f, 230f)
            await { !model.edits.busy }
            SystemClock.sleep(200)
            instrumentation.runOnMainSync {
                assertTrue("A short pen-up gap must not resume prefetch", renderer.needsPreparation(far.ink))
            }
            await { !renderer.needsPreparation(far.ink) }
        }
    }
}
