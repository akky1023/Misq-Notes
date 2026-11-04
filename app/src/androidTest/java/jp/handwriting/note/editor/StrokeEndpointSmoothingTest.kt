package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import jp.handwriting.note.storage.NoteRepository
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalInkCustomBrushApi::class)
@Suppress("RestrictedApi")
class StrokeEndpointSmoothingTest {
    private fun stroke(x: Float = 0f, y: Float = 0f): Stroke {
        val brush = Brush.createWithColorIntArgb(InkBrushes.pressurePen(PressureCurve()), Color.BLACK, 2f, 0.001f)
        val inputs = MutableStrokeInputBatch()
        repeat(41) { i ->
            inputs.add(InputToolType.STYLUS, x + i * 0.25f, y, i * 8L,
                0.0125f, if (i == 0 || i == 40) 1f else 0.2f, 0.25f, 0.75f)
        }
        inputs.setNoiseSeed(12345)
        return Stroke(brush, inputs)
    }

    @Test fun correctionPreservesSavedDataAndNonPressureAttributesEvenAtTheOrigin() {
        for ((x, y) in listOf(0f to 0f, 250f to 7257f)) {
            val original = EditorStroke(stroke(x, y))
            val saved = NoteRepository.encodeStroke(original)
            val local = StrokeLocalCoordinates.create(original.ink)
            assertEquals(original.ink.inputs[0].pressure, local.stroke.inputs[0].pressure, 0f)
            assertEquals(original.ink.inputs[40].pressure, local.stroke.inputs[40].pressure, 0f)
            assertTrue(local.stroke.inputs[1].pressure > original.ink.inputs[1].pressure)
            assertTrue(local.stroke.inputs[39].pressure > original.ink.inputs[39].pressure)
            assertEquals(original.ink.inputs[20].pressure, local.stroke.inputs[20].pressure, 0f)
            assertSame(original.ink.brush, local.stroke.brush)
            assertEquals(original.ink.inputs.getNoiseSeed(), local.stroke.inputs.getNoiseSeed())
            for (i in 0 until original.ink.inputs.size) {
                val before = original.ink.inputs[i]; val after = local.stroke.inputs[i]
                assertTrue("Pressure must never decrease", after.pressure >= before.pressure)
                assertEquals(before.x, after.x + local.x, 0f)
                assertEquals(before.y, after.y + local.y, 0f)
                assertEquals(before.elapsedTimeMillis, after.elapsedTimeMillis)
                assertEquals(before.toolType, after.toolType)
                assertEquals(before.strokeUnitLengthCm, after.strokeUnitLengthCm, 0f)
                assertEquals(before.tiltRadians, after.tiltRadians, 0f)
                assertEquals(before.orientationRadians, after.orientationRadians, 0f)
            }
            assertArrayEquals(saved, NoteRepository.encodeStroke(original))
        }
    }

    @Test fun commonRendererFillsThinPartsBesideThickEndpointsInPixels() {
        val source = stroke()
        val before = Bitmap.createBitmap(240, 80, Bitmap.Config.ARGB_8888)
        val after = Bitmap.createBitmap(240, 80, Bitmap.Config.ARGB_8888)
        try {
            val transform = Matrix().apply { setScale(16f, 16f) }
            fun canvas(bitmap: Bitmap) = Canvas(bitmap).apply { translate(32f, 40f); scale(16f, 16f) }
            CanvasStrokeRenderer.create(forcePathRendering = true).draw(canvas(before), source, transform)
            InkStrokeRenderer.create().draw(canvas(after), source, transform)
            fun pixels(bitmap: Bitmap, left: Int, right: Int): Int {
                var count = 0
                for (x in left until right) for (y in 0 until bitmap.height) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 128) count++
                }
                return count
            }
            assertTrue("Thin part near start", pixels(after, 40, 64) > pixels(before, 40, 64))
            assertTrue("Thin part near end", pixels(after, 160, 184) > pixels(before, 160, 184))
            for (range in listOf(16 until 32, 192 until 208)) {
                for (x in range) for (y in 0 until before.height) {
                    if (Color.alpha(before.getPixel(x, y)) > 128) {
                        assertTrue("Thick tip must not lose coverage at $x,$y", Color.alpha(after.getPixel(x, y)) > 128)
                    }
                }
            }
            assertEquals(pixels(before, 96, 128), pixels(after, 96, 128))
        } finally { before.recycle(); after.recycle() }
    }
}
