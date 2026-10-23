package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryPreferencesTest {
    @Test fun removedCaptureShareChoiceIsIgnoredInVersionSix() {
        val file = File(temp.newFolder(), "library.hwp")
        val current = LibraryPreferences(theme = AppThemeMode.DARK, amoled = true)
        LibraryPreferencesFile(file).write(current)
        assertEquals(current, LibraryPreferencesFile(file).read())
        val currentBytes = NoteFiles.readEnvelope(file).dropLast(16).toByteArray()
        for (oldChoice in listOf(0, 1)) {
            val versionSix = (currentBytes + byteArrayOf(oldChoice.toByte())).also { java.nio.ByteBuffer.wrap(it).putInt(6) }
            NoteFiles.atomicWrite(file, versionSix)
            assertEquals(current, LibraryPreferencesFile(file).read())
        }
    }
    @Test fun everyCacheModeSurvivesRestartAndVersionSevenUsesNearby() {
        val file = File(temp.newFolder(), "library.hwp")
        jp.handwriting.note.editor.StrokeCacheMode.entries.forEach { mode ->
            val value = LibraryPreferences(strokeCache = mode)
            LibraryPreferencesFile(file).write(value)
            assertEquals(value, LibraryPreferencesFile(file).read())
        }
        val old = NoteFiles.readEnvelope(file).dropLast(16).toByteArray()
        java.nio.ByteBuffer.wrap(old).putInt(7)
        NoteFiles.atomicWrite(file, old)
        assertEquals(jp.handwriting.note.editor.StrokeCacheMode.NEARBY, LibraryPreferencesFile(file).read().strokeCache)
    }
    @get:Rule val temp = TemporaryFolder()
    @Test fun everyThemeChoiceSurvivesRestart() {
        val file = File(temp.newFolder(), "library.hwp")
        AppThemeMode.entries.forEach { mode -> listOf(false, true).forEach { amoled ->
            val value = LibraryPreferences(theme = mode, amoled = amoled, grid = false, paper = NotePaper(pattern = PaperPattern.COLLEGE_RULED, lineColor = 0x40123456, marginColor = 0x80123456.toInt(), marginWidth = 6f, marginOffset = 176f))
            LibraryPreferencesFile(file).write(value)
            assertEquals(value, LibraryPreferencesFile(file).read())
        } }
    }
    @Test fun versionFourMigratesEveryThemeAndSeparatesTheOledFlag() {
        val file = File(temp.newFolder(), "library.hwp")
        for (themeId in 0..3) {
            val bytes = java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { out ->
                out.writeInt(4); out.writeBoolean(true)
                out.writeInt(0xFF112233.toInt()); out.writeInt(PaperPattern.PLAIN.ordinal); out.writeInt(0x40445566); out.writeInt(48)
                out.writeFloat(2.5f); out.writeBoolean(false)
                repeat(4) { out.writeInt(0) } // Folders, locations, sorts, manual orders.
                out.writeInt(themeId)
            } }.toByteArray()
            NoteFiles.atomicWrite(file, bytes)
            val value = LibraryPreferencesFile(file).read()
            assertEquals(AppThemeMode.entries[minOf(themeId, 2)], value.theme)
            assertEquals(themeId == 3, value.amoled)
            assertEquals(0xFF112233.toInt(), value.paper.color)
            LibraryPreferencesFile(file).write(value)
            assertEquals(value, LibraryPreferencesFile(file).read())
        }
    }
    @Test fun versionThreeKeepsFoldersAndManualSortAndDefaultsToSystemTheme() {
        val folder = UUID.randomUUID().toString(); val note = UUID.randomUUID().toString()
        val file = File(temp.newFolder(), "library.hwp")
        val bytes = java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { out ->
            out.writeInt(3); out.writeBoolean(false)
            out.writeInt(0xFF112233.toInt()); out.writeInt(PaperPattern.DOTS.ordinal); out.writeInt(0x40445566); out.writeInt(48)
            out.writeFloat(2.5f); out.writeBoolean(true)
            out.writeInt(1); out.writeUTF(folder); out.writeUTF("folder"); out.writeUTF(""); out.writeInt(0xFF123456.toInt())
            out.writeInt(1); out.writeUTF(note); out.writeUTF(folder)
            out.writeInt(1); out.writeUTF(folder); out.writeInt(NoteSort.MANUAL.ordinal)
            out.writeInt(1); out.writeUTF(folder); out.writeInt(1); out.writeUTF(note)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val value = LibraryPreferencesFile(file).read()
        assertEquals(AppThemeMode.SYSTEM, value.theme)
        assertEquals(folder, value.locations[note]); assertEquals(listOf(note), value.orders[folder])
        assertEquals(NoteSort.MANUAL, value.sortFor(folder)); assertEquals(0xFF123456.toInt(), value.folders.single().color)
        assertEquals(0x40445566, value.paper.lineColor)
        LibraryPreferencesFile(file).write(value.copy(theme = AppThemeMode.DARK, amoled = true))
        assertEquals(value.copy(theme = AppThemeMode.DARK, amoled = true), LibraryPreferencesFile(file).read())
    }
    @Test fun changingDirectionKeepsTheSelectedSortField() {
        for (sort in NoteSort.entries) {
            assertEquals(sort.field, sort.direction(true).field)
            assertEquals(sort.field, sort.direction(false).field)
            if (sort != NoteSort.MANUAL) { assertTrue(sort.direction(true).descending); assertFalse(sort.direction(false).descending) }
        }
        assertEquals(NoteSort.MANUAL, NoteSort.MANUAL.direction(true))
    }
    @Test fun nestedFoldersMoveAndDisplayAndPaperSurviveRestart() {
        val file = File(temp.newFolder(), "library.hwp")
        val root = LibraryPreferences().added("数学", null)
        val parent = root.folders.single().id
        val nested = root.added("解析", parent)
        val child = nested.folders.last().id; val note = UUID.randomUUID().toString()
        val value = nested.moved(note, child).copy(grid = false, paper = NotePaper(0xFF102030.toInt(), PaperPattern.GRID, 0xFF607080.toInt(), 40, 3.25f), suppressPenGestures = true)
        LibraryPreferencesFile(file).write(value)
        assertEquals(value, LibraryPreferencesFile(file).read())
        assertEquals("数学 / 解析", value.path(child))
        assertEquals(parent, value.moved(note, parent).locations[note])
        assertFalse(value.moved(note, null).locations.containsKey(note))
        assertThrows(IllegalArgumentException::class.java) { value.removedFolder(child) }
        assertEquals(1, value.moved(note, null).removedFolder(child).folders.size)
        assertThrows(IllegalArgumentException::class.java) { value.removedFolder(parent) }
    }
    @Test fun versionOneLibraryPreservesThePaperAndDefaultsToNormalPenGestures() {
        val file = File(temp.newFolder(), "library.hwp")
        val bytes = java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeBoolean(false)
            out.writeInt(0xFF112233.toInt()); out.writeInt(PaperPattern.DOTS.ordinal); out.writeInt(0xFF445566.toInt()); out.writeInt(48)
            out.writeInt(0); out.writeInt(0)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val value = LibraryPreferencesFile(file).read()
        assertFalse(value.grid); assertFalse(value.suppressPenGestures)
        assertEquals(NotePaper(0xFF112233.toInt(), PaperPattern.DOTS, 0xFF445566.toInt(), 48, 1f), value.paper)
        LibraryPreferencesFile(file).write(value); assertEquals(value, LibraryPreferencesFile(file).read())
    }
    @Test fun corruptOrMissingSettingsRecoverPreviousCommittedLibrary() {
        val file = File(temp.newFolder(), "library.hwp")
        val before = LibraryPreferences().added("講義", null)
        LibraryPreferencesFile(file).write(before); LibraryPreferencesFile(file).write(before.copy(grid = false))
        file.writeBytes(byteArrayOf(0))
        assertEquals(before, LibraryPreferencesFile(file).read())
        assertTrue(file.delete()); assertEquals(before, LibraryPreferencesFile(file).read())
    }
    @Test fun danglingLocationsAndCyclicOrDuplicateFoldersAreRejected() {
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) { LibraryPreferences(listOf(NoteFolder(a, "a", b))) }
        assertThrows(IllegalArgumentException::class.java) { LibraryPreferences(listOf(NoteFolder(a, "a", b), NoteFolder(b, "b", a))) }
        assertThrows(IllegalArgumentException::class.java) { LibraryPreferences(listOf(NoteFolder(a, "a"), NoteFolder(a, "b"))) }
        assertThrows(IllegalArgumentException::class.java) { LibraryPreferences(locations = mapOf(a to b)) }
        assertThrows(IllegalArgumentException::class.java) { NotePaper(color = 0x00112233) }
        assertThrows(IllegalArgumentException::class.java) { NotePaper(spacing = 0) }
        assertThrows(IllegalArgumentException::class.java) { NotePaper(lineWidth = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { NotePaper(lineWidth = 0f) }
    }
    @Test fun allSevenSortsAndManualOrderAreIndependentForEveryFolder() {
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString(); val c = UUID.randomUUID().toString()
        val folder = UUID.randomUUID().toString()
        val notes = listOf(NoteSummary(a, "Bravo", 30, 0, createdAt = 10), NoteSummary(b, "alpha", 10, 0, createdAt = 30), NoteSummary(c, "Charlie", 20, 0, createdAt = 20))
        val value = LibraryPreferences(folders = listOf(NoteFolder(folder, "folder"))).reordered(null, listOf(c, a, b))
        val expected = mapOf(NoteSort.NAME_ASC to listOf(b, a, c), NoteSort.NAME_DESC to listOf(c, a, b),
            NoteSort.CREATED_ASC to listOf(a, c, b), NoteSort.CREATED_DESC to listOf(b, c, a),
            NoteSort.UPDATED_ASC to listOf(b, c, a), NoteSort.UPDATED_DESC to listOf(a, c, b), NoteSort.MANUAL to listOf(c, a, b))
        expected.forEach { (sort, ids) ->
            val setting = value.copy(sorts = mapOf("" to sort, folder to NoteSort.NAME_ASC))
            assertEquals(ids, setting.sorted(notes.reversed(), null).map { it.id })
            assertEquals(NoteSort.NAME_ASC, setting.sortFor(folder))
            assertTrue(setting.sorted(notes, folder).isEmpty())
        }
    }
    @Test fun folderColorsSortsManualOrdersAndRgbaSurviveRestart() {
        val folder = UUID.randomUUID().toString(); val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        val value = LibraryPreferences(folders = listOf(NoteFolder(folder, "blue", color = 0xFF123456.toInt())),
            paper = NotePaper(pattern = PaperPattern.COLLEGE_RULED, lineColor = 0x40123456, marginColor = 0x80123456.toInt(), marginWidth = 6f, marginOffset = 176f), locations = mapOf(b to folder),
            sorts = mapOf("" to NoteSort.NAME_DESC, folder to NoteSort.MANUAL), orders = mapOf("" to listOf(a), folder to listOf(b)))
        val file = File(temp.newFolder(), "library.hwp")
        LibraryPreferencesFile(file).write(value)
        assertEquals(value, LibraryPreferencesFile(file).read())
        val moved = value.moved(a, folder)
        assertEquals(emptyList<String>(), moved.orders[""])
        assertEquals(listOf(b, a), moved.orders[folder])
        assertEquals(NoteSort.NAME_DESC, moved.sortFor(null))
        assertEquals(folder, moved.locations[a])
        assertEquals(listOf(a), moved.moved(a, null).orders[""])
    }
    @Test fun versionTwoLibraryKeepsFoldersAndPaperWhileAddingDefaultSortAndColor() {
        val folder = UUID.randomUUID().toString(); val note = UUID.randomUUID().toString()
        val file = File(temp.newFolder(), "library.hwp")
        val bytes = java.io.ByteArrayOutputStream().also { buffer -> java.io.DataOutputStream(buffer).use { out ->
            out.writeInt(2); out.writeBoolean(false)
            out.writeInt(0xFF112233.toInt()); out.writeInt(PaperPattern.DOTS.ordinal); out.writeInt(0xFF445566.toInt()); out.writeInt(48)
            out.writeFloat(2.5f); out.writeBoolean(true)
            out.writeInt(1); out.writeUTF(folder); out.writeUTF("folder"); out.writeUTF("")
            out.writeInt(1); out.writeUTF(note); out.writeUTF(folder)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val value = LibraryPreferencesFile(file).read()
        assertEquals(NoteSort.UPDATED_DESC, value.sortFor(folder)); assertTrue(value.orders.isEmpty())
        assertEquals(0xFFE9B949.toInt(), value.folders.single().color)
        assertEquals(folder, value.locations[note]); assertTrue(value.suppressPenGestures)
        assertEquals(2.5f, value.paper.lineWidth)
        LibraryPreferencesFile(file).write(value); assertEquals(value, LibraryPreferencesFile(file).read())
    }
    @Test fun removingEmptyFolderAlsoRemovesItsOrderAndSort() {
        val folder = UUID.randomUUID().toString()
        val value = LibraryPreferences(folders = listOf(NoteFolder(folder, "folder")), sorts = mapOf(folder to NoteSort.NAME_ASC), orders = mapOf(folder to emptyList()))
        val removed = value.removedFolder(folder)
        assertTrue(removed.sorts.isEmpty()); assertTrue(removed.orders.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { value.reordered(null, listOf(folder, folder)) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(sorts = mapOf("invalid" to NoteSort.MANUAL)) }
    }

    @Test fun versionEightUsesDefaultMarginWithoutLosingCacheModeOrPaper() {
        val file = File(temp.newFolder(), "library.hwp")
        val value = LibraryPreferences(paper = NotePaper(pattern = PaperPattern.GRID),
            strokeCache = jp.handwriting.note.editor.StrokeCacheMode.entries.last())
        LibraryPreferencesFile(file).write(value)
        val bytes = NoteFiles.readEnvelope(file).dropLast(12).toByteArray()
        java.nio.ByteBuffer.wrap(bytes).putInt(8)
        NoteFiles.atomicWrite(file, bytes)
        assertEquals(value, LibraryPreferencesFile(file).read())
    }

}
