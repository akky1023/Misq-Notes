package jp.handwriting.note.storage

import jp.handwriting.note.editor.ViewPosition
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteViewPositionsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun positionsSurviveRestartAndStaySeparateForEachNote() {
        val directory = File(temp.root, "view-positions")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val store = NoteViewPositions(directory)
        assertNull(store.read(first))
        store.write(first, ViewPosition(zoom = 2f, left = 100f, top = 5000f))
        store.write(second, ViewPosition(top = 8000f))
        val restarted = NoteViewPositions(directory)
        assertEquals(ViewPosition(zoom = 2f, left = 100f, top = 5000f), restarted.read(first))
        assertEquals(ViewPosition(top = 8000f), restarted.read(second))
    }

    @Test fun damagedPositionUsesBackupOrFallsBackToManifest() {
        val directory = temp.newFolder()
        val id = UUID.randomUUID().toString()
        val store = NoteViewPositions(directory)
        val previous = ViewPosition(top = 1000f)
        store.write(id, previous)
        store.write(id, ViewPosition(top = 2000f))
        File(directory, "$id.hwp").writeBytes(byteArrayOf(0))
        assertEquals(previous, store.read(id))
        File(directory, "$id.hwp.bak").writeBytes(byteArrayOf(0))
        assertNull(store.read(id))
    }
}
