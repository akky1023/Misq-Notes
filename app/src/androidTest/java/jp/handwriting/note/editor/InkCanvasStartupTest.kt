package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.Display
import android.hardware.display.DisplayManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkCanvasStartupTest {
    @Test fun firstDrawAndZoomedDrawHaveInitializedPersistentScrollbars() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val model = EditorViewModel()
            val context = instrumentation.targetContext
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val view = InkCanvasView(context.createDisplayContext(display), model)
            val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
            try {
                assertNotNull(view.verticalScrollbarThumbDrawable)
                assertNotNull(view.horizontalScrollbarThumbDrawable)
                assertFalse(view.isScrollbarFadingEnabled)
                view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 800, 1000)
                view.draw(Canvas(bitmap))
                model.viewport.restore(ViewPosition(2f, 250f, 1000f))
                view.layout(0, 0, 799, 1000)
                view.draw(Canvas(bitmap))
            } finally { bitmap.recycle() }
        }
    }
}
