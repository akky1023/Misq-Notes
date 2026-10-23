package jp.handwriting.note.editor

import kotlin.math.roundToInt
import kotlin.math.pow

internal object PenStyle {
    fun rgb(color: Int): Int = color and 0x00FFFFFF
    fun withRgb(color: Int, rgbValue: Int): Int = (color and 0xFF000000.toInt()) or rgb(rgbValue)
    fun fromRgb(red: Int, green: Int, blue: Int): Int {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        return (red shl 16) or (green shl 8) or blue
    }
    fun transparency(color: Int): Int = ((255 - (color ushr 24)) * 100f / 255).roundToInt()
    fun withTransparency(color: Int, percent: Int): Int {
        require(percent in 0..100)
        val alpha = ((100 - percent) * 255f / 100).roundToInt()
        return (alpha shl 24) or rgb(color)
    }
    fun width(value: Float): Float {
        require(value.isFinite())
        return value.roundToInt().coerceIn(1, 50).toFloat()
    }
    private val minimumBrushWidth = previousBrushWidth(10)
    private val widthSpan = 12.5f - minimumBrushWidth
    private val maximumBrushWidth = minimumBrushWidth + widthSpan * (49f / 39f).pow(1.4f)
    // Previous 10 -> new 1; previous 50 -> new 40. Keep finer steps at the thin end.
    fun brushWidth(display: Float): Float = minimumBrushWidth + widthSpan * ((width(display) - 1f) / 39f).pow(1.4f)
    fun displayWidth(brush: Float): Float {
        require(brush.isFinite())
        return width(1f + 39f * ((brush.coerceIn(minimumBrushWidth, maximumBrushWidth) - minimumBrushWidth) / widthSpan).pow(1f / 1.4f))
    }
    fun legacyWidth(display: Int): Int = displayWidth(display / 2f).toInt()
    private fun previousBrushWidth(display: Int): Float = 0.5f + 12f * ((display - 1f) / 49f).pow(1.4f)
    fun migratePreviousWidth(display: Int): Int {
        require(display in 1..50)
        return displayWidth(previousBrushWidth(display)).toInt()
    }
}
