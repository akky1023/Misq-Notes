package jp.handwriting.note.editor

/** Fills abrupt endpoint narrowings in distance space, independently of input sampling rate. */
internal object StrokeEndpointSmoothing {
    // A diameter may shrink by at most half the distance traveled away from an endpoint sample.
    private const val WIDTH_SLOPE = 0.5f

    /** Distances and widths are in brush-size units. Only fills thin parts, never reduces widths. */
    fun widths(distances: FloatArray, original: FloatArray): FloatArray {
        require(distances.size == original.size)
        if (original.size < 3 || distances.last() < 2f) return original
        val result = original.copyOf()
        val length = distances.last()
        fun isEndpoint(i: Int) = distances[i] <= 1f || length - distances[i] <= 1f
        var floor = Float.NEGATIVE_INFINITY
        for (i in original.indices) {
            if (i > 0) floor -= WIDTH_SLOPE * (distances[i] - distances[i - 1])
            if (isEndpoint(i)) floor = maxOf(floor, original[i])
            result[i] = maxOf(result[i], floor)
        }
        floor = Float.NEGATIVE_INFINITY
        for (i in original.lastIndex downTo 0) {
            if (i < original.lastIndex) floor -= WIDTH_SLOPE * (distances[i + 1] - distances[i])
            if (isEndpoint(i)) floor = maxOf(floor, original[i])
            result[i] = maxOf(result[i], floor)
        }
        return result
    }

    /** Invert the saved monotone curve, rather than smoothing raw pressure on a nonlinear curve. */
    fun pressure(curve: PressureCurve, width: Float, original: Float): Float {
        if (curve.factor(original) >= width) return original
        var low = original
        var high = 1f
        repeat(24) {
            val mid = (low + high) / 2f
            if (curve.factor(mid) < width) low = mid else high = mid
        }
        return high
    }
}
