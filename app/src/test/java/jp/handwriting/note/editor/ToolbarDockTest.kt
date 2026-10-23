package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class ToolbarDockTest {
    @Test fun releasedToolbarSnapsToTheCenterOfTheNearestEdge() {
        assertEquals(ToolbarDock(0.5f, 0f, DockEdge.TOP), ToolbarDock.snap(0.4f, 0.2f, 0.12f, 0.1f))
        assertEquals(ToolbarDock(0.5f, 1f, DockEdge.BOTTOM), ToolbarDock.snap(0.7f, 0.9f, 0.12f, 0.1f))
        assertEquals(ToolbarDock(0f, 0.5f, DockEdge.LEFT), ToolbarDock.snap(0.2f, 0.6f, 0.12f, 0.1f))
        assertEquals(ToolbarDock(1f, 0.5f, DockEdge.RIGHT), ToolbarDock.snap(0.9f, 0.4f, 0.12f, 0.1f))
    }
    @Test fun everyCornerReopensOnThePreviousEdgeOnlyWhenItIsAdjacent() {
        for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            val corner = ToolbarDock(x, y, DockEdge.CORNER)
            val horizontal = if (y == 0f) DockEdge.TOP else DockEdge.BOTTOM
            val vertical = if (x == 0f) DockEdge.LEFT else DockEdge.RIGHT
            for (previous in DockEdge.entries) {
                val expected = if (previous == vertical) vertical else horizontal
                assertEquals(ToolbarDock.centered(expected), corner.reopened(previous))
            }
        }
    }
    @Test fun allFourCornersCollapseAndOutsideDragsRemainOnScreen() {
        for (x in listOf(-1f, 2f)) for (y in listOf(-1f, 2f)) {
            val dock = ToolbarDock.snap(x, y, 0.12f, 0.1f)
            assertEquals(DockEdge.CORNER, dock.edge)
            assertEquals(x.coerceIn(0f, 1f), dock.x, 0f); assertEquals(y.coerceIn(0f, 1f), dock.y, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) { ToolbarDock.snap(Float.NaN, 0f, 0.1f, 0.1f) }
    }
}
