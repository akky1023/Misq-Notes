package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class ExportPaginationTest {
    private fun pages(vararg ranges: Pair<Float, Float>) = ExportPagination.pages(ranges.map { ExportPagination.Band(it.first, it.second) }, 100f, 260f)
    @Test fun movesTheBreakBeforeInkInsteadOfSplittingIt() {
        val pages = pages(20f to 40f, 90f to 120f, 175f to 200f)
        assertEquals(90f, pages.first().bottom, 0f)
        assertEquals(175f, pages[1].bottom, 0f)
        for (page in pages) for ((top, bottom) in listOf(20f to 40f, 90f to 120f, 175f to 200f))
            assertFalse(page.bottom > top && page.bottom < bottom)
    }
    @Test fun overlappingStrokesFormOneUninterruptedBand() {
        assertEquals(50f, pages(50f to 95f, 90f to 130f).first().bottom, 0f)
    }
    @Test fun anOverlongStrokeMayBeSplitAndAlwaysMakesProgress() {
        val pages = pages(0f to 250f)
        assertEquals(listOf(100f, 200f, 260f), pages.map { it.bottom })
        assertTrue(pages.all { it.bottom > it.top && it.bottom - it.top <= 100f })
    }
    @Test fun emptyNoteAndExactBoundaryProduceNoExtraPage() {
        assertEquals(listOf(ExportPagination.Page(0f, 100f)), ExportPagination.pages(emptyList(), 100f, 100f))
        assertEquals(100f, pages(20f to 100f).first().bottom, 0f)
    }
}
