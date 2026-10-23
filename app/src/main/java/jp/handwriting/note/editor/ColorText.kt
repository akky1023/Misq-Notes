package jp.handwriting.note.editor

import java.util.Locale

internal object ColorText {
    fun hex(color: Int, alpha: Boolean): String = if (alpha) String.format(Locale.ROOT, "#%06X%02X", color and 0xFFFFFF, color ushr 24)
        else String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    /** Web order: RGB / RGBA / RRGGBB / RRGGBBAA, alpha last. */
    fun parseHex(text: String, alpha: Boolean): Int? {
        val raw = text.removePrefix("#")
        if (raw.any { it.digitToIntOrNull(16) == null } || raw.length !in if (alpha) listOf(3, 4, 6, 8) else listOf(3, 6)) return null
        val full = if (raw.length <= 4) raw.flatMap { listOf(it, it) }.joinToString("") else raw
        val rgb = full.take(6).toIntOrNull(16) ?: return null
        val a = if (full.length == 8) full.takeLast(2).toInt(16) else 255
        return (a shl 24) or rgb
    }
    fun channels(color: Int) = listOf((color ushr 16 and 255).toString(), (color ushr 8 and 255).toString(), (color and 255).toString(), (color ushr 24).toString())
    fun fromChannels(values: List<String>, alpha: Boolean): Int? {
        if (values.take(if (alpha) 4 else 3).any { it.toIntOrNull() !in 0..255 }) return null
        return ((if (alpha) values[3].toInt() else 255) shl 24) or (values[0].toInt() shl 16) or (values[1].toInt() shl 8) or values[2].toInt()
    }
}
