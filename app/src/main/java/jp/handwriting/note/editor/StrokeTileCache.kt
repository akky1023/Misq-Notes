package jp.handwriting.note.editor

import android.graphics.*
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.withSave
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.*
import kotlin.math.floor

/** Screen-resolution pixels, rather than replayed vector commands, for completed ink only. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StrokeTileCache(
    private val invalidate: () -> Unit,
    private val maxBytes: Long = minOf(80L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3),
) {
    init { require(maxBytes >= 8L * 1024 * 1024) }
    companion object { internal const val SIZE = 256; private const val PAD = 2 }
    private data class Key(val x: Int, val y: Int)
    private data class Item(val id: Long, val stroke: EditorStroke, val selected: Boolean,
        val dx: Float, val dy: Float, val factor: Float)
    private data class Tile(val bitmap: Bitmap, val items: List<Item>)
    private data class Pending(val items: List<Item>, val token: Long, val job: Job)
    private val tiles = LinkedHashMap<Key, Tile>(16, .75f, true)
    private val pending = mutableMapOf<Key, Pending>()
    private val failed = mutableMapOf<Key, List<Item>>()
    private var scope: CoroutineScope? = null
    private var worker: InkStrokeRenderer.LocalRenderer? = null
    private var epoch = 0L
    private var token = 0L
    private var resolution = 0f
    private var wantedResolution = 0f
    private var resolutionChangedAt = 0L
    private var disabled = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    internal var bytes = 0L; private set
    internal var rasterizedTiles = 0; private set
    internal var appendedStrokes = 0; private set
    internal var fallbackTiles = 0; private set
    internal var needsRefinement = false; private set
    internal val pendingCount get() = pending.size

    fun clear() {
        epoch++
        pending.values.forEach { it.job.cancel() }; pending.clear(); failed.clear()
        // RenderThread may still reference these pixels in the preceding display list.
        // Drop ownership, rather than recycling bitmaps that the GPU could still be reading.
        tiles.clear(); bytes = 0; resolution = 0f; wantedResolution = 0f; disabled = false
        needsRefinement = false
    }
    fun close() { clear(); scope?.cancel(); scope = null; worker = null }

    fun draw(canvas: Canvas, entries: List<Pair<Long, EditorStroke>>, position: ViewPosition,
        width: Int, height: Int, offset: WorldPoint, selected: (EditorStroke) -> Boolean,
        selectionScale: Float, gestureActive: Boolean, fallback: (Canvas) -> Unit) {
        if (disabled || width <= 0 || height <= 0) {
            fallback(canvas); return
        }
        val scale = width / Viewport.WIDTH * position.zoom
        fun estimate(factor: Double) = (width*factor+2*SIZE)*(height*factor+2*SIZE)*4
        var factor = 1.0
        if (estimate(factor) > maxBytes * .75) {
            var low = 0.0; var high = 1.0
            repeat(20) { val middle=(low+high)/2; if (estimate(middle)<=maxBytes*.75) low=middle else high=middle }
            factor = low
        }
        val desired = scale * factor.toFloat()
        val now = SystemClock.uptimeMillis()
        if (wantedResolution != desired) { wantedResolution = desired; resolutionChangedAt = now }
        val currentEstimate = if (resolution == 0f) Double.MAX_VALUE else
            (width * resolution / scale + 2.0 * SIZE) * (height * resolution / scale + 2.0 * SIZE) * 4
        if (resolution == 0f || currentEstimate > maxBytes * .9 ||
            (!gestureActive && now - resolutionChangedAt >= 150 && resolution != desired)) {
            clear(); resolution = desired; wantedResolution = desired; resolutionChangedAt = now
        }
        needsRefinement = resolution != desired
        val cell = SIZE / resolution
        val firstX = floor(position.left / cell).toInt()
        val lastX = floor((position.left + width / scale) / cell).toInt()
        val firstY = floor(position.top / cell).toInt()
        val lastY = floor((position.top + height / scale) / cell).toInt()
        val contents = linkedMapOf<Key, MutableList<Item>>()
        for (y in firstY..lastY) for (x in firstX..lastX) contents[Key(x,y)] = mutableListOf()
        val margin = PAD / resolution
        for ((id, stroke) in entries) {
            val isSelected = selected(stroke)
            val dx = if (isSelected) offset.x else 0f; val dy = if (isSelected) offset.y else 0f
            val factor = if (isSelected && stroke.image != null) selectionScale else 1f
            val b = stroke.bounds ?: continue
            val left = b.xMin + dx; val top = b.yMin + dy
            val right = left + (b.xMax-b.xMin)*factor; val bottom = top + (b.yMax-b.yMin)*factor
            val x0 = maxOf(firstX, floor((left-margin)/cell).toInt())
            val x1 = minOf(lastX, floor((right+margin)/cell).toInt())
            val y0 = maxOf(firstY, floor((top-margin)/cell).toInt())
            val y1 = minOf(lastY, floor((bottom+margin)/cell).toInt())
            val item = Item(id, stroke, isSelected, dx, dy, factor)
            for (y in y0..y1) for (x in x0..x1) contents.getValue(Key(x,y)).add(item)
        }
        // Cancel obsolete work before it can replace a newer edit or repopulate an old viewport.
        pending.entries.toList().forEach { (key, value) ->
            if (contents[key] != value.items) { value.job.cancel(); pending.remove(key) }
        }
        failed.entries.removeAll { (key, value) -> contents[key] != value }
        fallbackTiles = 0
        val missing = Path()
        for ((key, items) in contents) {
            val left = (key.x * cell - position.left) * scale
            val top = (key.y * cell - position.top) * scale
            val right = ((key.x+1) * cell - position.left) * scale
            val bottom = ((key.y+1) * cell - position.top) * scale
            if (items.isEmpty()) { remove(key); continue }
            val tile = tiles[key]
            if (tile != null && tile.items == items) {
                val border = PAD * scale / resolution
                canvas.withSave {
                    clipRect(left, top, right, bottom)
                    drawBitmap(tile.bitmap, null, RectF(left-border, top-border, right+border, bottom+border), paint)
                }
            } else {
                missing.addRect(left, top, right, bottom, Path.Direction.CW); fallbackTiles++
                if (pending[key]?.items != items && failed[key] != items) schedule(key, items.toList(), tile)
            }
        }
        if (fallbackTiles != 0) canvas.withSave { clipPath(missing); fallback(this) }
    }

    private fun remove(key: Key) { tiles.remove(key)?.let { bytes -= it.bitmap.allocationByteCount } }

    private fun schedule(key: Key, items: List<Item>, previous: Tile?) {
        val targetScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)).also { scope = it }
        val renderer = worker ?: InkStrokeRenderer.createForDisplay().also { worker = it }
        val generation = epoch; val version = ++token; val density = resolution
        val append = previous != null && previous.items.size < items.size &&
            previous.items.indices.all { previous.items[it] == items[it] }
        val job = targetScope.launch(start = CoroutineStart.LAZY) {
            try {
                val bitmap = createBitmap(SIZE+PAD*2, SIZE+PAD*2)
                val target = Canvas(bitmap)
                if (append) target.drawBitmap(previous!!.bitmap, 0f, 0f, null)
                val matrix = Matrix().apply { setValues(floatArrayOf(density,0f,PAD-key.x*SIZE.toFloat(),
                    0f,density,PAD-key.y*SIZE.toFloat(),0f,0f,1f)) }
                val additions = if (append) items.drop(previous!!.items.size) else items
                for (item in additions) {
                    ensureActive()
                    target.withSave {
                        concat(matrix)
                        if (item.selected) translate(item.dx,item.dy)
                        val image = item.stroke.image
                        if (image != null) EmbeddedImages.draw(this, image.resized(item.factor))
                        else renderer.draw(this, item.stroke.ink, matrix)
                    }
                }
                ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    if (generation == epoch && pending[key]?.token == version) {
                        pending.remove(key); remove(key)
                        tiles[key] = Tile(bitmap, items); bytes += bitmap.allocationByteCount
                        rasterizedTiles++; if (append) appendedStrokes += additions.size
                        while (bytes > maxBytes && tiles.isNotEmpty()) remove(tiles.keys.first())
                        invalidate()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main.immediate) {
                    if (generation == epoch) { clear(); disabled = true; invalidate() }
                }
                Log.w("StrokeTileCache", "Pixel cache unavailable; retaining vector drawing", e)
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (generation == epoch && pending[key]?.token == version) {
                        pending.remove(key); failed[key] = items; invalidate()
                    }
                }
                Log.w("StrokeTileCache", "Tile preparation failed; retaining vector drawing", e)
            }
        }
        pending[key] = Pending(items, version, job); job.start()
    }
}
