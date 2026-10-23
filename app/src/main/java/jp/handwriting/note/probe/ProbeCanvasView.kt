package jp.handwriting.note.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Only a bounded input preview; this is not the Phase 2 ink renderer. */
class ProbeCanvasView(context: Context) : View(context) {
    private data class Dot(val x: Float, val y: Float, val pressure: Float)
    private data class Segment(val from: Dot, val to: Dot)
    private val segments = ArrayDeque<Segment>()
    private val previous = mutableMapOf<Int, Dot>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 74, 96)
        strokeCap = Paint.Cap.ROUND
    }
    private var hover: Dot? = null

    init {
        contentDescription = "ペン入力確認領域。指では描画しません"
        setBackgroundColor(Color.rgb(250, 249, 244))
        isFocusable = true
        // Android 14+ handwriting delegation must not divert probe events to an IME.
        if (android.os.Build.VERSION.SDK_INT >= 34) isAutoHandwritingEnabled = false
    }

    fun clear() { segments.clear(); previous.clear(); hover = null; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            previous.clear()
            invalidate()
            return true
        }
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) !in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) continue
            val id = event.getPointerId(i)
            if ((event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
                i == event.actionIndex) previous.remove(id)
            for (h in 0 until event.historySize) {
                add(id, Dot(event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalPressure(i, h)))
            }
            add(id, Dot(event.getX(i), event.getY(i), event.getPressure(i)))
            if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) &&
                i == event.actionIndex) previous.remove(id)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        postInvalidateOnAnimation()
        return true
    }

    private fun add(id: Int, dot: Dot) {
        val from = previous.put(id, dot) ?: dot
        if (segments.size >= 12000) segments.removeFirst()
        segments.addLast(Segment(from, dot))
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> hover = Dot(event.x, event.y, 0f)
            MotionEvent.ACTION_HOVER_EXIT -> hover = null
            else -> return super.onHoverEvent(event)
        }
        postInvalidateOnAnimation()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE || super.onGenericMotionEvent(event)

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(32, 74, 96)
        segments.forEach { (a, b) ->
            val pressure = if (b.pressure.isFinite() && b.pressure > 0) b.pressure.coerceIn(0.1f, 1.5f) else 0.5f
            paint.strokeWidth = resources.displayMetrics.density * (1f + pressure * 3f)
            if (hypot(b.x - a.x, b.y - a.y) < 0.1f) canvas.drawPoint(b.x, b.y, paint)
            else canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        hover?.let {
            paint.color = Color.rgb(204, 96, 52)
            paint.strokeWidth = 2f
            canvas.drawLine(it.x - 12, it.y, it.x + 12, it.y, paint)
            canvas.drawLine(it.x, it.y - 12, it.x, it.y + 12, paint)
        }
    }
}
