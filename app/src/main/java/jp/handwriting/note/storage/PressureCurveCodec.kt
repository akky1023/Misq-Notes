package jp.handwriting.note.storage

import java.io.DataInput
import java.io.DataOutput
import jp.handwriting.note.editor.BezierControls
import jp.handwriting.note.editor.PressureCurve
import jp.handwriting.note.editor.PressureControlPoint
import jp.handwriting.note.editor.PressureSpline
import jp.handwriting.note.editor.PressureBasis
import jp.handwriting.note.editor.PressureTangent

internal object PressureCurveCodec {
    fun write(out: DataOutput, curve: PressureCurve) {
        out.writeInt(when { curve.spline?.tangents != null -> 4; curve.spline?.basis == PressureBasis.BEZIER_CONTROLS -> 3; curve.spline != null -> 2; curve.bezier != null -> 1; else -> 0 })
        out.writeInt(curve.minimumPercent)
        val controls = curve.bezier
        if (curve.spline != null) {
            out.writeInt(curve.spline.points.size)
            curve.spline.points.forEach { out.writeInt(it.x); out.writeInt(it.y) }
            curve.spline.tangents?.forEach { out.writeFloat(it.angle); out.writeFloat(it.incoming); out.writeFloat(it.outgoing) }
        } else if (controls == null) out.writeInt(curve.exponentPercent)
        else listOf(controls.x1, controls.y1, controls.x2, controls.y2).forEach(out::writeInt)
    }
    fun read(input: DataInput): PressureCurve {
        val type = input.readInt(); require(type in 0..4)
        val minimum = input.readInt()
        return when (type) {
            0 -> PressureCurve(minimum, input.readInt())
            1 -> PressureCurve(minimum, bezier = BezierControls(input.readInt(), input.readInt(), input.readInt(), input.readInt()))
            else -> {
                val count = input.readInt(); require(count in 2..101)
                val points = List(count) { PressureControlPoint(input.readInt(), input.readInt()) }
                val tangents = if (type == 4) List(count) { PressureTangent(input.readFloat(), input.readFloat(), input.readFloat()) } else null
                PressureCurve(minimum, spline = PressureSpline(points,
                    if (type == 3) PressureBasis.BEZIER_CONTROLS else PressureBasis.INTERPOLATED, tangents))
            }
        }
    }
}
