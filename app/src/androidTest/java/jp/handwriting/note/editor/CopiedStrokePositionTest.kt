package jp.handwriting.note.editor

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.geometry.MutableVec
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.PerformanceActivity
import jp.handwriting.note.storage.NoteRepository
import jp.handwriting.note.storage.MisqFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Probe-only diagnostics; note inspection requires an explicit ID and exports only one selected stroke. */
class CopiedStrokePositionTest {
    @Test fun inspectExplicitProbeNoteStroke() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("inspectProbeNote")
        assumeTrue("Requires an explicit probe note ID", id != null)
        require(java.util.UUID.fromString(id).toString() == id)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe")
        val note = MisqFile(File(context.filesDir, "notes/$id.misq"), readOnlyAlias = true)
        val index = note.index()
        val selected = args.getString("inspectProbeStroke")?.toLong()
        val refs = if (selected == null) index.manifest.strokes.takeLast(24)
            else listOf(index.manifest.strokes.single { it.id == selected })
        val result = JSONObject().put("view", JSONObject().put("left", index.manifest.info.position.left)
            .put("top", index.manifest.info.position.top).put("zoom", index.manifest.info.position.zoom))
        val strokes = JSONArray()
        val output = File(context.getExternalFilesDir(null), "stroke-position-inspection").apply { mkdirs() }
        for (ref in refs) {
            val bytes = note.stroke(ref.storageId)
            val stroke = NoteRepository.decodeStroke(ref.storageId, bytes)
            val box = stroke.bounds ?: continue
            strokes.put(JSONObject().put("id", ref.id).put("bounds", JSONArray(listOf(box.xMin, box.yMin, box.xMax, box.yMax)))
                .put("color", stroke.inkOrNull?.brush?.colorIntArgb).put("inputs", stroke.inkOrNull?.inputs?.size))
            if (selected != null) File(output, "selected-$selected.ink").writeBytes(bytes)
        }
        result.put("strokes", strokes)
        File(output, "summary.json").writeText(result.toString(2))
    }

    @Test fun syntheticRetracingStartsKeepTheirRoundCapsOnGpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "synthetic-caps-").toFile()
        try {
            val family = InkBrushes.pressurePen(PressureCurve(minimumPercent = 36))
            for (degrees in listOf(-45, -15, 0, 30, 75)) {
                val angle = Math.toRadians(degrees.toDouble())
                val batch = MutableStrokeInputBatch()
                repeat(22) { i ->
                    // A short backwards landing movement followed by an ordinary pen stroke.
                    val distance = when (i) { 0 -> 0f; 1 -> -0.0625f; 2 -> -0.125f; else -> (i - 3) * 0.5f }
                    batch.add(InputToolType.STYLUS, 250f + (distance * kotlin.math.cos(angle)).toFloat(),
                        8000f + (distance * kotlin.math.sin(angle)).toFloat(), i * 8L,
                        pressure = if (i == 0) 0f else 0.2f)
                }
                val stroke = EditorStroke(Stroke(Brush.createWithColorIntArgb(family, Color.BLACK, 2.8f, 0.01f), batch))
                File(folder, "synthetic-$degrees.ink").writeBytes(NoteRepository.encodeStroke(stroke))
            }
            compareFiles(folder.listFiles()!!.toList(), File(context.cacheDir, "synthetic-cap-results"))
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }

    @Test fun compareExplicitCopiesAtSeveralPositionsOnGpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val argument = InstrumentationRegistry.getArguments().getString("strokePositionInputs")
        assumeTrue("Requires explicit copied stroke files", argument != null)
        val input = File(instrumentation.targetContext.getExternalFilesDir(null), argument!!)
        val files = input.listFiles()?.filter { it.extension == "ink" }?.sortedBy { it.name }.orEmpty()
        require(files.isNotEmpty())
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "stroke-position-results").apply { mkdirs() }
        compareFiles(files, output)
    }

    private fun compareFiles(files: List<File>, output: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        output.mkdirs()
        val results = JSONArray()
        val displayScale = InstrumentationRegistry.getArguments().getString("strokePositionScale")?.toFloat() ?: 35f
        for (file in files) {
            val original = NoteRepository.decodeStroke(java.util.UUID.randomUUID().toString(), file.readBytes())
            val initialDx = InstrumentationRegistry.getArguments().getString("strokePositionInitialDx")?.toFloat() ?: 0f
            val initialDy = InstrumentationRegistry.getArguments().getString("strokePositionInitialDy")?.toFloat() ?: 0f
            val source = if (initialDx == 0f && initialDy == 0f) original.ink else original.translated(initialDx, initialDy).ink
            val first = source.inputs[0]
            val deltas = listOf(-first.y, -1000f, -100f, -1f, 0f, 0.5f, 1f, 1000f)
            val copies = deltas.map { dy ->
                val batch = MutableStrokeInputBatch()
                for (i in 0 until source.inputs.size) {
                    val p = source.inputs[i]
                    batch.add(p.toolType, p.x, p.y + dy, p.elapsedTimeMillis, p.strokeUnitLengthCm,
                        p.pressure, p.tiltRadians, p.orientationRadians)
                }
                Stroke(source.brush, batch)
            }
            val renderer = CanvasStrokeRenderer.create()
            val pathRenderer = if (InstrumentationRegistry.getArguments().getString("strokePositionUseMesh") == "true")
                CanvasStrokeRenderer.create() else InkStrokeRenderer.create()
            lateinit var view: View
            ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    view = object : View(activity) {
                        override fun onDraw(canvas: Canvas) {
                            canvas.drawColor(Color.WHITE)
                            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f }
                            for (row in 0..1) for (column in copies.indices) {
                                val left = column * 220f; val top = row * 380f
                                val name = if (row == 0) "mesh old" else "path fixed"
                                canvas.drawText("$name dy=${deltas[column]}", left + 4f, top + 22f, label)
                                val matrix = Matrix().apply { setValues(floatArrayOf(displayScale, 0f, left + 100f - first.x * displayScale,
                                    0f, displayScale, top + 160f - (first.y + deltas[column]) * displayScale, 0f, 0f, 1f)) }
                                canvas.save()
                                try {
                                    canvas.clipRect(left, top + 30f, left + 220f, top + 380f)
                                    canvas.concat(matrix)
                                    if (row == 1 && InstrumentationRegistry.getArguments().getString("strokePositionLocalOutline") == "true") {
                                        val stroke = copies[column]
                                        val origin = stroke.inputs[0]
                                        canvas.translate(origin.x, origin.y)
                                        canvas.drawPath(localOutline(stroke), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke.brush.colorIntArgb })
                                    } else (if (row == 0) renderer else pathRenderer).draw(canvas, copies[column], matrix)
                                } finally { canvas.restore() }
                            }
                        }
                    }
                    activity.setContentView(view, FrameLayout.LayoutParams(1760, 760))
                }
                val drawn = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    view.invalidate()
                    view.postOnAnimation { view.postOnAnimation { drawn.countDown() } }
                }
                assertTrue(drawn.await(5, TimeUnit.SECONDS))
                val bitmap = Bitmap.createBitmap(1760, 760, Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1); var code = -1
                scenario.onActivity { activity ->
                    val location = IntArray(2); view.getLocationInWindow(location)
                    PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + 1760, location[1] + 760),
                        bitmap, { code = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, code)
                try {
                    File(output, file.nameWithoutExtension + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val result = JSONObject().put("file", file.name).put("positions", JSONArray(deltas))
                    val reference = Bitmap.createBitmap(1760, 380, Bitmap.Config.ARGB_8888)
                    val software = Canvas(reference)
                    software.drawColor(Color.WHITE)
                    for (column in copies.indices) {
                        val expected = copies[column]
                        val outline = localOutline(expected)
                        software.save()
                        software.clipRect(column * 220f, 30f, column * 220f + 220f, 380f)
                        software.translate(column * 220f + 100f, 160f)
                        software.scale(displayScale, displayScale)
                        software.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = expected.brush.colorIntArgb })
                        software.restore()
                    }
                    var missing = 0
                    fun hasInk(color: Int) = Color.red(color) < 245 || Color.green(color) < 245 || Color.blue(color) < 245
                    for (y in 52 until 348) for (x in 2 until 1758) {
                        if (listOf(0 to 0, -2 to 0, 2 to 0, 0 to -2, 0 to 2).all { (dx, dy) -> hasInk(reference.getPixel(x + dx, y + dy)) } &&
                            !hasInk(bitmap.getPixel(x, 380 + y))) missing++
                    }
                    reference.recycle()
                    result.put("missingInteriorPixels", missing)
                    for (row in 0..1) {
                        val pixels = JSONArray()
                        for (column in copies.indices) {
                            var different = 0
                            for (y in 50 until 350) for (x in 0 until 220) {
                                if (bitmap.getPixel(x, row * 380 + y) != bitmap.getPixel(column * 220 + x, row * 380 + y)) different++
                            }
                            pixels.put(different)
                        }
                        result.put(if (row == 0) "oldDifferentPixels" else "fixedDifferentPixels", pixels)
                    }
                    results.put(result)
                    assertEquals("Missing interior of ${file.name}", 0, missing)
                } finally { bitmap.recycle() }
            }
        }
        File(output, "summary.json").writeText(results.toString(2))
    }

    private fun localOutline(stroke: Stroke): Path {
        val origin = stroke.inputs[0]
        val path = Path(); val point = MutableVec()
        for (g in 0 until stroke.shape.getRenderGroupCount()) for (o in 0 until stroke.shape.getOutlineCount(g)) {
            val count = stroke.shape.getOutlineVertexCount(g, o)
            for (i in 0 until count) {
                stroke.shape.populateOutlinePosition(g, o, i, point)
                if (i == 0) path.moveTo(point.x - origin.x, point.y - origin.y)
                else path.lineTo(point.x - origin.x, point.y - origin.y)
            }
            if (count > 0) path.close()
        }
        return path
    }
}
