package jp.handwriting.note.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

internal data class SavedPalettes(
    val pen: List<Int> = SavedColors.defaults,
    val highlighter: List<Int> = SavedColors.highlighterDefaults,
)

/** Separate RGB palettes, with lossless migration from the original shared palette. */
internal class SavedColors(private val file: File) {
    fun read(): SavedPalettes {
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) return SavedPalettes()
        return try { decode(NoteFiles.readEnvelope(file)) }
        catch (e: IOException) { decode(NoteFiles.readEnvelope(backup)) }
    }
    fun write(colors: SavedPalettes) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(2)
                for (palette in listOf(colors.pen, colors.highlighter)) {
                    val values = palette.map { it and 0x00FFFFFF }.distinct()
                    out.writeInt(values.size); values.forEach(out::writeInt)
                }
            }
        }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
    }
    private fun decode(bytes: ByteArray): SavedPalettes = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        if (version !in 1..2) throw IOException("Invalid palette version")
        fun palette(): List<Int> {
            val count = input.readInt()
            if (count < 0 || count.toLong() * 4 > input.available().toLong()) throw IOException("Invalid palette length")
            return List(count) { input.readInt() and 0x00FFFFFF }.distinct()
        }
        val pen = palette()
        val highlighter = if (version == 1) pen.toList() else palette()
        if (input.available() != 0) throw IOException("Invalid palette length")
        SavedPalettes(pen, highlighter)
    }
    companion object {
        val defaults = listOf(0x202B33, 0x205EB8, 0xB93838)
        val highlighterDefaults = listOf(0xFFEB3B, 0xFF80AB, 0x8BE39A)
    }
}
