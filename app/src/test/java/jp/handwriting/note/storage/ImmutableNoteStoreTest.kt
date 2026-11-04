package jp.handwriting.note.storage

import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImmutableNoteStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source(): MisqFile {
        val notes = NoteFiles(temp.newFolder()); val empty = notes.create("source")
        val refs = (1L..3L).map { StrokeRef(it, UUID.randomUUID().toString()) }
        notes.save(empty.copy(strokes = refs, nextId = 4), refs.associate { it.storageId to StrokePlacement((it.id - 1) * 10000f, (it.id - 1) * 10000f, (it.id - 1) * 10000f + 20) }) {
            ByteArray(1024) { 7 }
        }
        return notes.document(empty.info.id)
    }
    private class Memory : PackedFiles {
        val data = linkedMapOf<String, ByteArray>(); var writes = 0; var failAt = Int.MAX_VALUE
        override fun names() = data.keys.toSet()
        override fun read(name: String) = data.getValue(name).copyOf()
        override fun write(name: String, bytes: ByteArray) {
            check(name !in data)
            writes++
            if (writes == failAt) { data[name] = bytes.copyOf(bytes.size / 2); throw IOException("power loss") }
            data[name] = bytes.copyOf()
        }
        override fun delete(name: String) { error("Physical deletion must remain disabled") }
    }
    @Test fun migrationRoundTripsAllHistoricalRecordsAndDoesNotChangeOriginal() {
        val original = source(); val before = original.index(); val changed = before.manifest.strokes[1].copy(storageId = UUID.randomUUID().toString())
        original.save(before.manifest.copy(strokes = listOf(changed, before.manifest.strokes[2])), mapOf(changed.storageId to StrokePlacement(5000f, 5000f, 5010f))) { byteArrayOf(9) }
        val hash = NotebookArchive.digest(original.file)
        val folder = temp.newFolder(); val migration = Format3Migration(folder)
        val store = migration.migrate(listOf(original), emptyMap(), placements = { error("already placed") })
        assertTrue(migration.active)
        assertEquals(hash, NotebookArchive.digest(original.file))
        val restored = File(temp.newFolder(), "${before.manifest.info.id}.misq")
        store.export(store.list().single(), restored)
        val actual = MisqFile(restored).index(); val expected = original.index()
        assertEquals(expected.manifest, actual.manifest); assertEquals(expected.events, actual.events); assertEquals(expected.entities, actual.entities)
        assertEquals(expected.records.mapValues { it.value.hash }, actual.records.mapValues { it.value.hash })
        assertEquals(1, folder.listFiles()!!.count { it.name.startsWith("format3-rollback-") })
    }
    @Test fun partialEditAndDeletionRetainUnreadReferencesAndNeverReadTheirBodies() {
        val original = source(); val store = ImmutableNoteStore(temp.newFolder())
        val initial = store.importNote(original); store.install(initial)
        val old = original.index().manifest
        val missingPack = store.find(initial, setOf(3)).getValue(3).body.objectId
        val bodyFile = File(store.directory, "objects/$missingPack"); assertTrue(bodyFile.delete())
        store.fetch = { error("Unread body was fetched during editing") }
        val moved = StrokeRef(1, UUID.randomUUID().toString())
        val edited = store.edit(initial, initial.info, 4, setOf(1, 2), listOf(moved to (StrokePlacement(6000f, 6000f, 6020f) to byteArrayOf(8))))
        store.install(edited, validate = false)
        assertEquals(2, edited.count)
        assertEquals(listOf(1L), store.visible(edited, 6000f, 6100f, emptySet()).map { it.first.id })
        assertEquals(old.strokes[2], store.manifest(edited).strokes.first { it.id == 3L })
        assertFalse(store.pending(edited, 10000f, 11000f, emptySet()))
        assertEquals(3, store.root(initial.id).count)
    }
    @Test fun coldVisibleReadFetchesOnlyItsSpatialPack() {
        val original = source(); val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(original); store.install(root)
        val packs = root.pages.flatMap(store::rows).map { it.body.objectId }.distinct()
        val saved = packs.associateWith { File(store.directory, "objects/$it").readBytes() }
        packs.forEach { assertTrue(File(store.directory, "objects/$it").delete()) }
        val reads = mutableListOf<String>()
        val cold = ImmutableNoteStore(store.directory) { id -> reads += id; saved.getValue(id).inputStream() }
        val reopened = cold.current(root.info.id)!!
        assertEquals(3, cold.manifest(reopened).strokes.size); assertTrue(reads.isEmpty())
        assertEquals(listOf(1L), cold.visible(reopened, 0f, 500f, emptySet()).map { it.first.id })
        assertEquals(1, reads.size)
    }
    @Test fun everyInterruptedUploadCanBeRetriedWithoutDeletingOrOverwritingAnything() {
        val original = source(); val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(original)
        val successful = Memory(); ImmutableNoteTransport(store, successful).publish(root)
        for (boundary in 1..successful.writes) {
            val remote = Memory().also { it.failAt = boundary }
            assertThrows(IOException::class.java) { ImmutableNoteTransport(store, remote).publish(root) }
            remote.failAt = Int.MAX_VALUE
            val resumed = ImmutableNoteTransport(store, remote); resumed.publish(root)
            val receiver = ImmutableNoteStore(temp.newFolder()); val incoming = ImmutableNoteTransport(receiver, remote).heads(root.info.id).single()
            receiver.install(incoming); assertEquals(root.id, receiver.current(root.info.id)!!.id)
        }
    }
    @Test fun incompleteIncomingCommitCannotReplacePreviouslyInstalledState() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        val remote = Memory(); ImmutableNoteTransport(store, remote).publish(root)
        val receiver = ImmutableNoteStore(temp.newFolder()); val download = ImmutableNoteTransport(receiver, remote)
        receiver.install(download.heads(root.info.id).single())
        val updated = store.edit(root, root.info.copy(title = "changed"), root.nextId, emptySet(), emptyList())
        ImmutableNoteTransport(store, remote).publish(updated)
        val removed = remote.data.keys.first { it.startsWith("seal-") && it.contains(updated.id.removePrefix("o-")) }
        remote.data.remove(removed)
        assertThrows(Exception::class.java) { download.heads(root.info.id) }
        assertEquals(root.id, receiver.current(root.info.id)!!.id)
    }
    @Test fun concurrentMovesRetainBothHeadsRegardlessOfArrivalOrder() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        fun move(y: Float): ImmutableNoteStore.Root {
            val ref = StrokeRef(1, UUID.randomUUID().toString())
            return store.edit(root, root.info, root.nextId, setOf(1), listOf(ref to (StrokePlacement(y, y, y + 10) to byteArrayOf(y.toInt().toByte()))))
        }
        val a = move(5000f); val b = move(6000f)
        val remote = Memory(); val transport = ImmutableNoteTransport(store, remote)
        transport.publish(b); transport.publish(root); transport.publish(a)
        assertEquals(setOf(a.id, b.id), transport.heads(root.info.id).map { it.id }.toSet())
        assertEquals(root.id, store.current(root.info.id)!!.id)
        val resolved = store.resolve(a, listOf(a.id, b.id)); transport.publish(resolved)
        assertEquals(listOf(resolved.id), transport.heads(root.info.id).map { it.id })
    }
    @Test fun unpublishedIntermediateCommitsAndPreviewsDoNotCreateFalseBranches() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        val remote = Memory(); val transport = ImmutableNoteTransport(store, remote); transport.publish(root)
        val intermediate = store.edit(root, root.info.copy(title = "new title"), root.nextId, emptySet(), emptyList())
        val preview = store.saveThumbnail(intermediate, byteArrayOf(1, 2, 3))
        transport.publish(preview)
        assertEquals(listOf(preview.id), transport.heads(root.info.id).map { it.id })
    }
    @Test fun independentBackupRestoresCurrentEditsAndDeletionTombstones() {
        val source = source(); val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source); store.install(root)
        val removed = store.remove(root); store.install(removed)
        val backup = ImmutableNoteStore(temp.newFolder()); val migration = Format3Migration(temp.newFolder())
        migration.backup(store, backup)
        // Restoration must not read any object from the original repository or its cache.
        store.objectNames().forEach { File(store.directory, "objects/$it").writeBytes(byteArrayOf(0)) }
        val restored = ImmutableNoteStore(temp.newFolder()); migration.restoreBackup(backup, restored)
        assertTrue(restored.current(root.info.id)!!.deleted)
        assertTrue(restored.list().isEmpty())
        assertEquals(3, restored.root(root.id).count)
        val exported = File(temp.newFolder(), "${root.info.id}.misq"); restored.export(removed, exported)
        assertTrue(MisqFile(exported).index().manifest.strokes.isEmpty())
        assertTrue(MisqFile.frontier(MisqFile(exported).index().events).all { it.blob == null })
    }
    @Test fun corruptOrUnsupportedFormatNeverChangesLocalHead() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        val value = JSONObject(String(store.objectBytes(root.id), Charsets.UTF_8)).put("minWriter", 4)
        val future = store.put(value.toString().toByteArray(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { store.root(future) }
        val body = store.find(root, setOf(1)).getValue(1).body.objectId
        File(store.directory, "objects/$body").writeBytes(byteArrayOf(0))
        assertThrows(Exception::class.java) { store.visible(root, 0f, 500f, emptySet()) }
        assertEquals(root.id, store.current(root.info.id)!!.id)
    }
    @Test fun cancelledMigrationNeverActivatesAndRetainsOriginalBytes() {
        val source = source(); val expected = NotebookArchive.digest(source.file); val root = temp.newFolder()
        val migration = Format3Migration(root); var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            migration.migrate(listOf(source), emptyMap(), checkpoint = { if (++checks == 6) throw java.util.concurrent.CancellationException() }, placements = { error("placed") })
        }
        assertFalse(migration.active); assertEquals(expected, NotebookArchive.digest(source.file))
    }
    @Test fun insufficientSpaceDoesNotStartMigrationOrModifyTheSource() {
        val source = source(); val hash = NotebookArchive.digest(source.file); val folder = temp.newFolder()
        val migration = Format3Migration(folder) { 0L }
        assertThrows(IllegalStateException::class.java) { migration.migrate(listOf(source), emptyMap(), placements = { error("not reached") }) }
        assertFalse(migration.active); assertTrue(folder.listFiles()!!.isEmpty()); assertEquals(hash, NotebookArchive.digest(source.file))
    }
    @Test fun missingActivatedStoreCannotBeRecreatedAsAnEmptyLibrary() {
        val folder = temp.newFolder(); val migration = Format3Migration(folder)
        val missing = "format3-${UUID.randomUUID()}"
        NoteFiles.atomicWrite(File(folder, ".format3-active"), JSONObject().put("format", 3).put("minWriter", 3).put("directory", missing).toString().toByteArray())
        assertThrows(IllegalStateException::class.java) { migration.open() }
        assertFalse(File(folder, missing).exists())
    }
    @Test fun interruptedHeadReplacementFindsTheBackupAndDoesNotHideTheNote() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        val updated = store.edit(root, root.info.copy(title = "newer"), root.nextId, emptySet(), emptyList()); store.install(updated)
        assertTrue(File(store.directory, "heads/${root.info.id}").delete())
        val reopened = ImmutableNoteStore(store.directory)
        assertEquals(root.id, reopened.current(root.info.id)!!.id)
        assertEquals(listOf(root.info.id), reopened.list().map { it.info.id })
        assertTrue(reopened.recovered(root.info.id))
        Format3Library(reopened, temp.newFolder()).saveThumbnail(root.info.id, byteArrayOf(1))
        assertEquals(root.id, reopened.current(root.info.id)!!.id)
        assertTrue(reopened.recovered(root.info.id))
        reopened.install(updated); assertFalse(reopened.recovered(root.info.id))
    }
    @Test fun staleMergeExportIsRejectedAndTheLatestDurableEditsRemain() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        val folder = temp.newFolder(); val library = Format3Library(store, folder)
        library.export(root.info.id, File(folder, "${root.info.id}.misq")); library.requireCurrentExport(root.info.id)
        library.rename(root.info.id, "newer title")
        val latest = store.current(root.info.id)!!.id
        library.saveThumbnail(root.info.id, byteArrayOf(1), expectedRevision = root.id)
        assertEquals(latest, store.current(root.info.id)!!.id)
        assertThrows(IllegalStateException::class.java) { library.requireCurrentExport(root.info.id) }
        assertEquals("newer title", library.manifest(root.info.id).info.title)
    }
    @Test fun clonedDevicesMayAllocateTheSameNumericIdWithoutLosingEitherEntity() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        fun append(value: Int): ImmutableNoteStore.Root {
            val ref = StrokeRef(4, UUID.randomUUID().toString())
            return store.edit(root, root.info, 5, emptySet(), listOf(ref to (StrokePlacement(100f, 100f, 110f) to byteArrayOf(value.toByte()))))
        }
        val a = append(1); val b = append(2)
        val folder = temp.newFolder(); val first = File(folder, "${root.info.id}.misq"); val second = File(folder, "other.tmp")
        store.export(a, first); store.export(b, second)
        val left = MisqFile(first); val right = MisqFile(second)
        val combined = MisqFile.merge(null, left, right).copy(placements = left.index().placements + right.index().placements)
        left.rewrite(combined, right)
        val resolved = store.importNote(left, listOf(a.id, b.id)); store.verify(resolved)
        assertEquals(5, resolved.count)
        assertEquals(5, store.manifest(resolved).strokes.map { it.id }.distinct().size)
        assertTrue(store.history(resolved).first.mapNotNull { it.blob }.toSet().containsAll(listOf(store.manifest(a).strokes.last { it.id == 4L }.storageId, store.manifest(b).strokes.last { it.id == 4L }.storageId)))
    }
    @Test fun deleteUndoAndRedoPreserveEntityCausalityEvenWhenTheSameBodyIsRepacked() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        val a = StrokeRef(4, UUID.randomUUID().toString()); val b = StrokeRef(5, UUID.randomUUID().toString())
        val p = StrokePlacement(100f, 100f, 120f)
        val appended = store.edit(root, root.info, 6, emptySet(), listOf(a to (p to byteArrayOf(4)), b to (p to byteArrayOf(5))))
        val removed = store.edit(appended, root.info, 6, setOf(4), emptyList())
        val restored = store.edit(removed, root.info, 6, setOf(4), listOf(a to (p to byteArrayOf(4))))
        store.verify(restored)
        val entity = store.find(restored, setOf(4)).getValue(4).entity
        val frontier = MisqFile.frontier(store.history(restored).first.filter { it.entity == entity })
        assertEquals(1, frontier.size); assertEquals(a.storageId, frontier.single().blob)
        assertEquals(5, restored.count)
        assertTrue(restored.tombstones.isEmpty())
    }
    @Test fun incompleteIndependentBackupCannotBeRestored() {
        val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source()); store.install(root)
        val migration = Format3Migration(temp.newFolder()); val backup = ImmutableNoteStore(temp.newFolder()); var boundaries = 0
        assertThrows(IOException::class.java) { migration.backup(store, backup) { if (++boundaries == 3) throw IOException("full disk") } }
        assertThrows(IOException::class.java) { migration.restoreBackup(backup, ImmutableNoteStore(temp.newFolder())) }
        assertEquals(root.id, store.current(root.info.id)!!.id)
    }
    @Test fun initialReadAndOneStrokeSaveAreBoundedBySpatialPagesRatherThanWholeNotebook() {
        val files = NoteFiles(temp.newFolder()); val empty = files.create("large")
        val refs = (1L..4096L).map { StrokeRef(it, UUID.randomUUID().toString()) }
        val placements = refs.associate { ref -> val y = ((ref.id - 1) / 64) * 10000f; ref.storageId to StrokePlacement(y, y, y + 20) }
        files.save(empty.copy(strokes = refs, nextId = 4097), placements) { ByteArray(1024) { 7 } }
        val source = files.document(empty.info.id); val store = ImmutableNoteStore(temp.newFolder()); val root = store.importNote(source)
        var bytes = 0L; var objects = 0
        val cold = ImmutableNoteStore(temp.newFolder()) { id ->
            objects++
            object : java.io.FilterInputStream(store.openObject(id)) {
                override fun read(): Int = super.read().also { if (it >= 0) bytes++ }
                override fun read(buffer: ByteArray, off: Int, len: Int): Int = `in`.read(buffer, off, len).also { if (it > 0) bytes += it }
            }
        }
        cold.install(root, validate = false)
        val opened = cold.current(root.info.id)!!
        assertEquals(4096, cold.manifest(opened).strokes.size)
        assertEquals(64, cold.visible(opened, 0f, 500f, emptySet()).size)
        assertTrue(bytes < source.file.length() / 8); assertEquals(3, objects)
        println("FORMAT3_COLD baselineBytes=${source.file.length()} initialBytes=$bytes initialObjects=$objects strokes=4096")
        val before = cold.objectNames()
        val ref = refs.first().copy(storageId = UUID.randomUUID().toString())
        val changed = cold.edit(opened, opened.info, opened.nextId, setOf(1), listOf(ref to (placements.getValue(refs.first().storageId) to ByteArray(1024) { 8 })))
        cold.install(changed, validate = false)
        val added = cold.objectNames() - before
        val savedBytes = added.sumOf { File(cold.directory, "objects/$it").length() }
        assertTrue(savedBytes < source.file.length() / 8)
        println("FORMAT3_EDIT writtenAndFetchedBytes=$savedBytes newObjects=${added.size}")
    }
}
