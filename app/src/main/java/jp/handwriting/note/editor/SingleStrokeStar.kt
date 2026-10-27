package jp.handwriting.note.editor

import kotlin.math.*

/** Matches a complete pentagram within one pen-down/pen-up stroke, never a group of strokes. */
internal object SingleStrokeStar {
    data class Point(val x: Float, val y: Float)
    private const val SAMPLES = 64
    private val template = normalize(resample((0..5).map {
        val angle = -PI / 2 + (it % 5) * 4 * PI / 5
        Point(cos(angle).toFloat(), sin(angle).toFloat())
    }))

    fun matches(points: List<Point>): Boolean {
        if (points.size < 6 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        if (width < 3f || height < 3f || width / height !in 0.35f..2.8f) return false
        val diagonal = hypot(width, height)
        if (distance(points.first(), points.last()) > diagonal * 0.35f) return false
        var length = 0f
        for (i in 1 until points.size) length += distance(points[i - 1], points[i])
        if (length / diagonal !in 2.8f..4.8f) return false
        val scaled = points.map { Point(it.x / width, it.y / height) }
        val candidate = normalize(resample(scaled + scaled.first()))
        // Fit translation, scale, starting vertex, direction and rotation. Sampling bounds the fit cost.
        var correlation = 0.0
        for (direction in intArrayOf(1, -1)) for (shift in 0 until SAMPLES) {
            var dot = 0.0
            var cross = 0.0
            for (i in 0 until SAMPLES) {
                val p = candidate[i]
                val t = template[(shift + direction * i + SAMPLES) % SAMPLES]
                dot += p.x.toDouble() * t.x + p.y.toDouble() * t.y
                cross += p.x.toDouble() * t.y - p.y.toDouble() * t.x
            }
            correlation = max(correlation, hypot(dot, cross) / SAMPLES)
        }
        return sqrt(max(0.0, 2.0 - 2.0 * correlation)) <= 0.50
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun resample(points: List<Point>): List<Point> {
        val lengths = FloatArray(points.size)
        for (i in 1 until points.size) lengths[i] = lengths[i - 1] + distance(points[i - 1], points[i])
        var segment = 1
        return List(SAMPLES) { i ->
            val target = lengths.last() * i / SAMPLES
            while (segment < points.lastIndex && lengths[segment] < target) segment++
            val span = lengths[segment] - lengths[segment - 1]
            val fraction = if (span > 0f) (target - lengths[segment - 1]) / span else 0f
            val a = points[segment - 1]; val b = points[segment]
            Point(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y))
        }
    }

    private fun normalize(points: List<Point>): List<Point> {
        val x = points.map { it.x }.average().toFloat()
        val y = points.map { it.y }.average().toFloat()
        val centered = points.map { Point(it.x - x, it.y - y) }
        val radius = sqrt(centered.map { it.x.toDouble() * it.x + it.y.toDouble() * it.y }.average()).toFloat()
        return centered.map { Point(it.x / radius, it.y / radius) }
    }
}
