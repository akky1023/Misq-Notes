package jp.handwriting.note.editor

import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.AppMessages
import jp.handwriting.note.ConflictFixtureActivity
import jp.handwriting.note.R
import jp.handwriting.note.storage.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class NoteConflictsDeviceTest {
    /** Explicit manual fixture creation in the probe library; never runs in the ordinary suite. */
    @Test fun createConflictNotesForManualTesting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedManualConflict") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("net.neomenia.misqnotes.probe", context.packageName)
        val notes = File(context.filesDir, "notes").apply { mkdirs() }
        val files = MisqTestFiles(notes)
        val empty = files.create("コンフリクト確認用")
        fun line(color: Int, points: List<Pair<Float, Float>>): EditorStroke {
            val inputs = MutableStrokeInputBatch()
            points.forEachIndexed { i, p -> inputs.add(InputToolType.STYLUS, p.first, p.second, i * 20L) }
            return EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), color, 6f, 0.01f), inputs))
        }
        val base = listOf(
            line(0xff4285f4.toInt(), listOf(100f to 300f, 180f to 100f, 260f to 300f)),
            line(0xff4285f4.toInt(), listOf(130f to 230f, 230f to 230f)),
        )
        val payloads = base.associate { it.storageId to NoteRepository.encodeStroke(it) }.toMutableMap()
        val original = empty.copy(strokes = base.mapIndexed { i, stroke -> StrokeRef(i + 1L, stroke.storageId) }, nextId = 3)
        files.save(original) { payloads.getValue(it) }
        val additional = line(0xffea4335.toInt(), listOf(400f to 100f, 480f to 300f, 560f to 100f))
        payloads[additional.storageId] = NoteRepository.encodeStroke(additional)
        val version = original.copy(info = original.info.copy(updatedAt = System.currentTimeMillis()),
            strokes = original.strokes + StrokeRef(3, additional.storageId), nextId = 4)
        val stage = File(context.cacheDir, "conflict-fixture-manual-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val writer = MisqTestFiles(stage)
            writer.save(version) { payloads.getValue(it) }
            val alias = File(notes, "コンフリクト確認用-${original.info.id.take(8)}-別版.misq")
            writer.document(original.info.id).file.copyTo(alias)
            val conflict = NoteConflicts(notes, File(context.filesDir, "conflicts")).scan().single { it.noteId == original.info.id }
            assertEquals(3, conflict.strokes)
            assertEquals(2, files.read(original.info.id).manifest.strokes.size)
            println("Manual conflict fixture ready: note=${original.info.id} title=コンフリクト確認用 base=blue-A other=blue-A-and-red-V")
        } finally { stage.deleteRecursively() }
    }

    private fun fixture(root: File): Pair<NoteManifest, File> {
        val files = MisqTestFiles(File(root, "notes"))
        val original = files.create("fixture original")
        val stage = MisqTestFiles(File(root, "source"))
        val stroke = EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202124.toInt(), 3f, 0.01f),
            MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 100f, 0).add(InputToolType.STYLUS, 200f, 100f, 20)))
        val local = EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff4285f4.toInt(), 3f, 0.01f),
            MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 100f, 0).add(InputToolType.STYLUS, 200f, 200f, 20)))
        val localManifest = original.copy(strokes = listOf(StrokeRef(1, local.storageId)), nextId = 2)
        files.save(localManifest) { NoteRepository.encodeStroke(local) }
        stage.save(original.copy(strokes = listOf(StrokeRef(1, stroke.storageId)), nextId = 2)) { NoteRepository.encodeStroke(stroke) }
        return localManifest to stage.document(original.info.id).file
    }
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(50)
        }
        fail("Condition timed out")
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun click(label: String) {
        waitFor { find(label) != null }
        var node = find(label)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun readOnlyConflictScanNeverShowsBusyOverlayInLibraryOrUnrelatedNote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "conflict-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val (_, source) = fixture(root)
        val unrelated = MisqTestFiles(File(root, "notes")).create("unrelated")
        val busy = java.util.concurrent.atomic.AtomicInteger()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        lateinit var model: EditorViewModel
        try {
            ActivityScenario.launch<ConflictFixtureActivity>(Intent(instrumentation.targetContext, ConflictFixtureActivity::class.java).putExtra("fixture", prepareModernReview(root).path)).use { scenario ->
                scenario.onActivity { model = it.model }
                waitFor { !model.workspace.value.working && model.workspace.value.notes.size == 2 }
                SystemClock.sleep(300)
                scenario.onActivity {
                    scope.launch { model.workspace.collect { if (it.working) busy.incrementAndGet() } }
                }
                source.copyTo(File(root, "notes/another-version.misq"))
                scenario.onActivity { model.refreshConflicts() }
                waitFor { model.conflicts.value.items.size == 1 }
                assertEquals("Read-only library scan must not show the full-screen busy overlay", 0, busy.get())
                scenario.onActivity { model.openNote(unrelated.info.id) }
                waitFor { model.workspace.value.current?.id == unrelated.info.id && !model.workspace.value.working }
                busy.set(0)
                repeat(3) {
                    scenario.onActivity { model.refreshConflicts() }
                    SystemClock.sleep(300)
                }
                assertEquals("Read-only scans must not flash over an unrelated editor", 0, busy.get())
            }
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun oneSidedAppendIsImportedWithoutPromptAndLocalUndoKeepsRemoteInk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "conflict-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val (original, _) = fixture(root)
        val files = MisqTestFiles(File(root, "notes"))
        fun stroke(x: Float) = EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202124.toInt(), 3f, 0.01f),
            MutableStrokeInputBatch().add(InputToolType.STYLUS, x, 400f, 0).add(InputToolType.STYLUS, x + 100f, 500f, 20)))
        lateinit var model: EditorViewModel
        ActivityScenario.launch<ConflictFixtureActivity>(Intent(instrumentation.targetContext, ConflictFixtureActivity::class.java).putExtra("fixture", prepareModernReview(root).path)).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working }
            scenario.onActivity { model.openNote(original.info.id) }
            waitFor { model.workspace.value.current != null && !model.workspace.value.working }
            val local = stroke(300f)
            scenario.onActivity { model.edits.addAll(listOf(local)) }
            waitFor { files.read(original.info.id).manifest.strokes.size == 2 && !model.workspace.value.saving }
            val before = files.read(original.info.id).manifest
            val stage = File(root, "append").apply { mkdirs() }
            val remoteFile = File(stage, "${original.info.id}.misq")
            files.document(original.info.id).file.copyTo(remoteFile)
            val extra = stroke(700f)
            MisqFile(remoteFile).save(before.copy(strokes = before.strokes + StrokeRef(before.nextId, extra.storageId), nextId = before.nextId + 1)) {
                NoteRepository.encodeStroke(extra)
            }
            val alias = File(root, "notes/append-from-other-device.misq").also { remoteFile.copyTo(it) }
            val hash = ContentHash.digest(alias.readBytes())
            val checker = NoteConflicts(File(root, "notes"), File(root, "unhandled-check"))
            val conflict = checker.scan().single()
            scenario.onActivity { model.refreshConflicts() }
            waitFor { model.edits.entries.size == 3 && model.mergeReview.value.conflict == null && model.conflicts.value.items.isEmpty() }
            assertEquals(original.info.id, model.workspace.value.current!!.id)
            assertEquals(hash, ContentHash.digest(alias.readBytes()))
            scenario.onActivity { model.edits.undo() }
            assertEquals(setOf(original.strokes.single().storageId, extra.storageId), model.edits.entries.map { it.storageId }.toSet())
            waitFor { files.read(original.info.id).manifest.strokes.size == 2 && !model.workspace.value.saving }
            scenario.onActivity { model.refreshConflicts() }
            assertFalse(checker.canAutoMerge(conflict))
            assertEquals(2, model.edits.entries.size)
        }
    }

    @Test fun libraryImportsOneSidedAppendBeforeShowingConflictButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "conflict-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val files = MisqTestFiles(File(root, "notes")); val original = files.create("append fixture")
        val stage = MisqTestFiles(File(root, "source"))
        val stroke = EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202124.toInt(), 3f, 0.01f),
            MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 100f, 0).add(InputToolType.STYLUS, 200f, 200f, 20)))
        stage.save(original.copy(strokes = listOf(StrokeRef(1, stroke.storageId)), nextId = 2)) { NoteRepository.encodeStroke(stroke) }
        stage.document(original.info.id).file.copyTo(File(root, "notes/append.misq"))
        lateinit var model: EditorViewModel
        ActivityScenario.launch<ConflictFixtureActivity>(Intent(instrumentation.targetContext, ConflictFixtureActivity::class.java).putExtra("fixture", prepareModernReview(root).path)).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { files.read(original.info.id).manifest.strokes.size == 1 && !model.workspace.value.working }
            assertNull(model.workspace.value.current)
            assertTrue(model.conflicts.value.items.isEmpty())
            assertEquals(1, files.list().size)
        }
    }
    @Test fun buttonIsHiddenUntilConflictThenOpensIndependentNoteToTheRightOfSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "conflict-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val (original, source) = fixture(root)
        val originalFile = File(root, "notes/${original.info.id}.misq")
        lateinit var model: EditorViewModel
        ActivityScenario.launch<ConflictFixtureActivity>(Intent(instrumentation.targetContext, ConflictFixtureActivity::class.java).putExtra("fixture", prepareModernReview(root).path)).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.workspace.value.notes.size == 1 }
            val conflictsLabel = AppMessages.text(R.string.conflicts)
            assertNull(find(conflictsLabel))
            scenario.onActivity { model.openNote(original.info.id) }
            waitFor { model.workspace.value.current != null && !model.workspace.value.working }
            val alias = File(root, "notes/another tool chose this name.misq")
            source.copyTo(alias)
            val aliasHash = ContentHash.digest(alias.readBytes())
            scenario.onActivity { model.refreshConflicts() }
            waitFor { find(conflictsLabel) != null }
            val settingsBounds = android.graphics.Rect(); val conflictBounds = android.graphics.Rect()
            find(AppMessages.text(R.string.note_settings))!!.getBoundsInScreen(settingsBounds)
            find(conflictsLabel)!!.getBoundsInScreen(conflictBounds)
            assertTrue("Conflict button follows the upper-left settings button", conflictBounds.left >= settingsBounds.right)
            click(conflictsLabel)
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), "conflicts-fixture.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
            val copyIcon = find(AppMessages.text(R.string.open_conflict_copy))!!
            val copyBounds = android.graphics.Rect(); val filenameBounds = android.graphics.Rect(); val mergeBounds = android.graphics.Rect()
            copyIcon.getBoundsInScreen(copyBounds)
            find(alias.name)!!.getBoundsInScreen(filenameBounds)
            find(AppMessages.text(R.string.merge_review))!!.getBoundsInScreen(mergeBounds)
            assertTrue("Open separate note icon follows its version", copyBounds.centerX() > filenameBounds.centerX())
            assertTrue("Merge action is above the version rows", mergeBounds.bottom <= copyBounds.top)
            assertTrue(copyIcon.text.isNullOrEmpty())
            click(AppMessages.text(R.string.open_conflict_copy))
            waitFor { model.workspace.value.current?.id != original.info.id && !model.workspace.value.working }
            assertTrue(model.conflicts.value.items.isEmpty())
            assertNull(find(conflictsLabel))
            assertEquals(1, model.edits.entries.size)
            // The ordinary editor may update viewport/thumbnail metadata while showing a note.
            val retained = MisqFile(originalFile).readManifest().manifest
            assertEquals(original.info.id, retained.info.id)
            assertEquals(original.info.title, retained.info.title)
            assertEquals(original.info.paper, retained.info.paper)
            assertEquals(original.strokes, retained.strokes)
            assertEquals(aliasHash, ContentHash.digest(alias.readBytes()))
        }
    }
    @Test fun editorHidesOtherNotesConflictsWhileLibraryStillShowsAllConflicts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "conflict-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val (original, source) = fixture(root)
        val files = MisqTestFiles(File(root, "notes"))
        val unrelated = files.create("unrelated note")
        source.copyTo(File(root, "notes/another version.misq"))
        lateinit var model: EditorViewModel
        ActivityScenario.launch<ConflictFixtureActivity>(Intent(instrumentation.targetContext, ConflictFixtureActivity::class.java).putExtra("fixture", prepareModernReview(root).path)).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.size == 1 }
            val label = AppMessages.text(R.string.conflicts)
            waitFor { find(label) != null }
            scenario.onActivity { model.openNote(unrelated.info.id) }
            waitFor { model.workspace.value.current?.id == unrelated.info.id && !model.workspace.value.working }
            waitFor { find(AppMessages.text(R.string.note_settings)) != null }
            instrumentation.uiAutomation.waitForIdle(100, 2000)
            waitFor { find(label) == null }
            assertEquals(1, model.conflicts.value.items.size)
            scenario.onActivity { model.openNote(original.info.id) }
            waitFor { model.workspace.value.current?.id == original.info.id && !model.workspace.value.working }
            waitFor { find(label) != null }
            scenario.onActivity { model.closeNote() }
            waitFor { model.workspace.value.current == null && !model.workspace.value.working }
            waitFor { find(AppMessages.text(R.string.notes)) != null }
            waitFor { find(label) != null }
        }
    }
}
