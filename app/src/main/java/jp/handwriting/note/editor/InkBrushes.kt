@file:Suppress("RestrictedApi")

package jp.handwriting.note.editor

import androidx.ink.brush.*
import androidx.ink.geometry.ImmutableVec
import android.annotation.SuppressLint
import kotlin.math.pow

@OptIn(ExperimentalInkCustomBrushApi::class)
@SuppressLint("RestrictedApi")
internal object InkBrushes {
    val highlighter = StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1)
    private val curves = object : LinkedHashMap<PressureCurve, BrushFamily>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PressureCurve, BrushFamily>?) = size > 32
    }
    @Synchronized fun pressurePen(curve: PressureCurve): BrushFamily = curves.getOrPut(curve) {
        // Ink requires distinct modifier endpoints. At 100%, the tip is already full size
        // for every pressure, so no size behavior is needed.
        val behaviors = if (curve.minimumPercent == 100) emptyList() else listOf(BrushBehavior(
            BrushBehavior.Source.NORMALIZED_PRESSURE, BrushBehavior.Target.SIZE_MULTIPLIER,
            0f, 1f, curve.minimumPercent / 100f, 1f,
            responseCurve = curve.spline?.let { spline -> EasingFunction.Linear(spline.samples.map { p ->
                ImmutableVec(p.x, (p.y - curve.minimumPercent / 100f) / (1f - curve.minimumPercent / 100f))
            }) } ?: curve.bezier?.let { c -> EasingFunction.CubicBezier(c.x1 / 100f, c.y1 / 100f, c.x2 / 100f, c.y2 / 100f) }
                ?: EasingFunction.Linear((0..32).map { i ->
                    val p = i / 32f
                    ImmutableVec(p, p.toDouble().pow(curve.exponentPercent / 100.0).toFloat())
                }),
            enabledToolTypes = setOf(InputToolType.STYLUS),
        ))
        BrushFamily(
            tip = BrushTip(behaviors = behaviors),
            clientBrushFamilyId = curve.spline?.let { spline ->
                if (spline.tangents != null) "net.neomenia.handwriting.pressure-v5:" + spline.points.mapIndexed { i, p ->
                    val t = spline.tangents[i]; "${p.x},${p.y},${t.angle},${t.incoming},${t.outgoing}"
                }.joinToString(":") else
                (if (spline.basis == PressureBasis.BEZIER_CONTROLS) "net.neomenia.handwriting.pressure-v4:" else "net.neomenia.handwriting.pressure-v3:") +
                    spline.points.joinToString(":") { "${it.x},${it.y}" }
            } ?: curve.bezier?.let { c ->
                "net.neomenia.handwriting.pressure-v2:${curve.minimumPercent}:${c.x1}:${c.y1}:${c.x2}:${c.y2}"
            } ?: "net.neomenia.handwriting.pressure-v1:${curve.minimumPercent}:${curve.exponentPercent}",
        )
    }
    fun curveFor(family: BrushFamily): PressureCurve? {
        val parts = family.clientBrushFamilyId.split(':')
        return when {
            parts.size in 3..102 && parts[0] == "net.neomenia.handwriting.pressure-v5" -> {
                val values = parts.drop(1).map { it.split(',').also { v -> require(v.size == 5) } }
                val spline = PressureSpline(values.map { PressureControlPoint(it[0].toInt(), it[1].toInt()) },
                    tangents = values.map { PressureTangent(it[2].toFloat(), it[3].toFloat(), it[4].toFloat()) })
                PressureCurve(spline.minimumPercent, spline = spline)
            }
            parts.size in 3..102 && parts[0] in listOf("net.neomenia.handwriting.pressure-v3", "net.neomenia.handwriting.pressure-v4") -> {
                val spline = PressureSpline(parts.drop(1).map {
                    val coordinates = it.split(','); require(coordinates.size == 2)
                    PressureControlPoint(coordinates[0].toInt(), coordinates[1].toInt())
                }, if (parts[0] == "net.neomenia.handwriting.pressure-v4") PressureBasis.BEZIER_CONTROLS else PressureBasis.INTERPOLATED)
                PressureCurve(spline.minimumPercent, spline = spline)
            }
            parts.size == 3 && parts[0] == "net.neomenia.handwriting.pressure-v1" -> PressureCurve(parts[1].toInt(), parts[2].toInt())
            parts.size == 6 && parts[0] == "net.neomenia.handwriting.pressure-v2" -> PressureCurve(parts[1].toInt(),
                bezier = BezierControls(parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), parts[5].toInt()))
            else -> null
        }
    }
}
