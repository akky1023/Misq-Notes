package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.hypot
import kotlinx.coroutines.*

class InkCanvasView(context: Context, private val model: EditorViewModel, renderAppendLimit: Int = StrokeRenderCache.APPEND_SIZE) : FrameLayout(context) {
    companion object { const val LOGICAL_WIDTH = Viewport.WIDTH; const val ERASER_RADIUS = 10f }
    private val strokeToView = Matrix()
    private val viewToStroke = Matrix()
    private var scale = 1f
    private val renderer = CanvasStrokeRenderer.create()
    // Recoloring a shared mesh for the flash must not modify resources in completed display lists.
    private val flashRenderer = CanvasStrokeRenderer.create()
    private val strokeFidelity = StrokeFidelityCache()
    private val strokeCache = StrokeRenderCache(renderAppendLimit)
    private data class RetentionState(val revision: Long, val position: ViewPosition, val width: Int,
        val height: Int, val mode: StrokeCacheMode, val selectionOffset: WorldPoint)
    private var retentionState: RetentionState? = null
    private var retainedStrokes = emptySet<EditorStroke>()
    private var prefetchScope: CoroutineScope? = null
    private var prefetchJob: Job? = null
    private val starClassifications = java.util.WeakHashMap<EditorStroke, Boolean>()
    private val pendingStarClassifications = mutableSetOf<EditorStroke>()
    private val detectedStars = mutableMapOf<Long, EditorStroke>()
    private var starNoteId: String? = null

    private fun classifyVisibleStars(visible: List<Pair<Long, EditorStroke>>) {
        val noteId = model.workspace.value.current?.id ?: return
        val scope = prefetchScope ?: return
        if (starNoteId != noteId) {
            detectedStars.clear()
            starNoteId = noteId
        }
        val pending = mutableListOf<Pair<Long, EditorStroke>>()
        visible.forEach { entry ->
            when (starClassifications[entry.second]) {
                true -> detectedStars[entry.first] = entry.second
                false -> detectedStars.remove(entry.first)
                null -> {
                    detectedStars.remove(entry.first)
                    if (pendingStarClassifications.add(entry.second)) pending.add(entry)
                }
            }
        }
        if (pending.isEmpty()) return
        scope.launch {
            try {
                val results = withContext(Dispatchers.Default) {
                    pending.map { entry ->
                        ensureActive()
                        val ink = entry.second.inkOrNull
                        val star = ink != null && SingleStrokeStar.matches(List(ink.inputs.size) { i ->
                            val p = ink.inputs[i]; SingleStrokeStar.Point(p.x, p.y)
                        })
                        entry to star
                    }
                }
                if (model.workspace.value.current?.id == noteId) {
                    results.forEach { (entry, star) ->
                        starClassifications[entry.second] = star
                        if (star) detectedStars[entry.first] = entry.second
                    }
                    completed.invalidate()
                }
            } finally { pending.forEach { pendingStarClassifications.remove(it.second) } }
        }
    }
    private data class StarFlash(val source: EditorStroke, val orange: Stroke, val noteId: String, val startedAt: Long)
    private val starFlashes = mutableListOf<StarFlash>()

    /** Only newly completed pen contacts flash; restoring, undo and preview navigation do not. */
    private fun flashIfStar(source: EditorStroke) {
        val noteId = model.workspace.value.current?.id ?: return
        val stroke = source.inkOrNull ?: return
        prefetchScope?.launch {
            val orange = withContext(Dispatchers.Default) {
                if (!SingleStrokeStar.matches(List(stroke.inputs.size) { i ->
                    val p = stroke.inputs[i]; SingleStrokeStar.Point(p.x, p.y)
                })) null else stroke.copy(stroke.brush.copyWithColorIntArgb(0xFFFF9800.toInt()))
            } ?: return@launch
            if (isAttachedToWindow && model.workspace.value.current?.id == noteId &&
                model.visibleStrokeEntries().any { it.second === source }) {
                starClassifications[source] = true
                starFlashes.add(StarFlash(source, orange, noteId, SystemClock.uptimeMillis()))
                completed.invalidate()
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawStarFlashes(canvas: Canvas) {
        if (starFlashes.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val visible = model.visibleStrokeEntries().mapTo(mutableSetOf()) { it.second }
        starFlashes.removeAll { now - it.startedAt >= 400L || it.noteId != model.workspace.value.current?.id || it.source !in visible }
        for (flash in starFlashes) {
            val bounds = flash.source.bounds ?: continue
            val elapsed = now - flash.startedAt
            val alpha = if (elapsed < 80L) 255 else ((400L - elapsed) * 255 / 320L).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.concat(strokeToView)
            // A small layer fades only this stroke, retaining its actual pressure-sensitive shape.
            canvas.saveLayerAlpha(bounds.xMin - 2f, bounds.yMin - 2f, bounds.xMax + 2f, bounds.yMax + 2f, alpha)
            flashRenderer.draw(canvas, flash.orange, strokeToView)
            canvas.restore()
            canvas.restore()
        }
        if (starFlashes.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun updateRetention(visible: List<Pair<Long, EditorStroke>>) {
        val mode = model.library.value.value.strokeCache
        val key = RetentionState(model.canvasRevision,
            if (mode == StrokeCacheMode.ALL) ViewPosition() else model.viewport.position,
            width, height, mode, model.selectionOffset)
        if (key == retentionState) return
        retentionState = key
        prefetchJob?.cancel()
        val entries = when (mode) {
            StrokeCacheMode.LIMITED -> visible.takeLast(128)
            StrokeCacheMode.VISIBLE -> visible
            else -> model.cachedStrokeEntries(mode) + visible
        }
        retainedStrokes = entries.map { it.second }.toSet()
        val retainedInk = retainedStrokes.mapNotNull { it.inkOrNull }
        strokeFidelity.retainVisible(retainedInk)
        if (mode == StrokeCacheMode.NEARBY || mode == StrokeCacheMode.ALL) {
            val visibleInk = visible.mapNotNull { it.second.inkOrNull }.toSet()
            val pending = retainedInk.filter { it !in visibleInk }
            prefetchJob = prefetchScope?.launch {
                delay(300)
                for (stroke in pending) {
                    while (pointerId != null || fingerStart != null || model.edits.busy) delay(100)
                    withContext(Dispatchers.Default) { strokeFidelity.prefetch(stroke) }
                    yield()
                }
            }
        }
    }

    private var lastCanvasChange = 0L
    private val compactStrokes: Runnable = Runnable { if (strokeCache.pendingCompaction) completed.invalidate() }
    private val paperBounds = RectF()
    private val defaultPaper = jp.handwriting.note.storage.NotePaper()
    private val completed: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            // Finger panning also draws this view without going through changeListener.
            // Compare later navigation with the frame actually drawn, not the last notification.
            completedState = currentCompletedState()
            canvas.save()
            canvas.concat(strokeToView)
            paperBounds.set(model.viewport.position.left, model.viewport.position.top, model.viewport.right, model.viewport.bottom)
            PaperRenderer.draw(canvas, model.workspace.value.current?.paper ?: defaultPaper, paperBounds, scale)
            canvas.restore()
            val compact = pointerId == null && fingerStart == null && !model.ui.value.busy && SystemClock.uptimeMillis() - lastCanvasChange >= 500
            val visible = model.visibleStrokeEntries()
            classifyVisibleStars(visible)
            updateRetention(visible)
            strokeCache.draw(canvas, visible, model.viewport.position,
                width, height, model.selectionOffset, model::isSelected, compact, retainedStrokes, model.selectionScale,
                starIds = detectedStars.keys) { target, entry, selected ->
                target.save()
                target.concat(strokeToView)
                if (selected) target.translate(model.selectionOffset.x, model.selectionOffset.y)
                if (entry.image != null) EmbeddedImages.draw(target, if (selected) entry.image.resized(model.selectionScale) else entry.image)
                else renderer.draw(target, strokeFidelity.get(entry.ink), strokeToView)
                target.restore()
            }
            if (compact && strokeCache.pendingCompaction) postOnAnimation(compactStrokes)
        }
    }
    private val ink = InProgressStrokesView(context)
    private var predictor = MotionEventPredictor.newInstance(this)
    private var predictionValid = true
    private var pointerId: Int? = null
    private var activeDevice: Int? = null
    private var drawing: InProgressStrokeId? = null
    private var erasing: Long? = null
    private var eraseRadius = 10f
    private var lastX = 0f
    private var lastY = 0f
    private var cursor: Pair<Float, Float>? = null
    private var stylusNear = false
    private var fingerDevice: Int? = null
    private var fingerSelectionId: Int? = null
    private var fingerStart: ViewPosition? = null
    private var fingerFocusX = 0f
    private var fingerFocusY = 0f
    private var fingerSpan = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var longPressPoint: WorldPoint? = null
    private var longPressWorldY = 0f
    private var longPressPointerId = 0
    private var canvasMenu: PopupWindow? = null
    private var spaceInsertionY: Float? = null
    private var spaceDragStart: Float? = null
    private var spaceDragOffset = 0f
    private data class DeferredTouch(val event: MotionEvent, val deliver: (MotionEvent) -> Unit)
    private var spaceExitTouches: MutableList<DeferredTouch>? = null
    private val spaceHandleRadius = 24f * resources.displayMetrics.density
    private val spaceBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 94, 112)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spaceHandleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 94, 112) }
    private val spaceHandleIcon = Paint(spaceBarPaint).apply {
        color = Color.WHITE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val spaceHandlePath = Path()

    private fun spaceHandleY() = model.viewport.screenY(checkNotNull(spaceInsertionY) + model.selectionOffset.y)

    private fun hitsSpaceHandle(x: Float, y: Float): Boolean =
        hypot(x - width / 2f, y - spaceHandleY()) <= spaceHandleRadius + 4f * resources.displayMetrics.density

    /** Preserve the first writing/touch gesture while translated strokes are prepared off-thread. */
    fun finishSpaceBeforeTouch(event: MotionEvent, localCoordinates: Boolean = false,
        deliver: (MotionEvent) -> Unit): Boolean {
        spaceExitTouches?.let { it.add(DeferredTouch(MotionEvent.obtain(event), deliver)); return true }
        if (spaceInsertionY == null || event.actionMasked != MotionEvent.ACTION_DOWN) return false
        val i = event.actionIndex
        val tool = event.getToolType(i)
        val location = IntArray(2)
        if (!localCoordinates) getLocationOnScreen(location)
        val x = if (localCoordinates) event.x else event.rawX - location[0]
        val y = if (localCoordinates) event.y else event.rawY - location[1]
        val startsWriting = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (!startsWriting && (tool != MotionEvent.TOOL_TYPE_FINGER || hitsSpaceHandle(x, y))) return false
        val touches = mutableListOf(DeferredTouch(MotionEvent.obtain(event), deliver))
        spaceExitTouches = touches
        spaceInsertionY = null; spaceDragStart = null; fingerDevice = null
        model.commitSelectionMove(clearAfter = true, onFinished = {
            if (spaceExitTouches === touches) {
                spaceExitTouches = null
                touches.forEach { touch ->
                    try { touch.deliver(touch.event) } finally { touch.event.recycle() }
                }
                touches.clear()
            }
        })
        invalidate()
        return true
    }
    private val fingerLongPress = Runnable {
        val point = longPressPoint
        if (point != null && fingerStart != null && !stylusNear && !model.edits.busy && !model.selectionComputing) {
            val y = longPressWorldY
            val device = fingerDevice
            finishFingerGesture(canceled = true)
            val x = model.viewport.worldX(point.x)
            if (model.selectImportedImage(x, y)) {
                fingerDevice = device; fingerStart = model.viewport.position
                fingerSelectionId = longPressPointerId
                selectionMoving = true; selectionStart = WorldPoint(x, y)
                model.beginSelectionGesture()
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                completed.invalidate(); invalidate()
            } else showCanvasMenu(point, y)
        }
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(fingerLongPress)
        longPressPoint = null
    }

    private fun showCanvasMenu(point: WorldPoint, y: Float) {
        canvasMenu?.dismiss()
        val label = TextView(context).apply {
            text = AppMessages.text(R.string.insert_space)
            textSize = 16f
            setTextColor(Color.rgb(32, 43, 51))
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }
        val popup = PopupWindow(label, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.WHITE) })
            elevation = 8 * resources.displayMetrics.density
            isOutsideTouchable = true
        }
        label.setOnClickListener {
            popup.dismiss()
            if (model.beginSpaceInsertion(y)) {
                spaceInsertionY = y
                Toast.makeText(context, AppMessages.text(R.string.insert_space_drag), Toast.LENGTH_LONG).show()
                invalidate()
            } else Toast.makeText(context, AppMessages.text(R.string.insert_space_empty), Toast.LENGTH_SHORT).show()
        }
        popup.setOnDismissListener { if (canvasMenu === popup) canvasMenu = null }
        canvasMenu = popup
        val location = IntArray(2)
        getLocationOnScreen(location)
        popup.showAtLocation(this, Gravity.TOP or Gravity.LEFT,
            location[0] + point.x.toInt(), location[1] + point.y.toInt())
    }
    private var lasso: MutableList<WorldPoint>? = null
    private var lassoMode = LassoMode.FREE
    private var selectionMoving = false
    private var imageResizing = false
    private var imagePinching = false
    private var imagePinchSpan = 0f
    private var imagePinchScale = 1f
    private var imagePinchCenter = WorldPoint(0f, 0f)
    private val resizeHandleRadius get() = 12f * resources.displayMetrics.density
    private fun resizeHandle(): WorldPoint? = model.resizableImage?.let { image ->
        WorldPoint(model.viewport.screenX(image.left + image.width * model.selectionScale + model.selectionOffset.x),
            model.viewport.screenY(image.top + image.height * model.selectionScale + model.selectionOffset.y))
    }
    private var selectionStart = WorldPoint(0f, 0f)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 94, 112); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }
    private val selectionFill = Paint().apply { color = Color.argb(24, 36, 94, 112) }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 88, 53); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val scrollIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawScrollIndicators(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val inset = 3f * density
        val thickness = 2f * density
        val paper = model.workspace.value.current?.paper?.color ?: defaultPaper.color
        val brightness = Color.red(paper) * 299 + Color.green(paper) * 587 + Color.blue(paper) * 114
        scrollIndicatorPaint.color = if (brightness < 128000) Color.argb(64, 255, 255, 255) else Color.argb(64, 0, 0, 0)
        val extent = model.viewport.height / model.viewport.scale
        val bottom = maxOf(model.contentBottom + 200f, model.viewport.bottom)
        val maxTop = (bottom - extent).coerceAtLeast(0f)
        if (maxTop > 0f) {
            val track = (height - 2f * inset).coerceAtLeast(0f)
            val thumb = (track * extent / bottom).coerceIn(minOf(24f * density, track), track)
            val top = inset + (track - thumb) * (model.viewport.position.top / maxTop).coerceIn(0f, 1f)
            canvas.drawRoundRect(width - inset - thickness, top, width - inset, top + thumb,
                thickness / 2f, thickness / 2f, scrollIndicatorPaint)
        }
        val maxLeft = Viewport.WIDTH - Viewport.WIDTH / model.viewport.position.zoom
        if (maxLeft > 0f) {
            val track = (width - 2f * inset).coerceAtLeast(0f)
            val thumb = track / model.viewport.position.zoom
            val left = inset + (track - thumb) * (model.viewport.position.left / maxLeft).coerceIn(0f, 1f)
            canvas.drawRoundRect(left, height - inset - thickness, left + thumb, height - inset,
                thickness / 2f, thickness / 2f, scrollIndicatorPaint)
        }
    }
    private val recovery = mutableMapOf<InProgressStrokeId, Recovery>()
    private data class CompletedState(val revision: Long, val position: ViewPosition,
        val paper: jp.handwriting.note.storage.NotePaper?, val selectionOffset: WorldPoint, val cacheMode: StrokeCacheMode)
    private var completedState: CompletedState? = null
    private fun currentCompletedState() = CompletedState(model.canvasRevision, model.viewport.position,
        model.workspace.value.current?.paper, model.selectionOffset, model.library.value.value.strokeCache)
    private val changeListener: () -> Unit = {
        updateTransforms()
        val state = currentCompletedState()
        if (state != completedState) {
            lastCanvasChange = SystemClock.uptimeMillis()
            completed.invalidate()
            completed.removeCallbacks(compactStrokes)
            completed.postDelayed(compactStrokes, 500)
        }
        invalidate()
    }

    /** Small input-only mirror, released on handoff; recovers completed strokes on View recreation. */
    private class Recovery(val operation: Long, val brush: Brush, val startTime: Long, val transform: Matrix, val pressureSupported: Boolean) {
        val inputs = MutableStrokeInputBatch()
        private val point = FloatArray(2)
        private var lastTime = -1L
        var completed = false
        fun record(event: MotionEvent, index: Int) {
            for (h in 0..event.historySize) {
                val historical = h < event.historySize
                val time = (if (historical) event.getHistoricalEventTime(h) else event.eventTime) - startTime
                if (time < 0 || time <= lastTime) continue
                point[0] = if (historical) event.getHistoricalX(index, h) else event.getX(index)
                point[1] = if (historical) event.getHistoricalY(index, h) else event.getY(index)
                transform.mapPoints(point)
                val x = point[0]; val y = point[1]
                if (!x.isFinite() || !y.isFinite()) continue
                val p = if (historical) event.getHistoricalPressure(index, h) else event.getPressure(index)
                inputs.add(InputToolType.STYLUS, x, y, time,
                    pressure = if (pressureSupported && p.isFinite()) p.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE)
                lastTime = time
            }
        }
    }

    init {
        setBackgroundColor(Color.rgb(250, 249, 244))
        setWillNotDraw(false)
        isFocusable = true
        contentDescription = AppMessages.text(R.string.canvas_description)
        if (Build.VERSION.SDK_INT >= 34) isAutoHandwritingEnabled = false
        addView(completed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ink, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Allocate Ink's rendering resources before the first actual pen contact.
        ink.eagerInit()
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        ink.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                strokes.forEach { (id, stroke) ->
                    recovery.remove(id)?.let {
                        val source = EditorStroke(stroke)
                        model.edits.resolveStroke(it.operation, source)
                        flashIfStar(source)
                    }
                }
                // Transfer both layers in the same UI loop to avoid gaps or double drawing.
                completed.invalidate()
                ink.removeFinishedStrokes(strokes.keys)
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        retentionState = null
        model.onCanvasChange = changeListener
        completed.invalidate()
    }
    override fun onDetachedFromWindow() {
        settleForPause()
        completed.removeCallbacks(compactStrokes)
        prefetchScope?.cancel(); prefetchScope = null; prefetchJob = null
        pendingStarClassifications.clear(); detectedStars.clear(); starClassifications.clear(); starNoteId = null
        starFlashes.clear()
        retentionState = null; retainedStrokes = emptySet()
        strokeCache.clear()
        strokeFidelity.clear()
        if (model.onCanvasChange === changeListener) model.onCanvasChange = null
        super.onDetachedFromWindow()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Ink recreates screen-sized front buffers when its SurfaceView changes size.
        // Keep that backing surface at the window size while the preview boundary moves;
        // the completed canvas and viewport still use the actual editor dimensions.
        val surfaceWidth = maxOf(MeasureSpec.getSize(widthMeasureSpec), rootView.width)
        val surfaceHeight = maxOf(MeasureSpec.getSize(heightMeasureSpec), rootView.height)
        ink.layoutParams.apply {
            width = surfaceWidth
            height = surfaceHeight
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && (w != oldw || h != oldh)) cancelGesture()
        ink.clipBounds = Rect(0, 0, w, h)
        model.viewport.size(w.toFloat(), h.toFloat())
        updateTransforms()
        completed.invalidate()
    }
    private fun updateTransforms() {
        scale = model.viewport.scale
        strokeToView.setValues(floatArrayOf(scale, 0f, -model.viewport.position.left * scale,
            0f, scale, -model.viewport.position.top * scale, 0f, 0f, 1f))
        strokeToView.invert(viewToStroke)
        model.updateViewportOverlay()
        invalidate()
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (finishSpaceBeforeTouch(event, localCoordinates = true) { onTouchEvent(it) }) return true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL && activeDevice == event.deviceId) {
            cancelGesture(); return true
        }
        val hasStylus = (0 until event.pointerCount).any {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        }
        if (!hasStylus) {
            if (activeDevice == null) fingerEvent(event)
            return true
        }
        if (spaceInsertionY != null) cancelGesture()
        finishFingerGesture(canceled = true)
        // Some tablets deliver palm and pen as separate devices, both with pointer ID 0.
        if (activeDevice != null && event.deviceId != activeDevice) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && pointerId != null) cancelGesture()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            predictionValid = true
            predictor = MotionEventPredictor.newInstance(this)
        }
        if (predictionValid) {
            try { predictor.record(event) } catch (_: IllegalArgumentException) { predictionValid = false }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                if (pointerId == null && event.getToolType(i) in arrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) {
                    requestUnbufferedDispatch(event)
                    val id = event.getPointerId(i)
                    pointerId = id
                    activeDevice = event.deviceId
                    if (model.selectionComputing) { resetGesture(); return true }
                    val x = model.viewport.worldX(event.getX(i)); val y = model.viewport.worldY(event.getY(i))
                    val moveSelection = model.beginCanvasContact(x, y)
                    val tool = if (moveSelection) EditorTool.LASSO
                        else if (event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) EditorTool.ERASER else model.effectiveTool()
                    if (tool == EditorTool.LASSO) {
                        if (model.ui.value.busy) { resetGesture(); return true }
                        model.penButton.beginLassoContact()
                        selectionMoving = moveSelection
                        selectionStart = WorldPoint(x, y)
                        if (!selectionMoving) {
                            lassoMode = model.preferences.value.value.lassoMode
                            lasso = mutableListOf(selectionStart)
                        }
                        model.beginSelectionGesture()
                        invalidate()
                    } else if (tool == EditorTool.ERASER) {
                        model.penButton.beginEraserContact()
                        erasing = model.edits.beginErase()
                        eraseRadius = model.eraserRadius
                        lastX = model.viewport.worldX(event.getX(i)); lastY = model.viewport.worldY(event.getY(i))
                        eraseSamples(event, i)
                    } else {
                        val operation = model.edits.reserveStroke()
                        val pressureRange = event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE, event.source)
                        val pressureSupported = pressureRange != null && pressureRange.max > pressureRange.min
                        val brush = model.brushForPressure(pressureSupported)
                        val strokeId = ink.startStroke(event, id, brush, viewToStroke)
                        recovery[strokeId] = Recovery(operation, brush, event.eventTime, Matrix(viewToStroke), pressureSupported).also { it.record(event, i) }
                        drawing = strokeId
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = pointerId
                val i = id?.let(event::findPointerIndex) ?: -1
                if (i >= 0) {
                    drawing?.let { strokeId ->
                        recovery[strokeId]?.record(event, i)
                        val prediction = if (predictionValid) predictor.predict() else null
                        try { ink.addToStroke(event, id!!, strokeId, prediction) } finally { prediction?.recycle() }
                    }
                    if (erasing != null) eraseSamples(event, i)
                    if (lasso != null || selectionMoving) selectionSamples(event, i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                if (event.getPointerId(i) == pointerId) {
                    if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancelGesture()
                    else {
                        drawing?.let { id ->
                            recovery[id]?.let { it.record(event, i); it.completed = true }
                            ink.finishStroke(event, event.getPointerId(i), id)
                        }
                        if (erasing != null) eraseSamples(event, i)
                        if (lasso != null || selectionMoving) selectionSamples(event, i)
                        lasso?.let { model.selectLasso(it.toList()) }
                        if (selectionMoving) model.commitSelectionMove()
                        erasing?.let { model.edits.endErase(it) }
                        resetGesture()
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }
    private fun eraseSamples(event: MotionEvent, i: Int) {
        val operation = erasing ?: return
        for (h in 0..event.historySize) {
            val x = model.viewport.worldX(if (h < event.historySize) event.getHistoricalX(i, h) else event.getX(i))
            val y = model.viewport.worldY(if (h < event.historySize) event.getHistoricalY(i, h) else event.getY(i))
            if (!x.isFinite() || !y.isFinite()) continue
            model.erase(operation, lastX, lastY, x, y, eraseRadius)
            lastX = x; lastY = y
        }
        cursor = lastX to lastY
        postInvalidateOnAnimation()
    }
    private fun selectionSamples(event: MotionEvent, i: Int) {
        for (h in 0..event.historySize) {
            val x = model.viewport.worldX(if (h < event.historySize) event.getHistoricalX(i, h) else event.getX(i))
            val y = model.viewport.worldY(if (h < event.historySize) event.getHistoricalY(i, h) else event.getY(i))
            if (!x.isFinite() || !y.isFinite()) continue
            if (imageResizing) model.resizableImage?.let { image ->
                val dx = x - selectionStart.x; val dy = y - selectionStart.y
                model.previewImageResize(1f + (dx * image.width + dy * image.height) / (image.width * image.width + image.height * image.height))
            }
            else if (selectionMoving) model.previewSelectionMove(x - selectionStart.x, y - selectionStart.y)
            else lasso?.let { points ->
                if (lassoMode == LassoMode.RECTANGLE) {
                    points.clear()
                    points.addAll(rectangleLasso(selectionStart, WorldPoint(x, y)))
                    return@let
                }
                val previous = points.last()
                if (hypot(x - previous.x, y - previous.y) * scale >= 1f) points.add(WorldPoint(x, y))
                if (points.size > 4096) {
                    val reduced = points.filterIndexed { index, _ -> index % 2 == 0 || index == points.lastIndex }
                    points.clear(); points.addAll(reduced)
                }
            }
        }
        postInvalidateOnAnimation()
    }
    fun cancelGesture(dismissMenu: Boolean = true) {
        cancelLongPress()
        spaceExitTouches?.forEach { it.event.recycle() }
        spaceExitTouches?.clear()
        spaceExitTouches = null
        if (dismissMenu) canvasMenu?.dismiss()
        if (spaceInsertionY != null) {
            spaceInsertionY = null; spaceDragStart = null; fingerDevice = null
            model.cancelSpaceInsertion()
        }
        finishFingerGesture(canceled = true)
        val stroke = drawing
        val erase = erasing
        if (selectionMoving || lasso != null) model.cancelSelectionMove()
        resetGesture()
        stroke?.let { id ->
            ink.cancelStroke(id)
            recovery.remove(id)?.let { model.edits.cancelStroke(it.operation) }
        }
        erase?.let { model.edits.endErase(it, canceled = true) }
    }
    private fun resetGesture() {
        pointerId = null; activeDevice = null; drawing = null; erasing = null; cursor = null
        model.penButton.endContact()
        model.buttonChanged()
        lasso = null; selectionMoving = false; imageResizing = false; imagePinching = false
        invalidate()
    }
    /** No synchronous render-thread flush during drawing. Only completed inputs survive recreation. */
    fun settleForPause(dismissMenu: Boolean = true) {
        stylusNear = false
        finishFingerGesture()
        cancelGesture(dismissMenu)
        recovery.toMap().forEach { (id, copy) ->
            if (copy.completed && !copy.inputs.isEmpty()) {
                model.edits.resolveStroke(copy.operation, EditorStroke(Stroke(copy.brush, copy.inputs)))
            } else model.edits.cancelStroke(copy.operation)
            recovery.remove(id)
        }
        model.viewportChanged()
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawStarFlashes(canvas)
        spaceInsertionY?.let {
            val bottom = spaceHandleY()
            val centerX = width / 2f
            canvas.drawLine(0f, bottom, width.toFloat(), bottom, spaceBarPaint)
            canvas.drawCircle(centerX, bottom, spaceHandleRadius, spaceHandleFill)
            // Division sign with an upward chevron above the bar and a downward one below it.
            val unit = spaceHandleRadius / 12f
            spaceHandlePath.rewind()
            spaceHandlePath.moveTo(centerX - 3 * unit, bottom - 5 * unit)
            spaceHandlePath.lineTo(centerX, bottom - 8 * unit)
            spaceHandlePath.lineTo(centerX + 3 * unit, bottom - 5 * unit)
            spaceHandlePath.moveTo(centerX - 5 * unit, bottom)
            spaceHandlePath.lineTo(centerX + 5 * unit, bottom)
            spaceHandlePath.moveTo(centerX - 3 * unit, bottom + 5 * unit)
            spaceHandlePath.lineTo(centerX, bottom + 8 * unit)
            spaceHandlePath.lineTo(centerX + 3 * unit, bottom + 5 * unit)
            canvas.drawPath(spaceHandlePath, spaceHandleIcon)
        }
        model.selectionBounds?.takeUnless { model.insertingSpace }?.let { b ->
            val dx = model.selectionOffset.x; val dy = model.selectionOffset.y
            val left = model.viewport.screenX(b.left + dx) - 4f
            val top = model.viewport.screenY(b.top + dy) - 4f
            val right = model.viewport.screenX(b.left + (b.right - b.left) * model.selectionScale + dx) + 4f
            val bottom = model.viewport.screenY(b.top + (b.bottom - b.top) * model.selectionScale + dy) + 4f
            canvas.drawRect(left, top, right, bottom, selectionFill)
            canvas.drawRect(left, top, right, bottom, selectionPaint)
            resizeHandle()?.let { handle ->
                canvas.drawCircle(handle.x, handle.y, resizeHandleRadius, spaceHandleFill)
                val r = resizeHandleRadius * 0.45f
                canvas.drawLine(handle.x - r, handle.y - r, handle.x + r, handle.y + r, spaceHandleIcon)
                canvas.drawLine(handle.x - r, handle.y - r, handle.x - r, handle.y, spaceHandleIcon)
                canvas.drawLine(handle.x - r, handle.y - r, handle.x, handle.y - r, spaceHandleIcon)
                canvas.drawLine(handle.x + r, handle.y + r, handle.x + r, handle.y, spaceHandleIcon)
                canvas.drawLine(handle.x + r, handle.y + r, handle.x, handle.y + r, spaceHandleIcon)
            }
        }
        lasso?.takeIf { it.isNotEmpty() }?.let { points ->
            val path = Path()
            path.moveTo(model.viewport.screenX(points.first().x), model.viewport.screenY(points.first().y))
            points.drop(1).forEach { path.lineTo(model.viewport.screenX(it.x), model.viewport.screenY(it.y)) }
            path.close(); canvas.drawPath(path, selectionPaint)
        }
        cursor?.let { (x, y) ->
            // Indicate eraser size; sweep tests additionally cover travel between samples.
            val r = eraseRadius * scale
            val sx = model.viewport.screenX(x); val sy = model.viewport.screenY(y)
            canvas.drawCircle(sx, sy, r, cursorPaint)
        }
        drawScrollIndicators(canvas)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun computeVerticalScrollRange() = (maxOf(model.contentBottom + 200f, model.viewport.bottom) * scale).toInt().coerceAtLeast(height)
    override fun computeVerticalScrollExtent() = height
    override fun computeVerticalScrollOffset() = (model.viewport.position.top * scale).toInt()
    override fun computeHorizontalScrollRange() = (Viewport.WIDTH * scale).toInt().coerceAtLeast(width)
    override fun computeHorizontalScrollExtent() = width
    override fun computeHorizontalScrollOffset() = (model.viewport.position.left * scale).toInt()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if ((0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }) {
            stylusNear = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            if (stylusNear) finishFingerGesture(canceled = true)
        }
        return super.dispatchHoverEvent(event)
    }
    private fun fingerEvent(event: MotionEvent) {
        if (stylusNear || model.penButton.held) { finishFingerGesture(canceled = true); return }
        if (spaceInsertionY != null) {
            cancelLongPress()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_FINGER &&
                        hitsSpaceHandle(event.x, event.y)) {
                        fingerDevice = event.deviceId
                        spaceDragStart = event.y
                        spaceDragOffset = model.selectionOffset.y
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelGesture()
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val start = spaceDragStart
                    if (start != null && fingerDevice == event.deviceId) {
                        model.previewSelectionMove(0f, (spaceDragOffset + (event.y - start) / scale).coerceAtLeast(0f))
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancelGesture()
                            else {
                                spaceDragStart = null; fingerDevice = null
                            }
                        }
                    }
                }
            }
            invalidate()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (model.ui.value.busy || event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_FINGER) return
            val x = model.viewport.worldX(event.x); val y = model.viewport.worldY(event.y)
            imageResizing = resizeHandle()?.let { hypot(event.x - it.x, event.y - it.y) <= resizeHandleRadius * 2 } == true
            val moveSelection = imageResizing || model.beginCanvasContact(x, y)
            if (!moveSelection && model.penButton.lassoHeld) return
            fingerDevice = event.deviceId; fingerStart = model.viewport.position
            if (moveSelection) {
                fingerSelectionId = event.getPointerId(event.actionIndex)
                selectionMoving = true; selectionStart = WorldPoint(x, y)
                model.beginSelectionGesture()
            }
            longPressPoint = WorldPoint(event.x, event.y)
            longPressWorldY = y
            longPressPointerId = event.getPointerId(event.actionIndex)
            if (!moveSelection) postDelayed(fingerLongPress, ViewConfiguration.getLongPressTimeout().toLong())
        }
        if (fingerDevice != event.deviceId) return
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL ||
            event.actionMasked == MotionEvent.ACTION_UP) cancelLongPress()
        longPressPoint?.let { start ->
            if ((0..event.historySize).any { h ->
                val x = if (h < event.historySize) event.getHistoricalX(0, h) else event.x
                val y = if (h < event.historySize) event.getHistoricalY(0, h) else event.y
                hypot(x - start.x, y - start.y) > touchSlop
            }) cancelLongPress()
        }
        if (fingerSelectionId != null && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_FINGER) {
            val i = event.actionIndex
            if (!model.beginCanvasContact(model.viewport.worldX(event.getX(i)), model.viewport.worldY(event.getY(i)))) {
                // A second finger outside the moving selection begins the normal pan/pinch gesture.
                finishFingerGesture(canceled = true)
                fingerDevice = event.deviceId; fingerStart = model.viewport.position
            } else if (model.preferences.value.value.pinchResizeMedia && model.resizableImage != null && event.pointerCount == 2) {
                imagePinching = true
                imageResizing = false
                imagePinchSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                imagePinchScale = model.selectionScale
                val image = checkNotNull(model.resizableImage)
                imagePinchCenter = WorldPoint(image.left + image.width * imagePinchScale / 2f + model.selectionOffset.x,
                    image.top + image.height * imagePinchScale / 2f + model.selectionOffset.y)
            }
        }
        fingerSelectionId?.let { id ->
            val i = event.findPointerIndex(id)
            val canceled = event.actionMasked == MotionEvent.ACTION_CANCEL ||
                (Build.VERSION.SDK_INT >= 33 &&
                    (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) &&
                    event.flags and MotionEvent.FLAG_CANCELED != 0)
            if (canceled || i < 0) finishFingerGesture(canceled = true)
            else if (imagePinching) {
                if (event.pointerCount >= 2 && imagePinchSpan > 0f) {
                    val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    model.previewImagePinch(imagePinchScale * span / imagePinchSpan, imagePinchCenter)
                }
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_UP) finishFingerGesture()
            }
            else when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> selectionSamples(event, i)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == id) {
                    selectionSamples(event, i)
                    finishFingerGesture()
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                }
            }
            return
        }
        if (model.ui.value.busy || model.penButton.lassoHeld) { finishFingerGesture(canceled = true); return }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            finishFingerGesture(canceled = event.actionMasked == MotionEvent.ACTION_CANCEL)
            return
        }
        val points = (0 until event.pointerCount).filter {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER &&
                !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && it == event.actionIndex)
        }.sortedBy { event.getPointerId(it) }.take(2)
        if (points.isEmpty()) return
        val x = points.sumOf { event.getX(it).toDouble() }.toFloat() / points.size
        val y = points.sumOf { event.getY(it).toDouble() }.toFloat() / points.size
        val span = if (points.size == 2) hypot(event.getX(points[0]) - event.getX(points[1]), event.getY(points[0]) - event.getY(points[1])) else 0f
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val factor = if (span > 0 && fingerSpan > 0) span / fingerSpan else 1f
            model.transformViewport(fingerFocusX, fingerFocusY, x, y, factor)
            updateTransforms(); completed.invalidate()
        }
        fingerFocusX = x; fingerFocusY = y; fingerSpan = span
    }
    private fun finishFingerGesture(canceled: Boolean = false) {
        cancelLongPress()
        val initial = fingerStart ?: return
        fingerDevice = null; fingerStart = null
        if (fingerSelectionId != null) {
            fingerSelectionId = null; selectionMoving = false; imageResizing = false; imagePinching = false
            if (canceled) model.cancelSelectionMove() else model.commitSelectionMove()
            completed.invalidate(); invalidate()
            return
        }
        if (canceled) model.viewport.restore(initial)
        updateTransforms(); completed.invalidate()
        if (!canceled && initial != model.viewport.position) model.viewportChanged()
    }
}
