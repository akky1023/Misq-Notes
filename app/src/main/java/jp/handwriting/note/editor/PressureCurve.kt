package jp.handwriting.note.editor

import kotlin.math.pow
import kotlin.math.roundToInt

/** Monotone cubic Bezier from (0, 0) to (1, 1), with integer-percent handles. */
internal data class BezierControls(val x1: Int = 33, val y1: Int = 33, val x2: Int = 67, val y2: Int = 67) {
    init { require(x1 in 0..100 && x2 in x1..100 && y1 in 0..100 && y2 in y1..100) }
    fun factor(x: Float): Float {
        require(x.isFinite())
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        fun coordinate(t: Double, a: Int, b: Int): Double {
            val s = 1 - t
            return 3 * s * s * t * a / 100 + 3 * s * t * t * b / 100 + t * t * t
        }
        // X is monotone even with coincident handles; bisection also handles flat tangents.
        var low = 0.0; var high = 1.0
        repeat(28) {
            val t = (low + high) / 2
            if (coordinate(t, x1, x2) < x) low = t else high = t
        }
        return coordinate((low + high) / 2, y1, y2).toFloat().coerceIn(0f, 1f)
    }
    fun moved(handle: Int, x: Int, y: Int): BezierControls = when (handle) {
        1 -> copy(x1 = x.coerceIn(0, x2), y1 = y.coerceIn(0, y2))
        2 -> copy(x2 = x.coerceIn(x1, 100), y2 = y.coerceIn(y1, 100))
        else -> throw IllegalArgumentException("Unknown Bezier handle")
    }
    companion object {
        /** Fit old settings for editing; existing strokes retain their exact power function. */
        fun fromPower(exponentPercent: Int): BezierControls {
            require(exponentPercent in 25..400)
            if (exponentPercent == 100) return BezierControls()
            val samples = (1..31).map { it / 32f }
            val targets = samples.map { it.pow(exponentPercent / 100f) }
            fun error(c: BezierControls): Double = samples.indices.sumOf { i ->
                val delta = c.factor(samples[i]) - targets[i]; (delta * delta).toDouble()
            }
            // Several starting shapes avoid a local minimum for steep legacy curves.
            val seeds = listOf(BezierControls(), BezierControls(0, 0, 100, 0), BezierControls(0, 100, 100, 100),
                BezierControls(0, 50, 0, 100), BezierControls(100, 0, 100, 50),
                BezierControls(33, 0, 67, 0), BezierControls(0, 33, 0, 67))
            val fitted = seeds.map { seed ->
                var best = seed; var score = error(best)
                for (step in listOf(25, 10, 5, 2, 1)) {
                    for (pass in 0 until 12) {
                        var improved = false
                        for (axis in 0..3) for (direction in listOf(-1, 1)) {
                            val values = intArrayOf(best.x1, best.y1, best.x2, best.y2)
                            values[axis] = (values[axis] + step * direction).coerceIn(0, 100)
                            if (values[0] > values[2] || values[1] > values[3]) continue
                            val candidate = BezierControls(values[0], values[1], values[2], values[3])
                            val candidateScore = error(candidate)
                            if (candidateScore < score) { best = candidate; score = candidateScore; improved = true }
                        }
                        if (!improved) break
                    }
                }
                best to score
            }
            return fitted.minBy { it.second }.first
        }
    }
}

internal data class PressureCurve(
    val minimumPercent: Int = 20,
    val exponentPercent: Int = 100,
    val bezier: BezierControls? = null,
    val spline: PressureSpline? = null,
) {
    init {
        require(minimumPercent in 1..100 && exponentPercent in 25..400)
        require((bezier == null && spline == null) || exponentPercent == 100)
        require(bezier == null || spline == null)
        require(spline == null || spline.minimumPercent == minimumPercent)
    }
    fun factor(pressure: Float): Float {
        require(pressure.isFinite())
        spline?.let { return it.factor(pressure) }
        val minimum = minimumPercent / 100f
        val p = pressure.coerceIn(0f, 1f)
        return minimum + (1f - minimum) * (bezier?.factor(p) ?: p.pow(exponentPercent / 100f))
    }
    fun asBezier(): PressureCurve = if (bezier != null || spline != null) this else
        PressureCurve(minimumPercent, bezier = if (minimumPercent == 100) BezierControls() else BezierControls.fromPower(exponentPercent))
    fun asSpline(): PressureCurve = if (spline != null) this else PressureSpline.fromCurve(this).let {
        PressureCurve(it.minimumPercent, spline = it)
    }
    fun asPassingCurve(): PressureCurve {
        if (spline?.basis == PressureBasis.INTERPOLATED) return this
        val points = spline?.points ?: asSpline().spline!!.points
        val passing = PressureSpline(points)
        return PressureCurve(passing.minimumPercent, spline = passing)
    }
    fun asHandleCurve(): PressureCurve {
        val handled = asPassingCurve().spline!!.withHandles()
        return if (spline == handled) this else PressureCurve(handled.minimumPercent, spline = handled)
    }
    fun asControlBezier(): PressureCurve {
        if (spline?.basis == PressureBasis.BEZIER_CONTROLS) return this
        val points = spline?.points ?: asBezier().let { old ->
            val c = old.bezier!!
            val x1 = c.x1.coerceIn(1, 98); val x2 = c.x2.coerceIn(x1 + 1, 99)
            fun absoluteY(y: Int) = (minimumPercent + (100 - minimumPercent) * y / 100f).roundToInt()
            listOf(PressureControlPoint(0, minimumPercent), PressureControlPoint(x1, absoluteY(c.y1)),
                PressureControlPoint(x2, absoluteY(c.y2)), PressureControlPoint(100, 100))
        }
        val controls = PressureSpline(points, PressureBasis.BEZIER_CONTROLS)
        return PressureCurve(controls.minimumPercent, spline = controls)
    }
    companion object {
        fun editableDefault() = PressureCurve(spline = PressureSpline(listOf(
            PressureControlPoint(0, 20), PressureControlPoint(100, 100))).withHandles())
    }
}
