package jp.handwriting.note.probe

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDecoderTest {
    @Test fun copiedMotionRetainsPressureHistoryAndPointerIdAfterRecycle() {
        val properties = MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { x = 10f; y = 20f; pressure = 0.25f }
        val event = MotionEvent.obtain(100, 100, MotionEvent.ACTION_MOVE, 1, arrayOf(properties),
            arrayOf(coords), 0, MotionEvent.BUTTON_STYLUS_PRIMARY, 1f, 1f, 1, 0, InputDevice.SOURCE_STYLUS, 0)
        event.addBatch(110, arrayOf(MotionEvent.PointerCoords().apply { x = 12f; y = 24f; pressure = 0.75f }), 0)
        val record = EventDecoder.motion(event, "touch")
        event.recycle()
        assertEquals(7, record.pointers.single().id)
        assertEquals("STYLUS", record.pointers.single().toolName)
        assertEquals(0.75f, record.pointers.single().pressure)
        assertEquals(100L, record.history.single().eventTimeMs)
        assertEquals(0.25f, record.history.single().pointers.single().pressure)
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, record.buttonState)
    }

    @Test fun keyScanCodeAndRepeatCountAreRetained() {
        val event = KeyEvent(100, 110, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            2, 0, 1, 123, 0, InputDevice.SOURCE_STYLUS)
        val record = EventDecoder.key(event)
        assertEquals("ACTION_DOWN", record.actionName)
        assertEquals(123, record.scanCode)
        assertEquals(2, record.repeatCount)
        assertEquals(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, record.keyCode)
    }
}
