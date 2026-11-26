package jp.handwriting.note.editor

import net.neomenia.misqnotes.drawing.WorldPoint
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
    /** Renderer/editor stress only: no repository, save queue, preview UI or external sync. */
    @Test fun optInMixedEndurance() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("endurance") == "true")
        val seconds = (args.getString("durationSeconds") ?: "1800").toLong().also { require(it in 1..86400) }
        val iterations = (args.getString("iterations") ?: Int.MAX_VALUE.toString()).toInt().also { require(it > 0) }
        val idleSeconds = (args.getString("idleSeconds") ?: "30").toLong().also { require(it in 0..3600) }
        val fixture = args.getString("fixture") ?: "sparse"
        require(fixture in setOf("sparse", "many", "dense", "image"))
        val mode = StrokeCacheMode.valueOf(args.getString("cacheMode") ?: "NEARBY")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "net.neomenia.misqnotes.probe")
        jp.handwriting.note.AppMessages.configure(instrumentation.targetContext)
        val brush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0x99202b33.toInt(), 3f, .01f)
        fun stroke(index: Int, points: Int = 32): EditorStroke {
            val inputs = MutableStrokeInputBatch()
            repeat(points) { i -> inputs.add(InputToolType.STYLUS,
                40f + index % 12 * 70 + i.toFloat() / points * 50,
                40f + index / 12 * 40 + kotlin.math.sin(i * .2f) * 12,
                i * 8L, pressure = .3f + .4f * i / points) }
            return EditorStroke(Stroke(brush, inputs))
        }
        val entries = (0 until if (fixture == "many") 10000 else 24).map {
            OrderedEdits.Entry(it + 1L, stroke(it, if (fixture == "dense") 8000 else 32))
        }.toMutableList()
        if (fixture == "image") {
            val bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0x993366cc.toInt())
            val output = java.io.ByteArrayOutputStream()
            try { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }
            finally { bitmap.recycle() }
            entries += OrderedEdits.Entry(25L, EditorStroke(jp.handwriting.note.storage.ImageRecord(
                jp.handwriting.note.storage.ImageData(output.toByteArray()), 100f, 100f, 300f, 300f)))
        }
        val handler = Handler(android.os.Looper.getMainLooper())
        fun ui(action: () -> Unit): Long {
            val done = java.util.concurrent.CountDownLatch(1)
            val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val started = SystemClock.elapsedRealtime()
            handler.post { try { action() } catch (e: Throwable) { error.set(e) } finally { done.countDown() } }
            check(done.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "UI unresponsive for 15 seconds" }
            error.get()?.let { throw it }
            return SystemClock.elapsedRealtime() - started
        }
        val model = EditorViewModel()
        var telemetry: EnduranceTelemetry? = null
        ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
            lateinit var view: InkCanvasView
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true); activity.setTurnScreenOn(true)
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model.edits.restore(entries); model.strokeCache(mode)
                view = InkCanvasView(activity, model)
                activity.setContentView(view)
                telemetry = EnduranceTelemetry(activity)
            }
            try {
                val deadline = SystemClock.elapsedRealtime() + seconds * 1000
                var cycle = 0
                var lastSample = 0L
                fun sample(phase: String) {
                    var loaded = 0
                    val latency = ui { loaded = model.edits.entries.size }
                    telemetry!!.sample(phase, latency, loaded)
                    lastSample = SystemClock.elapsedRealtime()
                }
                sample("start-$fixture-$mode")
                while (cycle < iterations && SystemClock.elapsedRealtime() < deadline) {
                    // A real stylus contact exercises asynchronous Ink handoff, not only completed strokes.
                    var beforeWriting = 0
                    ui { beforeWriting = model.edits.entries.size; model.chooseTool(EditorTool.PEN) }
                    val down = SystemClock.uptimeMillis()
                    repeat(24) { step ->
                        ui {
                            val properties = arrayOf(android.view.MotionEvent.PointerProperties().apply {
                                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS
                            })
                            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply {
                                x = view.width * (.2f + step * .02f)
                                y = view.height * (.3f + cycle % 10 * .03f) + kotlin.math.sin(step * .3f) * 10
                                pressure = .6f
                            })
                            val action = when (step) { 0 -> 0; 23 -> 1; else -> 2 }
                            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                                1, properties, coords, 0, 0, 1f, 1f, 1, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                            try { check(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                        }
                        SystemClock.sleep(8)
                    }
                    val handoffDeadline = SystemClock.elapsedRealtime() + 10000
                    while (true) {
                        var complete = false; var busy = false; var loaded = 0
                        ui {
                            busy = model.edits.busy; loaded = model.edits.entries.size
                            complete = !busy && loaded == beforeWriting + 1
                        }
                        if (complete) break
                        check(SystemClock.elapsedRealtime() < handoffDeadline) {
                            "Ink handoff stalled: cycle=$cycle busy=$busy loaded=$loaded expected=${beforeWriting + 1}"
                        }
                        SystemClock.sleep(20)
                    }
                    ui {
                        val before = model.edits.snapshot()
                        val erase = model.edits.beginErase()
                        model.edits.erase(erase) { it === before.last().value }; model.edits.endErase(erase)
                        model.edits.undo(); assertEquals(before, model.edits.snapshot())
                        model.edits.redo(); model.edits.undo()
                        model.viewport.restore(ViewPosition(1f + cycle % 3 * .5f, top = (cycle % 8) * 300f))
                        model.onCanvasChange?.invoke()
                    }
                    cycle++
                    if (SystemClock.elapsedRealtime() - lastSample >= 15000) sample("active-$cycle")
                    if (cycle % 20 == 0) {
                        // A new View matches screen recreation; it is not a persisted note switch.
                        ui {
                            val parent = view.parent as android.view.ViewGroup
                            val layout = view.layoutParams
                            parent.removeView(view)
                            view = InkCanvasView(parent.context, model)
                            parent.addView(view, layout)
                        }
                        sample("idle-start-$cycle")
                        val idleUntil = minOf(deadline, SystemClock.elapsedRealtime() + idleSeconds * 1000)
                        while (SystemClock.elapsedRealtime() < idleUntil) {
                            SystemClock.sleep(minOf(1000L, idleUntil - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                            if (SystemClock.elapsedRealtime() - lastSample >= 15000) sample("idle-$cycle")
                        }
                    }
                }
                sample("end-$cycle")
            } finally { telemetry?.close() }
        }
    }

    private fun report(name: String, samples: List<Long>) {
        val ordered = samples.sorted()
        val message = "$name samples=${ordered.size} p50us=${ordered[ordered.size / 2] / 1000} p95us=${ordered[(ordered.size * 95 / 100).coerceAtMost(ordered.lastIndex)] / 1000} p99us=${ordered[(ordered.size * 99 / 100).coerceAtMost(ordered.lastIndex)] / 1000} maxus=${ordered.last() / 1000}"
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
