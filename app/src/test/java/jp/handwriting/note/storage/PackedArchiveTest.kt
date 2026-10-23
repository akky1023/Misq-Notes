package jp.handwriting.note.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackedArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private class Files : PackedFiles {
        val data = linkedMapOf<String, ByteArray>()
        var fail: (String) -> Boolean = { false }
        override fun names() = data.keys.toSet()
        override fun read(name: String) = data.getValue(name)
        override fun write(name: String, bytes: ByteArray) { if (fail(name)) throw IOException("write failed"); data[name] = bytes.copyOf() }
        override fun delete(name: String) { data.remove(name) }
    }
    private fun fixture(title: String = "original"): Map<String, File> {
        val root = temp.newFolder()
        val folders = File(root, "folders.hwp")
        NotebookFolders.write(folders, LibraryPreferences().added(title, null))
        return mapOf("folders.hwp" to folders)
    }
    private fun reader(files: Files) = NotebookArchive(PackedArchiveDirectory(files))
    private fun restore(files: Files): String {
        val archive = reader(files); val target = temp.newFolder()
        archive.restore(archive.latest()!!, target)
        return NotebookFolders.read(File(target, "folders.hwp"), LibraryPreferences()).folders.single().name
    }
    @Test fun roundTripDeltaAndRestartKeepThePreviousRevision() {
        val physical = Files(); val original = fixture(); val archive = reader(physical)
        archive.latest(); archive.commit(original)
        val first = archive.latest()!!
        NotebookFolders.write(original.getValue("folders.hwp"), LibraryPreferences().added("updated", null))
        archive.commit(original)
        assertEquals("updated", restore(physical))
        assertEquals(2, PackedArchiveDirectory(physical).names().count { it.startsWith("revision-") })
        assertTrue(first.name in PackedArchiveDirectory(physical).names())
    }
    @Test fun packOrIndexWriteFailureCannotPublishAnIncompleteRevision() {
        for (prefix in listOf("pack-", "index-")) {
            val physical = Files(); val files = fixture(); val archive = reader(physical)
            archive.commit(files)
            NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("new", null))
            physical.fail = { it.startsWith(prefix) }
            assertThrows(IOException::class.java) { archive.commit(files) }
            physical.fail = { false }
            assertEquals("original", restore(physical))
            archive.commit(files)
            assertEquals("new", restore(physical))
        }
    }
    @Test fun corruptNewestPackFallsBackToThePreviousRevision() {
        val physical = Files(); val files = fixture(); val archive = reader(physical)
        archive.commit(files); val previous = physical.data.keys.toSet()
        NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("new", null))
        archive.commit(files)
        val newestPack = (physical.data.keys - previous).single { it.startsWith("pack-") }
        physical.data[newestPack] = byteArrayOf(0)
        assertEquals("original", restore(physical))
    }
    @Test fun corruptIndexCanUseThePreviousCheckedIndex() {
        val physical = Files(); val files = fixture(); val archive = reader(physical)
        val original = files.getValue("folders.hwp").readBytes()
        archive.commit(files)
        NotebookFolders.write(files.getValue("folders.hwp"), LibraryPreferences().added("new", null)); archive.commit(files)
        physical.data[physical.data.keys.filter { it.startsWith("index-") }.max()] = byteArrayOf(0)
        assertEquals("original", restore(physical))
        val recovering = reader(physical)
        recovering.latest(); assertTrue(recovering.recovered)
        files.getValue("folders.hwp").writeBytes(original)
        recovering.commit(files)
        val checked = reader(physical); checked.latest(); assertFalse(checked.recovered)
        recovering.commit(fixture("repaired"))
        assertEquals("repaired", restore(physical))
    }
    @Test fun anotherWriterCannotBeSilentlyOverwritten() {
        val physical = Files(); reader(physical).commit(fixture())
        val a = reader(physical); val b = reader(physical); a.latest(); b.latest()
        a.commit(fixture("A"))
        assertThrows(ArchiveConflictException::class.java) { b.commit(fixture("B")) }
        assertEquals("A", restore(physical))
    }
    @Test fun matchingLocalBlobsAreNotRereadAndExtraLocalNotesDoNotCountAsAMatch() {
        val physical = Files(); val files = fixture(); reader(physical).commit(files)
        val archive = reader(physical)
        archive.latestCaptured(files.mapValues { ArchiveBlob(it.value.readBytes()) }); assertTrue(archive.localMatches)
        val extra = File(files.values.first().parentFile, "extra").apply { writeText("extra") }
        archive.latestCaptured(files.mapValues { ArchiveBlob(it.value.readBytes()) } + ("notes/00000000-0000-0000-0000-000000000000/manifest.hwn" to ArchiveBlob(extra.readBytes())))
        assertFalse(archive.localMatches)
    }
    @Test fun unrelatedPhysicalFilesAndUnpublishedPacksAreRetained() {
        val physical = Files(); val archive = reader(physical); archive.commit(fixture())
        physical.data["personal-file"] = byteArrayOf(1)
        physical.data["pack-${"0".repeat(64)}"] = byteArrayOf(2)
        archive.commit(fixture("new"))
        assertArrayEquals(byteArrayOf(1), physical.data["personal-file"])
        assertArrayEquals(byteArrayOf(2), physical.data["pack-${"0".repeat(64)}"])
    }
}
