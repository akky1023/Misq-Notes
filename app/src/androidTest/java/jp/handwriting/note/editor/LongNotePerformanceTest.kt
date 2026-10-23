package jp.handwriting.note.editor

import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.FrameMetrics
import androidx.test.core.app.ActivityScenario
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import jp.handwriting.note.PerformanceActivity
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data only. No saved notebook or external storage is opened by these measurements. */
class LongNotePerformanceTest {
    private fun report(name: String, samples: List<Long>) {
        val ordered = samples.sorted()
        val message = "$name samples=${ordered.size} p50us=${ordered[ordered.size / 2] / 1000} p95us=${ordered[(ordered.size * 95 / 100).coerceAtMost(ordered.lastIndex)] / 1000} maxus=${ordered.last() / 1000}"
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nPERF $message\n") })
    }
    @Test fun longDocumentSnapshotsOnTheUiThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (size in listOf(1_000, 10_000, 100_000)) {
            val doc = OrderedEdits<Int>()
            doc.restore((1..size).map { OrderedEdits.Entry(it.toLong(), it) })
            val original = TreeMap<Long, Int>().apply { (1..size).forEach { put(it.toLong(), it) } }
            val old = mutableListOf<Long>(); val improved = mutableListOf<Long>()
            instrumentation.runOnMainSync {
                repeat(40) { iteration ->
                    val before = System.nanoTime()
                    original[(size + iteration + 1).toLong()] = iteration
                    val copied = original.map { OrderedEdits.Entry(it.key, it.value) }
                    // The previous enqueueSave also mapped every entry into a manifest reference.
                    val refs = copied.map { it.id }
                    old += System.nanoTime() - before
                    assertEquals(size + iteration + 1, refs.size)
                    val start = System.nanoTime()
                    val id = doc.reserveStroke(); doc.resolveStroke(id, iteration)
                    val captured = doc.snapshot()
                    improved += System.nanoTime() - start
                    assertEquals(size + iteration + 1, captured.size)
                }
            }
            report("snapshot-old-$size", old); report("snapshot-new-$size", improved)
        }
    }
    @Test fun appendOnALongNoteRerecordsAtMostEightVisibleStrokes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202b33.toInt(), 3f, 0.1f)
        val model = EditorViewModel()
        val entries = (1L..10_000L).map { id ->
            val x = 20f + (id % 20) * 45f; val y = 20f + (id / 20) * 20f
            val inputs = MutableStrokeInputBatch().add(InputToolType.STYLUS, x, y, 0, pressure = 0.3f)
                .add(InputToolType.STYLUS, x + 20, y + 8, 20, pressure = 0.8f)
            OrderedEdits.Entry(id, EditorStroke(Stroke(brush, inputs)))
        }
        instrumentation.runOnMainSync { model.edits.restore(entries); model.viewport.size(1000f, 1000f) }
        val visible = model.visibleStrokeEntries()
        assertTrue("Distant strokes must be culled", visible.size < 1100)
        for (groupSize in listOf(64, 8)) {
            val cache = StrokeRenderCache(groupSize)
            val target = RenderNode("append-benchmark-$groupSize")
            val renderer = CanvasStrokeRenderer.create(); val transform = Matrix()
            val durations = mutableListOf<Long>()
            instrumentation.runOnMainSync {
                try {
                    var frame = visible
                    repeat(41) { sample ->
                        var drawn = 0
                        val canvas = target.beginRecording(1000, 1000)
                        val start = System.nanoTime()
                        try { cache.draw(canvas, frame, ViewPosition(), 1000, 1000, WorldPoint(0f, 0f), { false }) { output, entry, _ ->
                            renderer.draw(output, entry.ink, transform); drawn++
                        } } finally { target.endRecording() }
                        if (sample > 0) { durations += System.nanoTime() - start; assertTrue(drawn <= groupSize) }
                        frame = frame + (10_001L + sample to EditorStroke(entries.first().value.ink))
                    }
                } finally { cache.clear(); target.discardDisplayList() }
            }
            report("recording-ui-$groupSize", durations)
        }
    }
    @Test fun actualCanvasFramesDuringAppend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0xff202b33.toInt(), 3f, 0.1f)
        val entries = (1L..10_000L).map { id ->
            val x = 20f + (id % 20) * 45f; val y = 20f + (id / 20) * 20f
            OrderedEdits.Entry(id, EditorStroke(Stroke(brush, MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, x, y, 0).add(InputToolType.STYLUS, x + 20, y + 8, 20))))
        }
        val thermal = instrumentation.targetContext.getSystemService(PowerManager::class.java).currentThermalStatus
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPERF thermalStatus=$thermal\n") })
        for (groupSize in listOf(64, 8)) {
            val thread = HandlerThread("misq-frame-measurement").apply { start() }
            val active = AtomicBoolean(false)
            val frames = mutableListOf<Long>(); val draws = mutableListOf<Long>()
            val model = EditorViewModel()
            try {
                ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        model.edits.restore(entries)
                        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
                            if (active.get()) synchronized(frames) {
                                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                                draws += metrics.getMetric(FrameMetrics.DRAW_DURATION)
                            }
                        }, Handler(thread.looper))
                        activity.setContentView(InkCanvasView(activity, model, groupSize))
                    }
                    SystemClock.sleep(400)
                    active.set(true)
                    repeat(24) { i ->
                        val x = 100f + i * 8; val y = 400f
                        val stroke = EditorStroke(Stroke(brush, MutableStrokeInputBatch()
                            .add(InputToolType.STYLUS, x, y, 0).add(InputToolType.STYLUS, x + 20, y + 20, 30)))
                        instrumentation.runOnMainSync { model.edits.addAll(listOf(stroke)) }
                        SystemClock.sleep(50)
                    }
                    SystemClock.sleep(100); active.set(false)
                }
                synchronized(frames) {
                    assertTrue("The attached canvas must produce actual frames", frames.isNotEmpty())
                    report("frame-total-$groupSize", frames.toList()); report("frame-draw-$groupSize", draws.toList())
                }
            } finally { active.set(false); thread.quitSafely(); thread.join(2000) }
        }
    }
}
