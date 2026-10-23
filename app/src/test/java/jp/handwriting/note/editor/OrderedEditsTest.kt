package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class OrderedEditsTest {
    @Test fun pastedGroupPreservesOrderAndIsOneUndoStep() {
        val doc = OrderedEdits<String>()
        doc.addAll(listOf("existing"))
        val ids = doc.addAll(listOf("copied A", "copied B"))
        assertEquals(listOf(2L, 3L), ids)
        assertEquals(listOf("existing", "copied A", "copied B"), doc.entries.toList())
        doc.undo(); assertEquals(listOf("existing"), doc.entries.toList())
        doc.redo(); assertEquals(listOf("existing", "copied A", "copied B"), doc.entries.toList())
        val revision = doc.revision
        assertTrue(doc.addAll(emptyList()).isEmpty()); assertEquals(revision, doc.revision)
    }
    private fun OrderedEdits<String>.add(value: String) = reserveStroke().also { resolveStroke(it, value) }
    @Test fun lassoMoveKeepsDrawingOrderAndUndoesAllSelectedStrokesTogether() {
        val doc = OrderedEdits<String>()
        val a = doc.add("A"); doc.add("B"); val c = doc.add("C")
        val next = doc.nextEntryId
        doc.replace(mapOf(c to "C moved", a to "A moved"))
        assertEquals(next, doc.nextEntryId)
        assertEquals(listOf("A moved", "B", "C moved"), doc.entries.toList())
        doc.undo(); assertEquals(listOf("A", "B", "C"), doc.entries.toList())
        doc.redo(); assertEquals(listOf("A moved", "B", "C moved"), doc.entries.toList())
        doc.delete(setOf(a, c)); assertEquals(listOf("B"), doc.entries.toList())
        doc.undo(); assertEquals(listOf("A moved", "B", "C moved"), doc.entries.toList())
    }
    @Test fun replacementsRefreshIndexInBothDirectionsAndDoNotChangeHistoryForNoOps() {
        val doc = OrderedEdits<String>(); val index = mutableMapOf<Long, String>()
        doc.onContentChange = { added, removed -> removed.forEach { index.remove(it.id) }; added.forEach { index[it.id] = it.value } }
        val a = doc.add("A")
        doc.replace(mapOf(a to "moved")); assertEquals(mapOf(a to "moved"), index)
        doc.undo(); assertEquals(mapOf(a to "A"), index)
        doc.replace(mapOf(a to "A")); doc.delete(emptySet()); assertTrue(doc.canRedo)
        doc.redo(); assertEquals(mapOf(a to "moved"), index)
    }
    @Test fun movesCannotOvertakePendingInkOrReferToMissingStrokes() {
        val doc = OrderedEdits<String>(); val a = doc.add("A"); val pending = doc.reserveStroke()
        assertThrows(IllegalStateException::class.java) { doc.replace(mapOf(a to "moved")) }
        assertThrows(IllegalStateException::class.java) { doc.delete(setOf(a)) }
        doc.cancelStroke(pending)
        assertThrows(IllegalArgumentException::class.java) { doc.replace(mapOf(999L to "missing")) }
        assertEquals(listOf("A"), doc.entries.toList())
    }

    @Test fun delayedInkCallbacksKeepStrokeOrder() {
        val doc = OrderedEdits<String>()
        val a = doc.reserveStroke()
        val b = doc.reserveStroke()
        doc.resolveStroke(b, "B")
        assertTrue(doc.entries.isEmpty())
        assertFalse(doc.canUndo)
        doc.resolveStroke(a, "A")
        assertEquals(listOf("A", "B"), doc.entries.toList())
        doc.undo()
        assertEquals(listOf("A"), doc.entries.toList())
    }
    @Test fun eraserWaitsForPendingInkAndIsOneUndoOperation() {
        val doc = OrderedEdits<String>()
        doc.add("A")
        val pending = doc.reserveStroke()
        val eraser = doc.beginErase()
        doc.erase(eraser) { it == "A" || it == "B" }
        doc.endErase(eraser)
        doc.resolveStroke(pending, "B")
        assertTrue(doc.entries.isEmpty())
        doc.undo()
        assertEquals(listOf("A", "B"), doc.entries.toList())
        doc.redo()
        assertTrue(doc.entries.isEmpty())
    }
    @Test fun canceledEraseRestoresZOrderAndKeepsRedo() {
        val doc = OrderedEdits<String>()
        doc.add("A"); doc.add("B"); doc.add("C")
        doc.undo()
        val erase = doc.beginErase()
        doc.erase(erase) { it == "A" }
        assertEquals(listOf("B"), doc.entries.toList())
        doc.endErase(erase, canceled = true)
        assertEquals(listOf("A", "B"), doc.entries.toList())
        doc.redo()
        assertEquals(listOf("A", "B", "C"), doc.entries.toList())
    }
    @Test fun newEditAfterUndoClearsRedoButEmptyEraseDoesNot() {
        val doc = OrderedEdits<String>()
        doc.add("A"); doc.undo()
        val empty = doc.beginErase()
        doc.erase(empty) { true }; doc.endErase(empty)
        assertTrue(doc.canRedo)
        doc.add("B")
        assertFalse(doc.canRedo)
    }
    @Test fun canceledStrokeUnblocksLaterOperationsWithoutAddingUndo() {
        val doc = OrderedEdits<String>()
        val canceled = doc.reserveStroke()
        doc.add("A")
        doc.cancelStroke(canceled)
        assertEquals(listOf("A"), doc.entries.toList())
        doc.undo()
        assertFalse(doc.canUndo)
        assertTrue(doc.entries.isEmpty())
    }
    @Test fun multipleEraserSamplesRemainOneOperation() {
        val doc = OrderedEdits<String>()
        doc.add("A"); doc.add("B"); doc.add("C")
        val erase = doc.beginErase()
        doc.erase(erase) { it == "A" }; doc.erase(erase) { it == "C" }
        assertFalse(doc.canUndo)
        doc.endErase(erase)
        doc.undo()
        assertEquals(listOf("A", "B", "C"), doc.entries.toList())
    }
    @Test fun oldestHistoryIsReleasedAtLimit() {
        val doc = OrderedEdits<String>(historyLimit = 2)
        doc.add("A"); doc.add("B"); doc.add("C")
        doc.undo(); doc.undo(); doc.undo()
        assertEquals(listOf("A"), doc.entries.toList())
    }
    @Test fun restorePreservesIdsAndNextIdWithoutSharingUndoBetweenNotebooks() {
        val doc = OrderedEdits<String>()
        doc.add("old note")
        doc.restore(listOf(OrderedEdits.Entry(12, "loaded")), next = 50)
        assertFalse(doc.canUndo)
        assertEquals(50L, doc.reserveStroke())
        assertEquals(listOf(OrderedEdits.Entry(12, "loaded")), doc.snapshot())
    }
    @Test fun eraserCandidateQueryRunsAfterDelayedStrokeHasEnteredTheIndex() {
        val doc = OrderedEdits<String>(); val indexed = mutableSetOf<Long>()
        doc.onContentChange = { added, removed -> removed.forEach { indexed.remove(it.id) }; added.forEach { indexed.add(it.id) } }
        val stroke = doc.reserveStroke(); val erase = doc.beginErase()
        doc.erase(erase, candidates = { indexed }) { true }; doc.endErase(erase)
        doc.resolveStroke(stroke, "late")
        assertTrue(doc.entries.isEmpty())
        doc.undo()
        assertEquals(listOf("late"), doc.entries.toList())
    }
}
