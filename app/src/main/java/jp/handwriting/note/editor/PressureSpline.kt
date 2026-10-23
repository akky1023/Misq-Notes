package jp.handwriting.note.editor

import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.hypot

internal data class PressureControlPoint(val x: Int, val y: Int) {
    init { require(x in 0..100 && y in 1..100) }
}
internal data class PressureSample(val x: Float, val y: Float)
internal enum class PressureBasis { INTERPOLATED, BEZIER_CONTROLS }

/** Shared samples drive Ink and the graph; the basis also preserves older interpolated strokes. */
internal class PressureSpline(points: List<PressureControlPoint>, val basis: PressureBasis = PressureBasis.INTERPOLATED,
    tangents: List<PressureTangent>? = null) {
    val points: List<PressureControlPoint> = points.toList()
    val tangents: List<PressureTangent>? = tangents?.toList()
    val minimumPercent get() = points.first().y
    init {
        require(points.size in 2..101 && points.first().x == 0 && points.last() == PressureControlPoint(100, 100))
        require(points.zipWithNext().all { (a, b) -> a.x < b.x && a.y <= b.y })
        if (tangents != null) {
            require(basis == PressureBasis.INTERPOLATED && tangents.size == points.size)
            require(tangents.first().incoming == 0f && tangents.last().outgoing == 0f)
            for (i in 0 until points.lastIndex) {
                require(tangents[i].outgoing * tangents[i].unitX + tangents[i + 1].incoming * tangents[i + 1].unitX <= points[i + 1].x - points[i].x + 0.0001f)
                require(tangents[i].outgoing * tangents[i].unitY + tangents[i + 1].incoming * tangents[i + 1].unitY <= points[i + 1].y - points[i].y + 0.0001f)
            }
        }
    }
    val samples: List<PressureSample> = when {
        tangents != null -> buildHandleSamples()
        basis == PressureBasis.BEZIER_CONTROLS -> buildBezierSamples()
        else -> buildSamples()
    }
    fun withHandles(): PressureSpline {
        if (tangents != null) return this
        require(basis == PressureBasis.INTERPOLATED)
        return PressureSpline(points, basis, PressureHandles.constrained(points, PressureHandles.automatic(points, automaticSlopes())))
    }
    private fun edited(newPoints: List<PressureControlPoint>, newTangents: List<PressureTangent>? = tangents): PressureSpline =
        PressureSpline(newPoints, basis, newTangents?.let { PressureHandles.constrained(newPoints, it) })
    fun factor(pressure: Float): Float {
        require(pressure.isFinite())
        if (pressure <= 0) return samples.first().y
        if (pressure >= 1) return samples.last().y
        var low = 0; var high = samples.lastIndex
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (samples[middle].x <= pressure) low = middle else high = middle
        }
        val a = samples[low]; val b = samples[high]
        return a.y + (b.y - a.y) * ((pressure - a.x) / (b.x - a.x))
    }
    fun added(x: Int, y: Int): PressureSpline {
        if (x !in 1..99 || points.any { it.x == x }) return this
        val index = points.indexOfFirst { it.x > x }
        val point = PressureControlPoint(x, y.coerceIn(points[index - 1].y, points[index].y))
        val newPoints = points.toMutableList().apply { add(index, point) }
        val newTangents = tangents?.toMutableList()?.apply {
            add(index, PressureSpline(newPoints).withHandles().tangents!![index])
        }
        return edited(newPoints, newTangents)
    }
    fun moved(index: Int, x: Int, y: Int): PressureSpline {
        require(index in points.indices)
        if (index == points.lastIndex) return this // Full pressure stays at full width.
        if (index == 0) {
            val minimum = y.coerceIn(1, 100); val before = minimumPercent
            val newPoints = points.map { point ->
                val relative = if (before == 100) point.x / 100f else (point.y - before).toFloat() / (100 - before)
                PressureControlPoint(point.x, (minimum + (100 - minimum) * relative).roundToInt().coerceIn(minimum, 100))
            }
            val newTangents = tangents?.let {
                if (before == 100) PressureSpline(newPoints).withHandles().tangents!!
                else PressureHandles.rescaledY(it, (100 - minimum).toFloat() / (100 - before))
            }
            return edited(newPoints, newTangents)
        }
        val point = PressureControlPoint(x.coerceIn(points[index - 1].x + 1, points[index + 1].x - 1),
            y.coerceIn(points[index - 1].y, points[index + 1].y))
        return edited(points.mapIndexed { i, old -> if (i == index) point else old })
    }
    fun removed(index: Int): PressureSpline {
        require(index in points.indices)
        return if (index == 0 || index == points.lastIndex) this else
            edited(points.filterIndexed { i, _ -> i != index }, tangents?.filterIndexed { i, _ -> i != index })
    }
    fun handlePosition(index: Int, incoming: Boolean): PressureSample {
        val tangent = requireNotNull(tangents)[index]
        require(if (incoming) index > 0 else index < points.lastIndex)
        val length = if (incoming) -tangent.incoming else tangent.outgoing
        return PressureSample((points[index].x + tangent.unitX * length) / 100,
            (points[index].y + tangent.unitY * length) / 100)
    }
    fun movedHandle(index: Int, incoming: Boolean, x: Float, y: Float): PressureSpline {
        require(x.isFinite() && y.isFinite())
        val handles = requireNotNull(tangents)
        require(if (incoming) index > 0 else index < points.lastIndex)
        val sign = if (incoming) -1 else 1
        val dx = (sign * (x - points[index].x)).coerceAtLeast(0f)
        val dy = (sign * (y - points[index].y)).coerceAtLeast(0f)
        val length = hypot(dx, dy).coerceAtMost(142f)
        val angle = if (length == 0f) handles[index].angle else (kotlin.math.atan2(dy.toDouble(), dx.toDouble()) * 180 / kotlin.math.PI).toFloat()
        val updated = if (incoming) handles[index].copy(angle = angle, incoming = length)
            else handles[index].copy(angle = angle, outgoing = length)
        return edited(points, handles.mapIndexed { i, old -> if (i == index) updated else old })
    }
    fun resetHandles(index: Int): PressureSpline {
        require(index in points.indices)
        val automatic = PressureSpline(points).withHandles().tangents!![index]
        return edited(points, requireNotNull(tangents).mapIndexed { i, old -> if (i == index) automatic else old })
    }
    fun dragged(index: Int, x: Float, y: Float, deleteMargin: Float = 20f): PressureSpline {
        require(index in points.indices && x.isFinite() && y.isFinite() && deleteMargin.isFinite() && deleteMargin >= 0)
        val outsideX = maxOf(0f, -x, x - 100)
        val outsideY = maxOf(0f, -y, y - 100)
        if (index in 1 until points.lastIndex && hypot(outsideX, outsideY) > deleteMargin) return removed(index)
        return moved(index, x.roundToInt(), y.roundToInt())
    }
    private fun buildBezierSamples(): List<PressureSample> {
        val degree = points.lastIndex
        return (0..512).map { step ->
            if (step == 0) return@map PressureSample(0f, minimumPercent / 100f)
            if (step == 512) return@map PressureSample(1f, 1f)
            val parameter = step / 512.0
            // Evaluate Bernstein weights from the nearer endpoint for numerical stability.
            val reverse = parameter > 0.5
            val t = if (reverse) 1 - parameter else parameter
            var weight = (1 - t).pow(degree)
            val ratio = t / (1 - t)
            var x = 0.0; var y = 0.0
            for (i in 0..degree) {
                val point = points[if (reverse) degree - i else i]
                x += weight * point.x; y += weight * point.y
                if (i < degree) weight *= (degree - i).toDouble() / (i + 1) * ratio
            }
            PressureSample((x / 100).toFloat(), (y / 100).toFloat().coerceIn(minimumPercent / 100f, 1f))
        }
    }
    private fun automaticSlopes(): FloatArray {
        val intervals = points.zipWithNext().map { (a, b) -> (b.x - a.x).toFloat() }
        val secants = points.zipWithNext().map { (a, b) -> (b.y - a.y).toFloat() / (b.x - a.x) }
        val slopes = FloatArray(points.size)
        slopes[0] = secants.first(); slopes[slopes.lastIndex] = secants.last()
        for (i in 1 until points.lastIndex) {
            if (secants[i - 1] > 0 && secants[i] > 0) {
                val a = 2 * intervals[i] + intervals[i - 1]; val b = intervals[i] + 2 * intervals[i - 1]
                slopes[i] = (a + b) / (a / secants[i - 1] + b / secants[i])
            }
        }
        // Ordered Bezier handles prevent overshoot while retaining continuous tangents.
        for (i in secants.indices) {
            val sum = slopes[i] + slopes[i + 1]; val limit = 3 * secants[i]
            if (sum > limit) { val scale = limit / sum; slopes[i] *= scale; slopes[i + 1] *= scale }
        }
        return slopes
    }
    private fun buildSamples(): List<PressureSample> {
        val intervals = points.zipWithNext().map { (a, b) -> (b.x - a.x).toFloat() }
        val slopes = automaticSlopes()
        return buildList {
            for (i in 0 until points.lastIndex) {
                val a = points[i]; val b = points[i + 1]; val width = intervals[i]
                val c1 = a.y + slopes[i] * width / 3; val c2 = b.y - slopes[i + 1] * width / 3
                for (step in 0 until 16) {
                    val t = step / 16f; val s = 1 - t
                    val y = s * s * s * a.y + 3 * s * s * t * c1 + 3 * s * t * t * c2 + t * t * t * b.y
                    add(PressureSample((a.x + width * t) / 100, y.coerceIn(a.y.toFloat(), b.y.toFloat()) / 100))
                }
            }
            add(PressureSample(1f, 1f))
        }
    }
    private fun buildHandleSamples(): List<PressureSample> = buildList {
        for (i in 0 until points.lastIndex) {
            val a = points[i]; val b = points[i + 1]
            val c1 = handlePosition(i, false); val c2 = handlePosition(i + 1, true)
            for (step in 0 until 32) {
                val t = step / 32f; val s = 1 - t
                val x = s * s * s * a.x / 100 + 3 * s * s * t * c1.x + 3 * s * t * t * c2.x + t * t * t * b.x / 100
                val y = s * s * s * a.y / 100 + 3 * s * s * t * c1.y + 3 * s * t * t * c2.y + t * t * t * b.y / 100
                add(PressureSample(x, y.coerceIn(a.y / 100f, b.y / 100f)))
            }
        }
        add(PressureSample(1f, 1f))
    }
    override fun equals(other: Any?) = other is PressureSpline && points == other.points && basis == other.basis && tangents == other.tangents
    override fun hashCode() = 31 * (31 * points.hashCode() + basis.hashCode()) + (tangents?.hashCode() ?: 0)
    companion object {
        fun fromCurve(curve: PressureCurve): PressureSpline {
            fun point(x: Int) = PressureControlPoint(x, (curve.factor(x / 100f) * 100).roundToInt().coerceIn(1, 100))
            var result = PressureSpline(listOf(0, 25, 50, 75, 100).map(::point))
            // Add points where the old curve differs most; old strokes retain their exact family.
            repeat(96) {
                val x = (1..99).filter { value -> result.points.none { it.x == value } }
                    .maxByOrNull { kotlin.math.abs(result.factor(it / 100f) - curve.factor(it / 100f)) } ?: return result
                if (kotlin.math.abs(result.factor(x / 100f) - curve.factor(x / 100f)) <= 0.01f) return result
                result = result.added(x, point(x).y)
            }
            return result
        }
    }
}
