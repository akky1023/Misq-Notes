package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class PressureCurveTest {
    @Test fun endpointsAndMonotonicityHoldForEverySupportedCurve() {
        for (minimum in listOf(1, 20, 100)) for (exponent in listOf(25, 100, 175, 400)) {
            val curve = PressureCurve(minimum, exponent)
            assertEquals(minimum / 100f, curve.factor(0f), 0.00001f)
            assertEquals(1f, curve.factor(1f), 0.00001f)
            val values = (0..100).map { curve.factor(it / 100f) }
            assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
        }
    }
    @Test fun exponentControlsHowEarlyTheStrokeGetsThicker() {
        assertEquals(0.6f, PressureCurve(20, 100).factor(0.5f), 0.00001f)
        assertTrue(PressureCurve(20, 25).factor(0.5f) > 0.6f)
        assertTrue(PressureCurve(20, 400).factor(0.5f) < 0.6f)
        assertEquals(1f, PressureCurve(100, 400).factor(0.1f), 0f)
    }
    @Test fun pressureIsClampedAndInvalidSettingsAreRejected() {
        assertEquals(0.2f, PressureCurve().factor(-1f), 0f)
        assertEquals(1f, PressureCurve().factor(2f), 0f)
        assertThrows(IllegalArgumentException::class.java) { PressureCurve(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { PressureCurve(20, 401) }
        assertThrows(IllegalArgumentException::class.java) { PressureCurve().factor(Float.NaN) }
    }
    @Test fun bezierInvertsThePressureAxisAndUsesBothControlPoints() {
        val linear = PressureCurve.editableDefault()
        for (i in 0..100) assertEquals(0.2f + 0.8f * i / 100, linear.factor(i / 100f), 0.00001f)
        assertEquals(0.3f, PressureCurve(bezier = BezierControls(0, 0, 100, 0)).factor(0.5f), 0.00001f)
        assertEquals(0.9f, PressureCurve(bezier = BezierControls(0, 100, 100, 100)).factor(0.5f), 0.00001f)
    }
    @Test fun bezierKeepsFiniteMonotoneWidthsWithFlatAndCoincidentHandles() {
        for (controls in listOf(BezierControls(0, 0, 0, 100), BezierControls(100, 0, 100, 100),
            BezierControls(0, 0, 100, 0), BezierControls(50, 50, 50, 50))) {
            for (minimum in listOf(1, 20, 100)) {
                val curve = PressureCurve(minimum, bezier = controls)
                assertEquals(minimum / 100f, curve.factor(0f), 0f)
                assertEquals(1f, curve.factor(1f), 0f)
                val widths = (0..100).map { curve.factor(it / 100f) }
                assertTrue(widths.all { it.isFinite() && it in minimum / 100f..1f })
                assertTrue(widths.zipWithNext().all { (a, b) -> b >= a })
            }
        }
    }
    @Test fun handleMovementCannotReversePressureOrWidthAndInvalidControlsAreRejected() {
        assertEquals(BezierControls(67, 67, 67, 67), BezierControls().moved(1, 200, 200))
        assertEquals(BezierControls(33, 33, 33, 33), BezierControls().moved(2, -100, -100))
        assertThrows(IllegalArgumentException::class.java) { BezierControls(90, 20, 10, 80) }
        assertThrows(IllegalArgumentException::class.java) { BezierControls(20, 90, 80, 10) }
        assertThrows(IllegalArgumentException::class.java) { BezierControls().factor(Float.NaN) }
    }
    @Test fun oldPowerSettingsBecomeEditableBezierWithSimilarResponse() {
        for (exponent in listOf(25, 50, 100, 175, 250, 400)) {
            val before = PressureCurve(20, exponent); val after = before.asBezier()
            assertNotNull(after.bezier)
            assertEquals(after, after.asBezier())
            for (i in 1..31) assertEquals("exponent=$exponent pressure=$i/32", before.factor(i / 32f), after.factor(i / 32f), 0.04f)
        }
    }
}
