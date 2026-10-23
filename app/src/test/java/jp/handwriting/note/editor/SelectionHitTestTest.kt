package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class SelectionHitTestTest {
    private val bounds = StrokeIndex.Bounds(100f, 200f, 300f, 400f)
    private val zero = WorldPoint(0f, 0f)

    @Test fun selectionInteriorEdgesAndOutsideAreDistinct() {
        assertFalse(SelectionHitTest.contains(null, zero, 200f, 300f, 1f))
        for (point in listOf(WorldPoint(200f, 300f), WorldPoint(100f, 200f), WorldPoint(300f, 400f))) {
            assertTrue(SelectionHitTest.contains(bounds, zero, point.x, point.y, 1f))
        }
        for (point in listOf(WorldPoint(80f, 300f), WorldPoint(320f, 300f), WorldPoint(200f, 180f), WorldPoint(200f, 420f))) {
            assertFalse(SelectionHitTest.contains(bounds, zero, point.x, point.y, 1f))
        }
    }

    @Test fun touchMarginIsEightScreenPixelsRegardlessOfZoom() {
        for (scale in listOf(0.5f, 1f, 2f, 6f)) {
            assertTrue(SelectionHitTest.contains(bounds, zero, 100f - 7f / scale, 300f, scale))
            assertFalse(SelectionHitTest.contains(bounds, zero, 100f - 9f / scale, 300f, scale))
        }
    }

    @Test fun duringDragTheMovedBoundsDetermineWhereAnotherFingerLands() {
        val offset = WorldPoint(250f, 400f)
        assertTrue(SelectionHitTest.contains(bounds, offset, 400f, 700f, 1f))
        assertFalse(SelectionHitTest.contains(bounds, offset, 200f, 300f, 1f))
    }
}
