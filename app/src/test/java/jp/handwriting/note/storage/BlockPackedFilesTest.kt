package jp.handwriting.note.storage

import java.io.*
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlockPackedFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private class Memory : PackedFiles {
        val data = linkedMapOf<String, ByteArray>()
        val writes = mutableMapOf<String, Int>()
        val reads = mutableMapOf<String, Int>()
        var fail: (String) -> Boolean = { false }
        override fun names() = data.keys.toSet()
        override fun read(name: String): ByteArray {
            reads[name] = reads.getOrDefault(name, 0) + 1
            return data.getValue(name).copyOf()
        }
        override fun write(name: String, bytes: ByteArray) {
            if (fail(name)) throw IOException("interrupted")
            check(name !in data || data[name]!!.contentEquals(bytes))
            data[name] = bytes.copyOf(); writes[name] = bytes.size
        }
        override fun delete(name: String) { data.remove(name) }
        fun reset() { writes.clear(); reads.clear() }
    }
    private fun random(size: Int) = Random(123).nextBytes(size)
    private fun put(files: BlockPackedFiles, bytes: ByteArray): String = "blob-${NotebookArchive.digest(bytes)}".also {
        files.writeStream(it, bytes.inputStream())
    }
    @Test fun upgradePreservesRawRecoveryAndCanFallBackAcrossBothFormats() {
        val memory = Memory(); val root = temp.newFolder()
        val notes = NoteFiles(File(root, "notes")); val note = notes.create("legacy")
        val folders = File(root, "folders.hwp").also { NotebookFolders.write(it, LibraryPreferences()) }
        val file = notes.document(note.info.id).file
        val sources = mapOf("folders.hwp" to folders, "notes/${file.name}" to file)
        NotebookArchive(MisqArchiveDirectory(memory)).commit(sources)
        val oldName = "blob-${NotebookArchive.digest(file)}"; val original = memory.data.getValue(oldName).copyOf()
        val transport = BlockPackedFiles(memory); val upgraded = NotebookArchive(MisqArchiveDirectory(transport))
        assertNotNull(upgraded.latest())
        notes.rename(note.info.id, "blocks"); upgraded.commit(sources)
        assertArrayEquals(original, memory.data[oldName])
        val currentName = "blob-${NotebookArchive.digest(file)}"
        val descriptor = BlockPackedFiles.inspect(memory.data.getValue(currentName).inputStream())!!
        memory.data["block-${descriptor.parts.first().hash}"] = byteArrayOf(0)
        val reopened = NotebookArchive(MisqArchiveDirectory(BlockPackedFiles(memory)))
        val revision = reopened.latest()!!
        assertTrue(reopened.recovered)
        assertEquals(oldName.removePrefix("blob-"), revision.files["notes/${file.name}"])
        val restored = temp.newFolder(); reopened.restore(revision, restored)
        assertEquals("legacy", NoteFiles(File(restored, "notes")).read(note.info.id).manifest.info.title)
    }
    @Test fun emptyAndShortReadsRoundTripAndUnchangedViewsWriteNoBlocks() {
        val memory = Memory(); val files = BlockPackedFiles(memory); files.names()
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3))) {
            val name = put(files, bytes)
            assertArrayEquals(bytes, files.read(name))
            assertTrue(files.matchesNote(name, NotebookArchive.digest(bytes)))
        }
        memory.reset()
        val bytes = byteArrayOf(1, 2, 3); val name = "blob-${NotebookArchive.digest(bytes)}"
        assertTrue(files.publishNote(name, "small.misq", NotebookArchive.digest(bytes)))
        assertTrue(memory.writes.keys.none { it.startsWith("block-") })
    }

    @Test fun changingHeaderAndAppendingUploadsOnlyTwoBlocksAndViewsAreSmall() {
        val memory = Memory(); val files = BlockPackedFiles(memory); files.names()
        val original = random(BlockPackedFiles.BLOCK_SIZE * 4 + 123)
        val first = put(files, original)
        memory.reset()
        val updated = (original + ByteArray(321) { 7 }).also { it[100] = (it[100].toInt() xor 1).toByte() }
        val second = put(files, updated)
        assertEquals(2, memory.writes.keys.count { it.startsWith("block-") })
        assertTrue(memory.writes.filterKeys { it.startsWith("block-") }.values.sum() < 2 * BlockPackedFiles.BLOCK_SIZE)
        assertTrue(memory.data.getValue(second).size < 2048)
        val readCount = memory.reads.filterKeys { it.startsWith("block-") }.values.sum()
        assertTrue(files.matchesNote(second, second.removePrefix("blob-")))
        assertTrue(files.publishNote(second, "note.misq", second.removePrefix("blob-")))
        assertEquals(readCount, memory.reads.filterKeys { it.startsWith("block-") }.values.sum())
        assertTrue(memory.writes.getValue("note.misq") < 2048)
        assertArrayEquals(updated, files.read("note.misq"))
        assertArrayEquals(original, files.read(first))
        val restarted = BlockPackedFiles(memory); restarted.names()
        assertTrue(restarted.matchesNote(second, NotebookArchive.digest(updated)))
        assertArrayEquals(updated, restarted.read(second))
    }
    @Test fun rawLegacyNoteIsReadableAndNeverRewrittenOrRemoved() {
        val memory = Memory(); val old = random(10000); val name = "blob-${NotebookArchive.digest(old)}"
        memory.data[name] = old
        val files = BlockPackedFiles(memory); files.names()
        assertArrayEquals(old, files.read(name))
        assertTrue(files.matchesNote(name, NotebookArchive.digest(old)))
        put(files, old + byteArrayOf(5))
        assertArrayEquals(old, memory.data[name])
        assertFalse(memory.writes.containsKey(name))
    }
    @Test fun crossingBlockBoundariesPreservesEveryStrokeByteAndIdentity() {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("block test")
        val refs = (1L..3L).map { StrokeRef(it, UUID.randomUUID().toString()) }
        val payload = random(BlockPackedFiles.BLOCK_SIZE + 99)
        val manifest = empty.copy(strokes = refs, nextId = 4)
        notes.save(manifest) { payload }
        val source = notes.document(empty.info.id)
        val memory = Memory(); val files = BlockPackedFiles(memory); files.names()
        val name = "blob-${NotebookArchive.digest(source.file)}"
        files.writeNote(name, source.file)
        val descriptor = BlockPackedFiles.inspect(memory.data.getValue(name).inputStream())!!
        assertEquals(empty.info.id, descriptor.noteId)
        assertEquals(source.externalIdentity().second, descriptor.revision)
        val restored = File(temp.newFolder(), source.file.name)
        files.openRead(name).use { input -> restored.outputStream().use { input.copyTo(it) } }
        val document = MisqFile(restored)
        assertEquals(manifest, document.readManifest().manifest)
        document.readStrokes(refs) { _, bytes -> assertArrayEquals(payload, bytes) }
    }
    @Test fun failedBlockUploadDoesNotPublishAndRetryRepairsPartialBlock() {
        val memory = Memory(); val files = BlockPackedFiles(memory); files.names()
        val old = random(2000); val first = put(files, old)
        val next = random(BlockPackedFiles.BLOCK_SIZE + 900)
        val name = "blob-${NotebookArchive.digest(next)}"
        memory.fail = { it.startsWith("block-") }
        assertThrows(IOException::class.java) { put(files, next) }
        assertFalse(memory.data.containsKey(name))
        assertArrayEquals(old, files.read(first))
        memory.fail = { false }
        val damaged = "block-${NotebookArchive.digest(next.copyOfRange(0, BlockPackedFiles.BLOCK_SIZE))}"
        memory.data[damaged] = byteArrayOf(0)
        val restarted = BlockPackedFiles(memory); restarted.names()
        put(restarted, next)
        assertArrayEquals(next, restarted.read(name))
    }
    @Test fun missingCorruptAndReorderedBlocksAreRejected() {
        val memory = Memory(); val files = BlockPackedFiles(memory); files.names()
        val bytes = random(BlockPackedFiles.BLOCK_SIZE + 12); val name = put(files, bytes)
        val block = memory.data.keys.first { it.startsWith("block-") }
        val saved = memory.data.getValue(block)
        memory.data[block] = byteArrayOf(3)
        val restarted = BlockPackedFiles(memory); restarted.names()
        assertThrows(IOException::class.java) { restarted.read(name) }
        assertThrows(IOException::class.java) { restarted.matchesNote(name, name.removePrefix("blob-")) }
        memory.data.remove(block)
        restarted.names()
        assertThrows(IOException::class.java) { restarted.matchesNote(name, name.removePrefix("blob-")) }
        memory.data[block] = saved
        restarted.names()
        assertArrayEquals(bytes, restarted.read(name))
        val manifest = BlockPackedFiles.inspect(memory.data.getValue(name).inputStream())!!
        val reorderedBody = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.writeInt(1); output.writeUTF(manifest.hash); output.writeLong(manifest.size)
            output.writeUTF(manifest.noteId.orEmpty()); output.writeUTF(manifest.revision.orEmpty())
            output.writeInt(manifest.parts.size)
            manifest.parts.reversed().forEach { output.writeUTF(it.hash); output.writeInt(it.size) }
        } }.toByteArray()
        val validDescriptor = memory.data.getValue(name)
        memory.data[name] = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.write("MISQBLK1".toByteArray()); output.writeInt(reorderedBody.size); output.write(reorderedBody)
            output.write(java.security.MessageDigest.getInstance("SHA-256").digest(reorderedBody))
        } }.toByteArray()
        assertThrows(IOException::class.java) { restarted.read(name) }
        memory.data[name] = validDescriptor
        val descriptor = memory.data.getValue(name).copyOf(); descriptor[20] = (descriptor[20].toInt() xor 1).toByte()
        memory.data[name] = descriptor
        assertThrows(IllegalArgumentException::class.java) { restarted.read(name) }
    }
    @Test fun revisionKeepsOldBlocksAndCorruptViewCanBeRepairedWithoutFullUpload() {
        val memory = Memory(); val blockFiles = BlockPackedFiles(memory)
        val archive = NotebookArchive(MisqArchiveDirectory(blockFiles))
        val root = temp.newFolder(); val notes = NoteFiles(File(root, "notes")); val note = notes.create("one")
        val folders = File(root, "folders.hwp").also { NotebookFolders.write(it, LibraryPreferences()) }
        val sources = mapOf("folders.hwp" to folders, "notes/${note.info.id}.misq" to notes.document(note.info.id).file)
        archive.commit(sources)
        val firstBlocks = memory.data.keys.filter { it.startsWith("block-") }.toSet()
        notes.rename(note.info.id, "two"); archive.commit(sources)
        assertTrue(memory.data.keys.containsAll(firstBlocks))
        memory.data["${note.info.id}.misq"] = "MISQBLK1broken".toByteArray()
        memory.reset(); archive.commit(sources)
        assertTrue(memory.writes.keys.none { it.startsWith("block-") })
        val restored = File(temp.newFolder(), "${note.info.id}.misq")
        blockFiles.openRead(restored.name).use { input -> restored.outputStream().use { input.copyTo(it) } }
        assertEquals("two", MisqFile(restored).summary().title)
    }
}
