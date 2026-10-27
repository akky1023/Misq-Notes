package jp.handwriting.note.editor

import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.NoteFiles
import jp.handwriting.note.storage.NoteRepository
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in: reads only the user-supplied `stars` note; never edits the library. */
class StarSamplesDeviceTest {
    @Test fun recognizesSuppliedStarsIndividually() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("starSamples") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = NoteRepository(NoteFiles(File(context.filesDir, "notes")))
        val note = repository.list().single { it.title == "stars" }
        val loaded = repository.load(note.id)
        assertEquals(20, loaded.entries.size)
        val rows = StarPreviewRows.group(loaded.entries.map { entry ->
            val b = checkNotNull(entry.value.bounds)
            StrokeIndex.Bounds(b.xMin, b.yMin, b.xMax, b.yMax)
        })
        assertEquals("Twenty supplied stars occupy four rows", 4, rows.size)
        loaded.entries.forEach { entry ->
            val ink = entry.value.ink
            val points = List(ink.inputs.size) { i ->
                val p = ink.inputs[i]
                SingleStrokeStar.Point(p.x, p.y)
            }
            assertTrue("Supplied stroke ${entry.id}", SingleStrokeStar.matches(points))
            points.chunked((points.size + 1) / 2).forEach {
                assertFalse("Separate strokes must not form a star", SingleStrokeStar.matches(it))
            }
        }
    }
}
