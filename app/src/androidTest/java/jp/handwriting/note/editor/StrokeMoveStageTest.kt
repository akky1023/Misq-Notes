package jp.handwriting.note.editor

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.ink.geometry.MutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.PerformanceActivity
import jp.handwriting.note.storage.NoteRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated, opt-in experiments on one explicitly supplied stroke; never opens saved notebooks. */
@OptIn(ExperimentalInkCustomBrushApi::class)
class StrokeMoveStageTest {
    private data class Stage(val name: String, val stroke: EditorStroke, val offset: WorldPoint = WorldPoint(0f, 0f))

    @Test fun compareMoveSaveReloadAndDisplayListsAtTheActualViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val sourceName = args.getString("stageSource")
        assumeTrue("Requires one explicitly supplied stroke", sourceName != null)
        val root = instrumentation.targetContext.getExternalFilesDir(null)!!
        fun load(name: String) = NoteRepository.decodeStroke(java.util.UUID.randomUUID().toString(), File(root, name).readBytes())
        val source = load(sourceName!!)
        val dx = args.getString("stageDx")?.toFloat() ?: 13.22821f
        val dy = args.getString("stageDy")?.toFloat() ?: 23.655273f
        val moved = source.translated(dx, dy)
        val reloaded = NoteRepository.decodeStroke(java.util.UUID.randomUUID().toString(), NoteRepository.encodeStroke(moved))
        val rebuilt = EditorStroke(Stroke(moved.ink.brush, moved.ink.inputs))
        val stages = mutableListOf(Stage("canvas-translation", source, WorldPoint(dx, dy)),
            Stage("move-in-memory", moved), Stage("save-reload", reloaded), Stage("same-inputs-rebuilt", rebuilt))
        args.getString("stageSaved")?.let { stages += Stage("saved-user-stroke", load(it)) }
        val first = moved.ink.inputs[0]
        val localInputs = MutableStrokeInputBatch()
        for (i in 0 until moved.ink.inputs.size) {
            val p = moved.ink.inputs[i]
            localInputs.add(p.toolType, p.x - first.x, p.y - first.y, p.elapsedTimeMillis, p.strokeUnitLengthCm,
                p.pressure, p.tiltRadians, p.orientationRadians)
        }
        localInputs.setNoiseSeed(moved.ink.inputs.getNoiseSeed())
        stages += Stage("local-geometry-regenerated", EditorStroke(Stroke(moved.ink.brush, localInputs)), WorldPoint(first.x, first.y))
        val position = ViewPosition(args.getString("stageZoom")?.toFloat() ?: 3.7992334f,
            args.getString("stageLeft")?.toFloat() ?: 320.72104f, args.getString("stageTop")?.toFloat() ?: 7160.276f)
        val scale = 3200f / Viewport.WIDTH * position.zoom
        val matrix = Matrix().apply { setValues(floatArrayOf(scale, 0f, -position.left * scale,
            0f, scale, -position.top * scale, 0f, 0f, 1f)) }
        val region = Rect(1320, 1120, 1580, 1240)
        val output = File(root, "stroke-move-stages").apply { mkdirs() }
        val methods = listOf("ink-direct", "ink-fresh", "ink-node-cold", "ink-node-replay", "manual-world", "manual-clean-world", "manual-local", "manual-pixel")
        val snapshots = JSONArray()
        fun outline(ink: Stroke): List<List<Pair<Float, Float>>> = buildList {
            val p = MutableVec()
            for (g in 0 until ink.shape.getRenderGroupCount()) for (o in 0 until ink.shape.getOutlineCount(g)) {
                add(List(ink.shape.getOutlineVertexCount(g, o)) { i -> ink.shape.populateOutlinePosition(g, o, i, p); p.x to p.y })
            }
        }
        val outlines = stages.map { outline(it.stroke.ink) }
        val paths = outlines.map { lines -> List(3) { kind ->
            val origin = lines.first().first()
            Path().apply {
                lines.forEach { points ->
                    var previous: Pair<Float, Float>? = null
                    points.forEachIndexed { i, point ->
                        if (kind == 1 && point == previous) return@forEachIndexed
                        val x = if (kind == 2) point.first - origin.first else point.first
                        val y = if (kind == 2) point.second - origin.second else point.second
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                        previous = point
                    }
                    close()
                }
            }
        } }
        for ((index, stage) in stages.withIndex()) {
            val inputs = JSONArray()
            for (i in 0 until stage.stroke.ink.inputs.size) {
                val p = stage.stroke.ink.inputs[i]
                inputs.put(JSONArray(listOf(p.x, p.y, p.elapsedTimeMillis, p.pressure)))
            }
            snapshots.put(JSONObject().put("stage", stage.name).put("inputs", inputs)
                .put("outlines", JSONArray(outlines[index].map { line -> line.map { listOf(it.first, it.second) } })))
        }
        File(output, "geometry.json").writeText(snapshots.toString(2))
        val renderer = InkStrokeRenderer.create()
        val captured = mutableMapOf<Pair<String, String>, IntArray>()
        val cache = StrokeRenderCache()
        var stageIndex = 0; var method = methods.first()
        lateinit var view: View
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        ActivityScenario.launch(PerformanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = object : View(activity) {
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(0xFFFAF9F4.toInt())
                        val stage = stages[stageIndex]
                        fun drawInk(target: Canvas, active: CanvasStrokeRenderer) {
                            target.save()
                            target.concat(matrix)
                            target.translate(stage.offset.x, stage.offset.y)
                            active.draw(target, stage.stroke.ink, matrix)
                            target.restore()
                        }
                        if (method.startsWith("ink-node")) {
                            cache.draw(canvas, listOf(11546L to stage.stroke), position, width, height,
                                WorldPoint(0f, 0f), { false }) { target, _, _ -> drawInk(target, renderer) }
                        } else if (method == "ink-direct" || method == "ink-fresh") {
                            drawInk(canvas, if (method == "ink-fresh") InkStrokeRenderer.create() else renderer)
                        } else {
                            paint.color = stage.stroke.ink.brush.colorIntArgb
                            canvas.save()
                            if (method == "manual-pixel") {
                                val path = Path()
                                for (points in outlines[stageIndex]) {
                                    points.forEachIndexed { i, p ->
                                        val x = ((p.first.toDouble() + stage.offset.x - position.left) * scale).toFloat()
                                        val y = ((p.second.toDouble() + stage.offset.y - position.top) * scale).toFloat()
                                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                    }; path.close()
                                }
                                canvas.drawPath(path, paint)
                            } else {
                                canvas.concat(matrix); canvas.translate(stage.offset.x, stage.offset.y)
                                val kind = when (method) { "manual-clean-world" -> 1; "manual-local" -> 2; else -> 0 }
                                if (kind == 2) {
                                    val origin = outlines[stageIndex].first().first()
                                    canvas.translate(origin.first, origin.second)
                                }
                                canvas.drawPath(paths[stageIndex][kind], paint)
                            }
                            canvas.restore()
                        }
                    }
                }
                activity.setContentView(view, FrameLayout.LayoutParams(3200, 2000))
            }
            fun capture(): Bitmap {
                val drawn = CountDownLatch(1)
                instrumentation.runOnMainSync { view.invalidate(); view.postOnAnimation { view.postOnAnimation { drawn.countDown() } } }
                assertTrue(drawn.await(5, TimeUnit.SECONDS))
                val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1); var code = -1
                scenario.onActivity { activity ->
                    val location = IntArray(2); view.getLocationInWindow(location)
                    val windowRegion = Rect(region).apply { offset(location[0], location[1]) }
                    PixelCopy.request(activity.window, windowRegion, bitmap, { code = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, code)
                return bitmap
            }
            try {
                for (i in stages.indices) for (name in methods) {
                    scenario.onActivity {
                        if (name != "ink-node-replay") cache.clear()
                        stageIndex = i; method = name
                    }
                    val bitmap = capture()
                    try {
                        File(output, "${stages[i].name}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        captured[stages[i].name to name] = IntArray(bitmap.width * bitmap.height).also {
                            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        }
                    }
                    finally { bitmap.recycle() }
                }
            } finally { scenario.onActivity { cache.clear() } }
        }
        File(output, "viewport.json").writeText(JSONObject().put("left", position.left).put("top", position.top)
            .put("zoom", position.zoom).put("scale", scale).put("roi", JSONArray(listOf(region.left, region.top, region.right, region.bottom))).toString(2))
        if (args.getString("stageVerifyFix") == "true") {
            val reference = captured.getValue("local-geometry-regenerated" to "ink-direct")
            fun missing(pixels: IntArray): Int {
                fun dark(color: Int) = maxOf(Color.red(color), Color.green(color), Color.blue(color)) < 120
                var count = 0; val width = region.width()
                for (y in 1 until region.height() - 1) for (x in 1 until width - 1) {
                    val i = y * width + x
                    if (listOf(i, i - 1, i + 1, i - width, i + width).all { dark(reference[it]) } && !dark(pixels[i])) count++
                }
                return count
            }
            val before = missing(captured.getValue("move-in-memory" to "manual-world"))
            val after = missing(captured.getValue("move-in-memory" to "ink-direct"))
            File(output, "regression.json").writeText(JSONObject().put("beforeMissing", before).put("afterMissing", after).toString(2))
            assertTrue("Fixture must reproduce the notch", before >= 20)
            assertEquals("Localized renderer must keep the filled stroke", 0, after)
            for (stage in stages) for (name in listOf("ink-fresh", "ink-node-cold", "ink-node-replay")) {
                assertArrayEquals("${stage.name}: $name", captured.getValue(stage.name to "ink-direct"), captured.getValue(stage.name to name))
            }
        }
    }
}
