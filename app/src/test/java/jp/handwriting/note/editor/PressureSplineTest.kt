package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class PressureSplineTest {
    private fun curve() = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(10, 60),
        PressureControlPoint(40, 60), PressureControlPoint(75, 95), PressureControlPoint(100, 100)))
    @Test fun joinedCurvePassesThroughEveryPointAndNeverOvershootsOrReverses() {
        val spline = curve()
        spline.points.forEach { assertEquals(it.y / 100f, spline.factor(it.x / 100f), 0.000001f) }
        val values = (0..1000).map { spline.factor(it / 1000f) }
        assertTrue(values.all { it.isFinite() && it in 0.2f..1f })
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0.6f, spline.factor(0.25f), 0.000001f)
    }
    @Test fun addMoveAndDeleteKeepOrderedPointsAndFixedEndpoints() {
        val before = curve()
        val added = before.added(55, 80)
        assertEquals(before.points.size + 1, added.points.size)
        val index = added.points.indexOfFirst { it.x == 55 }
        assertEquals(0.8f, added.factor(0.55f), 0.000001f)
        val moved = added.moved(index, 200, -20)
        assertEquals(PressureControlPoint(74, 60), moved.points[index])
        assertEquals(before, added.removed(index))
        assertEquals(before, before.added(40, 80))
        assertEquals(before, before.moved(before.points.lastIndex, 80, 80))
        assertEquals(before, before.removed(0))
    }
    @Test fun leftEndpointReplacesMinimumSliderIncludingOneHundredAndRecovery() {
        val flat = curve().moved(0, 50, 100)
        assertEquals(100, flat.minimumPercent)
        for (i in 0..100) assertEquals(1f, flat.factor(i / 100f), 0f)
        val restored = flat.moved(0, 50, 20)
        assertEquals(PressureControlPoint(0, 20), restored.points.first())
        assertEquals(0.6f, restored.factor(0.5f), 0.01f)
        assertEquals(1, curve().moved(0, 0, -100).minimumPercent)
    }
    @Test fun densePointsAndFlatIntervalsRemainFiniteAndMonotone() {
        val spline = PressureSpline((0..100).map { PressureControlPoint(it, maxOf(1, it)) })
        for (i in 0..100) assertEquals(maxOf(1, i) / 100f, spline.factor(i / 100f), 0.000001f)
        assertEquals(spline, spline.added(50, 50))
        assertEquals(0.01f, spline.factor(-1f), 0f)
        assertEquals(1f, spline.factor(2f), 0f)
    }
    @Test fun oldPowerAndBezierSettingsMigrateToSimilarEditableCurves() {
        for (old in listOf(PressureCurve(20, 25), PressureCurve(20, 400),
            PressureCurve(20, bezier = BezierControls(0, 0, 100, 0)), PressureCurve(100))) {
            val converted = old.asSpline()
            assertNotNull(converted.spline)
            assertEquals(converted, converted.asSpline())
            for (i in 0..100) assertEquals(old.factor(i / 100f), converted.factor(i / 100f), 0.02f)
        }
    }
    @Test fun invalidPointsAndMismatchedMinimumAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PressureControlPoint(50, 0) }
        assertThrows(IllegalArgumentException::class.java) { PressureSpline(listOf(PressureControlPoint(10, 20), PressureControlPoint(100, 100))) }
        assertThrows(IllegalArgumentException::class.java) { PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(50, 80), PressureControlPoint(50, 90), PressureControlPoint(100, 100))) }
        assertThrows(IllegalArgumentException::class.java) { PressureSpline(listOf(PressureControlPoint(0, 80), PressureControlPoint(50, 20), PressureControlPoint(100, 100))) }
        assertThrows(IllegalArgumentException::class.java) { PressureCurve(10, spline = curve()) }
        assertThrows(IllegalArgumentException::class.java) { curve().factor(Float.NaN) }
    }
}
