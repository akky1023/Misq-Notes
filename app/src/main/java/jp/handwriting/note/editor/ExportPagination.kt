package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

/** Bounds already include brush thickness. A break in a gap cannot sever a stroke. */
internal object ExportPagination {
    data class Band(val top: Float, val bottom: Float) {
        init { require(top.isFinite() && bottom.isFinite() && bottom >= top) }
    }
    data class Page(val top: Float, val bottom: Float)
    fun pages(bands: List<Band>, height: Float, contentBottom: Float): List<Page> {
        require(height.isFinite() && height > 0 && contentBottom.isFinite() && contentBottom > 0)
        val merged = mutableListOf<Band>()
        for (band in bands.sortedBy { it.top }) {
            val previous = merged.lastOrNull()
            if (previous != null && band.top <= previous.bottom) merged[merged.lastIndex] = Band(previous.top, maxOf(previous.bottom, band.bottom))
            else merged += band
        }
        val pages = mutableListOf<Page>()
        var top = 0f
        while (top < contentBottom) {
            check(pages.size < 10000) { AppMessages.text(R.string.pdf_too_many_pages) }
            val target = minOf(top + height, contentBottom)
            val crossing = merged.firstOrNull { it.top < target && it.bottom > target }
            // If the uninterrupted ink band is taller than a page, splitting is unavoidable.
            val bottom = if (crossing != null && crossing.top > top + 1f) crossing.top else target
            check(bottom > top) { AppMessages.text(R.string.pagination_failed) }
            pages += Page(top, bottom)
            top = bottom
        }
        return pages
    }
}
