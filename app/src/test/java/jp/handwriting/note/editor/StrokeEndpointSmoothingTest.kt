package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class StrokeEndpointSmoothingTest {
    @Test fun thickEndpointsKeepTheirWidthWhileAdjacentThinPartsAreFilled() {
        val distances = FloatArray(81) { it / 10f }
        val widths = FloatArray(81) { 0.2f }.also { it[0] = 1f; it[80] = 1f }
        val result = StrokeEndpointSmoothing.widths(distances, widths)
        assertEquals(1f, result.first(), 0f)
        assertEquals(1f, result.last(), 0f)
        assertEquals(0.95f, result[1], 0.00001f)
        assertEquals(0.95f, result[79], 0.00001f)
        assertEquals(0.2f, result[40], 0f)
        assertEquals(1f, widths.first(), 0f)
    }

    @Test fun aSuddenDropAtTheTipBecomesAGradualTaper() {
        val distances = FloatArray(81) { it / 10f }
        val widths = FloatArray(81) { 1f }.also { it[80] = 0.2f }
        val result = StrokeEndpointSmoothing.widths(distances, widths)
        assertEquals(1f, result[40], 0f)
        assertEquals(0.95f, result.last(), 0.00001f)
        for (i in 1 until result.size) {
            assertTrue(kotlin.math.abs(result[i] - result[i - 1]) <= 0.05001f)
            assertTrue(result[i] >= widths[i])
        }
    }

    @Test fun duplicateCoordinatesFillTheThinPartWithoutShrinkingTheThickPart() {
        val result = StrokeEndpointSmoothing.widths(floatArrayOf(0f, 0f, 1f, 3f, 4f),
            floatArrayOf(1f, 0.2f, 0.2f, 0.2f, 0.2f))
        assertEquals(1f, result[0], 0f)
        assertEquals(1f, result[1], 0f)
    }

    @Test fun tapsShortStrokesAndConstantWidthsKeepTheirShape() {
        for (distances in listOf(floatArrayOf(0f), floatArrayOf(0f, 5f), floatArrayOf(0f, 0.1f, 0.2f))) {
            val widths = FloatArray(distances.size) { (it + 1f) / distances.size }
            assertArrayEquals(widths, StrokeEndpointSmoothing.widths(distances, widths), 0f)
        }
        val widths = FloatArray(31) { 0.5f }
        assertArrayEquals(widths, StrokeEndpointSmoothing.widths(FloatArray(31) { it / 10f }, widths), 0f)
    }

    @Test fun insertingIdenticalSamplesDoesNotChangeCorrection() {
        val sparse = StrokeEndpointSmoothing.widths(floatArrayOf(0f, 0.25f, 1f, 4f), floatArrayOf(1f, 0.2f, 0.2f, 0.2f))
        val dense = StrokeEndpointSmoothing.widths(floatArrayOf(0f, 0.25f, 0.25f, 1f, 4f), floatArrayOf(1f, 0.2f, 0.2f, 0.2f, 0.2f))
        assertEquals(sparse[0], dense[0], 0f)
        assertEquals(sparse[1], dense[1], 0f)
        assertEquals(sparse[2], dense[3], 0f)
    }

    @Test fun correctionIsSymmetricWhenDrawingDirectionIsReversed() {
        val distances = floatArrayOf(0f, 0.1f, 0.3f, 1f, 2f, 4f, 5f)
        val widths = floatArrayOf(1f, 0.2f, 0.3f, 0.6f, 1f, 0.9f, 0.2f)
        val forward = StrokeEndpointSmoothing.widths(distances, widths)
        val reverse = StrokeEndpointSmoothing.widths(
            FloatArray(distances.size) { distances.last() - distances[distances.lastIndex - it] },
            widths.reversedArray()).reversedArray()
        assertArrayEquals(forward, reverse, 0.00001f)
    }

    @Test fun nonlinearCurvesAreLimitedInWidthSpace() {
        val curve = PressureCurve(minimumPercent = 20, exponentPercent = 300)
        val pressure = StrokeEndpointSmoothing.pressure(curve, 0.4f, 0.1f)
        assertEquals(0.4f, curve.factor(pressure), 0.00001f)
        assertTrue(pressure > 0.4f)
        assertEquals(1f, StrokeEndpointSmoothing.pressure(curve, 0.4f, 1f), 0f)
    }
}
