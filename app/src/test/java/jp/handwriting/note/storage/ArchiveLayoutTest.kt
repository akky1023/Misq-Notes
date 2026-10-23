package jp.handwriting.note.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveLayoutTest {
    @get:Rule val temp = TemporaryFolder()
    private fun blob(file: File): Pair<String, ByteArray> {
        val bytes = file.readBytes()
        return "blob-${NotebookArchive.digest(bytes)}" to bytes
    }
    @Test fun separatesNoteManifestsStrokesAndPortableSettings() {
        val root = temp.newFolder()
        val note = NoteFiles(File(root, "notes")).create("無題")
        val manifest = blob(File(root, "notes/${note.info.id}/manifest.hwn"))
        assertEquals("notes/manifests/${manifest.first}", ArchiveLayout.path(manifest.first, manifest.second))
        val strokeFile = File(root, "stroke"); NoteFiles.atomicWrite(strokeFile, java.nio.ByteBuffer.allocate(8).putInt(0x484E5331).putInt(1).array())
        val stroke = blob(strokeFile)
        assertEquals("notes/strokes/${stroke.first}", ArchiveLayout.path(stroke.first, stroke.second))
        val folderFile = File(root, "folders"); NotebookFolders.write(folderFile, LibraryPreferences())
        val folders = blob(folderFile)
        assertEquals("settings/folders/${folders.first}", ArchiveLayout.path(folders.first, folders.second))
        val presetsFile = File(root, "presets"); NotebookPresets.write(presetsFile, NotePaper(), SavedPalettes(), ToolPreferences())
        val presets = blob(presetsFile)
        assertEquals("settings/presets/${presets.first}", ArchiveLayout.path(presets.first, presets.second))
        for ((name, bytes) in listOf(manifest, stroke, folders, presets)) {
            assertTrue(ArchiveLayout.complete(name, bytes))
            assertFalse(ArchiveLayout.complete(name, bytes.copyOf(bytes.size - 1)))
        }
    }
    @Test fun acceptsACommittedRevisionForHistoryAndRejectsIncompleteCopies() {
        val memory = mutableMapOf<String, ByteArray>()
        val directory = object : ArchiveDirectory {
            override fun names() = memory.keys.toSet()
            override fun read(name: String) = memory.getValue(name)
            override fun write(name: String, bytes: ByteArray) { memory[name] = bytes }
            override fun delete(name: String) { memory.remove(name) }
        }
        val file = temp.newFile(); NotebookFolders.write(file, LibraryPreferences())
        NotebookArchive(directory).commit(mapOf("folders.hwp" to file))
        val (name, bytes) = memory.entries.single { it.key.startsWith("revision-") }
        assertEquals("history/$name", ArchiveLayout.path(name, bytes))
        assertTrue(ArchiveLayout.complete(name, bytes))
        assertFalse(ArchiveLayout.complete(name, bytes.copyOf(bytes.size - 1)))
        assertFalse(ArchiveLayout.isRecord("personal-file"))
        assertThrows(IllegalArgumentException::class.java) { ArchiveLayout.path("../escape", bytes) }
    }
}
