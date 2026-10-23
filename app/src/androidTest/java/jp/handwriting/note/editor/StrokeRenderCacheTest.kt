package jp.handwriting.note.editor

import android.graphics.RenderNode
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrokeRenderCacheTest {
    @Test fun panningReusesGroupsAndAppendedTailsUntilZoomOrSizeChanges() {
        val ink = ink(); val cache = StrokeRenderCache(); val target = RenderNode("pan-test")
        var entries = (1L..300L).map { it to EditorStroke(ink) }
        var width = 1000
        fun draw(position: ViewPosition, compact: Boolean = false) {
            val canvas = target.beginRecording(width, 1000)
            try { cache.draw(canvas, entries, position, width, 1000, WorldPoint(0f, 0f), { false }, compact) { _, _, _ -> } }
            finally { target.endRecording() }
        }
        try {
            draw(ViewPosition(2f))
            val initial = cache.recordedGroups
            repeat(60) { draw(ViewPosition(2f, it.toFloat(), it * 10f)) }
            assertEquals(initial, cache.recordedGroups)
            entries = entries + (301L to EditorStroke(ink))
            draw(ViewPosition(2f, 80f, 700f))
            assertEquals(initial + 1, cache.recordedGroups)
            draw(ViewPosition(2f, 100f, 800f))
            assertEquals(initial + 1, cache.recordedGroups)
            draw(ViewPosition(2f, 100f, 800f), compact = true)
            assertEquals(initial + 2, cache.recordedGroups)
            draw(ViewPosition(2f, 120f, 900f))
            assertEquals(initial + 2, cache.recordedGroups)
            draw(ViewPosition(3f, 120f, 900f))
            assertEquals(initial * 2 + 2, cache.recordedGroups)
            width = 800
            draw(ViewPosition(3f, 120f, 900f))
            assertEquals(initial * 3 + 2, cache.recordedGroups)
        } finally { cache.clear(); target.discardDisplayList() }
    }

    @Test fun appendedTailsCompactOnlyWhenRequestedAndKeepTheSameOrder() {
        val ink = ink(); val cache = StrokeRenderCache(); val target = RenderNode("test-compaction")
        var entries = (1L..20L).map { it to EditorStroke(ink) }
        val drawn = mutableListOf<EditorStroke>()
        fun draw(compact: Boolean = false) {
            val canvas = target.beginRecording(1000, 1000)
            try { cache.draw(canvas, entries, ViewPosition(), 1000, 1000, WorldPoint(0f, 0f), { false }, compact) { _, entry, _ -> drawn += entry } }
            finally { target.endRecording() }
        }
        try {
            draw(); assertFalse(cache.pendingCompaction)
            repeat(20) { index ->
                entries = entries + (21L + index to EditorStroke(ink))
                drawn.clear(); draw()
                assertTrue(drawn.size <= StrokeRenderCache.APPEND_SIZE)
                assertSame(entries.last().second, drawn.last())
            }
            assertTrue(cache.pendingCompaction)
            drawn.clear(); draw(compact = true)
            assertFalse(cache.pendingCompaction)
            assertEquals(entries.map { it.second }, drawn)
            drawn.clear(); draw(); assertTrue(drawn.isEmpty())
        } finally { cache.clear(); target.discardDisplayList() }
    }
    private fun ink(): Stroke {
        val points = MutableStrokeInputBatch().add(InputToolType.STYLUS, 10f, 10f, 0)
            .add(InputToolType.STYLUS, 20f, 20f, 20)
        return Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff000000.toInt(), 3f, 0.1f), points)
    }
    @Test fun addingAndErasingInvalidateOnlyTheirGroupAndPreserveDrawOrder() {
        val ink = ink()
        val entries = (1L..1093L).map { it to EditorStroke(ink) }
        val cache = StrokeRenderCache()
        val target = RenderNode("test")
        val drawn = mutableListOf<EditorStroke>()
        fun draw(values: List<Pair<Long, EditorStroke>>, position: ViewPosition = ViewPosition()) {
            val canvas = target.beginRecording(1000, 1000)
            try { cache.draw(canvas, values, position, 1000, 1000, WorldPoint(0f, 0f), { false }) { _, stroke, _ -> drawn += stroke } }
            finally { target.endRecording() }
        }
        try {
            draw(entries)
            assertEquals(entries.map { it.second }, drawn)
            val groups = cache.recordedGroups
            drawn.clear(); draw(entries)
            assertTrue(drawn.isEmpty())
            assertEquals(groups, cache.recordedGroups)
            val appended = entries + (1094L to EditorStroke(ink))
            draw(appended)
            assertEquals(groups + 1, cache.recordedGroups)
            assertTrue(drawn.size <= StrokeRenderCache.APPEND_SIZE)
            drawn.clear(); draw(appended.drop(1))
            assertEquals(groups + 2, cache.recordedGroups)
            assertTrue(drawn.size <= StrokeRenderCache.GROUP_SIZE)
            drawn.clear(); draw(appended, ViewPosition(2f))
            assertEquals(appended.map { it.second }, drawn)
        } finally { cache.clear(); target.discardDisplayList() }
    }
    @Test fun selectionMovementAndDeselectionInvalidateOnlyAffectedGroups() {
        val ink = ink()
        val entries = (1L..130L).map { it to EditorStroke(ink) }
        val selected = entries[70].second
        val cache = StrokeRenderCache()
        val target = RenderNode("test-selection")
        var drawn = 0
        fun draw(offset: WorldPoint, selection: Boolean) {
            val canvas = target.beginRecording(1000, 1000)
            try { cache.draw(canvas, entries, ViewPosition(), 1000, 1000, offset, { selection && it === selected }) { _, _, _ -> drawn++ } }
            finally { target.endRecording() }
        }
        try {
            draw(WorldPoint(0f, 0f), false)
            drawn = 0; draw(WorldPoint(0f, 0f), true); assertEquals(StrokeRenderCache.GROUP_SIZE, drawn)
            drawn = 0; draw(WorldPoint(20f, 30f), true); assertEquals(StrokeRenderCache.GROUP_SIZE, drawn)
            drawn = 0; draw(WorldPoint(0f, 0f), false); assertEquals(StrokeRenderCache.GROUP_SIZE, drawn)
        } finally { cache.clear(); target.discardDisplayList() }
    }
}
