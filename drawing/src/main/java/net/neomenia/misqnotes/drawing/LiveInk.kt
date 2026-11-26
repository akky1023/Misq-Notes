package net.neomenia.misqnotes.drawing

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor

/**
 * Live stylus layer for a host View that owns touch routing and completed strokes.
 * Add [view] above the completed layer and call these methods on the UI thread.
 * [onFinished] should publish the stroke and invalidate the completed layer immediately.
 */
class LiveInk(
    private val host: View,
    onFinished: (token: Long, stroke: Stroke) -> Unit,
) {
    val view = InProgressStrokesView(host.context).also(InkStrokeRenderer::configure)
    private var predictor = MotionEventPredictor.newInstance(host)
    private var predictionValid = true
    private val pending = mutableMapOf<InProgressStrokeId, InputCopy>()
    private var active: InProgressStrokeId? = null
    private var hidden = false

    val isDrawing: Boolean get() = active != null
    val activeBrush: Brush? get() = pending[active]?.brush
    val lastPressure: Float get() {
        val inputs = pending[active]?.inputs ?: return StrokeInput.NO_PRESSURE
        return if (inputs.isEmpty()) StrokeInput.NO_PRESSURE else inputs[inputs.size - 1].pressure
    }

    init {
        view.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                strokes.forEach { (id, stroke) ->
                    pending.remove(id)?.let { onFinished(it.token, stroke) }
                }
                view.removeFinishedStrokes(strokes.keys)
            }
        })
    }

    /** Pass the matching device's stylus events, including the initial ACTION_DOWN. */
    fun recordForPrediction(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            predictor = MotionEventPredictor.newInstance(host)
            predictionValid = true
        }
        if (predictionValid) {
            try { predictor.record(event) } catch (_: IllegalArgumentException) { predictionValid = false }
        }
    }

    fun start(event: MotionEvent, index: Int, brush: Brush, viewToStroke: Matrix, token: Long, pressureSupported: Boolean) {
        check(active == null)
        val id = view.startStroke(event, event.getPointerId(index), brush, viewToStroke)
        pending[id] = InputCopy(token, brush, event.eventTime, Matrix(viewToStroke), pressureSupported)
            .also { it.record(event, index) }
        active = id
        hidden = false
    }

    fun add(event: MotionEvent, index: Int) {
        val id = active ?: return
        if (hidden) return
        pending[id]?.record(event, index)
        val prediction = if (predictionValid) predictor.predict() else null
        try { view.addToStroke(event, event.getPointerId(index), id, prediction) }
        finally { prediction?.recycle() }
    }

    /** Hide freehand ink while the host previews a replacement for the active stroke. */
    fun hideActiveStroke() {
        val id = active ?: return
        if (hidden) return
        view.cancelStroke(id)
        hidden = true
    }

    fun finish(event: MotionEvent, index: Int) {
        val id = active ?: return
        check(!hidden)
        pending[id]?.let { it.record(event, index); it.completed = true }
        active = null
        view.finishStroke(event, event.getPointerId(index), id)
    }

    /** Discard active input and return its token so the host can cancel or replace the edit. */
    fun cancelActiveStroke(): Long? {
        val id = active ?: return null
        active = null
        if (!hidden) view.cancelStroke(id)
        hidden = false
        return pending.remove(id)?.token
    }

    /** Recover queued strokes without waiting for the rendering thread during a pause. */
    fun settle(onRecovered: (token: Long, stroke: Stroke) -> Unit, onCanceled: (token: Long) -> Unit) {
        cancelActiveStroke()?.let(onCanceled)
        val copies = pending.values.toList()
        pending.clear()
        copies.forEach { copy ->
            if (copy.completed && !copy.inputs.isEmpty()) onRecovered(copy.token, Stroke(copy.brush, copy.inputs))
            else onCanceled(copy.token)
        }
    }

    // Input-only mirror, released as soon as Jetpack Ink hands over the completed stroke.
    private class InputCopy(val token: Long, val brush: Brush, val startTime: Long, val transform: Matrix, val pressureSupported: Boolean) {
        val inputs = MutableStrokeInputBatch()
        private val point = FloatArray(2)
        private var lastTime = -1L
        var completed = false

        fun record(event: MotionEvent, index: Int) {
            for (h in 0..event.historySize) {
                val historical = h < event.historySize
                val time = (if (historical) event.getHistoricalEventTime(h) else event.eventTime) - startTime
                if (time < 0 || time <= lastTime) continue
                point[0] = if (historical) event.getHistoricalX(index, h) else event.getX(index)
                point[1] = if (historical) event.getHistoricalY(index, h) else event.getY(index)
                transform.mapPoints(point)
                val x = point[0]; val y = point[1]
                if (!x.isFinite() || !y.isFinite()) continue
                val p = if (historical) event.getHistoricalPressure(index, h) else event.getPressure(index)
                inputs.add(InputToolType.STYLUS, x, y, time,
                    pressure = if (pressureSupported && p.isFinite()) p.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE)
                lastTime = time
            }
        }
    }
}
