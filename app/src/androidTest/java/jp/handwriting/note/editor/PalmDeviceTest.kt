package jp.handwriting.note.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PalmDeviceTest {
    @Test fun palmWithSamePointerIdCannotFinishOrCancelThePenGesture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val model = EditorViewModel()
            model.chooseTool(EditorTool.ERASER)
            val view = InkCanvasView(instrumentation.targetContext, model)
            view.layout(0, 0, 1000, 800)
            val start = SystemClock.uptimeMillis()
            fun send(action: Int, device: Int, tool: Int, offset: Long) {
                val event = MotionEvent.obtain(start, start + offset, action, 1,
                    arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool }),
                    arrayOf(MotionEvent.PointerCoords().apply { x = 100f; y = 100f; pressure = 0.5f }),
                    0, 0, 1f, 1f, device, 0,
                    if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, 14, MotionEvent.TOOL_TYPE_STYLUS, 0)
            assertTrue(model.edits.busy)
            send(MotionEvent.ACTION_DOWN, 13, MotionEvent.TOOL_TYPE_FINGER, 10)
            send(MotionEvent.ACTION_CANCEL, 13, MotionEvent.TOOL_TYPE_FINGER, 20)
            assertTrue(model.edits.busy)
            send(MotionEvent.ACTION_UP, 13, MotionEvent.TOOL_TYPE_FINGER, 30)
            assertTrue(model.edits.busy)
            send(MotionEvent.ACTION_UP, 14, MotionEvent.TOOL_TYPE_STYLUS, 40)
            assertFalse(model.edits.busy)
            assertTrue(model.edits.entries.isEmpty())
        }
    }
}
