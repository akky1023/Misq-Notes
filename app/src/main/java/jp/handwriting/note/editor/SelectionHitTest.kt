package jp.handwriting.note.editor

/** The selection's touch margin stays eight screen pixels at every zoom level. */
internal object SelectionHitTest {
    fun contains(bounds: StrokeIndex.Bounds?, offset: WorldPoint, x: Float, y: Float, scale: Float): Boolean {
        if (bounds == null || !x.isFinite() || !y.isFinite() || !scale.isFinite() || scale <= 0f) return false
        val padding = 8f / scale
        return x >= bounds.left + offset.x - padding && x <= bounds.right + offset.x + padding &&
            y >= bounds.top + offset.y - padding && y <= bounds.bottom + offset.y + padding
    }
}
