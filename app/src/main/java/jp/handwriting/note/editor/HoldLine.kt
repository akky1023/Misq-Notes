package jp.handwriting.note.editor

import kotlin.math.*

/** Coordinates use the canvas y-down system; angles use the usual y-up polar system. */
internal class HoldLine(
    val start: WorldPoint, private val dpPerUnit: Float, private val snap: Boolean,
    time: Long,
) {
    companion object { const val HOLD_MS = 600L }
    var end = start
        private set
    var active = false
        private set
    var stationarySince = time
        private set
    private var anchor = start
    private var fineSnap = false
    private var shortAngle: Float? = null
    var angle = 0f
        private set
    private var rawEnd = start
    private val freehandPoints = mutableListOf(start)

    fun sample(point: WorldPoint, time: Long) {
        if (!point.x.isFinite() || !point.y.isFinite()) return
        rawEnd = point
        // Keep spatial samples rather than every stationary pressure/jitter event.
        if (!active && distance(freehandPoints.last(), point) * dpPerUnit >= 1f) freehandPoints.add(point)
        if (!active && distance(anchor, point) * dpPerUnit > 8f) {
            anchor = point
            stationarySince = time
        }
        updateEnd()
    }

    fun activate(time: Long): Boolean {
        if (!active && time - stationarySince >= HOLD_MS && distance(start, rawEnd) * dpPerUnit >= 12f && roughlyStraight()) {
            active = true
            freehandPoints.clear()
            updateEnd()
        }
        return active
    }

    private fun roughlyStraight(): Boolean {
        val dx = rawEnd.x - start.x
        val dy = rawEnd.y - start.y
        val length = hypot(dx, dy)
        // Allow mild wobble, with a screen-space floor for short lines and a cap for long curves.
        val tolerance = (length * dpPerUnit * 0.06f).coerceIn(3f, 12f) / dpPerUnit
        val ux = dx / length
        val uy = dy / length
        return freehandPoints.all { point ->
            val x = point.x - start.x
            val y = point.y - start.y
            val along = (x * ux + y * uy).coerceIn(0f, length)
            hypot(x - along * ux, y - along * uy) <= tolerance
        }
    }

    private fun updateEnd() {
        val radius = distance(start, rawEnd)
        val dp = radius * dpPerUnit
        val rawAngle = Math.toDegrees(atan2((start.y - rawEnd.y).toDouble(), (rawEnd.x - start.x).toDouble())).toFloat()
        if (dp >= 120f) {
            fineSnap = true
            shortAngle = null
        } else if (fineSnap) {
            // Remember the last long-line angle, including across the +/-180 degree seam.
            val reference = shortAngle ?: angle.also { shortAngle = it }
            val difference = abs(((rawAngle - reference + 540f) % 360f) - 180f)
            if (difference >= 30f) fineSnap = false
        }
        val step = if (fineSnap) 15f else 45f
        val target = round(rawAngle / step) * step
        // Allow only float roundoff at the inclusive 2.5-degree boundary.
        val snapped = active && snap && abs(rawAngle - target) <= 2.5f + 0.00001f
        angle = if (snapped) target else rawAngle
        if (angle > 180f) angle -= 360f
        if (angle < -180f) angle += 360f
        if (angle == -0f) angle = 0f
        val radians = Math.toRadians(angle.toDouble())
        end = if (snapped) WorldPoint(start.x + radius * cos(radians).toFloat(), start.y - radius * sin(radians).toFloat()) else rawEnd
    }

    private fun distance(a: WorldPoint, b: WorldPoint) = hypot(b.x - a.x, b.y - a.y)
}
