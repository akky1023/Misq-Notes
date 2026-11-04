package jp.handwriting.note.storage

import android.content.Context
import android.net.Uri
import android.os.*
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import jp.handwriting.note.AppMessages
import jp.handwriting.note.PerformanceActivity
import jp.handwriting.note.editor.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in, isolated copy. A measured sweep travels 2.7 full screens, then returns. */
class RealNoteFrameTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
    private class Samples {
        val total = mutableListOf<Long>()
        val draw = mutableListOf<Long>()
        val intervals = mutableListOf<Long>()
        var dropped = 0
    }
    private fun report(name: String, samples: Samples) = synchronized(samples) {
        fun stats(values: List<Long>): String {
            val sorted = values.sorted(); assertTrue(sorted.isNotEmpty())
            return "n=${sorted.size} p50ms=${sorted[sorted.size / 2] / 1e6} p95ms=${sorted[sorted.size * 95 / 100] / 1e6} p99ms=${sorted[sorted.size * 99 / 100] / 1e6} maxms=${sorted.last() / 1e6} over16ms=${sorted.count { it > 16_666_667L }}"
        }
        report("FRAMES $name total ${stats(samples.total)} droppedMetrics=${samples.dropped}")
        report("FRAMES $name draw ${stats(samples.draw)}")
        report("FRAMES $name vsync ${stats(samples.intervals)} missed60=${samples.intervals.count { it > 25_000_000L }}")
    }

    @Test fun panThreeScreensAndPinchOnAuthorizedNote() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val tree = args.getString("sourceTree"); val note = args.getString("sourceNote"); val title = args.getString("sourceTitle")
        assumeNotNull(tree, note, title)
        require(UUID.fromString(note).toString() == note)
        val context = instrumentation.targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe"); AppMessages.configure(context)
        val preferences = context.getSharedPreferences("note-storage", Context.MODE_PRIVATE)
        val saved = preferences.all.toMap()
        val folder = File(context.cacheDir, "frame-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val models = ViewModelStore()
        var model: EditorViewModel? = null
        val thread = HandlerThread("frame-metrics").apply { start() }
        try {
            val remote = MisqDocumentFiles(context.contentResolver, Uri.parse(tree)).also { it.names() }
            val source = File(folder, "$note.misq")
            remote.openRead(source.name).use { input -> source.outputStream().use { input.copyTo(it, 65536) } }
            val sourceHash = NotebookArchive.digest(source)
            assertEquals(title, MisqFile(source).summary().title)
            val store = ImmutableNoteStore(File(folder, "format3-${UUID.randomUUID()}"))
            Format3Library(store, File(folder, "exports").also { it.mkdirs() }).imported(source)
            NoteFiles.atomicWrite(File(folder, ".format3-active"), JSONObject().put("format", 3).put("minWriter", 3)
                .put("directory", store.directory.name).toString().toByteArray())
            check(preferences.edit().clear().commit())
            val editor = withContext(Dispatchers.Main) {
                ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also {
                    it.configureStorage(context, folder, File(folder, "ui-cache")); it.start(folder)
                }
            }
            model = editor
            suspend fun ready() = withTimeout(60000) {
                while (editor.workspace.value.working || editor.ui.value.busy || editor.ui.value.loadingRest) delay(20)
                assertNull(editor.workspace.value.error)
            }
            ready()
            val active = AtomicReference<Samples?>(null)
            ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
                lateinit var canvas: InkCanvasView
                scenario.onActivity { activity ->
                    activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = 60f }
                    activity.window.addOnFrameMetricsAvailableListener({ _, metrics, dropped ->
                        active.get()?.let { samples -> synchronized(samples) {
                            samples.total += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                            samples.draw += metrics.getMetric(FrameMetrics.DRAW_DURATION)
                            samples.dropped += dropped
                        } }
                    }, Handler(thread.looper))
                    canvas = InkCanvasView(activity, editor)
                    activity.setContentView(canvas)
                    editor.openNote(note!!)
                }
                ready(); delay(600)
                val height = withContext(Dispatchers.Main) { editor.viewport.height / editor.viewport.scale }
                // Read the complete travel area before timing drawing; no blank unread regions.
                for (screen in 0..4) {
                    withContext(Dispatchers.Main) { editor.moveViewportFromPreview(0f, screen * height) }
                    ready(); delay(150)
                }
                withContext(Dispatchers.Main) { editor.moveViewportFromPreview(0f, 0f) }
                ready(); delay(600)
                report("FRAME_FIXTURE strokes=${store.current(note!!)!!.count} loaded=${editor.edits.snapshot().size} view=${canvas.width}x${canvas.height} worldHeight=$height refresh=${canvas.display.refreshRate} thermal=${context.getSystemService(PowerManager::class.java).currentThermalStatus}")
                fun touch(action: Int, fraction: Float) {
                    val now = SystemClock.uptimeMillis()
                    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = canvas.width * .5f; y = canvas.height * fraction; pressure = 1f })
                    val event = MotionEvent.obtain(now, now, action, 1, properties, coordinates,
                        0, 0, 1f, 1f, 1, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(canvas.onTouchEvent(event)) } finally { event.recycle() }
                }
                suspend fun frames(count: Int, samples: Samples, action: (Int) -> Unit) {
                    val done = CountDownLatch(1); val failure = AtomicReference<Throwable?>(null)
                    withContext(Dispatchers.Main) {
                        var frame = 0; var previous = 0L
                        val callback = object : Choreographer.FrameCallback {
                            override fun doFrame(time: Long) {
                                try {
                                    if (previous != 0L) synchronized(samples) { samples.intervals += time - previous }
                                    previous = time
                                    action(frame++)
                                    if (frame < count) Choreographer.getInstance().postFrameCallback(this) else done.countDown()
                                } catch (error: Throwable) { failure.set(error); done.countDown() }
                            }
                        }
                        Choreographer.getInstance().postFrameCallback(callback)
                    }
                    assertTrue("Frame loop stalled", withContext(Dispatchers.IO) { done.await(45, TimeUnit.SECONDS) })
                    failure.get()?.let { throw it }
                }
                for (pass in 0..1) {
                    val samples = Samples(); active.set(samples)
                    for (direction in listOf(1, -1)) {
                        repeat(3) {
                            ready()
                            val start = if (direction == 1) .95f else .05f
                            withContext(Dispatchers.Main) { touch(MotionEvent.ACTION_DOWN, start) }
                            frames(60, samples) { step -> touch(MotionEvent.ACTION_MOVE, start - direction * .9f * (step + 1) / 60f) }
                            withContext(Dispatchers.Main) { touch(MotionEvent.ACTION_UP, start - direction * .9f) }
                            ready()
                        }
                        val top = withContext(Dispatchers.Main) { editor.viewport.position.top }
                        if (direction == 1) assertEquals(2.7f * height, top, height * .03f)
                        else assertEquals(0f, top, height * .03f)
                    }
                    delay(100); active.set(null)
                    report("pan-pass-$pass", samples)
                }
                val samples = Samples(); active.set(samples)
                withContext(Dispatchers.Main) { editor.viewportGestureActive = true }
                frames(180, samples) { step ->
                    val zoom = 1f + (1f - kotlin.math.cos(step * Math.PI * 2 / 180).toFloat()) * .5f
                    val old = editor.viewport.position.zoom
                    editor.transformViewport(canvas.width / 2f, canvas.height / 2f, canvas.width / 2f, canvas.height / 2f, zoom / old)
                    editor.onCanvasChange?.invoke()
                }
                withContext(Dispatchers.Main) { editor.viewportGestureActive = false; editor.viewportChanged() }
                delay(100); active.set(null); report("pinch", samples)
            }
            assertEquals(sourceHash, NotebookArchive.digest(source))
            report("FRAME_SOURCE_COPY unchanged=true")
        } finally {
            val job = model?.viewModelScope?.coroutineContext?.get(Job)
            withContext(Dispatchers.Main) { models.clear() }; job?.join()
            thread.quitSafely()
            val edit = preferences.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
            } }
            check(edit.commit()); folder.deleteRecursively()
        }
    }
}
