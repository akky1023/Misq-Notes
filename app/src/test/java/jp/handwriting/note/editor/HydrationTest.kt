package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class HydrationTest {
    @Test fun hydrationPreservesUndoRedoRevisionAndGlobalNextId() {
        val edits = OrderedEdits<String>()
        edits.restore(listOf(OrderedEdits.Entry(5L, "visible")), 100)
        assertEquals(listOf(100L), edits.addAll(listOf("new")))
        edits.undo()
        val revision = edits.revision
        edits.hydrate(listOf(OrderedEdits.Entry(2L, "unread")))
        assertEquals(revision, edits.revision)
        assertTrue(edits.canRedo)
        edits.redo()
        assertEquals(listOf(2L, 5L, 100L), edits.snapshot().map { it.id })
        assertEquals(101L, edits.nextEntryId)
        edits.undo()
        assertEquals(listOf(2L, 5L), edits.snapshot().map { it.id })
        assertThrows(IllegalArgumentException::class.java) { edits.hydrate(listOf(OrderedEdits.Entry(2L, "duplicate"))) }
    }
}
