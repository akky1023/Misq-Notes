package jp.handwriting.note

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class AppLanguageTest {
    @Test fun languageTagsRecognizeRegionsAndFallbackToDevice() {
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("EN-GB"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja"))
        for (tag in listOf(null, "", "fr-FR")) assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(tag))
    }

    @Test fun everyTranslationHasTheSameFormattingArgumentsAndCanBeFormatted() {
        val resourceDirectory = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        fun strings(directory: String): Map<String, Element> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(resourceDirectory, "$directory/strings.xml")).getElementsByTagName("string")
            val entries = (0 until nodes.length).map { nodes.item(it) as Element }
            assertEquals("Duplicate translation keys in $directory", entries.size, entries.map { it.getAttribute("name") }.toSet().size)
            return entries.associateBy { it.getAttribute("name") }
        }
        val japanese = strings("values")
        val english = strings("values-en")
        assertEquals("Missing translations", japanese.keys, english.keys)
        val argument = Regex("""%(\d+)\${'$'}s""")
        for ((key, source) in japanese) {
            val translated = english.getValue(key)
            assertEquals(key, source.getAttribute("formatted"), translated.getAttribute("formatted"))
            if (source.getAttribute("formatted") == "false") continue
            fun arguments(text: String) = argument.findAll(text).map { it.groupValues[1].toInt() }.toSet()
            val positions = arguments(source.textContent)
            assertEquals("Argument mismatch: $key", positions, arguments(translated.textContent))
            val values = Array<Any>(positions.maxOrNull() ?: 0) { "7" }
            for (entry in listOf(source, translated)) {
                // A translation must not throw or leave an unsupported format directive at runtime.
                assertTrue(key, String.format(Locale.ENGLISH, entry.textContent, *values).isNotEmpty())
            }
        }
    }
}
