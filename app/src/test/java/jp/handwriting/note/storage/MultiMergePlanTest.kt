package jp.handwriting.note.storage

import java.io.File
import java.util.UUID
import jp.handwriting.note.editor.StrokeIndex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MultiMergePlanTest {
    @get:Rule val temp = TemporaryFolder()
    private val payloads = mutableMapOf<String, ByteArray>()
    private fun ref(id: Long) = StrokeRef(id, UUID.randomUUID().toString()).also { payloads[it.storageId] = it.storageId.toByteArray() }
    private fun branches(): List<MisqFile> {
        val files = MisqTestFiles(temp.newFolder()); val empty = files.create("note"); val common = ref(1)
        files.save(empty.copy(strokes = listOf(common), nextId = 10)) { payloads.getValue(it) }
        return List(3) { MisqFile(File(temp.newFolder(), "${empty.info.id}.misq").also { files.document(empty.info.id).file.copyTo(it) }) }
    }
    private fun plan(files: List<MisqFile>) = MultiMergePlan.build(files.map { it.index() }) { StrokeIndex.Bounds(100f, 100f, 200f, 200f) }
    private fun append(file: MisqFile) {
        val note = file.readManifest().manifest
        file.save(note.copy(strokes = note.strokes + ref(10), nextId = 11)) { payloads.getValue(it) }
    }
    @Test fun threeOverlappingVersionsAllowSelectingAnySubsetAndRequireConfirmation() {
        val files = branches(); files.forEach(::append); val plan = plan(files)
        val step = plan.steps(MergeStrength.CAUTIOUS).single()
        assertTrue(step.suggested.isEmpty()); assertTrue(step.accepts(setOf(1, 2)))
        assertFalse(plan.complete(MergeStrength.CAUTIOUS, emptyMap()))
        assertThrows(IllegalStateException::class.java) { plan.result(MergeStrength.CAUTIOUS, emptyMap()) }
        val result = plan.result(MergeStrength.CAUTIOUS, mapOf(step.key to setOf(1, 2)))
        assertEquals(3, result.size)
        assertEquals(setOf(1, 2), result.filter { it.entity != plan.unchanged.single().entity }.map { it.source }.toSet())
        assertEquals(setOf(0, 1, 2), plan.steps(MergeStrength.AGGRESSIVE).single().suggested)
    }
    @Test fun deletingAgainstMovingRequiresExactlyOneVersion() {
        val files = branches(); val old = files[0].readManifest().manifest
        files[0].save(old.copy(strokes = emptyList())) { error("empty") }
        files[1].save(old.copy(strokes = listOf(ref(1)))) { payloads.getValue(it) }
        val plan = plan(files)
        for (strength in MergeStrength.entries) {
            val step = plan.steps(strength).single()
            assertFalse(step.accepts(setOf(0, 1))); assertTrue(step.accepts(setOf(0)))
            assertThrows(IllegalStateException::class.java) { plan.result(strength, mapOf(step.key to setOf(0, 1))) }
            assertTrue(plan.result(strength, mapOf(step.key to setOf(0))).isEmpty())
            assertEquals(1, plan.result(strength, mapOf(step.key to setOf(1))).size)
        }
    }
    @Test fun identicalEditsAreNotStepsAndCombiningMatchingVersionsDoesNotDuplicateStrokes() {
        val files = branches(); val old = files[0].readManifest().manifest
        val changed = ref(1)
        files.forEach { it.save(old.copy(strokes = listOf(changed))) { payloads.getValue(it) } }
        assertTrue(plan(files).steps(MergeStrength.MANUAL).isEmpty())
        append(files[0])
        val plan = plan(files); val step = plan.steps(MergeStrength.CAUTIOUS).single()
        assertEquals(1, plan.unchanged.size)
        assertEquals(2, plan.result(MergeStrength.CAUTIOUS, mapOf(step.key to setOf(0, 1, 2))).size)
    }
    @Test fun metadataNeedsOneSourceAndCannotBeSilentlyConfirmed() {
        val files = branches()
        files[1].save(files[1].readManifest().manifest.let { it.copy(info = it.info.copy(title = "remote")) }) { payloads.getValue(it) }
        val plan = plan(files); val step = plan.steps(MergeStrength.AGGRESSIVE).single()
        assertFalse(step.accepts(setOf(0, 1)))
        assertEquals("remote", plan.title(MergeStrength.AGGRESSIVE, mapOf(step.key to setOf(1))))
        assertFalse(plan.complete(MergeStrength.AGGRESSIVE, mapOf(step.key to setOf(9))))
    }
    @Test fun unknownSharedHistoryDoesNotTurnDifferentEditsOfOneEntityIntoAutomaticAdditions() {
        val files = branches(); val original = files[0].readManifest().manifest
        for (file in files.drop(1)) {
            file.file.delete()
            MisqFile(file.file).save(original) { payloads.getValue(it) }
        }
        files.forEach { old ->
            val file = MisqFile(old.file)
            file.save(original.copy(strokes = listOf(ref(1)))) { payloads.getValue(it) }
        }
        val plan = plan(files.map { MisqFile(it.file) })
        assertTrue(plan.steps(MergeStrength.AGGRESSIVE).single().suggested.isEmpty())
    }
}
