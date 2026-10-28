package jp.handwriting.note.editor

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.MainActivity
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class InkSurfaceSizingTest {
    @Test fun movingPreviewBoundaryReusesInkSurfaceWhileViewportResizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fraction = mutableFloatStateOf(1f)
        val model = EditorViewModel()
        lateinit var canvas: InkCanvasView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        AndroidView(factory = { InkCanvasView(it, model).also { canvas = it } },
                            modifier = Modifier.width(maxWidth * fraction.floatValue).fillMaxHeight())
                    }
                }
            }
            instrumentation.waitForIdleSync()
            var surfaceWidth = 0
            var surfaceHeight = 0
            var editorWidth = 0
            instrumentation.runOnMainSync {
                val ink = (0 until canvas.childCount).map(canvas::getChildAt).filterIsInstance<InProgressStrokesView>().single()
                surfaceWidth = ink.width
                surfaceHeight = ink.height
                editorWidth = canvas.width
            }
            for (value in listOf(0.98f, 0.9f, 0.8f, 0.7f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 0.6f, 1f)) {
                instrumentation.runOnMainSync { fraction.floatValue = value }
                val expectedWidth = (editorWidth * value).roundToInt()
                val deadline = SystemClock.uptimeMillis() + 3000
                var actualWidth = 0
                do {
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { actualWidth = canvas.width }
                    if (actualWidth != expectedWidth) SystemClock.sleep(16)
                } while (actualWidth != expectedWidth && SystemClock.uptimeMillis() < deadline)
                assertEquals("Editor must actually resize before checking its backing buffer", expectedWidth, actualWidth)
                instrumentation.runOnMainSync {
                    val ink = (0 until canvas.childCount).map(canvas::getChildAt).filterIsInstance<InProgressStrokesView>().single()
                    assertEquals("Ink buffer width must stay fixed across preview resizing", surfaceWidth, ink.width)
                    assertEquals(surfaceHeight, ink.height)
                    assertTrue(canvas.width <= ink.width)
                    assertEquals(canvas.width, ink.clipBounds!!.width())
                    assertEquals(canvas.height, ink.clipBounds!!.height())
                    assertEquals(canvas.width / Viewport.WIDTH * model.viewport.position.zoom, model.viewport.scale, 0.001f)
                }
            }
        }
    }
}
