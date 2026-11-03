package jp.handwriting.note.storage

/** Ownership follows the first input; visibility follows full bounds, including crossed bands. */
data class StrokePlacement(val startY: Float, val top: Float, val bottom: Float) {
    init { require(startY.isFinite() && top.isFinite() && bottom.isFinite() && top <= bottom) }
    val band: Long get() = kotlin.math.floor(startY.toDouble() / 1000.0).toLong()
}
