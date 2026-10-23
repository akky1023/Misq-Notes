package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class ColorTextTest {
    @Test fun webHexUsesAlphaLastAndAcceptsShortForms() {
        assertEquals(0xFF112233.toInt(), ColorText.parseHex("#123", false))
        assertEquals(0x44112233, ColorText.parseHex("1234", true))
        assertEquals(0x8012ABEF.toInt(), ColorText.parseHex("#12abef80", true))
        assertEquals(0, ColorText.parseHex("00000000", true))
        assertNull(ColorText.parseHex("#1234", false)); assertNull(ColorText.parseHex("GG0000", true))
        assertNull(ColorText.parseHex("", false)); assertNull(ColorText.parseHex("#12345", true))
    }
    @Test fun decimalAndHexRoundTripAllAlphaEndpoints() {
        for (a in listOf(0, 1, 127, 128, 254, 255)) {
            val color = (a shl 24) or 0x1234EF
            assertEquals(color, ColorText.parseHex(ColorText.hex(color, true), true))
            assertEquals(color, ColorText.fromChannels(ColorText.channels(color), true))
            assertEquals("#1234EF", ColorText.hex(color, false))
        }
        assertNull(ColorText.fromChannels(listOf("256", "2", "3", "255"), true))
        assertNull(ColorText.fromChannels(listOf("", "2", "3", "255"), false))
    }
}
