package jp.handwriting.note.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import jp.handwriting.note.storage.NoteRepository
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalInkCustomBrushApi::class)
class StrokeLocalCoordinatesTest {
    private val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0x66123456, 2.8f, 0.01f)
    private fun stroke(x: Float, y: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
        repeat(32) { i ->
            inputs.add(InputToolType.STYLUS, x + i * 0.25f, y + ((i - 16) * (i - 16)) * 0.015625f,
                i * 8L, 0.0125f, (i + 1) / 32f, 0.25f, 0.75f)
        }
        inputs.setNoiseSeed(12345)
        return Stroke(brush, inputs)
    }

    @Test fun savedBytesAndEveryInputAttributeStayUnchanged() {
        val original = EditorStroke(stroke(250.125f, 7257.75f))
        val saved = NoteRepository.encodeStroke(original)
        val local = StrokeLocalCoordinates.create(original.ink)
        assertSame(original.ink.brush, local.stroke.brush)
        assertEquals(original.ink.inputs.getNoiseSeed(), local.stroke.inputs.getNoiseSeed())
        for (i in 0 until original.ink.inputs.size) {
            val before = original.ink.inputs[i]; val after = local.stroke.inputs[i]
            assertEquals(before.x, after.x + local.x, 0f)
            assertEquals(before.y, after.y + local.y, 0f)
            assertEquals(before.elapsedTimeMillis, after.elapsedTimeMillis)
            assertEquals(before.toolType, after.toolType)
            assertEquals(before.pressure, after.pressure, 0f)
            assertEquals(before.strokeUnitLengthCm, after.strokeUnitLengthCm, 0f)
            assertEquals(before.tiltRadians, after.tiltRadians, 0f)
            assertEquals(before.orientationRadians, after.orientationRadians, 0f)
        }
        assertArrayEquals(saved, NoteRepository.encodeStroke(original))
    }

    @Test fun equalRelativeInputsProduceTheSameOutlineThroughoutALongNote() {
        fun outline(stroke: Stroke): List<Pair<Float, Float>> = buildList {
            val p = MutableVec()
            for (g in 0 until stroke.shape.getRenderGroupCount()) for (o in 0 until stroke.shape.getOutlineCount(g)) {
                for (i in 0 until stroke.shape.getOutlineVertexCount(g, o)) {
                    stroke.shape.populateOutlinePosition(g, o, i, p)
                    add(p.x to p.y)
                }
            }
        }
        val expected = outline(StrokeLocalCoordinates.create(stroke(0f, 0f)).stroke)
        for (top in listOf(1000f, 7257.75f, 10000f, 100000f)) {
            assertEquals("Outline at $top", expected, outline(StrokeLocalCoordinates.create(stroke(250.125f, top)).stroke))
        }
    }

    @Test fun anAlreadyLocalStrokeCanKeepItsExistingGeometry() {
        val source = Stroke(brush, MutableStrokeInputBatch().add(InputToolType.STYLUS, 0f, 0f, 0))
        val local = StrokeLocalCoordinates.create(source)
        assertSame(source, local.stroke)
        assertEquals(0f, local.x, 0f); assertEquals(0f, local.y, 0f)
    }
}
