package jp.handwriting.note.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import jp.handwriting.note.MainActivity
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class HoldLineContactTest {
    private fun send(view: InkCanvasView, action: Int, x: Float, y: Float, start: Long) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f }),
            0, 0, 1f, 1f, 14, 0, InputDevice.SOURCE_STYLUS, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun contact(tool: EditorTool, snap: Boolean = true, cancel: Boolean = false, shapes: Boolean = true, endY: Float = 12f, bent: Boolean = false) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: EditorViewModel
            lateinit var view: InkCanvasView
            var start = 0L
            var density = 1f
            var startWorld = WorldPoint(0f, 0f)
            scenario.onActivity { activity ->
                model = EditorViewModel()
                model.chooseTool(tool); model.snapShapes(snap); model.drawShapes(shapes)
                view = InkCanvasView(activity, model)
                activity.setContentView(view)
                density = activity.resources.displayMetrics.density
            }
            SystemClock.sleep(250L)
            scenario.onActivity {
                assertTrue(view.width > 0 && view.height > 0)
                startWorld = WorldPoint(model.viewport.worldX(100f), model.viewport.worldY(700f))
                start = SystemClock.uptimeMillis()
                send(view, MotionEvent.ACTION_DOWN, 100f, 700f, start)
                // Mild wobble qualifies; a pronounced bend must remain freehand.
                send(view, MotionEvent.ACTION_MOVE, 100f + 75f * density,
                    700f + (if (bent) 30f else -39f) * density, start)
                send(view, MotionEvent.ACTION_MOVE, 100f + 150f * density, 700f - 80f * density, start)
            }
            SystemClock.sleep(800L)
            scenario.onActivity {
                // Shorten at 30 degrees and rotate slightly; fine snapping must persist.
                send(view, MotionEvent.ACTION_MOVE, 100f + 20f * density, 700f - endY * density, start)
                send(view, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                    100f + 22f * density, 700f - endY * density, start)
            }
            SystemClock.sleep(500L)
            scenario.onActivity {
                assertFalse(model.edits.busy)
                if (cancel) assertTrue(model.edits.entries.isEmpty())
                else {
                    assertEquals(1, model.edits.entries.size)
                    val stroke = model.edits.entries.single().ink
                    if (shapes && !bent) {
                        assertEquals(2, stroke.inputs.size)
                        val a = stroke.inputs[0]; val b = stroke.inputs[1]
                        assertEquals(startWorld.x, a.x, 0.01f); assertEquals(startWorld.y, a.y, 0.01f)
                        val angle = Math.toDegrees(atan2((a.y - b.y).toDouble(), (b.x - a.x).toDouble()))
                        if (snap && endY == 12f) assertEquals(30.0, angle, 0.01)
                        else assertEquals(Math.toDegrees(atan2(endY.toDouble(), 22.0)), angle, 0.01)
                    } else assertTrue(stroke.inputs.size > 2)
                    assertEquals(model.brushForPressure(false).colorIntArgb, stroke.brush.colorIntArgb)
                    model.edits.undo(); assertTrue(model.edits.entries.isEmpty())
                    model.edits.redo(); assertEquals(1, model.edits.entries.size)
                }
                view.settleForPause()
                assertFalse(model.edits.busy)
            }
        }
    }

    @Test fun penHoldsThenMovesAndSavesOneStraightStroke() = contact(EditorTool.PEN)
    @Test fun highlighterHoldsThenMovesAndSavesOneStraightStroke() = contact(EditorTool.HIGHLIGHTER)
    @Test fun disablingSnapKeepsTheExactEndpoint() = contact(EditorTool.PEN, snap = false)
    @Test fun outsideSnapRangeKeepsTheExactEndpoint() = contact(EditorTool.PEN, endY = 10f)
    @Test fun cancelAfterHoldDiscardsTheLine() = contact(EditorTool.PEN, cancel = true)
    @Test fun bentPenStrokeStaysFreehandAfterHold() = contact(EditorTool.PEN, bent = true)
    @Test fun bentHighlighterStrokeStaysFreehandAfterHold() = contact(EditorTool.HIGHLIGHTER, bent = true)
    @Test fun disablingShapesKeepsFreehandInput() = contact(EditorTool.PEN, shapes = false)
}
