package jp.handwriting.note.editor

import kotlin.math.abs

internal data class WorldPoint(val x: Float, val y: Float)

internal enum class LassoMode { FREE, RECTANGLE }

internal fun rectangleLasso(start: WorldPoint, end: WorldPoint): List<WorldPoint> = listOf(
    start, WorldPoint(end.x, start.y), end, WorldPoint(start.x, end.y),
)

/** Closed lasso against stroke center lines, including boundary and crossing segments. */
internal class LassoGeometry(val points: List<WorldPoint>) {
    val valid = points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() } &&
        points.indices.any { i -> abs(cross(points[0], points[i], points[(i + 1) % points.size])) > 0.001f }
    fun contains(p: WorldPoint): Boolean {
        if (!valid) return false
        var inside = false
        var previous = points.last()
        for (current in points) {
            if (onSegment(previous, current, p)) return true
            if ((current.y > p.y) != (previous.y > p.y) &&
                p.x < (previous.x - current.x) * (p.y - current.y) / (previous.y - current.y) + current.x) inside = !inside
            previous = current
        }
        return inside
    }
    fun intersects(a: WorldPoint, b: WorldPoint): Boolean {
        if (!valid) return false
        if (contains(a) || contains(b)) return true
        return points.indices.any { i -> segments(a, b, points[i], points[(i + 1) % points.size]) }
    }
    private fun segments(a: WorldPoint, b: WorldPoint, c: WorldPoint, d: WorldPoint): Boolean {
        val abC = cross(a, b, c); val abD = cross(a, b, d)
        val cdA = cross(c, d, a); val cdB = cross(c, d, b)
        return (abC > 0 && abD < 0 || abC < 0 && abD > 0) && (cdA > 0 && cdB < 0 || cdA < 0 && cdB > 0) ||
            onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b)
    }
    private fun onSegment(a: WorldPoint, b: WorldPoint, p: WorldPoint): Boolean =
        abs(cross(a, b, p)) < 0.001f && p.x >= minOf(a.x, b.x) - 0.001f && p.x <= maxOf(a.x, b.x) + 0.001f &&
            p.y >= minOf(a.y, b.y) - 0.001f && p.y <= maxOf(a.y, b.y) + 0.001f
    private fun cross(a: WorldPoint, b: WorldPoint, c: WorldPoint) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
}
