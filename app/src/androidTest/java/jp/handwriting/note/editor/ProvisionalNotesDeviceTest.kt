package jp.handwriting.note.editor

import android.content.Intent
import android.os.SystemClock
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.ConflictFixtureActivity
import jp.handwriting.note.storage.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ProvisionalNotesDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ink(x: Float) = EditorStroke(Stroke(
        Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202124.toInt(), 4f, .01f),
        MutableStrokeInputBatch().add(InputToolType.STYLUS, x, 100f, 0).add(InputToolType.STYLUS, x + 100f, 200f, 20)))
    private data class Fixture(val root: File, val files: NoteFiles, val latest: NoteManifest, val aliases: List<File>)
    private fun fixture(single: Boolean = false, tied: Boolean = false): Fixture {
        val root = File(context.cacheDir, "conflict-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val files = NoteFiles(File(root, "notes"))
        val initial = files.create("earlier", now = 100)
        val first = ink(100f)
        val earlier = initial.copy(strokes = listOf(StrokeRef(1, first.storageId)), nextId = 2)
        files.save(earlier) { NoteRepository.encodeStroke(first) }
        val aliases = mutableListOf<File>()
        if (!single) aliases += File(root, "notes/earlier.misq").also { files.document(initial.info.id).file.copyTo(it) }
        val latestInk = ink(600f)
        val latest = earlier.copy(info = earlier.info.copy(title = "latest provisional", updatedAt = if (tied) 100 else 200),
            strokes = listOf(StrokeRef(1, latestInk.storageId)))
        files.save(latest) { NoteRepository.encodeStroke(latestInk) }
        aliases += File(root, "notes/latest.misq").also { files.document(initial.info.id).file.copyTo(it) }
        files.delete(initial.info.id)
        return Fixture(root, files, latest, aliases)
    }
    private fun hashes(f: Fixture) = f.aliases.associate { it.name to NotebookArchive.digest(it.readBytes()) }
    private fun waitFor(message: () -> String = { "Provisional note operation timed out" }, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(50) }
        fail(message())
    }
    @Test fun newestVersionIsListedAndViewingPreviewAndViewportNeverPublishesIt() {
        val f = fixture(); val before = hashes(f)
        val repository = NoteRepository(f.files, NoteListCache(f.files.directory))
        val summary = repository.list().single()
        assertTrue(summary.provisional)
        assertEquals(f.latest.info.title, summary.title)
        val loaded = repository.load(summary.id)
        assertEquals(f.latest, loaded.manifest)
        assertEquals(600f, loaded.entries.single().value.ink.inputs[0].x, 0f)
        assertTrue(repository.preview(summary.id).exists())
        repository.save(NoteSnapshot(loaded.manifest.copy(info = loaded.manifest.info.copy(
            updatedAt = 300, position = ViewPosition(2f, 120f, 400f))), loaded.entries))
        assertFalse(f.files.document(summary.id).file.exists())
        assertTrue(NoteRepository(f.files, NoteListCache(f.files.directory)).list().single().provisional)
        assertEquals(before, hashes(f))
    }
    @Test fun firstInkEditPublishesSameIdentityAndRetainsHistoryAndAllAliases() {
        val f = fixture(); val before = hashes(f); val repository = NoteRepository(f.files)
        val loaded = repository.load(f.latest.info.id)
        val extra = ink(300f)
        val edited = loaded.manifest.copy(strokes = loaded.manifest.strokes + StrokeRef(2, extra.storageId), nextId = 3)
        repository.save(NoteSnapshot(edited, loaded.entries + OrderedEdits.Entry(2, extra)))
        val saved = f.files.document(f.latest.info.id)
        assertEquals(edited, saved.readManifest().manifest)
        assertEquals(f.latest, saved.previous())
        assertFalse(repository.list().single().provisional)
        assertEquals(2, repository.load(f.latest.info.id).entries.size)
        assertEquals(before, hashes(f))
    }
    @Test fun concurrentWorkingCopyArrivalIsNeverOverwrittenByProvisionalEdit() {
        val f = fixture(); val repository = NoteRepository(f.files)
        val loaded = repository.load(f.latest.info.id)
        val destination = f.files.document(f.latest.info.id).file
        f.aliases.first().copyTo(destination)
        val before = destination.readBytes()
        assertThrows(IllegalStateException::class.java) {
            repository.save(NoteSnapshot(loaded.manifest.copy(info = loaded.manifest.info.copy(title = "edited")), loaded.entries))
        }
        assertArrayEquals(before, destination.readBytes())
    }
    @Test fun equalTimestampsUseTheSameContentForLibraryEditorAndMerge() {
        val f = fixture(tied = true)
        val selected = f.files.provisionalVersions().getValue(f.latest.info.id).first
        val expected = MisqFile(selected, readOnlyAlias = true).readManifest().manifest
        lateinit var model: EditorViewModel
        ActivityScenario.launch<ConflictFixtureActivity>(Intent(context, ConflictFixtureActivity::class.java)
            .putExtra("fixture", f.root.path)).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.size == 2 }
            assertEquals(expected.info.title, model.workspace.value.notes.single().title)
            scenario.onActivity { model.openNote(expected.info.id) }
            waitFor { model.workspace.value.current != null && !model.workspace.value.working }
            assertEquals(expected.strokes.map { it.storageId }, model.edits.entries.map { it.storageId })
            scenario.onActivity { model.beginMerge(model.conflicts.value.items.last()) }
            waitFor { model.mergeReview.value.session != null || model.mergeReview.value.error != null }
            assertNull(model.mergeReview.value.error)
            assertEquals(expected, model.mergeReview.value.session!!.multiPlan.indexes.first().manifest)
            scenario.onActivity { model.dismissMerge() }
            assertFalse(f.files.document(expected.info.id).file.exists())
        }
    }
    @Test fun missingWorkingCopyCanBeMergedOrCancelledAndSingleVersionCanBeConfirmed() {
        for (single in listOf(false, true)) {
            val f = fixture(single); val before = hashes(f)
            assertEquals(if (single) 1 else 2, NoteConflicts(f.files.directory, File(f.root, "conflicts")).scan().size)
            lateinit var model: EditorViewModel
            ActivityScenario.launch<ConflictFixtureActivity>(Intent(context, ConflictFixtureActivity::class.java)
                .putExtra("fixture", f.root.path)).use { scenario ->
                scenario.onActivity { model = it.model }
                waitFor({ "single=$single workspace=${model.workspace.value} conflicts=${model.conflicts.value}" }) { !model.workspace.value.working && model.conflicts.value.items.isNotEmpty() }
                assertTrue(model.workspace.value.notes.single().provisional)
                scenario.onActivity { model.beginMerge(model.conflicts.value.items.first()) }
                waitFor { model.mergeReview.value.session != null || model.mergeReview.value.error != null }
                assertNull(model.mergeReview.value.error)
                assertEquals(f.latest, model.mergeReview.value.session!!.multiPlan.indexes.first().manifest)
                scenario.onActivity { model.dismissMerge() }
                assertFalse(f.files.document(f.latest.info.id).file.exists())
                scenario.onActivity { model.beginMerge(model.conflicts.value.items.first()) }
                waitFor { model.mergeReview.value.session != null || model.mergeReview.value.error != null }
                assertNull(model.mergeReview.value.error)
                val session = model.mergeReview.value.session!!
                val decisions = session.multiPlan.steps(MergeStrength.CAUTIOUS).associate { it.key to setOf(0) }
                assertFalse(f.files.document(f.latest.info.id).file.exists())
                scenario.onActivity { model.saveResolvedMerge(MergeStrength.CAUTIOUS, decisions) }
                waitFor { model.mergeReview.value.error != null || model.workspace.value.current != null }
                assertNull(model.mergeReview.value.error)
                assertEquals(f.latest.info.id, model.workspace.value.current!!.id)
                assertEquals(f.latest.strokes, f.files.read(f.latest.info.id).manifest.strokes)
                assertEquals(before, hashes(f))
            }
        }
    }
}
