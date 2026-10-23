package jp.handwriting.note.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import jp.handwriting.note.storage.NotePaper
import jp.handwriting.note.storage.PaperPattern
import kotlin.math.floor

internal object PaperRenderer {
    private val paints = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) }
    fun draw(canvas: Canvas, paper: NotePaper, visible: RectF, scale: Float) {
        canvas.drawColor(paper.color)
        if (paper.pattern == PaperPattern.PLAIN) return
        val paint = paints.get()!!.apply { color = paper.lineColor; strokeWidth = paper.lineWidth / scale }
        val step = paper.spacing.toFloat()
        val left = maxOf(0f, visible.left); val right = minOf(Viewport.WIDTH, visible.right)
        val top = maxOf(0f, visible.top)
        val firstY = floor(top.toDouble() / step) * step
        val rows = ((visible.bottom.toDouble() - firstY) / step).toInt().coerceIn(0, 4096)
        for (row in 0..rows) {
            val y = (firstY + row * step).toFloat()
            if (paper.pattern != PaperPattern.DOTS) canvas.drawLine(left, y, right, y, paint)
            else {
                var x = floor(left / step) * step
                while (x <= right) { canvas.drawCircle(x, y, 1.3f * paper.lineWidth / scale, paint); x += step }
            }
        }
        if (paper.pattern == PaperPattern.COLLEGE_RULED) {
            paint.color = paper.marginColor
            paint.strokeWidth = paper.marginWidth / scale
            canvas.drawLine(paper.marginOffset, top, paper.marginOffset, visible.bottom, paint)
        }
        if (paper.pattern == PaperPattern.GRID) {
            var x = floor(left / step) * step
            while (x <= right) { canvas.drawLine(x, top, x, visible.bottom, paint); x += step }
        }
    }
}
