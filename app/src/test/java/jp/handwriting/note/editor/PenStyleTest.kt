package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class PenStyleTest {
    @Test fun previousTenAndFiftyBecomeNewOneAndFortyAndEveryLevelRoundTrips() {
        assertEquals(12.5f, PenStyle.brushWidth(40f), 0f)
        assertEquals(40f, PenStyle.displayWidth(12.5f), 0f)
        assertEquals(0.5f + 12f * (9f / 49f).pow(1.4f), PenStyle.brushWidth(1f), 0f)
        assertTrue(PenStyle.brushWidth(50f) > 12.5f)
        assertEquals(50f, PenStyle.displayWidth(100f), 0f)
        assertEquals(40, PenStyle.legacyWidth(25))
        assertEquals(50, PenStyle.legacyWidth(100))
        for (width in 1..50) assertEquals(width.toFloat(), PenStyle.displayWidth(PenStyle.brushWidth(width.toFloat())), 0f)
    }
    @Test fun previousPresetsMigrateToClosestNewWidthAndClampBelowOldTen() {
        assertEquals(1, PenStyle.migratePreviousWidth(10))
        assertEquals(40, PenStyle.migratePreviousWidth(50))
        for (old in 1..10) assertEquals(1, PenStyle.migratePreviousWidth(old))
        for (old in 11..50) {
            val physical = 0.5f + 12f * ((old - 1f) / 49f).pow(1.4f)
            assertEquals(PenStyle.displayWidth(physical).toInt(), PenStyle.migratePreviousWidth(old))
        }
    }
    @Test fun thinWidthsHaveSmallerGradualSteps() {
        val widths = (1..50).map { PenStyle.brushWidth(it.toFloat()) }
        val increments = widths.zipWithNext { before, after -> after - before }
        assertTrue(increments.all { it > 0f })
        assertTrue(increments.zipWithNext().all { (before, after) -> after >= before })
        assertTrue(widths[2] < widths[0] * 1.5f)
        assertTrue(increments.first() < increments.last() / 3f)
    }
    @Test fun rgbChangesPreserveTransparencyIncludingFullyTransparentInk() {
        val rgb = PenStyle.fromRgb(0, 128, 255)
        assertEquals(0x0080FF, rgb)
        for (alpha in listOf(0, 1, 128, 255)) {
            assertEquals((alpha shl 24) or rgb, PenStyle.withRgb((alpha shl 24) or 0xFFFFFF, rgb))
        }
    }
    @Test fun everyTransparencyPercentRoundTripsAndKeepsRgb() {
        for (percent in 0..100) {
            val color = PenStyle.withTransparency(0xFF123456.toInt(), percent)
            assertEquals(percent, PenStyle.transparency(color))
            assertEquals(0x123456, PenStyle.rgb(color))
        }
        assertEquals(255, PenStyle.withTransparency(0, 0) ushr 24)
        assertEquals(0, PenStyle.withTransparency(0, 100) ushr 24)
    }
    @Test fun legacyFractionalWidthsNormalizeWithoutChangingExistingStrokeData() {
        assertEquals(3f, PenStyle.width(2.5f), 0f)
        assertEquals(1f, PenStyle.width(0.1f), 0f)
        assertEquals(50f, PenStyle.width(101f), 0f)
        for (width in 1..50) assertEquals(width.toFloat(), PenStyle.width(width.toFloat()), 0f)
    }
    @Test fun invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PenStyle.fromRgb(256, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { PenStyle.fromRgb(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { PenStyle.withTransparency(0, 101) }
        assertThrows(IllegalArgumentException::class.java) { PenStyle.width(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PenStyle.displayWidth(Float.POSITIVE_INFINITY) }
    }
}
