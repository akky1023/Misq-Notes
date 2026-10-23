package jp.handwriting.note.storage

import java.io.*

/** Portable defaults requested with the notebook; device shortcuts and theme stay local. */
internal data class NotebookPresets(val paper: NotePaper, val palettes: SavedPalettes,
    val penWidths: List<Int>, val highlighterWidths: List<Int>, val eraserWidths: List<Int>) {
    fun applyTo(tools: ToolPreferences) = tools.copy(widths = penWidths, highlighterWidths = highlighterWidths, eraserWidths = eraserWidths)
    companion object {
        fun write(file: File, paper: NotePaper, palettes: SavedPalettes, tools: ToolPreferences) {
            val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
                out.writeInt(2)
                out.writeInt(paper.color); out.writeInt(paper.pattern.ordinal); out.writeInt(paper.lineColor); out.writeInt(paper.spacing); out.writeFloat(paper.lineWidth); paper.writeMargin(out)
                for (colors in listOf(palettes.pen, palettes.highlighter)) {
                    out.writeInt(colors.size); colors.forEach(out::writeInt)
                }
                listOf(tools.widths, tools.highlighterWidths, tools.eraserWidths).forEach { it.forEach(out::writeInt) }
            } }.toByteArray()
            NoteFiles.atomicWrite(file, bytes)
        }
        fun read(file: File): NotebookPresets = DataInputStream(ByteArrayInputStream(NoteFiles.readEnvelope(file))).use { input ->
            val version = input.readInt(); require(version in 1..2)
            val paper = NotePaper(input.readInt(), PaperPattern.entries[input.readInt()], input.readInt(), input.readInt(), input.readFloat()).let { if (version >= 2) it.readMargin(input) else it }
            fun colors(): List<Int> {
                val count = input.readInt(); require(count in 0..100000 && count.toLong() * 4 <= input.available())
                return List(count) { input.readInt().also { require(it in 0..0xFFFFFF) } }.also { require(it.distinct().size == it.size) }
            }
            val palettes = SavedPalettes(colors(), colors())
            fun widths() = List(3) { input.readInt().also { require(it in 1..50) } }
            NotebookPresets(paper, palettes, widths(), widths(), widths()).also { require(input.available() == 0) }
        }
    }
}
