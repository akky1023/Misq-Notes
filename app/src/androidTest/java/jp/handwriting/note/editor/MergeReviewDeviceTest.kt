package jp.handwriting.note.editor

import android.content.Intent
import android.os.SystemClock
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
import org.junit.Test

class MergeReviewDeviceTest {
    private var previousStrength: String? = null
    @org.junit.Before fun useCautiousStrength() {
        val preferences = instrumentation.targetContext.getSharedPreferences("merge-review", 0)
        previousStrength = preferences.getString("strength", null)
        preferences.edit().putString("strength", MergeStrength.CAUTIOUS.name).commit()
    }
    @org.junit.After fun restoreStrength() {
        instrumentation.targetContext.getSharedPreferences("merge-review", 0).edit().apply {
            if (previousStrength == null) remove("strength") else putString("strength", previousStrength)
        }.commit()
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun ink(x: Float, y: Float, color: Int): EditorStroke = EditorStroke(Stroke(
        Brush.createWithColorIntArgb(StockBrushes.marker(), color, 5f, 0.01f), MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, x, y, 0).add(InputToolType.STYLUS, x + 100f, y + 100f, 20)))
    private data class Fixture(val root: File, val a: File, val b: File, val original: NoteManifest, val conflict: NoteConflict)
    private fun fixture(deleteAgainstMove: Boolean = false, independentHistory: Boolean = false, multiple: Boolean = false, fourth: Boolean = false, more: Boolean = false, metadata: Boolean = false, metadataOnly: Boolean = false, distant: Boolean = false): Fixture {
        val root = File(instrumentation.targetContext.cacheDir, "conflict-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val files = NoteFiles(File(root, "notes")); val empty = files.create("merge fixture")
        val common = ink(100f, 100f, 0xff202124.toInt())
        val payloads = mutableMapOf(common.storageId to NoteRepository.encodeStroke(common))
        val base = empty.copy(strokes = listOf(StrokeRef(1, common.storageId)), nextId = 2)
        files.save(base) { payloads.getValue(it) }
        val source = File(root, "source").apply { mkdirs() }
        val other = File(source, "${base.info.id}.misq").also {
            if (independentHistory) MisqFile(it).save(base) { id -> payloads.getValue(id) }
            else files.document(base.info.id).file.copyTo(it)
        }
        val left = ink(100f, if (distant) 2600f else 400f, 0xff4285f4.toInt()); val right = ink(if (deleteAgainstMove) 200f else if (multiple) 100f else 600f, 400f, 0xffea4335.toInt())
        payloads[left.storageId] = NoteRepository.encodeStroke(left); payloads[right.storageId] = NoteRepository.encodeStroke(right)
        var a = base.copy(strokes = if (deleteAgainstMove) emptyList() else base.strokes + StrokeRef(2, left.storageId), nextId = if (deleteAgainstMove) 2 else 3)
        var b = base.copy(strokes = if (deleteAgainstMove) listOf(StrokeRef(1, right.storageId)) else base.strokes + StrokeRef(2, right.storageId), nextId = if (deleteAgainstMove) 2 else 3)
        if (multiple) for (number in 2..if (more) 4 else if (fourth) 3 else 2) {
            val extraDir = File(root, "extra-$number").apply { mkdirs() }
            val extra = MisqFile(File(extraDir, other.name).also { other.copyTo(it) })
            val stroke = ink(100f, 400f, when (number) { 2 -> 0xff109d58.toInt(); 3 -> 0xffff9800.toInt(); else -> 0xffa142f4.toInt() })
            payloads[stroke.storageId] = NoteRepository.encodeStroke(stroke)
            extra.save(base.copy(strokes = base.strokes + StrokeRef(2, stroke.storageId), nextId = 3)) { payloads.getValue(it) }
            extra.file.copyTo(File(root, "notes/third-device-copy-$number.misq"))
        }
        if (metadata) {
            a = a.copy(info = a.info.copy(title = "original title", paper = NotePaper(pattern = PaperPattern.RULED)))
            b = b.copy(info = b.info.copy(title = "remote title", paper = NotePaper(pattern = PaperPattern.GRID)))
            if (metadataOnly) { a = a.copy(strokes = base.strokes); b = b.copy(strokes = base.strokes) }
        }
        files.save(a) { payloads.getValue(it) }; MisqFile(other).save(b) { payloads.getValue(it) }
        val alias = File(root, "notes/another-device-copy.misq").also { other.copyTo(it) }
        val conflict = NoteConflicts(File(root, "notes"), File(root, "conflicts")).scan().first { it.file == alias }
        return Fixture(root, files.document(base.info.id).file, alias, a, conflict)
    }
    private fun waitFor(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(50) }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "merge-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        fail("Merge UI timed out")
    }
    private fun find(label: String, source: Int? = null, checkable: Boolean? = null): AccessibilityNodeInfo? {
        val paper = source?.let {
            val canvas = find(AppMessages.text(R.string.merge_canvas, it.toString())) ?: return null
            android.graphics.Rect().also(canvas::getBoundsInScreen)
        }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                val box = android.graphics.Rect().also(node::getBoundsInScreen)
                if ((paper == null || paper.contains(box.centerX(), box.centerY())) && (checkable == null || node.isCheckable == checkable)) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun click(label: String, source: Int? = null) {
        SystemClock.sleep(300)
        instrumentation.uiAutomation.waitForIdle(150, 3000)
        waitFor { find(label, source) != null }
        var node = find(label, source)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        instrumentation.uiAutomation.waitForIdle(150, 3000)
    }
    private fun paperGesture(source: Int, drag: Float = 0f, hold: Long = 0) {
        val label = AppMessages.text(R.string.merge_canvas, source.toString())
        waitFor { find(label)?.isClickable == true }
        val bounds = android.graphics.Rect().also { find(label)!!.getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, bounds.centerX().toFloat())
        SystemClock.sleep(50)
        if (hold > 0) SystemClock.sleep(hold)
        if (drag != 0f) for (i in 1..5) {
            SystemClock.sleep(20)
            send(android.view.MotionEvent.ACTION_MOVE, bounds.centerX() + drag * i / 5)
        }
        send(android.view.MotionEvent.ACTION_UP, bounds.centerX() + drag)
        instrumentation.waitForIdleSync()
    }
    private fun pinchPaper(source: Int) {
        val bounds = android.graphics.Rect().also { find(AppMessages.text(R.string.merge_canvas, source.toString()))!!.getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, count: Int, distance: Float) {
            val properties = Array(count) { i -> android.view.MotionEvent.PointerProperties().apply {
                id = i; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            } }
            val coords = Array(count) { i -> android.view.MotionEvent.PointerCoords().apply {
                x = bounds.centerX() + if (i == 0) -distance else distance
                y = bounds.centerY().toFloat(); pressure = 1f; size = 1f
            } }
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, 1, 25f)
        send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 25f)
        for (i in 1..6) { SystemClock.sleep(20); send(android.view.MotionEvent.ACTION_MOVE, 2, 25f + i * 8f) }
        send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 73f)
        send(android.view.MotionEvent.ACTION_UP, 1, 73f)
        instrumentation.waitForIdleSync()
    }
    private fun paperSelected(source: Int): Boolean = find(AppMessages.text(R.string.merge_canvas, source.toString()))
        ?.let { it.isSelected || it.isChecked } == true
    private fun scenario(fixture: Fixture) = ActivityScenario.launch<ConflictFixtureActivity>(
        Intent(instrumentation.targetContext, ConflictFixtureActivity::class.java).putExtra("fixture", fixture.root.path))
    @Test fun identicalInkWithIndependentHistoryNeedsNoDecisionAndIsSavedOnce() = kotlinx.coroutines.runBlocking {
        val fixture = fixture(independentHistory = true)
        val cache = File(fixture.root, "merge-reviews")
        val createdAt = fixture.original.info.createdAt
        val bHash = NotebookArchive.digest(fixture.b.readBytes())
        MergeReviewSession.prepare(fixture.conflict, fixture.a to fixture.b, cache).use { session ->
            assertEquals(1, session.plan.unchanged.size)
            assertEquals(2, session.plan.changes.size)
            val result = session.save(File(fixture.root, "notes"), MergeStrength.CAUTIOUS, emptyMap(), true, "merged")
            assertEquals(3, result.strokes.size)
            assertEquals(3, MisqFile(File(fixture.root, "notes/${result.info.id}.misq")).readManifest().manifest.strokes.size)
        }
        assertEquals(fixture.original.info.id, MisqFile(fixture.a).readManifest().manifest.info.id)
        assertEquals(createdAt, MisqFile(fixture.a).readManifest().manifest.info.createdAt)
        assertEquals(fixture.original, MisqFile(fixture.a).previous())
        assertEquals(1, NoteFiles(File(fixture.root, "notes")).list().size)
        assertEquals(bHash, NotebookArchive.digest(fixture.b.readBytes()))
    }
    @Test fun overwriteRecordsRejectedHeadsSoSyncCannotRestoreDeletedInk() = kotlinx.coroutines.runBlocking {
        val fixture = fixture(deleteAgainstMove = true)
        MergeReviewSession.prepare(fixture.conflict, fixture.a to fixture.b, File(fixture.root, "merge-reviews")).use { session ->
            val decisions = session.multiPlan.steps(MergeStrength.CAUTIOUS).associate { it.key to setOf(0) }
            val result = session.saveResolved(File(fixture.root, "notes"), MergeStrength.CAUTIOUS, decisions, fixture.original.info.title)
            assertTrue(result.strokes.isEmpty())
            val saved = MisqFile(fixture.a)
            assertEquals(fixture.original, saved.previous())
            val reimported = MisqFile.merge(null, saved, MisqFile(fixture.b, readOnlyAlias = true))
            assertTrue(reimported.manifest.strokes.isEmpty())
            val events = saved.index().events
            assertTrue(events.any { it.blob != null })
            assertTrue(events.groupBy { it.entity }.values.all { MisqFile.frontier(it).size == 1 })
        }
    }
    @Test fun thumbnailCacheUpdateDuringReviewDoesNotInvalidateUnchangedNote() = kotlinx.coroutines.runBlocking {
        val fixture = fixture()
        MergeReviewSession.prepare(fixture.conflict, fixture.a to fixture.b, File(fixture.root, "merge-reviews")).use { session ->
            MisqFile(fixture.a).saveThumbnail(byteArrayOf(1, 2, 3))
            val result = session.save(File(fixture.root, "notes"), MergeStrength.CAUTIOUS, emptyMap(), true, fixture.original.info.title)
            assertEquals(fixture.original.info.id, result.info.id)
            assertEquals(3, result.strokes.size)
        }
    }
    @Test fun changedOriginalCannotBeOverwrittenByStaleReview() = kotlinx.coroutines.runBlocking {
        val fixture = fixture()
        MergeReviewSession.prepare(fixture.conflict, fixture.a to fixture.b, File(fixture.root, "merge-reviews")).use { session ->
            val current = MisqFile(fixture.a)
            val edited = fixture.original.copy(info = fixture.original.info.copy(title = "edited during review"))
            current.save(edited) { error("No new payload") }
            val before = NotebookArchive.digest(fixture.a.readBytes())
            try {
                session.save(File(fixture.root, "notes"), MergeStrength.CAUTIOUS, emptyMap(), true, "merged")
                fail("A stale review must not overwrite the original")
            } catch (_: IllegalStateException) { }
            assertEquals(before, NotebookArchive.digest(fixture.a.readBytes()))
            assertEquals(edited, MisqFile(fixture.a).readManifest().manifest)
        }
    }
    @Test fun confirmAllStepsAndReviewBeforeFinalSave() {
        val fixture = fixture()
        val preferences = instrumentation.targetContext.getSharedPreferences("merge-review", 0)
        val previous = preferences.getString("strength", null)
        preferences.edit().putString("strength", MergeStrength.CAUTIOUS.name).commit()
        val bHash = NotebookArchive.digest(fixture.b.readBytes())
        lateinit var model: EditorViewModel
        try { scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.isNotEmpty() }
            click(AppMessages.text(R.string.conflicts)); click(AppMessages.text(R.string.merge_review))
            waitFor { model.mergeReview.value.session != null || model.mergeReview.value.error != null }
            assertNull(model.mergeReview.value.error)
            val session = model.mergeReview.value.session!!
            assertEquals(3, session.plan.result(MergeStrength.CAUTIOUS, emptyMap(), true).size)
            waitFor { find(AppMessages.text(R.string.merge_canvas, "0")) != null }
            for (label in listOf("変更箇所", "Changes")) assertNull(find(label))
            for (label in listOf("重ね合わせ", "Overlay")) assertNull(find(label))
            val steps = session.multiPlan.steps(MergeStrength.CAUTIOUS)
            assertEquals(2, steps.size)
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "merge-step-ui.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            steps.indices.forEach { index ->
                waitFor { find(AppMessages.text(R.string.merge_step_number, index + 1, steps.size)) != null }
                assertNull(model.workspace.value.current)
                if (index == 1) {
                    click(AppMessages.text(R.string.merge_previous))
                    waitFor { find(AppMessages.text(R.string.merge_step_number, 1, steps.size)) != null }
                    click(AppMessages.text(R.string.merge_confirm_step))
                    waitFor { find(AppMessages.text(R.string.merge_step_number, 2, steps.size)) != null }
                }
                click(AppMessages.text(R.string.merge_confirm_step))
            }
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            click(AppMessages.text(R.string.merge_back_to_steps))
            waitFor { find(AppMessages.text(R.string.merge_step_number, steps.size, steps.size)) != null }
            click(AppMessages.text(R.string.merge_confirm_step))
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            assertNull(model.workspace.value.current)
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
            try { File(instrumentation.targetContext.getExternalFilesDir(null), "merge-review.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
            click(AppMessages.text(R.string.merge_finalize))
            waitFor { model.mergeReview.value.conflict == null && model.workspace.value.current != null }
            assertEquals(fixture.original.info.id, model.workspace.value.current!!.id)
            assertEquals(3, model.edits.entries.size)
            assertEquals(fixture.original, MisqFile(fixture.a).previous())
            assertEquals(1, NoteFiles(File(fixture.root, "notes")).list().size)
            assertEquals(bHash, NotebookArchive.digest(fixture.b.readBytes()))
            assertTrue(model.conflicts.value.items.isEmpty())
        } } finally { preferences.edit().apply { if (previous == null) remove("strength") else putString("strength", previous) }.commit() }
    }
    @Test fun fourPapersSelectByTapWithCentralWatermarksAndLabelsAbove() {
        val fixture = fixture(multiple = true, fourth = true)
        lateinit var model: EditorViewModel
        scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.size == 3 }
            scenario.onActivity { model.beginMerge(model.conflicts.value.items.first()) }
            waitFor { model.mergeReview.value.session != null }
            assertEquals(4, model.mergeReview.value.session!!.multiPlan.indexes.size)
            waitFor { find(AppMessages.text(R.string.merge_canvas, "0"))?.isClickable == true }
            assertNull(find(AppMessages.text(R.string.merge_choose_versions)))
            for (source in 0..3) {
                val label = if (source == 0) AppMessages.text(R.string.merge_original_version) else model.mergeReview.value.session!!.sourceLabel(source)
                waitFor { find(AppMessages.text(R.string.merge_canvas, source.toString())) != null && find(label, source) != null }
                val paper = android.graphics.Rect(); val text = android.graphics.Rect()
                find(AppMessages.text(R.string.merge_canvas, source.toString()))!!.getBoundsInScreen(paper)
                find(label, source)!!.getBoundsInScreen(text)
                assertNull(find(AppMessages.text(R.string.merge_select_version, label), source))
                assertTrue("Timestamp is at the top of its paper", text.centerY() < paper.centerY())
                assertTrue(paper.contains(text.centerX(), text.centerY()))
            }
            val progress = android.graphics.Rect().also {
                find(AppMessages.text(R.string.merge_step_number, 1, 1))!!.getBoundsInScreen(it)
            }
            val paper = android.graphics.Rect().also { find(AppMessages.text(R.string.merge_canvas, "0"))!!.getBoundsInScreen(it) }
            assertTrue("Change count is at the bottom", progress.centerY() > paper.centerY())
            val next = android.graphics.Rect().also { find(AppMessages.text(R.string.merge_confirm_step))!!.getBoundsInScreen(it) }
            val previous = android.graphics.Rect().also { find(AppMessages.text(R.string.back))!!.getBoundsInScreen(it) }
            val nearby = 160f * instrumentation.targetContext.resources.displayMetrics.density
            assertTrue("Next is integrated to the right of the counter", next.centerX() > progress.centerX() && next.centerX() - progress.centerX() < nearby)
            assertTrue("Back is integrated to the left of the counter", previous.centerX() < progress.centerX() && progress.centerX() - previous.centerX() < nearby)
            assertTrue(kotlin.math.abs(next.centerY() - progress.centerY()) < 20)
            paperGesture(1); waitFor { paperSelected(1) }
            paperGesture(1); waitFor { !paperSelected(1) }
            paperGesture(1); waitFor { paperSelected(1) }
            paperGesture(1, drag = 80f); assertTrue("Dragging does not toggle selection", paperSelected(1))
            paperGesture(1, hold = 800); assertTrue("Long press does not toggle selection", paperSelected(1))
            pinchPaper(1); assertTrue("Pinch does not toggle selection", paperSelected(1))
            paperGesture(3); waitFor { paperSelected(3) }
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "merge-paper-tap-ui.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            click(AppMessages.text(R.string.merge_confirm_step))
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            assertNull(model.workspace.value.current)
            click(AppMessages.text(R.string.merge_finalize))
            waitFor { model.workspace.value.current != null && model.mergeReview.value.session == null }
            assertEquals(3, model.edits.entries.size)
            assertTrue(model.conflicts.value.items.isEmpty())
            assertEquals(fixture.original.info.id, model.workspace.value.current!!.id)
        }
    }
    @Test fun fourAliasesRequireChoosingThreeAndUnselectedAliasRemainsUnresolved() {
        val fixture = fixture(multiple = true, fourth = true, more = true)
        lateinit var model: EditorViewModel
        scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.size == 4 }
            val versions = model.conflicts.value.items.toList()
            click(AppMessages.text(R.string.conflicts))
            click(AppMessages.text(R.string.merge_review))
            waitFor { model.mergeReview.value.candidates.size == 4 }
            assertNull(model.mergeReview.value.session)
            val start = AppMessages.text(R.string.merge_start_comparison)
            waitFor { find(start) != null }
            scenario.onActivity { model.selectMergeCandidates(emptySet()) }
            assertNull(model.mergeReview.value.session)
            assertNotNull(find(AppMessages.text(R.string.merge_pick_count, 0)))
            val chosen = listOf(versions[0], versions[1], versions[3])
            chosen.forEach { click(AppMessages.text(R.string.merge_compare_version, it.filename)) }
            assertTrue(find(start)!!.isEnabled)
            click(start)
            waitFor { model.mergeReview.value.session != null }
            val session = model.mergeReview.value.session!!
            assertEquals(chosen.map { it.key }.toSet(), session.conflicts.map { it.key }.toSet())
            assertEquals(4, session.multiPlan.indexes.size)
            for (source in 1..3) click(AppMessages.text(R.string.merge_canvas, source.toString()))
            click(AppMessages.text(R.string.merge_confirm_step))
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            click(AppMessages.text(R.string.merge_finalize))
            waitFor { model.workspace.value.current != null && model.mergeReview.value.session == null }
            assertEquals(4, model.edits.entries.size)
            assertEquals(listOf(versions[2].key), model.conflicts.value.items.map { it.key })
        }
    }
    @Test fun selectingMultipleVersionsDoesNotImportOnlyTheFirstAppendableVersion() {
        val fixture = fixture(multiple = true)
        lateinit var model: EditorViewModel
        scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.size == 2 }
            val stage = File(fixture.root, "appendable").apply { mkdirs() }
            val file = File(stage, fixture.a.name).also { fixture.a.copyTo(it) }
            val extra = ink(700f, 600f, 0xffa142f4.toInt())
            val note = MisqFile(file).readManifest().manifest
            MisqFile(file).save(note.copy(strokes = note.strokes + StrokeRef(3, extra.storageId), nextId = 4)) {
                NoteRepository.encodeStroke(extra)
            }
            file.copyTo(fixture.b, overwrite = true)
            val store = NoteConflicts(File(fixture.root, "notes"), File(fixture.root, "check-mixed"))
            val candidates = store.scan().sortedBy { if (it.file == fixture.b) 0 else 1 }
            assertTrue(store.canAutoMerge(candidates.first()))
            scenario.onActivity { model.beginMerge(candidates.first(), candidates) }
            waitFor { model.mergeReview.value.session != null || model.mergeReview.value.error != null }
            assertNull(model.mergeReview.value.error)
            assertEquals(3, model.mergeReview.value.session!!.multiPlan.indexes.size)
            assertEquals(2, MisqFile(fixture.a).readManifest().manifest.strokes.size)
            scenario.onActivity { model.dismissMerge() }
        }
    }
    @Test fun titleAndPaperAreChosenSeparatelyBeforeStrokeOnlyStepsAndPersistInResult() {
        val fixture = fixture(metadata = true)
        lateinit var model: EditorViewModel
        scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.isNotEmpty() }
            scenario.onActivity { model.beginMerge(model.conflicts.value.items.first()) }
            waitFor { find(AppMessages.text(R.string.merge_metadata_review)) != null }
            assertNull(find(AppMessages.text(R.string.merge_canvas, "0")))
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "merge-metadata-screen.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            val original = AppMessages.text(R.string.merge_original_version)
            val remote = model.mergeReview.value.session!!.sourceLabel(1)
            val nameChoice = AppMessages.text(R.string.merge_metadata_name_choice, remote)
            fun choiceSelected(): Boolean {
                var node = find(nameChoice) ?: return false
                while (!node.isClickable && node.parent != null) node = node.parent
                node.refresh()
                return node.isSelected || node.isChecked
            }
            click(nameChoice)
            waitFor { choiceSelected() }
            click(nameChoice)
            waitFor { !choiceSelected() }
            click(nameChoice)
            click(AppMessages.text(R.string.merge_metadata_paper_choice, original))
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "merge-metadata-selection.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            click(AppMessages.text(R.string.merge_metadata_continue))
            waitFor { find(AppMessages.text(R.string.merge_step_number, 1, 2)) != null }
            assertNull(find(AppMessages.text(R.string.merge_metadata_review)))
            repeat(2) { click(AppMessages.text(R.string.merge_confirm_step)) }
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            click(AppMessages.text(R.string.merge_finalize))
            waitFor { model.workspace.value.current != null && model.mergeReview.value.session == null }
            assertEquals("remote title", model.workspace.value.current!!.title)
            assertEquals(PaperPattern.RULED, model.workspace.value.current!!.paper.pattern)
            assertEquals(3, model.edits.entries.size)
            assertEquals(fixture.original, MisqFile(fixture.a).previous())
        }
    }
    @Test fun metadataOnlyConflictGoesDirectlyToFinalPreview() {
        val fixture = fixture(metadata = true, metadataOnly = true)
        lateinit var model: EditorViewModel
        scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.isNotEmpty() }
            scenario.onActivity { model.beginMerge(model.conflicts.value.items.first()) }
            waitFor { find(AppMessages.text(R.string.merge_metadata_review)) != null }
            click(AppMessages.text(R.string.merge_metadata_name_choice, AppMessages.text(R.string.merge_original_version)))
            click(AppMessages.text(R.string.merge_metadata_paper_choice, model.mergeReview.value.session!!.sourceLabel(1)))
            click(AppMessages.text(R.string.merge_metadata_to_final))
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            assertNull(find(AppMessages.text(R.string.merge_confirm_step)))
            assertNull(model.workspace.value.current)
            click(AppMessages.text(R.string.back))
            waitFor { find(AppMessages.text(R.string.merge_metadata_review)) != null }
            click(AppMessages.text(R.string.merge_metadata_to_final))
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            click(AppMessages.text(R.string.merge_finalize))
            waitFor { model.workspace.value.current != null && model.mergeReview.value.session == null }
            assertEquals(PaperPattern.GRID, model.workspace.value.current!!.paper.pattern)
            assertEquals(1, model.edits.entries.size)
        }
    }
    @Test fun finalPreviewAddsEditorSidebarWithoutSavingAndFrameDragOnlyNavigatesReview() {
        val fixture = fixture(distant = true)
        val originalEvents = MisqFile(fixture.a).index().events
        val aliasHash = NotebookArchive.digest(fixture.b.readBytes())
        lateinit var model: EditorViewModel
        scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.isNotEmpty() }
            scenario.onActivity { model.beginMerge(model.conflicts.value.items.first()) }
            waitFor { model.mergeReview.value.session != null }
            repeat(model.mergeReview.value.session!!.multiPlan.steps(MergeStrength.CAUTIOUS).size) {
                click(AppMessages.text(R.string.merge_confirm_step))
            }
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null &&
                find(AppMessages.text(R.string.note_preview), checkable = false) != null }
            val main = android.graphics.Rect().also { find(AppMessages.text(R.string.merge_canvas, "result"))!!.getBoundsInScreen(it) }
            val mini = android.graphics.Rect().also { find(AppMessages.text(R.string.note_preview), checkable = false)!!.getBoundsInScreen(it) }
            assertTrue("Existing large paper remains beside the new preview", main.width() > mini.width() * 2)
            assertTrue(main.right <= mini.left || mini.right <= main.left)
            assertNull(model.workspace.value.current)
            val editorPosition = model.viewport.position
            SystemClock.sleep(700)
            val before = instrumentation.uiAutomation.takeScreenshot()!!
            try {
                val down = SystemClock.uptimeMillis()
                val frameHeight = 1000f * main.height() / main.width()
                val x = mini.centerX().toFloat()
                val y = mini.top + frameHeight * mini.width() / 2000f
                for (i in 0..12) {
                    val action = when (i) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y + 400f * i / 12f, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    SystemClock.sleep(20)
                }
                SystemClock.sleep(700)
                val after = instrumentation.uiAutomation.takeScreenshot()!!
                val oldPaper = android.graphics.Bitmap.createBitmap(before, main.left, main.top, main.width(), main.height())
                val newPaper = android.graphics.Bitmap.createBitmap(after, main.left, main.top, main.width(), main.height())
                try { assertFalse("Dragging the preview frame moves the reviewed paper", oldPaper.sameAs(newPaper)) }
                finally { oldPaper.recycle(); newPaper.recycle(); after.recycle() }
            } finally { before.recycle() }
            assertEquals(editorPosition, model.viewport.position)
            assertEquals(fixture.original, MisqFile(fixture.a).readManifest().manifest)
            assertEquals(originalEvents, MisqFile(fixture.a).index().events)
            assertEquals(aliasHash, NotebookArchive.digest(fixture.b.readBytes()))
            click(AppMessages.text(R.string.preview_star_filter))
            waitFor { find(AppMessages.text(R.string.preview_no_stars)) != null }
            click(AppMessages.text(R.string.preview_star_filter))
            find(AppMessages.text(R.string.note_preview), checkable = true)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitFor { find(AppMessages.text(R.string.note_preview), checkable = false) == null }
            find(AppMessages.text(R.string.note_preview), checkable = true)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitFor { find(AppMessages.text(R.string.note_preview), checkable = false) != null }
            SystemClock.sleep(400)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "merge-final-sidebar.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            click(AppMessages.text(R.string.merge_finalize))
            waitFor { model.workspace.value.current != null && model.mergeReview.value.session == null }
            assertEquals(fixture.original.info.id, model.workspace.value.current!!.id)
            assertEquals(3, model.edits.entries.size)
        }
    }
    @Test fun activeChangesHaveGreenRedAndBlueMarksAndFinalPreviewIsClean() = kotlinx.coroutines.runBlocking {
        val fixture = fixture(deleteAgainstMove = true)
        MergeReviewSession.prepare(fixture.conflict, fixture.a to fixture.b, File(fixture.root, "render-reviews")).use { session ->
            val step = session.multiPlan.steps(MergeStrength.CAUTIOUS).single()
            fun count(bitmap: android.graphics.Bitmap, color: Int): Int {
                var count = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val p = bitmap.getPixel(x, y)
                    if (kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(color)) < 10 &&
                        kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(color)) < 10 &&
                        kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(color)) < 10) count++
                }
                return count
            }
            val marks = session.renderStep(1000, 1000, MergeViewport(), 1, MergeStrength.CAUTIOUS, emptyMap(), step)
            val final = session.renderStep(1000, 1000, MergeViewport(), null, MergeStrength.CAUTIOUS, mapOf(step.key to setOf(1)), null)
            try {
                val background = marks.getPixel(150, 300)
                assertTrue(android.graphics.Color.blue(background) > android.graphics.Color.red(background))
                fun pastel(color: Int) = androidx.core.graphics.ColorUtils.compositeColors((color and 0xffffff) or (115 shl 24), background)
                val green = pastel(android.graphics.Color.rgb(30, 165, 75))
                val red = pastel(android.graphics.Color.rgb(210, 45, 45))
                assertTrue(count(marks, green) > 10)
                assertTrue(count(marks, red) > 10)
                assertEquals(0, count(final, green))
                assertEquals(0, count(final, red))
                var inkPixels = 0
                for (y in 0 until final.height) for (x in 0 until final.width) {
                    if (final.getPixel(x, y) == 0xffea4335.toInt()) {
                        inkPixels++
                        assertEquals("Original stroke color at $x,$y", final.getPixel(x, y), marks.getPixel(x, y))
                    }
                }
                assertTrue(inkPixels > 100)
                File(instrumentation.targetContext.getExternalFilesDir(null), "merge-step-marks.png").outputStream().use { marks.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { marks.recycle(); final.recycle() }
            Unit
        }
    }
    @Test fun aggressiveStillRequiresChoiceForDeletionAgainstMoveAndCancelKeepsData() {
        val fixture = fixture(deleteAgainstMove = true)
        lateinit var model: EditorViewModel
        scenario(fixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.isNotEmpty() }
            scenario.onActivity { model.beginMerge(model.conflicts.value.items.single()) }
            waitFor { model.mergeReview.value.session != null || model.mergeReview.value.error != null }
            assertNull(model.mergeReview.value.error)
            val session = model.mergeReview.value.session!!
            assertNull(session.plan.groups(MergeStrength.AGGRESSIVE).single().suggested)
            assertFalse(session.plan.groups(MergeStrength.AGGRESSIVE).single().bothAllowed)
            scenario.onActivity { model.saveResolvedMerge(MergeStrength.AGGRESSIVE, emptyMap()) }
            assertNull(model.workspace.value.current)
            scenario.onActivity { model.saveResolvedMerge(MergeStrength.CAUTIOUS, emptyMap()) }
            assertNull(model.workspace.value.current)
            click(AppMessages.text(R.string.merge_canvas, "1"))
            click(AppMessages.text(R.string.merge_confirm_step))
            waitFor { find(AppMessages.text(R.string.merge_final_review)) != null }
            click(AppMessages.text(R.string.merge_finalize))
            waitFor { model.workspace.value.current != null && model.mergeReview.value.session == null }
            assertEquals(1, model.edits.entries.size)
            assertEquals(200f, model.edits.entries.single().ink.inputs[0].x, 0f)
            assertEquals(1, MisqFile(fixture.a).readManifest().manifest.strokes.size)
            assertEquals(fixture.original, MisqFile(fixture.a).previous())
        }
        val cancelFixture = fixture()
        val originalHash = NotebookArchive.digest(cancelFixture.b.readBytes())
        scenario(cancelFixture).use { scenario ->
            scenario.onActivity { model = it.model }
            waitFor { !model.workspace.value.working && model.conflicts.value.items.isNotEmpty() }
            scenario.onActivity { model.beginMerge(model.conflicts.value.items.single()) }
            waitFor { model.mergeReview.value.session != null }
            scenario.onActivity { model.dismissMerge() }
            waitFor { File(cancelFixture.root, "merge-reviews").listFiles().orEmpty().isEmpty() }
            assertEquals(1, NoteFiles(File(cancelFixture.root, "notes")).list().size)
            assertEquals(originalHash, NotebookArchive.digest(cancelFixture.b.readBytes()))
        }
    }
}
