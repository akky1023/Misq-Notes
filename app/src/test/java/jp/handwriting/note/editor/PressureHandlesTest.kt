package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class PressureHandlesTest {
    private fun curve() = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(40, 50),
        PressureControlPoint(100, 100))).withHandles()
    private fun assertSafe(spline: PressureSpline) {
        spline.points.forEach { assertEquals(it.y / 100f, spline.factor(it.x / 100f), 0.000001f) }
        assertTrue(spline.samples.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in spline.minimumPercent / 100f..1f })
        assertTrue(spline.samples.zipWithNext().all { (a, b) -> a.x < b.x && b.y >= a.y - 0.000001f })
        val values = (0..1000).map { spline.factor(it / 1000f) }
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - 0.000001f })
    }
    @Test fun automaticHandlesPreserveTheExistingPassingShape() {
        val points = listOf(PressureControlPoint(0, 20), PressureControlPoint(10, 60), PressureControlPoint(40, 60),
            PressureControlPoint(75, 95), PressureControlPoint(100, 100))
        val original = PressureSpline(points); val handled = original.withHandles()
        for (i in 0..1000) assertEquals(original.factor(i / 1000f), handled.factor(i / 1000f), 0.002f)
        assertSafe(handled)
    }
    @Test fun draggingChangesShapeWhilePreservingAnchorsAndLinkedTangentDirections() {
        val original = curve(); val changed = original.movedHandle(1, false, 65f, 55f)
        assertEquals(original.points, changed.points)
        assertTrue(kotlin.math.abs(original.factor(0.65f) - changed.factor(0.65f)) > 0.01f)
        val a = changed.handlePosition(1, true); val b = changed.handlePosition(1, false)
        assertEquals((0.5f - a.y) / (0.4f - a.x), (b.y - 0.5f) / (b.x - 0.4f), 0.00001f)
        assertEquals(original.tangents!![1].incoming, changed.tangents!![1].incoming, 0.0001f)
        assertSafe(changed)
    }
    @Test fun extremeHandlesDenseAnchorsAndFlatIntervalsNeverReverseOrDivideByZero() {
        for (start in listOf(curve(), PressureSpline((0..100).map { PressureControlPoint(it, maxOf(1, it)) }).withHandles(),
            PressureSpline(listOf(PressureControlPoint(0, 100), PressureControlPoint(50, 100), PressureControlPoint(100, 100))).withHandles())) {
            var draft = start
            for (i in start.points.indices) {
                if (i > 0) { draft = draft.movedHandle(i, true, -1000f, -1000f); assertSafe(draft) }
                if (i < start.points.lastIndex) { draft = draft.movedHandle(i, false, 1000f, 1000f); assertSafe(draft) }
            }
        }
        assertSafe(curve().movedHandle(1, false, 40f, 90f))
        assertSafe(curve().movedHandle(1, false, 40f, 50f))
    }
    @Test fun editingAndDeletingPointsRetainsCustomHandlesAndLargeDeleteMargin() {
        val edited = curve().movedHandle(0, false, 15f, 23f)
        val added = edited.added(75, 80)
        assertEquals(edited.tangents!![0], added.tangents!![0])
        assertEquals(edited.tangents[0], added.moved(2, 76, 81).tangents!![0])
        assertSafe(added)
        val near = added.dragged(2, 76f, 110f)
        assertEquals(4, near.points.size)
        assertSafe(near)
        val far = near.dragged(2, 76f, 121f)
        assertEquals(3, far.points.size)
        assertEquals(edited.tangents[0], far.tangents!![0])
        assertSafe(far)
    }
    @Test fun constantFullWidthAndRecoveryAndHandleResetRemainEditable() {
        val edited = curve().movedHandle(1, false, 65f, 55f)
        val flat = edited.moved(0, 0, 100)
        assertSafe(flat)
        for (i in 0..100) assertEquals(1f, flat.factor(i / 100f), 0f)
        val restored = flat.moved(0, 0, 20)
        assertSafe(restored)
        assertEquals(curve(), edited.resetHandles(1))
        assertTrue(restored.factor(0.5f) < 1f)
    }
    @Test fun invalidOrCrossedHandleMetadataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PressureTangent(Float.NaN, 0f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PressureTangent(91f, 0f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PressureTangent(0f, -1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PressureSpline(curve().points, tangents = listOf(PressureTangent(0f, 0f, 1f))) }
        assertThrows(IllegalArgumentException::class.java) { PressureSpline(curve().points,
            tangents = List(3) { PressureTangent(45f, if (it == 0) 0f else 100f, if (it == 2) 0f else 100f) }) }
        assertThrows(IllegalArgumentException::class.java) { curve().movedHandle(1, false, Float.NaN, 50f) }
    }
}
