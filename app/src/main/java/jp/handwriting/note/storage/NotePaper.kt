package jp.handwriting.note.storage

enum class PaperPattern { PLAIN, RULED, GRID, DOTS, COLLEGE_RULED }

data class NotePaper(val color: Int = 0xFFFAF9F4.toInt(), val pattern: PaperPattern = PaperPattern.PLAIN,
    val lineColor: Int = 0xFFCBD2D6.toInt(), val spacing: Int = 32, val lineWidth: Float = 1f,
    val marginColor: Int = 0xFFE49A9A.toInt(), val marginWidth: Float = 1f, val marginOffset: Float = 100f) {
    init {
        require(marginWidth.isFinite() && marginWidth in 0.25f..8f && marginOffset.isFinite() && marginOffset in 0f..1000f)
        require(color ushr 24 == 255 && spacing in 12..100 && lineWidth.isFinite() && lineWidth in 0.25f..8f)
    }
}

internal fun NotePaper.writeMargin(out: java.io.DataOutputStream) {
    out.writeInt(marginColor); out.writeFloat(marginWidth); out.writeFloat(marginOffset)
}
internal fun NotePaper.readMargin(input: java.io.DataInputStream): NotePaper =
    copy(marginColor = input.readInt(), marginWidth = input.readFloat(), marginOffset = input.readFloat())
