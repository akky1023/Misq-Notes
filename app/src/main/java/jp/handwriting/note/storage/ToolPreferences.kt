package jp.handwriting.note.storage

import java.io.*
import jp.handwriting.note.editor.EditorTool
import jp.handwriting.note.editor.PenStyle
import jp.handwriting.note.editor.PressureCurve
import jp.handwriting.note.editor.PenButtonAction
import jp.handwriting.note.editor.PenButtonState
import jp.handwriting.note.editor.LassoMode

internal data class ToolPreferences(
    val widths: List<Int> = listOf(3, 6, 12).map(PenStyle::legacyWidth),
    val highlighterColor: Int = 0x66FFEB3B,
    val highlighterSize: Int = 40,
    val highlighterWidths: List<Int> = listOf(25, 38, 50).map(PenStyle::migratePreviousWidth),
    val eraserWidths: List<Int> = listOf(10, 20, 35),
    val eraserSize: Int = 20,
    val pressureCurve: PressureCurve = PressureCurve.editableDefault(),
    val toolOrder: List<EditorTool> = EditorTool.entries.toList(),
    val buttonActions: List<PenButtonAction> = PenButtonState.defaults,
    val lassoMode: LassoMode = LassoMode.FREE,
    val pinchResizeMedia: Boolean = true,
    val lassoIncludeMedia: Boolean = false,
) {
    init {
        require(listOf(widths, highlighterWidths, eraserWidths).all { it.size == 3 && it.all { value -> value in 1..50 } })
        require(highlighterSize in 1..50 && eraserSize in 1..50)
        require(toolOrder.size == EditorTool.entries.size && toolOrder.toSet() == EditorTool.entries.toSet())
        require(buttonActions.size == 3)
    }
    fun widthsFor(tool: EditorTool): List<Int> = when (tool) {
        EditorTool.HIGHLIGHTER -> highlighterWidths
        EditorTool.ERASER -> eraserWidths
        else -> widths
    }
}

internal class ToolPreferencesFile(private val file: File) {
    fun read(): ToolPreferences {
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) return ToolPreferences()
        return try { decode(NoteFiles.readEnvelope(file)) }
        catch (_: IOException) { decode(NoteFiles.readEnvelope(backup)) }
    }
    fun write(value: ToolPreferences) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(11); value.widths.forEach(out::writeInt)
            out.writeInt(value.highlighterColor); out.writeInt(value.highlighterSize)
            value.highlighterWidths.forEach(out::writeInt); value.eraserWidths.forEach(out::writeInt)
            out.writeInt(value.eraserSize)
            PressureCurveCodec.write(out, value.pressureCurve)
            value.toolOrder.forEach { out.writeInt(it.ordinal) }
            value.buttonActions.forEach { out.writeInt(it.ordinal) }
            out.writeInt(value.lassoMode.ordinal)
            out.writeBoolean(value.pinchResizeMedia)
            out.writeBoolean(value.lassoIncludeMedia)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
    }
    private fun decode(bytes: ByteArray): ToolPreferences = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        try {
            val version = input.readInt(); require(version in 1..11)
            val widths = List(3) { input.readInt() }; val color = input.readInt(); val size = input.readInt()
            val result = if (version == 1) {
                require(widths.all { it in 1..100 } && size in 1..100)
                val migrated = widths.map(PenStyle::legacyWidth)
                ToolPreferences(migrated, color, PenStyle.legacyWidth(size), highlighterWidths = migrated.toList())
            } else {
                val highlighterWidths = List(3) { input.readInt() }; val eraserWidths = List(3) { input.readInt() }
                val eraserSize = input.readInt()
                if (version == 2) ToolPreferences(widths.map(PenStyle::migratePreviousWidth), color, PenStyle.migratePreviousWidth(size),
                    highlighterWidths.map(PenStyle::migratePreviousWidth), eraserWidths, eraserSize,
                    PressureCurve(input.readInt(), input.readInt()).asHandleCurve())
                else ToolPreferences(widths, color, size, highlighterWidths, eraserWidths, eraserSize,
                    PressureCurveCodec.read(input).let { if (version < 7) it.asHandleCurve() else it })
            }
            val ordered = if (version >= 8) result.copy(toolOrder = List(4) {
                val ordinal = input.readInt(); require(ordinal in EditorTool.entries.indices); EditorTool.entries[ordinal]
            }) else result
            val assigned = if (version >= 9) ordered.copy(buttonActions = List(3) {
                val ordinal = input.readInt(); require(ordinal in PenButtonAction.entries.indices); PenButtonAction.entries[ordinal]
            }) else ordered
            val shaped = if (version >= 10) assigned.copy(lassoMode = input.readInt().let {
                require(it in LassoMode.entries.indices); LassoMode.entries[it]
            }) else assigned
            val media = if (version >= 11) shaped.copy(pinchResizeMedia = input.readBoolean(), lassoIncludeMedia = input.readBoolean()) else shaped
            require(input.available() == 0)
            media
        } catch (e: IllegalArgumentException) { throw IOException("Invalid tool preferences", e) }
    }
}
