package jp.handwriting.note.editor

/** Screen-space thumb geometry with a fixed world-space range for each drag. */
internal data class FastScroll(val start: Float, val end: Float, val thumbSize: Float, val maxTop: Float) {
    val travel get() = end - start - thumbSize
    fun thumbTop(top: Float): Float = start + if (maxTop > 0f) travel * (top / maxTop).coerceIn(0f, 1f) else 0f
    fun scrollTop(thumbTop: Float): Float = if (travel > 0f) ((thumbTop - start) / travel).coerceIn(0f, 1f) * maxTop else 0f

    companion object {
        fun create(viewport: Viewport, contentBottom: Float, density: Float): FastScroll? {
            val extent = viewport.height / viewport.scale
            // Include blank space already reached by ordinary panning, but never grow during a drag.
            val bottom = maxOf(contentBottom + 200f, viewport.bottom)
            val maxTop = (bottom - extent).coerceAtLeast(0f)
            val start = 72f * density
            val end = viewport.height - 48f * density
            val track = end - start
            if (maxTop <= 0f || track <= 48f * density) return null
            val thumb = (track * extent / bottom).coerceIn(48f * density, track)
            return FastScroll(start, end, thumb, maxTop)
        }
    }
}
