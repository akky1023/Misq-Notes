package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class LassoGeometryTest {
    @Test fun rectangleSelectsInteriorAndCrossingStrokesInEveryDragDirection() {
        for (start in listOf(WorldPoint(10f, 10f), WorldPoint(30f, 10f), WorldPoint(10f, 30f), WorldPoint(30f, 30f))) {
            val end = WorldPoint(40f - start.x, 40f - start.y)
            val rectangle = LassoGeometry(rectangleLasso(start, end))
            assertTrue(rectangle.valid)
            assertTrue(rectangle.contains(WorldPoint(20f, 20f)))
            assertTrue(rectangle.intersects(WorldPoint(0f, 20f), WorldPoint(40f, 20f)))
            assertFalse(rectangle.contains(WorldPoint(35f, 35f)))
            assertFalse(rectangle.intersects(WorldPoint(0f, 35f), WorldPoint(40f, 35f)))
        }
        assertFalse(LassoGeometry(rectangleLasso(WorldPoint(10f, 10f), WorldPoint(10f, 30f))).valid)
    }
    private fun point(x: Int, y: Int) = WorldPoint(x.toFloat(), y.toFloat())
    private val square = listOf(point(10, 10), point(30, 10), point(30, 30), point(10, 30))
    @Test fun enclosedAndBoundaryPointsAreSelectedInEitherDrawingDirection() {
        for (points in listOf(square, square.reversed(), square + square.first())) {
            val lasso = LassoGeometry(points)
            assertTrue(lasso.contains(point(20, 20)))
            assertTrue(lasso.contains(point(10, 20)))
            assertTrue(lasso.contains(point(30, 30)))
            assertFalse(lasso.contains(point(9, 20)))
        }
    }
    @Test fun crossingStrokeIsSelectedEvenWhenBothSamplesAreOutside() {
        val lasso = LassoGeometry(square)
        assertTrue(lasso.intersects(point(0, 20), point(40, 20)))
        assertTrue(lasso.intersects(point(0, 10), point(40, 10)))
        assertFalse(lasso.intersects(point(0, 0), point(40, 0)))
    }
    @Test fun concaveRegionsDoNotSelectTheMissingCorner() {
        val lasso = LassoGeometry(listOf(point(0, 0), point(30, 0), point(30, 10), point(10, 10), point(10, 30), point(0, 30)))
        assertTrue(lasso.contains(point(5, 20)))
        assertFalse(lasso.contains(point(20, 20)))
        assertFalse(lasso.intersects(point(20, 20), point(25, 25)))
    }
    @Test fun tapsStraightLinesAndNonFiniteInputCannotSelectAnything() {
        for (points in listOf(emptyList(), listOf(point(1, 1)), listOf(point(0, 0), point(1, 1), point(2, 2)), square + WorldPoint(Float.NaN, 0f))) {
            val lasso = LassoGeometry(points)
            assertFalse(lasso.valid)
            assertFalse(lasso.contains(point(20, 20)))
        }
    }
    @Test fun selectionIsIndependentOfViewportAndSupportsFarDownTheNote() {
        val offset = 12000f
        val lasso = LassoGeometry(square.map { WorldPoint(it.x, it.y + offset) })
        assertTrue(lasso.contains(WorldPoint(20f, 20f + offset)))
        assertFalse(lasso.contains(point(20, 20)))
    }
}
