package jp.handwriting.note.storage

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PackedDocumentFilesTest {
    @Test fun refreshedDocumentIdsPreserveEncodedNamesAndDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe")
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "working-set-provider/$id").also { check(it.mkdirs()) }
        val tree = DocumentsContract.buildTreeDocumentUri("net.neomenia.misqnotes.probe.tests.working-set", id)
        val packed = PackedDocumentFiles(context.contentResolver, tree, "nested/misq-v3")
        val name = "日本語 % # + object"
        val bytes = byteArrayOf(0, 1, 2, -1)
        try {
            assertTrue(packed.names().isEmpty())
            packed.write(name, bytes)
            repeat(3) {
                assertEquals(setOf(name), packed.names())
                assertArrayEquals(bytes, packed.read(name))
            }
            val second = PackedDocumentFiles(context.contentResolver, tree, "nested/misq-v3")
            assertEquals(setOf(name), second.names())
            assertArrayEquals(bytes, second.openRead(name).use { it.readBytes() })
            second.delete(name)
            assertTrue(packed.names().isEmpty())
            packed.write(name, byteArrayOf(5))
            assertEquals(setOf(name), second.names())
            assertArrayEquals(byteArrayOf(5), second.read(name))
        } finally { directory.deleteRecursively() }
    }
}
