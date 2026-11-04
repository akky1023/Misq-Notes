package jp.handwriting.note.storage

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.PixelCopy
import android.net.Uri
import android.os.*
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import jp.handwriting.note.AppMessages
import jp.handwriting.note.PerformanceActivity
import jp.handwriting.note.NotebookApp
import jp.handwriting.note.NotebookTheme
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
        val gpu = mutableListOf<Long>()
        val command = mutableListOf<Long>()
        val intended = mutableListOf<Long>()
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
        report("FRAMES $name gpu ${stats(samples.gpu)} command ${stats(samples.command)}")
        report("FRAMES $name uniqueVsync=${samples.intended.toSet().size} reported=${samples.intended.size}")
        report("FRAMES $name vsync ${stats(samples.intervals)} missed60=${samples.intervals.count { it > 25_000_000L }}")
    }

    @Test fun panThreeScreensAndPinchOnAuthorizedNote() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val tree = args.getString("sourceTree"); val note = args.getString("sourceNote"); val title = args.getString("sourceTitle")
        assumeNotNull(tree, note, title)
        require(UUID.fromString(note).toString() == note)
        val context = instrumentation.targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe"); AppMessages.configure(context)
        val preferenceName = "frame-storage-${UUID.randomUUID()}"
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(if (name == "note-storage") preferenceName else name, mode)
        }
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
            val editor = withContext(Dispatchers.Main) {
                ViewModelProvider(models, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also {
                    it.configureStorage(isolatedContext, folder, File(folder, "ui-cache")); it.start(folder)
                }
            }
            model = editor
            suspend fun ready() = withTimeout(60000) {
                while (editor.workspace.value.working || editor.ui.value.busy || editor.ui.value.loadingRest) delay(20)
                assertNull(editor.workspace.value.error)
            }
            ready()
            val active = AtomicReference<Samples?>(null)
            fun gfx(name: String) {
                val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo net.neomenia.misqnotes.probe framestats")
                val text = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                File(context.cacheDir, "frame-gfx-$name.txt").writeText(text)
                report("GFX $name " + text.lineSequence().filter { it.contains("Janky frames") || it.contains("percentile:") || it.contains("Total frames rendered") }.joinToString(" | ") { it.trim() })
                val layers = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys SurfaceFlinger --list"))
                    .bufferedReader().use { it.readLines() }.filter { it.contains("PerformanceActivity") }
                val output = StringBuilder()
                layers.forEach { layer ->
                    val name = layer.removePrefix("RequestedLayerState{").substringBefore(" parentId=")
                        .removeSuffix("}").replace(Regex("^[0-9a-f]+ "), "")
                    val quoted = "'" + name.replace("'", "'\\''") + "'"
                    val timing = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys SurfaceFlinger --latency $quoted"))
                        .bufferedReader().use { it.readText() }
                    output.append("LAYER $name\n$timing\n")
                }
                File(context.cacheDir, "frame-surface-$name.txt").writeText(output.toString())
            }
            ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
                lateinit var canvas: InkCanvasView
                scenario.onActivity { activity ->
                    activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = 60f }
                    activity.window.addOnFrameMetricsAvailableListener({ _, metrics, dropped ->
                        active.get()?.let { samples -> synchronized(samples) {
                            samples.total += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                            samples.draw += metrics.getMetric(FrameMetrics.DRAW_DURATION)
                            samples.gpu += metrics.getMetric(FrameMetrics.GPU_DURATION)
                            samples.command += metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                            samples.intended += metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                            samples.dropped += dropped
                        } }
                    }, Handler(thread.looper))
                    if (args.getString("fullEditor") == "true") {
                        activity.setContent {
                            NotebookTheme(AppThemeMode.SYSTEM) {
                                NotebookApp(editor, onCanvas = { canvas = it }, onCanvasDisposed = {}, onNotes = {})
                            }
                        }
                    } else {
                        canvas = InkCanvasView(activity, editor)
                        activity.setContentView(canvas)
                    }
                    editor.openNote(note!!)
                }
                ready(); delay(600)
                if (args.getString("preview") == "true") {
                    val oldWidth = canvas.width
                    val location = IntArray(2)
                    withContext(Dispatchers.Main) { canvas.getLocationOnScreen(location) }
                    val inset = 24f * context.resources.displayMetrics.density
                    val x = location[0] + if (editor.library.value.value.previewOnRight) canvas.width - inset else inset
                    val y = location[1] + canvas.height * .5f
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    delay(1000); ready()
                    assertTrue("Preview must be visibly open", canvas.width < oldWidth)
                }
                val height = withContext(Dispatchers.Main) { editor.viewport.height / editor.viewport.scale }
                // Read the complete travel area before timing drawing; no blank unread regions.
                for (screen in 0..4) {
                    withContext(Dispatchers.Main) { editor.moveViewportFromPreview(0f, screen * height) }
                    ready(); delay(150)
                }
                withContext(Dispatchers.Main) { editor.moveViewportFromPreview(0f, 0f) }
                ready(); delay(600)
                val expected = store.current(note!!)!!.pages.flatMap(store::rows)
                    .filter { it.placement.bottom >= 0f && it.placement.top <= height * 5f }.map { it.ref.id }.toSet()
                assertTrue("Every stroke across the travel area must be loaded", editor.edits.snapshot().map { it.id }.toSet().containsAll(expected))
                report("FRAME_FIXTURE strokes=${store.current(note!!)!!.count} loaded=${editor.edits.snapshot().size} view=${canvas.width}x${canvas.height} worldHeight=$height refresh=${canvas.display.refreshRate} thermal=${context.getSystemService(PowerManager::class.java).currentThermalStatus}")
                if (args.getString("perfetto") == "true") {
                    val descriptors = instrumentation.uiAutomation.executeShellCommandRw("perfetto --background-wait --txt -c - -o /data/misc/perfetto-traces/misq-frames.pftrace")
                    val config = """
                        buffers { size_kb: 8192 fill_policy: RING_BUFFER }
                        buffers { size_kb: 131072 fill_policy: RING_BUFFER }
                        data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 0 } }
                        data_sources { config { name: "linux.ftrace" target_buffer: 1 ftrace_config {
                          atrace_categories: "gfx"
                          atrace_categories: "view"
                          atrace_apps: "net.neomenia.misqnotes.probe"
                        } } }
                        duration_ms: 60000
                    """.trimIndent()
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write(config.toByteArray()) }
                    val pid = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText().trim() }
                    check(pid.matches(Regex("[0-9]+"))) { "Perfetto did not start: $pid" }
                    report("PERFETTO pid=$pid")
                }
                fun touch(action: Int, fraction: Float) {
                    val now = SystemClock.uptimeMillis()
                    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = canvas.width * .5f; y = canvas.height * fraction; pressure = 1f })
                    val event = MotionEvent.obtain(now, now, action, 1, properties, coordinates,
                        0, 0, 1f, 1f, 1, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(canvas.onTouchEvent(event)) } finally { event.recycle() }
                }
                var inputId = 100
                suspend fun frames(count: Int, samples: Samples, action: (Int) -> Unit) {
                    val done = CountDownLatch(1); val failure = AtomicReference<Throwable?>(null)
                    val started = System.nanoTime()
                    val id = inputId++
                    Trace.beginAsyncSection("misq-input-$count", id)
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
                    Trace.endAsyncSection("misq-input-$count", id)
                    failure.get()?.let { throw it }
                    report("FRAME_LOOP frames=$count elapsedMs=${(System.nanoTime() - started) / 1e6}")
                }
                for (pass in 0..1) {
                    Trace.beginAsyncSection("misq-pan-$pass", pass)
                    if (pass == 0 && args.getString("profile") == "true")
                        Debug.startMethodTracingSampling(File(context.cacheDir, "frame-scroll").path, 32 * 1024 * 1024, 1000)
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
                    Trace.endAsyncSection("misq-pan-$pass", pass)
                    report("pan-pass-$pass", samples)
                    gfx("pan-$pass")
                    if (pass == 0 && args.getString("profile") == "true") Debug.stopMethodTracing()
                }
                val samples = Samples(); active.set(samples)
                Trace.beginAsyncSection("misq-pinch", 10)
                withContext(Dispatchers.Main) { editor.viewportGestureActive = true }
                frames(180, samples) { step ->
                    val zoom = 1f + (1f - kotlin.math.cos(step * Math.PI * 2 / 180).toFloat()) * .5f
                    val old = editor.viewport.position.zoom
                    editor.transformViewport(canvas.width / 2f, canvas.height / 2f, canvas.width / 2f, canvas.height / 2f, zoom / old)
                    editor.onCanvasChange?.invoke()
                }
                withContext(Dispatchers.Main) { editor.viewportGestureActive = false; editor.viewportChanged() }
                delay(100); active.set(null); report("pinch", samples)
                Trace.endAsyncSection("misq-pinch", 10)
                gfx("pinch")
                if (args.getString("write") == "true") {
                    ready()
                    fun previewMagentaPixels(): Int {
                        val location = IntArray(2)
                        instrumentation.runOnMainSync { canvas.getLocationInWindow(location) }
                        val rect = if (editor.library.value.value.previewOnRight)
                            Rect(location[0] + canvas.width, location[1], canvas.rootView.width, location[1] + canvas.height)
                        else Rect(0, location[1], location[0], location[1] + canvas.height)
                        check(rect.width() > 0)
                        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                        try {
                            val done = CountDownLatch(1); var result = -1
                            scenario.onActivity { activity -> PixelCopy.request(activity.window, rect, bitmap,
                                { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
                            check(done.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            return pixels.count { Color.red(it) > 180 && Color.blue(it) > 180 && Color.green(it) < 100 }
                        } finally { bitmap.recycle() }
                    }
                    val beforePreview = if (args.getString("preview") == "true") previewMagentaPixels() else 0
                    val next = editor.edits.nextEntryId
                    withContext(Dispatchers.Main) { editor.chooseTool(EditorTool.PEN); editor.style(color = Color.MAGENTA) }
                    val writing = Samples(); active.set(writing)
                    Trace.beginAsyncSection("misq-writing", 20)
                    repeat(12) { contact ->
                        ready()
                        val down = SystemClock.uptimeMillis()
                        frames(24, writing) { step ->
                            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 23 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                                x = canvas.width * (.2f + step * .02f)
                                y = canvas.height * (.25f + contact * .035f) + kotlin.math.sin(step * .3f) * 10f
                                pressure = .6f
                            })
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, properties, coordinates,
                                0, 0, 1f, 1f, 1, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
                            try { assertTrue(canvas.onTouchEvent(event)) } finally { event.recycle() }
                        }
                        ready(); delay(50)
                    }
                    delay(200); active.set(null); report("writing", writing)
                    Trace.endAsyncSection("misq-writing", 20)
                    ready()
                    assertEquals("Every pen contact must finish as one stroke", 12, editor.edits.snapshot().count { it.id >= next })
                    if (args.getString("preview") == "true") {
                        delay(1000)
                        val afterPreview = previewMagentaPixels()
                        assertTrue("New strokes must appear in the preview", afterPreview > beforePreview + 100)
                        report("PREVIEW_UPDATED magentaBefore=$beforePreview magentaAfter=$afterPreview")
                    }
                    gfx("writing")
                }
            }
            assertEquals(sourceHash, NotebookArchive.digest(source))
            report("FRAME_SOURCE_COPY unchanged=true")
        } finally {
            val job = model?.viewModelScope?.coroutineContext?.get(Job)
            withContext(Dispatchers.Main) { models.clear() }; job?.join()
            thread.quitSafely()
            context.deleteSharedPreferences(preferenceName)
            folder.deleteRecursively()
        }
    }
}
