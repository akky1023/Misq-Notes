package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class PressureControlBezierTest {
    private fun quadratic() = PressureSpline(listOf(PressureControlPoint(0, 20),
        PressureControlPoint(50, 100), PressureControlPoint(100, 100)), PressureBasis.BEZIER_CONTROLS)
    @Test fun intermediatePointControlsTheBezierInsteadOfBeingAnInterpolationPoint() {
        val curve = quadratic()
        assertEquals(0.8f, curve.factor(0.5f), 0.00001f)
        assertNotEquals(curve.points[1].y / 100f, curve.factor(0.5f), 0.01f)
        val old = PressureSpline(curve.points)
        assertEquals(1f, old.factor(0.5f), 0f)
        assertNotEquals(old, curve)
    }
    @Test fun cubicAndHigherDegreesUseEveryControlPointWithBernsteinWeights() {
        val cubic = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(25, 20),
            PressureControlPoint(75, 100), PressureControlPoint(100, 100)), PressureBasis.BEZIER_CONTROLS)
        assertEquals(0.325f, cubic.factor(0.2265625f), 0.00001f)
        val quartic = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(10, 60),
            PressureControlPoint(40, 60), PressureControlPoint(75, 95), PressureControlPoint(100, 100)), PressureBasis.BEZIER_CONTROLS)
        assertEquals(0.6875f, quartic.factor(0.425f), 0.00001f)
        assertEquals(PressureBasis.BEZIER_CONTROLS, quartic.added(55, 80).basis)
    }
    @Test fun highDegreeCurvesRemainFiniteMonotoneAndMatchTheGraphSamples() {
        val curve = PressureSpline((0..100).map { PressureControlPoint(it, maxOf(1, it)) }, PressureBasis.BEZIER_CONTROLS)
        assertTrue(curve.samples.zipWithNext().all { (a, b) -> b.x > a.x && b.y >= a.y })
        val values = (0..1000).map { curve.factor(it / 1000f) }
        assertTrue(values.all { it.isFinite() && it in 0.01f..1f })
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
        curve.samples.forEach { assertEquals(it.y, curve.factor(it.x), 0.000001f) }
    }
    @Test fun crossingTheOuterDeleteMarginRemovesTheDraggedControlWhileTheEdgeZoneKeepsIt() {
        for ((x, y) in listOf(-20.001f to 50f, 120.001f to 50f, 50f to -20.001f, 50f to 120.001f)) {
            val deleted = quadratic().dragged(1, x, y)
            assertEquals(2, deleted.points.size)
            assertEquals(listOf(PressureControlPoint(0, 20), PressureControlPoint(100, 100)), deleted.points)
            assertEquals(PressureBasis.BEZIER_CONTROLS, deleted.basis)
        }
        for ((x, y) in listOf(0f to 50f, 100f to 50f, 50f to 0f, 50f to 100f,
            -0.001f to 50f, 100.001f to 50f, 50f to -0.001f, 50f to 100.001f,
            -20f to 50f, 120f to 50f, 50f to -20f, 50f to 120f)) {
            assertEquals(3, quadratic().dragged(1, x, y).points.size)
        }
        assertEquals(3, quadratic().dragged(0, -50f, 50f).points.size)
        assertEquals(3, quadratic().dragged(2, 150f, 100f).points.size)
    }
    @Test fun edgeDraggingClampsThePointAndAllowsReentryUntilTheLargerDeleteMargin() {
        val edge = quadratic().dragged(1, -15f, 50f, deleteMargin = 30f)
        assertEquals(PressureControlPoint(1, 50), edge.points[1])
        val returned = edge.dragged(1, 25f, 60f, deleteMargin = 30f)
        assertEquals(PressureControlPoint(25, 60), returned.points[1])
        assertEquals(3, returned.dragged(1, -30f, 60f, deleteMargin = 30f).points.size)
        assertEquals(2, returned.dragged(1, -30.01f, 60f, deleteMargin = 30f).points.size)
        assertThrows(IllegalArgumentException::class.java) { returned.dragged(1, 10f, 10f, -1f) }
    }
    @Test fun passingModeKeepsPointPositionsAndMakesTheCurvePassThroughEveryPoint() {
        val old = quadratic()
        val passing = PressureCurve(old.minimumPercent, spline = old).asPassingCurve()
        assertEquals(old.points, passing.spline!!.points)
        assertEquals(PressureBasis.INTERPOLATED, passing.spline!!.basis)
        passing.spline!!.points.forEach { assertEquals(it.y / 100f, passing.factor(it.x / 100f), 0.000001f) }
        assertEquals(passing, passing.asPassingCurve())
    }
    @Test fun existingPointCoordinatesAreReusedAsControlsAndMinimumOneHundredIsConstant() {
        val old = PressureSpline(quadratic().points)
        val converted = PressureCurve(old.minimumPercent, spline = old).asControlBezier()
        assertEquals(old.points, converted.spline!!.points)
        assertEquals(PressureBasis.BEZIER_CONTROLS, converted.spline!!.basis)
        assertEquals(converted, converted.asControlBezier())
        val flat = converted.spline!!.moved(0, 0, 100)
        for (i in 0..100) assertEquals(1f, flat.factor(i / 100f), 0f)
    }
}
