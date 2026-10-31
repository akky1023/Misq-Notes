package jp.handwriting.note.storage

import jp.handwriting.note.editor.StrokeIndex
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StrokeMergePlanTest {
    @get:Rule val temp = TemporaryFolder()
    private val payloads = mutableMapOf<String, ByteArray>()
    private val boxes = mutableMapOf<String, StrokeIndex.Bounds>()
    private var counter = 0
    private fun ref(id: Long, x: Float = 0f, y: Float = 0f): StrokeRef {
        val ref = StrokeRef(id, UUID.randomUUID().toString())
        val bytes = "payload-${++counter}".toByteArray()
        payloads[ref.storageId] = bytes
        boxes[NotebookArchive.digest(bytes)] = StrokeIndex.Bounds(x, y, x + 10, y + 10)
        return ref
    }
    private fun base(refs: List<StrokeRef> = emptyList()): MisqFile {
        val files = NoteFiles(temp.newFolder())
        val empty = files.create("note")
        files.save(empty.copy(strokes = refs, nextId = (refs.maxOfOrNull { it.id } ?: 0) + 1)) { payloads.getValue(it) }
        return files.document(empty.info.id)
    }
    private fun branch(base: MisqFile) = MisqFile(File(temp.newFolder(), base.file.name).also { base.file.copyTo(it) })
    private fun save(file: MisqFile, refs: List<StrokeRef>) {
        file.save(file.readManifest().manifest.copy(strokes = refs, nextId = (refs.maxOfOrNull { it.id } ?: 0) + 1)) { payloads.getValue(it) }
    }
    private fun plan(a: MisqFile, b: MisqFile) = StrokeMergePlan.build(a.index(), b.index()) { boxes[it.hash] }

    @Test fun independentAdditionsFarApartMergeAndManualModeRequiresReview() {
        val common = ref(1); val base = base(listOf(common)); val a = branch(base); val b = branch(base)
        val x = ref(2, 100f); val y = ref(2, 500f)
        save(a, listOf(common, x)); save(b, listOf(common, y))
        val plan = plan(a, b)
        assertEquals(2, plan.changes.size)
        assertEquals(setOf(common.storageId, x.storageId, y.storageId), plan.result(MergeStrength.CAUTIOUS, emptyMap(), true).map { it.blob }.toSet())
        assertTrue(plan.decisions(MergeStrength.MANUAL, emptyMap()).values.all { it == null })
        assertThrows(IllegalStateException::class.java) { plan.result(MergeStrength.MANUAL, emptyMap(), true) }
    }
    @Test fun overlappingIndependentAdditionsNeedReviewUnlessAggressiveAndLayerOrderIsExplicit() {
        val base = base(); val a = branch(base); val b = branch(base)
        val x = ref(1, 100f); val y = ref(1, 105f)
        save(a, listOf(x)); save(b, listOf(y))
        val plan = plan(a, b)
        assertNull(plan.groups(MergeStrength.CAUTIOUS).single().suggested)
        assertNull(plan.groups(MergeStrength.STANDARD).single().suggested)
        assertEquals(listOf(y.storageId, x.storageId), plan.result(MergeStrength.AGGRESSIVE, emptyMap(), true).map { it.blob })
        assertEquals(listOf(x.storageId, y.storageId), plan.result(MergeStrength.AGGRESSIVE, emptyMap(), false).map { it.blob })
    }
    @Test fun cautiousKeepsMoreDistanceThanStandard() {
        val base = base(); val a = branch(base); val b = branch(base)
        save(a, listOf(ref(1, 100f))); save(b, listOf(ref(1, 120f)))
        val plan = plan(a, b)
        assertTrue(plan.decisions(MergeStrength.CAUTIOUS, emptyMap()).values.any { it == null })
        assertTrue(plan.decisions(MergeStrength.STANDARD, emptyMap()).values.all { it != null })
    }
    @Test fun deleteAndDistantAdditionDoNotResurrectDeletedStroke() {
        val old = ref(1); val base = base(listOf(old)); val a = branch(base); val b = branch(base)
        val added = ref(2, 500f)
        save(a, emptyList()); save(b, listOf(old, added))
        val plan = plan(a, b)
        assertEquals(listOf(added.storageId), plan.result(MergeStrength.CAUTIOUS, emptyMap(), true).map { it.blob })
    }
    @Test fun deleteAndNearbyAdditionRequireReviewEvenWhenAggressive() {
        val old = ref(1); val base = base(listOf(old)); val a = branch(base); val b = branch(base)
        save(a, emptyList()); save(b, listOf(old, ref(2, 2f)))
        val group = plan(a, b).groups(MergeStrength.AGGRESSIVE).single()
        assertTrue(group.overlaps); assertNull(group.suggested); assertFalse(group.bothAllowed)
    }
    @Test fun movingAndDeletingSameStrokeAlwaysRequireExplicitChoice() {
        val old = ref(1); val base = base(listOf(old)); val a = branch(base); val b = branch(base)
        val moved = ref(1, 200f)
        save(a, listOf(moved)); save(b, emptyList())
        val plan = plan(a, b); val key = plan.changes.single().key
        MergeStrength.entries.forEach { assertNull(plan.groups(it).single().suggested) }
        assertEquals(listOf(moved.storageId), plan.result(MergeStrength.CAUTIOUS, mapOf(key to MergeChoice.A), true).map { it.blob })
        assertTrue(plan.result(MergeStrength.CAUTIOUS, mapOf(key to MergeChoice.B), true).isEmpty())
        assertThrows(IllegalStateException::class.java) { plan.result(MergeStrength.CAUTIOUS, mapOf(key to MergeChoice.BOTH), true) }
    }
    @Test fun equalEditsAreDeduplicatedByContentHashRatherThanPayloadIdentity() {
        val old = ref(1); val base = base(listOf(old)); val a = branch(base); val b = branch(base)
        val x = ref(1, 100f); val y = ref(1, 100f)
        payloads[y.storageId] = payloads.getValue(x.storageId)
        save(a, listOf(x)); save(b, listOf(y))
        val plan = plan(a, b)
        assertTrue(plan.changes.isEmpty())
        MergeStrength.entries.forEach { assertEquals(1, plan.result(it, emptyMap(), true).size) }
    }
    @Test fun identicalContentsWithoutCommonHistoryAreUnchangedEvenInManualMode() {
        val first = ref(1); val second = ref(2, 2f)
        val a = base(listOf(first, second))
        val b = MisqFile(File(temp.newFolder(), a.file.name))
        b.save(a.readManifest().manifest) { payloads.getValue(it) }
        assertTrue(a.index().events.map { it.version }.intersect(b.index().events.map { it.version }.toSet()).isEmpty())
        val same = plan(a, b)
        assertTrue(same.changes.isEmpty())
        MergeStrength.entries.forEach { assertEquals(2, same.result(it, emptyMap(), true).size) }

        val added = ref(3, 1f)
        save(b, listOf(first, second, added))
        val changed = plan(a, b)
        assertEquals(2, changed.unchanged.size)
        assertEquals(1, changed.changes.size)
        assertEquals(3, changed.result(MergeStrength.CAUTIOUS, emptyMap(), true).size)
        assertEquals(1, changed.decisions(MergeStrength.MANUAL, emptyMap()).values.count { it == null })
    }
    @Test fun ancestryWinsOverClockSkewAndSameDeletionIsSafe() {
        val old = ref(1); val base = base(listOf(old)); val a = branch(base); val b = branch(base)
        val moved = ref(1, 200f)
        save(a, listOf(moved)); a.preserveEventTimes(mapOf(a.index().entities.getValue(1) to 1L))
        assertEquals(listOf(moved.storageId), plan(a, b).result(MergeStrength.STANDARD, emptyMap(), true).map { it.blob })
        save(a, emptyList()); save(b, emptyList())
        assertTrue(plan(a, b).result(MergeStrength.CAUTIOUS, emptyMap(), true).isEmpty())
    }
    @Test fun metadataChoicesAndMissingGeometryAreNeverSilentlyResolved() {
        val base = base(); val a = branch(base); val b = branch(base)
        a.save(a.readManifest().manifest.copy(info = a.readManifest().manifest.info.copy(title = "A"))) { error("empty") }
        b.save(b.readManifest().manifest.copy(info = b.readManifest().manifest.info.copy(title = "B", paper = NotePaper(color = 0xffeeeeee.toInt())))) { error("empty") }
        val plan = plan(a, b)
        assertTrue(plan.decisions(MergeStrength.AGGRESSIVE, emptyMap()).values.all { it == null })
        val decisions = mapOf("@title" to MergeChoice.B, "@paper" to MergeChoice.B)
        assertEquals("B", plan.title(decisions)); assertEquals(b.index().manifest.info.paper, plan.paper(decisions))
        assertThrows(IllegalStateException::class.java) { plan.result(MergeStrength.AGGRESSIVE, mapOf("@title" to MergeChoice.BOTH, "@paper" to MergeChoice.B), true) }
        save(a, listOf(ref(1)))
        val unknown = StrokeMergePlan.build(a.index(), b.index()) { null }
        assertTrue(unknown.changes.any { it.uncertain })
        assertTrue(unknown.decisions(MergeStrength.AGGRESSIVE, emptyMap()).values.any { it == null })
    }
}
