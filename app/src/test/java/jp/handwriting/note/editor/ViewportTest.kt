package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class ViewportTest {
    @Test fun zoomPercentUsesDefaultWidthRatherThanScreenPixelScale() {
        val view = Viewport(); view.size(2400f, 1600f)
        assertEquals(100, view.zoomPercent)
        view.transform(0f, 0f, 0f, 0f, 2.345f)
        assertEquals(235, view.zoomPercent)
        view.size(1200f, 800f)
        assertEquals(235, view.zoomPercent)
        view.restore(ViewPosition(6f))
        assertEquals(600, view.zoomPercent)
        view.fitWidth()
        assertEquals(100, view.zoomPercent)
    }
    @Test fun pinchKeepsThePointUnderTheFingerAndConversionsRoundTrip() {
        val view = Viewport(); view.size(2000f, 1200f)
        view.restore(ViewPosition(2f, 200f, 5000f))
        val x = view.worldX(800f); val y = view.worldY(600f)
        view.transform(800f, 600f, 900f, 700f, 1.5f)
        assertEquals(x, view.worldX(900f), 0.001f)
        assertEquals(y, view.worldY(700f), 0.001f)
        assertEquals(700f, view.screenY(y), 0.001f)
    }
    @Test fun fitWidthHasNoHorizontalScrollAndVerticalSpaceKeepsGrowing() {
        val view = Viewport(); view.size(1000f, 600f)
        view.transform(500f, 500f, -1000f, -100000f, 1f)
        assertEquals(0f, view.position.left)
        assertTrue(view.position.top > 100000f)
        assertEquals(1000f, view.right)
    }
    @Test fun resizePreservesLogicalPositionAndZoomLimitsAreEnforced() {
        val view = Viewport(); view.restore(ViewPosition(2f, 250f, 10000f))
        view.size(2400f, 1600f)
        assertEquals(250f, view.worldX(0f))
        assertEquals(10000f, view.worldY(0f))
        view.transform(0f, 0f, 0f, 0f, 100f)
        assertEquals(6f, view.position.zoom)
        view.fitWidth()
        assertEquals(0f, view.position.left)
        assertEquals(10000f, view.position.top)
    }
}
