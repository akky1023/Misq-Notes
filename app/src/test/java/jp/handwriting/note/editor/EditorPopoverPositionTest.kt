package jp.handwriting.note.editor

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class EditorPopoverPositionTest {
    @Test fun keepsThePopoverAboveTheKeyboard() {
        val position = EditorPopoverPosition(IntRect(600, 700, 700, 780), 2, keyboard = 300)
            .calculatePosition(IntRect.Zero, IntSize(1000, 800), LayoutDirection.Ltr, IntSize(320, 300))
        assertTrue(position.y >= 0 && position.y + 300 <= 500)
    }
    @Test fun usesTheAvailableSideOfTheToolbarAndStaysInsideTheWindow() {
        val window = IntSize(1000, 800); val popup = IntSize(320, 400)
        val top = IntRect(400, 10, 600, 80); val bottom = IntRect(400, 720, 600, 790)
        val left = IntRect(10, 300, 80, 500); val right = IntRect(920, 300, 990, 500)
        for (anchor in listOf(top, bottom, left, right)) {
            val p = EditorPopoverPosition(anchor, 2).calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, popup)
            assertTrue(p.x >= 0 && p.y >= 0 && p.x + popup.width <= window.width && p.y + popup.height <= window.height)
            if (anchor == top) assertTrue(p.y >= anchor.bottom)
            if (anchor == bottom) assertTrue(p.y + popup.height <= anchor.top)
            if (anchor == left) assertTrue(p.x >= anchor.right)
            if (anchor == right) assertTrue(p.x + popup.width <= anchor.left)
        }
    }
}
