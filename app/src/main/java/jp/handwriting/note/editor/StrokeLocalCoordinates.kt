package jp.handwriting.note.editor

import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke

/** Rendering-only geometry: saved coordinates and pressures remain untouched. */
internal data class LocalStrokeGeometry(val stroke: Stroke, val x: Float, val y: Float)

internal object StrokeLocalCoordinates {
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun create(source: Stroke, epsilon: Float = source.brush.epsilon): LocalStrokeGeometry {
        if (source.inputs.isEmpty()) return LocalStrokeGeometry(source, 0f, 0f)
        val brush = if (epsilon < source.brush.epsilon) source.brush.copy(epsilon = epsilon) else source.brush
        val first = source.inputs[0]
        if (first.x == 0f && first.y == 0f) {
            return LocalStrokeGeometry(if (brush === source.brush) source else Stroke(brush, source.inputs), 0f, 0f)
        }
        val inputs = MutableStrokeInputBatch()
        for (i in 0 until source.inputs.size) {
            val p = source.inputs[i]
            inputs.add(p.toolType, p.x - first.x, p.y - first.y, p.elapsedTimeMillis,
                p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
        }
        inputs.setNoiseSeed(source.inputs.getNoiseSeed())
        return LocalStrokeGeometry(Stroke(brush, inputs), first.x, first.y)
    }
}
