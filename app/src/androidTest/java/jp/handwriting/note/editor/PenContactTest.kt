package jp.handwriting.note.editor

import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import jp.handwriting.note.MainActivity
import org.junit.Assert.*
import org.junit.Test

class PenContactTest {
    @Test fun releasingLowerButtonKeepsErasingUntilPenUpAndCreatesOneUndo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val model = EditorViewModel()
            fun line(y: Float): EditorStroke = EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xFF000000.toInt(), 10f, 0.1f),
                MutableStrokeInputBatch().add(InputToolType.STYLUS, 495f, y, 0, pressure = 1f).add(InputToolType.STYLUS, 505f, y, 10, pressure = 1f)))
            model.edits.restore(listOf(OrderedEdits.Entry(1L, line(200f)), OrderedEdits.Entry(2L, line(500f))))
            val view = InkCanvasView(activity, model)
            activity.setContentView(view)
            assertNotNull("The canvas listener must observe the button release", model.onCanvasChange)
            view.layout(0, 0, 1000, 800)
            val start = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float, offset: Long) {
                val event = MotionEvent.obtain(start, start + offset, action, 1,
                    arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
                    arrayOf(MotionEvent.PointerCoords().apply { x = 500f; this.y = y; pressure = 0.5f }),
                    0, 0, 1f, 1f, 14, 0, InputDevice.SOURCE_STYLUS, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            model.handlePenButton(18, "Xiaomi Focus Pen Mouse", 93, true, 0); model.buttonChanged()
            send(MotionEvent.ACTION_DOWN, 200f, 0)
            assertEquals(1, model.edits.entries.size)
            model.handlePenButton(18, "Xiaomi Focus Pen Mouse", 93, false, 0); model.buttonChanged()
            assertTrue(model.edits.busy); assertEquals(EditorTool.ERASER, model.effectiveTool())
            send(MotionEvent.ACTION_MOVE, 500f, 20)
            assertTrue(model.edits.entries.isEmpty())
            send(MotionEvent.ACTION_UP, 500f, 40)
            assertFalse(model.edits.busy); assertEquals(EditorTool.PEN, model.effectiveTool())
            model.edits.undo(); assertEquals(2, model.edits.entries.size)
        } }
    }
}
