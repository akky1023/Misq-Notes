package jp.handwriting.note.editor

internal data class MergeViewport(val left: Float = 0f, val top: Float = 0f, val width: Float = MAX_WIDTH) {
    fun constrained(aspectRatio: Float, paperBottom: Float): MergeViewport {
        val boundedWidth = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        val ratio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val bottom = paperBottom.coerceAtLeast(MAX_WIDTH)
        return MergeViewport(left.coerceIn(0f, MAX_WIDTH - boundedWidth),
            top.coerceIn(0f, (bottom - boundedWidth / ratio).coerceAtLeast(0f)), boundedWidth)
    }

    fun transform(canvasWidth: Int, x: Float, y: Float, panX: Float, panY: Float, zoom: Float,
        canvasHeight: Int = canvasWidth, paperBottom: Float = MAX_WIDTH): MergeViewport {
        if (canvasWidth <= 0 || !zoom.isFinite() || zoom <= 0f ||
            !listOf(x, y, panX, panY).all { it.isFinite() }) return this
        val newWidth = (width / zoom).coerceIn(MIN_WIDTH, MAX_WIDTH)
        return MergeViewport(left + (x * (width - newWidth) - panX * newWidth) / canvasWidth,
            top + (y * (width - newWidth) - panY * newWidth) / canvasWidth, newWidth)
            .constrained(canvasWidth.toFloat() / canvasHeight.coerceAtLeast(1), paperBottom)
    }

    companion object {
        const val MIN_WIDTH = 25f
        const val MAX_WIDTH = 1000f
    }
}
