package jp.handwriting.note.editor

/** Exact distances for a circular eraser swept along a segment (a capsule). */
internal object SweepGeometry {
    fun pointDistanceSquared(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / length).coerceIn(0f, 1f)
        val x = px - ax - t * dx; val y = py - ay - t * dy
        return x * x + y * y
    }
    fun segmentDistanceSquared(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        fun cross(x: Float, y: Float, u: Float, v: Float) = x * v - y * u
        val abC = cross(bx - ax, by - ay, cx - ax, cy - ay)
        val abD = cross(bx - ax, by - ay, dx - ax, dy - ay)
        val cdA = cross(dx - cx, dy - cy, ax - cx, ay - cy)
        val cdB = cross(dx - cx, dy - cy, bx - cx, by - cy)
        if ((abC > 0 && abD < 0 || abC < 0 && abD > 0) && (cdA > 0 && cdB < 0 || cdA < 0 && cdB > 0)) return 0f
        return minOf(pointDistanceSquared(ax, ay, cx, cy, dx, dy), pointDistanceSquared(bx, by, cx, cy, dx, dy),
            pointDistanceSquared(cx, cy, ax, ay, bx, by), pointDistanceSquared(dx, dy, ax, ay, bx, by))
    }
}
