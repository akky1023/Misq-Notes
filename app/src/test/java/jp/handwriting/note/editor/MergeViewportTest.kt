package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class MergeViewportTest {
    @Test fun repeatedZoomOutStopsAtNoteWidthWithoutDrifting() {
        var view = MergeViewport(100f, 200f, 100f)
        repeat(100) { view = view.transform(1000, 500f, 300f, 0f, 0f, 0.5f) }
        assertEquals(1000f, view.width, 0f)
        val stopped = view
        repeat(100) { view = view.transform(1000, 500f, 300f, 0f, 0f, 0.5f) }
        assertEquals(stopped, view)
    }

    @Test fun zoomLimitsPreserveThePointUnderTheFingersAndAllowPanning() {
        val view = MergeViewport(100f, 200f, 500f)
        for (zoom in listOf(2f, 10000f)) {
            val next = view.transform(1000, 400f, 300f, 50f, 20f, zoom)
            assertTrue(next.width in 25f..1000f)
            assertEquals(view.left + 200f, next.left + 450f * next.width / 1000f, 0.001f)
            assertEquals(view.top + 150f, next.top + 320f * next.width / 1000f, 0.001f)
        }
        assertEquals(view, view.transform(1000, 0f, 0f, 0f, 0f, Float.NaN))
    }

    @Test fun repeatedPanningStopsAtAllFourPaperEdges() {
        var view = MergeViewport(100f, 200f, 250f)
        repeat(100) { view = view.transform(1000, 500f, 300f, 10000f, 10000f, 1f, 600, 3000f) }
        assertEquals(MergeViewport(0f, 0f, 250f), view)
        repeat(100) { view = view.transform(1000, 500f, 300f, -10000f, -10000f, 1f, 600, 3000f) }
        assertEquals(MergeViewport(750f, 2850f, 250f), view)
    }

    @Test fun zoomOutAndPaneResizeClampTheExistingPosition() {
        val view = MergeViewport(750f, 2850f, 250f)
        val zoomed = view.transform(1000, 500f, 300f, 0f, 0f, 0.001f, 600, 3000f)
        assertEquals(1000f, zoomed.width, 0f)
        assertEquals(0f, zoomed.left, 0f)
        assertTrue(zoomed.top in 0f..2400f)
        assertEquals(MergeViewport(750f, 2500f, 250f), view.constrained(0.5f, 3000f))
        assertEquals(MergeViewport(0f, 0f, 1000f), MergeViewport(-500f, -500f).constrained(0.5f, 1000f))
    }

    @Test fun fullWidthCannotPanHorizontallyAndLongNotesRemainReachable() {
        val full = MergeViewport().transform(1000, 500f, 300f, -1000000f, -1000000f, 1f, 600, 200000f)
        assertEquals(0f, full.left, 0f)
        assertEquals(199400f, full.top, 0f)
        assertEquals(MergeViewport(0f, 0f, 1000f), MergeViewport(900f, 900f).constrained(0.5f, 1000f))
    }
}
