package jp.handwriting.note.storage

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedColorsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun savedRgbColorsKeepOrderAcrossRestartAndIgnoreAlphaDuplicates() {
        val file = File(temp.newFolder(), "palette.hwp")
        assertEquals(SavedPalettes(), SavedColors(file).read())
        SavedColors(file).write(SavedPalettes(
            pen = listOf(0x80123456.toInt(), 0xFFABCDEF.toInt(), 0x00123456),
            highlighter = listOf(0xFFFFEB3B.toInt(), 0x80FF80AB.toInt()),
        ))
        assertEquals(SavedPalettes(listOf(0x123456, 0xABCDEF), listOf(0xFFEB3B, 0xFF80AB)), SavedColors(file).read())
    }
    @Test fun deletingAllColorsDoesNotRestoreDefaultsOnRestart() {
        val file = File(temp.newFolder(), "palette.hwp")
        SavedColors(file).write(SavedPalettes(emptyList(), emptyList()))
        assertEquals(SavedPalettes(emptyList(), emptyList()), SavedColors(file).read())
    }
    @Test fun brokenOrInterruptedLatestPaletteRecoversPreviousColors() {
        val file = File(temp.newFolder(), "palette.hwp")
        val colors = SavedColors(file)
        val before = SavedPalettes(listOf(0x123456), listOf(0xFFEB3B))
        colors.write(before); colors.write(SavedPalettes(listOf(0xABCDEF), emptyList()))
        file.writeBytes(byteArrayOf(0))
        assertEquals(before, SavedColors(file).read())
        assertTrue(file.delete())
        assertEquals(before, SavedColors(file).read())
    }
    @Test fun legacySharedPaletteMigratesWithoutDroppingColorsOrResurrectingDeletedDefaults() {
        for (values in listOf(listOf(0x123456, 0xABCDEF), emptyList())) {
            val file = File(temp.newFolder(), "palette.hwp")
            NoteFiles.atomicWrite(file, legacy(values))
            val migrated = SavedColors(file).read()
            assertEquals(SavedPalettes(values, values), migrated)
            SavedColors(file).write(migrated.copy(highlighter = listOf(0xFFEB3B)))
            assertEquals(SavedPalettes(values, listOf(0xFFEB3B)), SavedColors(file).read())
        }
    }
    @Test fun editingAndDeletingOnePaletteCannotChangeTheOther() {
        val file = File(temp.newFolder(), "palette.hwp")
        val original = SavedPalettes(listOf(0x123456, 0x205EB8), listOf(0xFFEB3B))
        SavedColors(file).write(original)
        SavedColors(file).write(SavedColors(file).read().copy(pen = emptyList()))
        assertEquals(SavedPalettes(emptyList(), original.highlighter), SavedColors(file).read())
        SavedColors(file).write(SavedColors(file).read().copy(highlighter = listOf(0xABCDEF, 0xFF80AB)))
        assertEquals(SavedPalettes(emptyList(), listOf(0xABCDEF, 0xFF80AB)), SavedColors(file).read())
    }
    @Test fun invalidNewPayloadRecoversLegacyBackup() {
        val file = File(temp.newFolder(), "palette.hwp")
        NoteFiles.atomicWrite(file, legacy(listOf(0x123456)))
        val invalid = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.writeInt(2); it.writeInt(0); it.writeInt(Int.MAX_VALUE)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, invalid)
        assertEquals(SavedPalettes(listOf(0x123456), listOf(0x123456)), SavedColors(file).read())
    }
    private fun legacy(values: List<Int>): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeInt(values.size); values.forEach(out::writeInt)
        }
    }.toByteArray()
}
