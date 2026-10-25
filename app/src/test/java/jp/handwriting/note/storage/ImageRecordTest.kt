package jp.handwriting.note.storage

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageRecordTest {
    @get:Rule val temp = TemporaryFolder()
    private fun png(): ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAwAAAAICAYAAADN5B7xAAAAEUlEQVR4nGNgGBrgPxgNXwAAvNoB/z2zBvkAAAAASUVORK5CYII=")

    @Test fun importedBytesSurviveSourceDeletionRestartAndArchiveRestore() {
        val root = temp.newFolder()
        val source = File(root, "original.png").apply { writeBytes(png()) }
        val expected = source.readBytes()
        val record = ImageRecord(ImageData(source.readBytes()), 20f, 300f, 900f, 600f)
        assertTrue(source.delete())
        val notes = File(root, "notes"); val files = NoteFiles(notes)
        val empty = files.create("Imported photo")
        val ref = StrokeRef(1, java.util.UUID.randomUUID().toString())
        val manifest = empty.copy(strokes = listOf(ref), nextId = 2)
        files.save(manifest) { record.encode() }
        val restored = ImageRecord.decode(NoteFiles(notes).stroke(empty.info.id, ref.storageId))
        assertArrayEquals(expected, restored.data.png)
        assertEquals(12, restored.data.width); assertEquals(8, restored.data.height)

        val records = mutableMapOf<String, ByteArray>()
        val directory = object : ArchiveDirectory {
            override fun names() = records.keys.toSet()
            override fun read(name: String) = records.getValue(name)
            override fun write(name: String, bytes: ByteArray) { records[name] = bytes }
            override fun delete(name: String) { records.remove(name) }
        }
        val archive = NotebookArchive(directory)
        val content = notes.walkTopDown().filter { it.isFile && (it.name.endsWith(".ink") || it.name == "manifest.hwn") }
            .associateBy { "notes/" + it.relativeTo(notes).invariantSeparatorsPath }
        val folders = File(root, "folders.hwp"); NotebookFolders.write(folders, LibraryPreferences())
        archive.commit(content + ("folders.hwp" to folders))
        val target = temp.newFolder(); archive.restore(checkNotNull(archive.latest()), target)
        val reopened = NoteFiles(File(target, "notes"))
        assertEquals(manifest, reopened.read(empty.info.id).manifest)
        assertArrayEquals(expected, ImageRecord.decode(reopened.stroke(empty.info.id, ref.storageId)).data.png)
        val asset = records.entries.first { it.key.startsWith("blob-") && runCatching {
            ByteBuffer.wrap(it.value).getInt(8) == ImageRecord.MAGIC
        }.getOrDefault(false) }
        assertEquals("notes/strokes/${asset.key}", ArchiveLayout.path(asset.key, asset.value))
    }

    @Test fun positionsRoundTripAndTranslationDoesNotChangeOwnedImageBytes() {
        val image = ImageRecord(ImageData(png()), 10f, 30f, 600f, 400f)
        val moved = image.translated(12f, 24f)
        val restored = ImageRecord.decode(moved.encode())
        assertEquals(22f, restored.left, 0f); assertEquals(54f, restored.top, 0f)
        assertEquals(600f, restored.width, 0f); assertEquals(400f, restored.height, 0f)
        assertSame(image.data, moved.data)
        assertArrayEquals(image.data.png, restored.data.png)
    }

    @Test fun invalidOrOversizedPayloadAndNonfiniteGeometryAreRejected() {
        val image = ImageRecord(ImageData(png()), 0f, 0f, 1000f, 600f)
        val bytes = image.encode()
        assertThrows(IllegalArgumentException::class.java) { ImageRecord.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { ImageRecord.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { ImageRecord.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(24, Int.MAX_VALUE) }) }
        assertThrows(IllegalArgumentException::class.java) { ImageData(png().also { ByteBuffer.wrap(it).putInt(16, 1000000) }) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(top = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(width = -1f) }
    }

    @Test fun resizingPreservesAspectRatioPositionAndEmbeddedBytesAfterReload() {
        val image = ImageRecord(ImageData(png()), 20f, 40f, 600f, 400f)
        val resized = image.resized(0.5f)
        assertEquals(20f, resized.left, 0f); assertEquals(40f, resized.top, 0f)
        assertEquals(300f, resized.width, 0f); assertEquals(200f, resized.height, 0f)
        assertSame(image.data, resized.data)
        val restored = ImageRecord.decode(resized.encode())
        assertEquals(resized.width, restored.width, 0f)
        assertEquals(resized.height, restored.height, 0f)
        assertArrayEquals(image.data.png, restored.data.png)
        for (factor in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { image.resized(factor) }
        }
    }
    @Test fun centeredResizeKeepsTheImageCenterAndHandleResizeKeepsTheTopLeft() {
        val image = ImageRecord(ImageData(png()), 200f, 200f, 400f, 300f)
        for (factor in listOf(0.5f, 1.5f)) {
            val centered = image.resizedFromCenter(factor)
            assertEquals(400f, centered.left + centered.width / 2f, 0f)
            assertEquals(350f, centered.top + centered.height / 2f, 0f)
            assertSame(image.data, centered.data)
            val handle = image.resized(factor)
            assertEquals(image.left, handle.left, 0f)
            assertEquals(image.top, handle.top, 0f)
        }
    }
}
