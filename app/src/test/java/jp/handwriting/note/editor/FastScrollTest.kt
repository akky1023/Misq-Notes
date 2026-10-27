package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class FastScrollTest {
    @Test fun shortNoteNeedsNoHandle() {
        val viewport = Viewport().apply { size(1000f, 800f) }
        assertNull(FastScroll.create(viewport, 100f, 1f))
    }

    @Test fun longNoteHasGrabbableThumbAndReachesBothEnds() {
        val viewport = Viewport().apply { size(1000f, 800f) }
        val scroll = FastScroll.create(viewport, 100000f, 1f)!!
        assertEquals(48f, scroll.thumbSize, 0f)
        assertEquals(0f, scroll.scrollTop(-100f), 0f)
        assertEquals(99400f, scroll.scrollTop(scroll.end), 0f)
        for (top in listOf(0f, 10000f, 49700f, 99400f)) {
            assertEquals(top, scroll.scrollTop(scroll.thumbTop(top)), 0.02f)
        }
    }

    @Test fun zoomAndBlankSpaceAreIncludedWithoutGrowingTheDragRange() {
        val viewport = Viewport().apply { size(2000f, 1200f); restore(ViewPosition(2f, 250f, 10000f)) }
        val scroll = FastScroll.create(viewport, 2000f, 2f)!!
        assertEquals(10000f, scroll.maxTop, 0.01f)
        assertEquals(scroll.end - scroll.thumbSize, scroll.thumbTop(10000f), 0.01f)
        viewport.restore(viewport.position.copy(top = scroll.scrollTop(scroll.start)))
        assertEquals(0f, viewport.position.top, 0f)
        assertEquals(2f, viewport.position.zoom, 0f)
        assertEquals(250f, viewport.position.left, 0f)
        assertEquals(10000f, scroll.scrollTop(scroll.end), 0.01f)
    }

    @Test fun compactWindowDoesNotProduceAnInvalidTrack() {
        val viewport = Viewport().apply { size(1000f, 140f) }
        assertNull(FastScroll.create(viewport, 10000f, 1f))
    }
}
