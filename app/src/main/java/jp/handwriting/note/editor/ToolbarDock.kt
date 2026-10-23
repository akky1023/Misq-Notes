package jp.handwriting.note.editor

internal enum class DockEdge { TOP, BOTTOM, LEFT, RIGHT, CORNER }
internal data class ToolbarDock(val x: Float, val y: Float, val edge: DockEdge) {
    fun reopened(previous: DockEdge): ToolbarDock {
        require(edge == DockEdge.CORNER)
        val horizontalEdge = if (y < 0.5f) DockEdge.TOP else DockEdge.BOTTOM
        val verticalEdge = if (x < 0.5f) DockEdge.LEFT else DockEdge.RIGHT
        return centered(if (previous == horizontalEdge || previous == verticalEdge) previous else horizontalEdge)
    }
    companion object {
        fun centered(edge: DockEdge): ToolbarDock = when (edge) {
            DockEdge.TOP -> ToolbarDock(0.5f, 0f, edge)
            DockEdge.BOTTOM -> ToolbarDock(0.5f, 1f, edge)
            DockEdge.LEFT -> ToolbarDock(0f, 0.5f, edge)
            DockEdge.RIGHT -> ToolbarDock(1f, 0.5f, edge)
            DockEdge.CORNER -> error("A corner requires two sides")
        }
        fun snap(x: Float, y: Float, marginX: Float, marginY: Float): ToolbarDock {
            require(listOf(x, y, marginX, marginY).all { it.isFinite() })
            val h = x.coerceIn(0f, 1f); val v = y.coerceIn(0f, 1f)
            val nearX = h <= marginX || h >= 1 - marginX
            val nearY = v <= marginY || v >= 1 - marginY
            if (nearX && nearY) return ToolbarDock(if (h < 0.5f) 0f else 1f, if (v < 0.5f) 0f else 1f, DockEdge.CORNER)
            return when (listOf(v, 1 - v, h, 1 - h).indices.minBy { listOf(v, 1 - v, h, 1 - h)[it] }) {
                0 -> centered(DockEdge.TOP)
                1 -> centered(DockEdge.BOTTOM)
                2 -> centered(DockEdge.LEFT)
                else -> centered(DockEdge.RIGHT)
            }
        }
    }
}
