package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class StrokeFidelityTest {
    @Test fun thinTipsGetProportionallyFinerGeometryAndExportIsFinerThanDisplay() {
        for (size in listOf(0.1f, 0.5f, 1f, 3f, 12.5f, 50f, 100f)) {
            val display = StrokeFidelity.displayEpsilon(size)
            val export = StrokeFidelity.exportEpsilon(size)
            assertTrue(display > 0f && display <= size / 128f)
            assertTrue(export > 0f && export < display)
            assertTrue(export <= 0.001f)
        }
        assertTrue(StrokeFidelity.displayEpsilon(0.5f) < StrokeFidelity.displayEpsilon(12.5f))
    }
}
