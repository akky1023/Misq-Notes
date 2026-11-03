package jp.handwriting.note.storage

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Optional read-only source; all writes/edits are private copies in disposable provider trees. */
class BlockStoragePerformanceTest {
    @Test fun compareFullAndSpatialOpenOnReadOnlySourceCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        jp.handwriting.note.AppMessages.configure(context)
        val args = InstrumentationRegistry.getArguments()
        val sourceTree = args.getString("sourceTree"); val sourceId = args.getString("sourceNote")
        assumeNotNull(sourceTree, sourceId)
        val root = File(context.cacheDir, "spatial-benchmark-${UUID.randomUUID()}").also { it.mkdirs() }
        val provider = Uri.parse("content://net.neomenia.misqnotes.probe.tests.working-set")
        @Suppress("DEPRECATION")
        val target = context.contentResolver.call(provider, "test-new-tree", null, null)!!.getParcelable<Uri>("tree")!!
        try {
            val source = MisqDocumentFiles(context.contentResolver, Uri.parse(sourceTree)); source.names()
            val seed = File(root, "$sourceId.misq")
            source.openRead(seed.name).use { input -> seed.outputStream().use { input.copyTo(it, 65536) } }
            val remote = Counted(PackedDocumentFiles(context.contentResolver, target, "inkdata")); remote.names()
            remote.writeNote(seed.name, seed)
            val notes = NoteFiles(File(root, "notes"))
            notes.backing = object : NoteBacking {
                override fun contains(id: String) = id == sourceId
                override fun ensure(id: String) { remote.openRead("$id.misq").use { input -> File(notes.directory, "$id.misq").outputStream().use { input.copyTo(it, 65536) } } }
                override fun summaries() = emptyList<NoteSummary>()
                override fun deleted(id: String) = Unit
                override fun preview(id: String) = File(root, "unused-preview")
            }
            remote.readBytes = 0
            val fullStart = System.nanoTime()
            val full = NoteRepository(notes).load(sourceId!!)
            val fullMs = (System.nanoTime() - fullStart) / 1_000_000
            val fullReadBytes = remote.readBytes
            val regionFiles = Counted(PackedDocumentFiles(context.contentResolver, target, "inkdata/.regions"))
            val file = notes.document(sourceId).file; val hash = NotebookArchive.digest(file)
            SpatialNoteCache(regionFiles).publish(file, hash)
            regionFiles.readBytes = 0
            val visibleStart = System.nanoTime()
            val spatial = SpatialNoteCache(regionFiles).open(hash)!!
            val top = spatial.manifest.info.position.top
            val visible = spatial.load((top - 500f).coerceAtLeast(0f), top + 2000f, emptySet()) { ref, bytes ->
                jp.handwriting.note.editor.OrderedEdits.Entry(ref.id, NoteRepository.decodeStroke(ref.storageId, bytes))
            }
            val visibleMs = (System.nanoTime() - visibleStart) / 1_000_000
            val visibleReadBytes = regionFiles.readBytes
            assertEquals(full.manifest, spatial.manifest)
            assertTrue(visible.size < full.entries.size)
            assertTrue(visibleReadBytes < fullReadBytes)
            val known = full.entries.associateBy { it.id }
            visible.forEach { assertEquals(known.getValue(it.id).value.storageId, it.value.storageId) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nREAL_COPY_OPEN fullMs=$fullMs spatialMs=$visibleMs totalStrokes=${full.entries.size} initialStrokes=${visible.size} fullReadBytes=$fullReadBytes spatialReadBytes=$visibleReadBytes\n")
            })
        } finally {
            DocumentsContract.deleteDocument(context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target)))
            root.deleteRecursively()
        }
    }
    private class Counted(private val files: PackedFiles) : PackedFiles {
        var readBytes = 0L
        var writtenBytes = 0L
        private fun counted(input: InputStream, accept: (Int) -> Unit) = object : FilterInputStream(input) {
            override fun read(): Int = `in`.read().also { if (it >= 0) accept(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { if (it > 0) accept(it) }
        }
        override fun names() = files.names()
        override fun openRead(name: String): InputStream = counted(files.openRead(name)) { readBytes += it }
        override fun read(name: String) = openRead(name).use { it.readBytes() }
        override fun write(name: String, bytes: ByteArray) { files.write(name, bytes); writtenBytes += bytes.size }
        override fun writeStream(name: String, input: InputStream) = files.writeStream(name, counted(input) { writtenBytes += it })
        override fun delete(name: String) = files.delete(name)
    }
    @Test fun compareFullAndBlockSaveOnReadOnlySourceCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val sourceTree = args.getString("sourceTree"); val sourceId = args.getString("sourceNote")
        assumeNotNull(sourceTree, sourceId)
        val tree = Uri.parse(sourceTree)
        require(UUID.fromString(sourceId).toString() == sourceId)
        check(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
        val root = File(context.cacheDir, "block-benchmark-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            val seed = File(root, "$sourceId.misq")
            val source = MisqDocumentFiles(context.contentResolver, tree); source.names()
            source.openRead(seed.name).use { input -> seed.outputStream().use { input.copyTo(it, 65536) } }
            val count = MisqFile(seed).summary().strokes
            for (blockMode in listOf(false, true)) {
                val provider = Uri.parse("content://net.neomenia.misqnotes.probe.tests.working-set")
                @Suppress("DEPRECATION")
                val target = context.contentResolver.call(provider, "test-new-tree", null, null)!!.getParcelable<Uri>("tree")!!
                try {
                    val current = PackedDocumentFiles(context.contentResolver, target, "inkdata")
                    val recovery = PackedDocumentFiles(context.contentResolver, target, "inkdata/.recovery")
                    val blocks = PackedDocumentFiles(context.contentResolver, target, "inkdata/.blocks")
                    val raw = object : PackedFiles {
                        fun owner(name: String) = when {
                            name.startsWith("block-") -> blocks
                            name.startsWith("blob-") || name.startsWith("revision-") -> recovery
                            else -> current
                        }
                        override fun names() = current.names() + recovery.names() + blocks.names()
                        override fun read(name: String) = owner(name).read(name)
                        override fun openRead(name: String) = owner(name).openRead(name)
                        override fun write(name: String, bytes: ByteArray) = owner(name).write(name, bytes)
                        override fun writeStream(name: String, input: InputStream) = owner(name).writeStream(name, input)
                        override fun delete(name: String) = owner(name).delete(name)
                    }
                    val counted = Counted(raw)
                    val backend: PackedFiles = if (blockMode) BlockPackedFiles(counted) else counted
                    val work = File(root, "mode-$blockMode").also { it.mkdirs() }
                    val notes = NoteFiles(File(work, "notes"))
                    seed.copyTo(File(notes.directory, seed.name))
                    val file = notes.document(sourceId!!).file
                    val folders = File(work, "folders.hwp").also { NotebookFolders.write(it, LibraryPreferences()) }
                    val files = mapOf("folders.hwp" to folders, "notes/${file.name}" to file)
                    val archive = NotebookArchive(MisqArchiveDirectory(backend)); archive.commit(files)
                    counted.readBytes = 0; counted.writtenBytes = 0
                    val start = System.nanoTime()
                    notes.rename(sourceId, "benchmark copy")
                    archive.commit(files)
                    val ms = (System.nanoTime() - start) / 1_000_000
                    val reads = counted.readBytes; val writes = counted.writtenBytes
                    assertEquals(count, notes.read(sourceId).manifest.strokes.size)
                    val restored = File(work, "restored/${file.name}").also { it.parentFile!!.mkdirs() }
                    backend.openRead(file.name).use { input -> restored.outputStream().use { input.copyTo(it, 65536) } }
                    assertEquals(NotebookArchive.digest(file), NotebookArchive.digest(restored))
                    MisqFile(restored).validate()
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "\nREAL_COPY_SAVE blocks=$blockMode noteBytes=${file.length()} strokes=$count saveMs=$ms providerReadBytes=$reads providerWriteBytes=$writes\n")
                    })
                } finally {
                    DocumentsContract.deleteDocument(context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target)))
                }
            }
        } finally { root.deleteRecursively() }
    }
}
