package jp.handwriting.note.editor

import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.math.hypot

/** Rendering-only geometry: saved coordinates and pressures remain untouched. */
internal data class LocalStrokeGeometry(val stroke: Stroke, val x: Float, val y: Float)

internal object StrokeLocalCoordinates {
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun create(source: Stroke, epsilon: Float = source.brush.epsilon): LocalStrokeGeometry {
        if (source.inputs.isEmpty()) return LocalStrokeGeometry(source, 0f, 0f)
        val brush = if (epsilon < source.brush.epsilon) source.brush.copy(epsilon = epsilon) else source.brush
        val first = source.inputs[0]
        val pressures = smoothedPressures(source)
        if (first.x == 0f && first.y == 0f && pressures == null) {
            return LocalStrokeGeometry(if (brush === source.brush) source else Stroke(brush, source.inputs), 0f, 0f)
        }
        val inputs = MutableStrokeInputBatch()
        for (i in 0 until source.inputs.size) {
            val p = source.inputs[i]
            inputs.add(p.toolType, p.x - first.x, p.y - first.y, p.elapsedTimeMillis,
                p.strokeUnitLengthCm, pressures?.get(i) ?: p.pressure, p.tiltRadians, p.orientationRadians)
        }
        inputs.setNoiseSeed(source.inputs.getNoiseSeed())
        return LocalStrokeGeometry(Stroke(brush, inputs), first.x, first.y)
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun smoothedPressures(source: Stroke): FloatArray? {
        // The custom pen's saved monotone curve defines its actual pressure-to-width mapping.
        // Fixed-width brushes and legacy stock brushes must retain their own behavior.
        val curve = InkBrushes.curveFor(source.brush.family) ?: return null
        if (curve.minimumPercent == 100 || source.inputs.size < 3) return null
        val distances = FloatArray(source.inputs.size)
        val widths = FloatArray(source.inputs.size)
        for (i in widths.indices) {
            val p = source.inputs[i]
            if (p.toolType != InputToolType.STYLUS || !p.pressure.isFinite() || p.pressure !in 0f..1f) return null
            widths[i] = curve.factor(p.pressure)
            if (i > 0) {
                val previous = source.inputs[i - 1]
                distances[i] = distances[i - 1] + hypot(p.x - previous.x, p.y - previous.y) / source.brush.size
            }
        }
        val limited = StrokeEndpointSmoothing.widths(distances, widths)
        if (widths.indices.none { limited[it] > widths[it] }) return null
        return FloatArray(widths.size) { i ->
            StrokeEndpointSmoothing.pressure(curve, limited[i], source.inputs[i].pressure)
        }
    }
}
