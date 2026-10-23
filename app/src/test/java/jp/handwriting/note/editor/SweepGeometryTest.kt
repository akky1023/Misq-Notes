package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class SweepGeometryTest {
    @Test fun stationaryCircularEraserExcludesSquareCornersButIncludesTangency() {
        assertEquals(162f, SweepGeometry.segmentDistanceSquared(0f, 0f, 0f, 0f, 9f, 9f, 10f, 9f), 0f)
        assertEquals(100f, SweepGeometry.segmentDistanceSquared(0f, 0f, 0f, 0f, 6f, 8f, 7f, 8f), 0f)
    }
    @Test fun sweptCircleFindsCrossingsAndParallelNearMissesBetweenSamples() {
        assertEquals(0f, SweepGeometry.segmentDistanceSquared(0f, 0f, 100f, 0f, 50f, -20f, 50f, 20f), 0f)
        assertEquals(81f, SweepGeometry.segmentDistanceSquared(0f, 0f, 100f, 0f, 40f, 9f, 60f, 9f), 0f)
        assertEquals(121f, SweepGeometry.segmentDistanceSquared(0f, 0f, 100f, 0f, 40f, 11f, 60f, 11f), 0f)
    }
    @Test fun collinearTouchingAndDegenerateSegmentsHaveCorrectDistances() {
        assertEquals(0f, SweepGeometry.segmentDistanceSquared(0f, 0f, 10f, 0f, 5f, 0f, 20f, 0f), 0f)
        assertEquals(0f, SweepGeometry.segmentDistanceSquared(0f, 0f, 10f, 0f, 10f, 0f, 10f, 10f), 0f)
        assertEquals(25f, SweepGeometry.segmentDistanceSquared(0f, 0f, 0f, 0f, 3f, 4f, 3f, 4f), 0f)
    }
}
