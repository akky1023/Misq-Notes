package jp.handwriting.note.storage

import jp.handwriting.note.editor.PenButtonAction
import jp.handwriting.note.editor.PressureCurve
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotebookPresetsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun portablePaperPalettesAndWidthsRoundTripWithoutReplacingDevicePreferences() {
        val paper = NotePaper(0xFF223344.toInt(), PaperPattern.COLLEGE_RULED, 0x40778899, 36, 2.5f, 0x80123456.toInt(), 4f, 125f)
        val palettes = SavedPalettes(listOf(0x123456, 0x987654), emptyList())
        val source = ToolPreferences(widths = listOf(1, 25, 50), highlighterWidths = listOf(10, 30, 40), eraserWidths = listOf(5, 15, 35))
        val file = temp.newFile(); NotebookPresets.write(file, paper, palettes, source)
        val restored = NotebookPresets.read(file)
        assertEquals(paper, restored.paper); assertEquals(palettes, restored.palettes)
        val local = ToolPreferences(highlighterColor = 0x66123456, highlighterSize = 17, eraserSize = 29,
            pressureCurve = PressureCurve(10, 120), buttonActions = List(3) { PenButtonAction.TEMPORARY_LASSO })
        assertEquals(local.copy(widths = source.widths, highlighterWidths = source.highlighterWidths, eraserWidths = source.eraserWidths), restored.applyTo(local))
    }
    @Test fun versionOnePresetsKeepTheOriginalPaperAndUseDefaultMargin() {
        val file = temp.newFile()
        val paper = NotePaper(pattern = PaperPattern.RULED, lineWidth = 3f)
        NotebookPresets.write(file, paper, SavedPalettes(), ToolPreferences())
        val bytes = NoteFiles.readEnvelope(file)
        val old = bytes.take(24).toByteArray() + bytes.drop(36).toByteArray()
        java.nio.ByteBuffer.wrap(old).putInt(1)
        NoteFiles.atomicWrite(file, old)
        assertEquals(paper, NotebookPresets.read(file).paper)
    }

}
