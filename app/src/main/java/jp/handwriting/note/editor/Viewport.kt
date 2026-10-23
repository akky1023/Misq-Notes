package jp.handwriting.note.editor

import kotlin.math.roundToInt

data class ViewPosition(val zoom: Float = 1f, val left: Float = 0f, val top: Float = 0f) {
    init {
        require(zoom.isFinite() && zoom in 1f..6f)
        require(left.isFinite() && left >= 0 && top.isFinite() && top >= 0)
    }
}

/** Fixed-width world, with no document-height limit. Positions are independent of screen pixels. */
class Viewport {
    companion object { const val WIDTH = 1000f }
    var position = ViewPosition()
        private set
    var width = 1f
        private set
    var height = 1f
        private set
    val scale get() = width / WIDTH * position.zoom
    val zoomPercent get() = (position.zoom * 100).roundToInt()
    val right get() = position.left + width / scale
    val bottom get() = position.top + height / scale
    fun size(width: Float, height: Float) { this.width = width.coerceAtLeast(1f); this.height = height.coerceAtLeast(1f); clamp() }
    fun restore(value: ViewPosition) { position = value; clamp() }
    fun worldX(x: Float) = x / scale + position.left
    fun worldY(y: Float) = y / scale + position.top
    fun screenX(x: Float) = (x - position.left) * scale
    fun screenY(y: Float) = (y - position.top) * scale
    fun transform(oldX: Float, oldY: Float, newX: Float, newY: Float, factor: Float) {
        if (!listOf(oldX, oldY, newX, newY, factor).all { it.isFinite() } || factor <= 0) return
        val anchorX = worldX(oldX); val anchorY = worldY(oldY)
        val zoom = (position.zoom * factor).coerceIn(1f, 6f)
        val newScale = width / WIDTH * zoom
        val top = anchorY - newY / newScale
        val left = anchorX - newX / newScale
        if (top.isFinite() && left.isFinite()) {
            position = ViewPosition(zoom, left.coerceAtLeast(0f), top.coerceAtLeast(0f))
            clamp()
        }
    }
    fun fitWidth() { position = position.copy(zoom = 1f, left = 0f) }
    private fun clamp() {
        val maxLeft = (WIDTH - WIDTH / position.zoom).coerceAtLeast(0f)
        position = position.copy(left = position.left.coerceIn(0f, maxLeft))
    }
}
