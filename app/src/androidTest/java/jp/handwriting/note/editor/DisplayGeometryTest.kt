package jp.handwriting.note.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.storage.encode
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

@OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
class DisplayGeometryTest {
    @Test fun directRefinementMatchesTheExistingTwoStepGeometryAndPreservesInputs() {
        for (origin in listOf(0f, 250.125f, 7257.75f)) {
            for (family in listOf(StockBrushes.marker(), InkBrushes.pressurePen(PressureCurve()))) {
                val inputs = MutableStrokeInputBatch()
                repeat(32) { i -> inputs.add(InputToolType.STYLUS, origin + i, origin + i * .5f,
                    i * 8L, pressure = if (i == 0 || i == 31) .1f else .8f) }
                inputs.setNoiseSeed(12345)
                val source = Stroke(Brush.createWithColorIntArgb(family, 0x66123456, 2.8f, .1f), inputs)
                val originalInputs = source.inputs
                val expected = StrokeLocalCoordinates.create(StrokeFidelityCache().get(source))
                val renderer = InkStrokeRenderer.createForDisplay()
                val actual = renderer.geometry(source)
                assertEquals(expected.x, actual.x, 0f); assertEquals(expected.y, actual.y, 0f)
                assertEquals(expected.stroke.brush, actual.stroke.brush)
                val before = ByteArrayOutputStream().also { expected.stroke.inputs.encode(it) }.toByteArray()
                val after = ByteArrayOutputStream().also { actual.stroke.inputs.encode(it) }.toByteArray()
                assertArrayEquals(before, after)
                assertEquals(expected.stroke.shape.computeBoundingBox(), actual.stroke.shape.computeBoundingBox())
                assertSame(originalInputs, source.inputs)
            }
        }
    }

    @Test fun retainedGeometrySurvivesMoreThan128StrokesAndCannotBeRepopulatedByStaleWork() {
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202b33.toInt(), 2f, .1f)
        val strokes = List(300) { i -> Stroke(brush, MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, i.toFloat(), 100f, 0)) }
        val renderer = InkStrokeRenderer.createForDisplay()
        renderer.retainVisible(strokes)
        val first = renderer.geometry(strokes.first())
        strokes.forEach(renderer::prefetch)
        assertSame(first, renderer.geometry(strokes.first()))
        renderer.retainVisible(strokes.drop(1))
        renderer.prefetch(strokes.first())
        assertNotSame(first, renderer.geometry(strokes.first()))
        val last = renderer.geometry(strokes.last())
        renderer.clear(); renderer.prefetch(strokes.last())
        assertNotSame(last, renderer.geometry(strokes.last()))
    }
}
