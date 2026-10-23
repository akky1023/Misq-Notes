package jp.handwriting.note.editor

import kotlin.math.*

/** The two lengths are independent; their common angle keeps the tangent direction aligned. */
internal data class PressureTangent(val angle: Float, val incoming: Float, val outgoing: Float) {
    init { require(angle.isFinite() && angle in 0f..90f && incoming.isFinite() && incoming in 0f..142f && outgoing.isFinite() && outgoing in 0f..142f) }
    val unitX get() = cos(angle * PI / 180).toFloat().coerceAtLeast(0f)
    val unitY get() = sin(angle * PI / 180).toFloat().coerceAtLeast(0f)
}

internal object PressureHandles {
    fun automatic(points: List<PressureControlPoint>, slopes: FloatArray): List<PressureTangent> = points.indices.map { i ->
        val angle = (atan(slopes[i].toDouble()) * 180 / PI).toFloat()
        val incoming = if (i == 0) 0f else (points[i].x - points[i - 1].x) / 3f
        val outgoing = if (i == points.lastIndex) 0f else (points[i + 1].x - points[i].x) / 3f
        PressureTangent(angle, hypot(incoming, incoming * slopes[i]), hypot(outgoing, outgoing * slopes[i]))
    }
    fun constrained(points: List<PressureControlPoint>, tangents: List<PressureTangent>): List<PressureTangent> {
        require(points.size == tangents.size)
        val incoming = tangents.map { it.incoming }.toFloatArray()
        val outgoing = tangents.map { it.outgoing }.toFloatArray()
        incoming[0] = 0f; outgoing[outgoing.lastIndex] = 0f
        for (i in 0 until points.lastIndex) {
            val dx = outgoing[i] * tangents[i].unitX + incoming[i + 1] * tangents[i + 1].unitX
            val dy = outgoing[i] * tangents[i].unitY + incoming[i + 1] * tangents[i + 1].unitY
            val scale = minOf(1f, if (dx > 0) (points[i + 1].x - points[i].x) / dx else 1f,
                if (dy > 0) (points[i + 1].y - points[i].y) / dy else 1f).coerceIn(0f, 1f)
            outgoing[i] *= scale; incoming[i + 1] *= scale
        }
        return tangents.mapIndexed { i, tangent -> tangent.copy(incoming = incoming[i], outgoing = outgoing[i]) }
    }
    fun rescaledY(tangents: List<PressureTangent>, scale: Float): List<PressureTangent> = tangents.map { t ->
        val x = t.unitX; val y = t.unitY * scale
        val angle = if (x == 0f && y == 0f) 0f else (atan2(y.toDouble(), x.toDouble()) * 180 / PI).toFloat()
        val lengthScale = hypot(x, y)
        PressureTangent(angle, (t.incoming * lengthScale).coerceAtMost(142f), (t.outgoing * lengthScale).coerceAtMost(142f))
    }
}
