package jp.handwriting.note.editor

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

class RetentionWindowTest {
    @Test fun smallPansReuseTheWorkingSetWithoutOmittingVisibleInkAndEditsInvalidateIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = EditorViewModel()
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202b33.toInt(), 2f, .1f)
        fun stroke(y: Float) = EditorStroke(Stroke(brush, MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, 50f, y, 0).add(InputToolType.STYLUS, 60f, y + 5f, 10)))
        lateinit var view: InkCanvasView
        ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                model.edits.restore((1L..500L).map { OrderedEdits.Entry(it, stroke(it * 10f)) })
                view = InkCanvasView(activity, model)
                activity.setContentView(view, android.widget.FrameLayout.LayoutParams(1000, 1000))
            }
            fun drawn() {
                val done = CountDownLatch(1)
                instrumentation.runOnMainSync { view.invalidate(); view.postOnAnimation { view.postOnAnimation { done.countDown() } } }
                assertTrue(done.await(5, TimeUnit.SECONDS))
            }
            val field = InkCanvasView::class.java.getDeclaredField("retainedStrokes").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") fun retained() = field.get(view) as Set<EditorStroke>
            fun checkVisible() = instrumentation.runOnMainSync {
                assertTrue(retained().containsAll(model.visibleStrokeEntries().map { it.second }))
            }
            fun move(top: Float) {
                instrumentation.runOnMainSync { model.viewport.restore(ViewPosition(top = top)); model.onCanvasChange?.invoke() }
                drawn(); checkVisible()
            }
            move(1000f)
            val captured = retained()
            move(1100f); assertSame(captured, retained())
            move(1249f); assertSame(captured, retained())
            move(1251f); assertNotSame(captured, retained())
            val beforeEdit = retained()
            lateinit var added: EditorStroke
            instrumentation.runOnMainSync { added = stroke(1400f); model.edits.addAll(listOf(added)) }
            drawn(); checkVisible(); assertTrue(added in retained()); assertNotSame(beforeEdit, retained())
            val beforeZoom = retained()
            instrumentation.runOnMainSync {
                model.transformViewport(500f, 500f, 500f, 500f, 2f); model.onCanvasChange?.invoke()
            }
            drawn(); checkVisible(); assertNotSame(beforeZoom, retained())
        }
    }
}
