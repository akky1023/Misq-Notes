package jp.handwriting.note.editor

import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class StrokeCachePolicyTest {
    @Test fun nearbyIncludesOneScreenAboveAndBelowAndAllIncludesDistantStrokes() {
        val model = EditorViewModel()
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), Color.BLACK, 3f, 0.1f)
        val entries = (1L..60L).map { id -> OrderedEdits.Entry(id, EditorStroke(Stroke(brush,
            MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, id * 100f + 50f, 0)))) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.edits.restore(entries)
            model.viewport.size(1000f, 1000f)
            model.viewport.restore(ViewPosition(top = 2000f))
            val visible = model.visibleStrokeEntries().map { it.first }.toSet()
            val nearby = model.cachedStrokeEntries(StrokeCacheMode.NEARBY).map { it.first }.toSet()
            assertEquals((20L..29L).toSet(), visible)
            assertEquals((10L..39L).toSet(), nearby)
            assertEquals(entries.map { it.id }, model.cachedStrokeEntries(StrokeCacheMode.ALL).map { it.first })
            model.viewport.restore(ViewPosition(zoom = 2f, top = 2000f))
            assertEquals((15L..29L).toSet(), model.cachedStrokeEntries(StrokeCacheMode.NEARBY).map { it.first }.toSet())
        }
    }
}
