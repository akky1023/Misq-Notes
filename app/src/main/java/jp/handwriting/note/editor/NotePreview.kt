package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.ceil

internal class PreviewDocument(val bottom: Float, val rows: List<StarPreviewRows.Row>,
    val render: suspend (RectF) -> Bitmap)

@Composable
internal fun NotePreview(model: EditorViewModel, modifier: Modifier = Modifier, onRight: Boolean = false,
    renderContent: Boolean = true) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val visibleRegion by model.visibleRegion.collectAsStateWithLifecycle()
    val note = workspace.current ?: return
    var document by remember(note.id) { mutableStateOf<PreviewDocument?>(null) }
    val starCache = remember(note.id) { mutableMapOf<String, StrokeIndex.Bounds?>() }
    LaunchedEffect(note.id, note.paper, renderContent) {
        if (!renderContent) return@LaunchedEffect
        // Hydration publishes frequently. Finish each snapshot instead of restarting a debounce
        // (and the first preview) until the entire note has arrived.
        // Finishing a viewport read alone does not change the preview document.
        val changes = snapshotFlow { ui.contentRevision to ui.busy }
            .filter { !it.second }.map { it.first }.distinctUntilChanged().conflate()
        changes.collect {
            val entries = model.edits.snapshot()
            val strokes = entries.map { it.value }
            val bottom = maxOf(1000f, model.contentBottom + 24f)
            fun snapshot(): PreviewDocument {
                val stars = strokes.mapNotNull { starCache[it.storageId] }
                val starIds = strokes.filter { starCache[it.storageId] != null }.mapTo(mutableSetOf()) { it.storageId }
                return PreviewDocument(bottom, StarPreviewRows.group(stars)) { bounds ->
                    NoteExport.preview(strokes, note.paper, bounds, enlargedStrokes = starIds)
                }
            }
            // Ink is usable before recognition and before the unread regions finish loading.
            document = snapshot()
            val foundStars = withContext(Dispatchers.Default) {
                starCache.keys.retainAll(entries.map { it.value.storageId }.toSet())
                var found = false
                entries.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    val stroke = entry.value
                    if (!starCache.containsKey(stroke.storageId)) {
                        val ink = stroke.inkOrNull
                        val bounds = stroke.bounds
                        starCache[stroke.storageId] = if (ink != null && bounds != null &&
                            SingleStrokeStar.matches(List(ink.inputs.size) { i ->
                                val p = ink.inputs[i]; SingleStrokeStar.Point(p.x, p.y)
                            })) StrokeIndex.Bounds(bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax) else null
                        if (starCache[stroke.storageId] != null) found = true
                    }
                }
                found
            }
            if (foundStars) document = snapshot()
            delay(300)
        }
    }
    key(note.id) {
        Box(modifier) {
            NotePreview(document, visibleRegion, ui.busy || workspace.working, Modifier.fillMaxSize(), onRight, renderContent,
                onMove = model::moveViewportFromPreview, onMoveEnd = model::viewportChanged)
            if (renderContent && ui.partialContent) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/** Shared by the live editor and the immutable merge review. Navigation belongs to the caller. */
@Composable
internal fun NotePreview(document: PreviewDocument?, visibleRegion: StrokeIndex.Bounds, busy: Boolean,
    modifier: Modifier = Modifier, onRight: Boolean = false, renderContent: Boolean = true,
    onMove: (Float, Float) -> Unit, onMoveEnd: () -> Unit = {}) {
    var starsOnly by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    val starState = rememberLazyListState()
    Box(modifier) {
        if (renderContent) {
            val contentModifier = Modifier.fillMaxSize()
            if (starsOnly) StarRowsPreview(document, visibleRegion, busy, starState, contentModifier, onMove, onMoveEnd)
            else WholeNotePreview(document, visibleRegion, busy, state, contentModifier, onMove, onMoveEnd)
        }
        PreviewStarButton(starsOnly, { starsOnly = !starsOnly },
            Modifier.align(if (onRight) AbsoluteAlignment.BottomRight else AbsoluteAlignment.BottomLeft).padding(8.dp))
    }
}

@Composable
private fun PreviewStarButton(checked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(240), label = "previewStarFill")
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    FloatingActionButton(onClick = onClick, modifier = modifier.semantics {
        contentDescription = AppMessages.text(R.string.preview_star_filter)
        role = Role.Switch
        toggleableState = ToggleableState(checked)
    }) {
        Canvas(Modifier.size(24.dp)) {
            val radius = size.minDimension * (0.46f + 0.04f * kotlin.math.sin(progress * kotlin.math.PI).toFloat())
            val star = Path().apply {
                repeat(10) { index ->
                    val angle = -kotlin.math.PI / 2 + index * kotlin.math.PI / 5
                    val r = radius * if (index % 2 == 0) 1f else 0.45f
                    val x = center.x + r * kotlin.math.cos(angle).toFloat()
                    val y = center.y + r * kotlin.math.sin(angle).toFloat()
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(star, tint, alpha = progress)
            drawPath(star, tint, style = Stroke(1.7.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun StarRowsPreview(document: PreviewDocument?, visibleRegion: StrokeIndex.Bounds,
    busy: Boolean, state: LazyListState, modifier: Modifier, onMove: (Float, Float) -> Unit, onMoveEnd: () -> Unit) {
    if (document == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
    } else if (document.rows.isEmpty()) {
        Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Text(AppMessages.text(R.string.preview_no_stars), style = MaterialTheme.typography.bodySmall)
        }
    } else LazyColumn(modifier.semantics { contentDescription = AppMessages.text(R.string.preview_star_rows) },
        state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(document.rows.size) { index ->
            val row = document.rows[index]
            Box(Modifier.fillMaxWidth().semantics {
                contentDescription = AppMessages.text(R.string.preview_star_row, index + 1)
            }.clickable(enabled = !busy) {
                val height = visibleRegion.bottom - visibleRegion.top
                onMove(visibleRegion.left, ((row.top + row.bottom - height) / 2f).coerceAtLeast(0f))
                onMoveEnd()
            }) {
                PreviewBand(document, row.top, row.bottom, visibleRegion, showViewport = false)
            }
        }
    }
}

@Composable
private fun WholeNotePreview(document: PreviewDocument?, visibleRegion: StrokeIndex.Bounds,
    busy: Boolean, state: LazyListState, modifier: Modifier, onMove: (Float, Float) -> Unit, onMoveEnd: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val currentRegion by rememberUpdatedState(visibleRegion)
    val currentBusy by rememberUpdatedState(busy)
    val currentMove by rememberUpdatedState(onMove)
    val currentMoveEnd by rememberUpdatedState(onMoveEnd)
    val documentBottom by rememberUpdatedState(document?.bottom ?: 1000f)
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val scale = with(density) { maxWidth.toPx() } / Viewport.WIDTH
        val paneHeight = with(density) { maxHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }.coerceAtMost(paneHeight / 10f)
        LaunchedEffect(visibleRegion, scale, paneHeight, document != null, dragging) {
            if (dragging || document == null || scale <= 0f) return@LaunchedEffect
            val scroll = state.firstVisibleItemIndex * Viewport.WIDTH * scale + state.firstVisibleItemScrollOffset
            val top = visibleRegion.top * scale
            val bottom = visibleRegion.bottom * scale
            val target = when {
                bottom - top >= paneHeight - 2f * margin -> top - margin
                top < scroll + margin -> top - margin
                bottom > scroll + paneHeight - margin -> bottom - paneHeight + margin
                else -> return@LaunchedEffect
            }.coerceAtLeast(0f)
            val bandPixels = Viewport.WIDTH * scale
            state.scrollToItem((target / bandPixels).toInt(), (target % bandPixels).toInt())
        }
        LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = AppMessages.text(R.string.note_preview) }
            .pointerInput(scale) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val scroll = state.firstVisibleItemIndex * Viewport.WIDTH * scale + state.firstVisibleItemScrollOffset
                    val region = currentRegion
                    val x = down.position.x / scale
                    val y = (down.position.y + scroll) / scale
                    val tolerance = 8.dp.toPx() / scale
                    if (!currentBusy && x in (region.left - tolerance)..(region.right + tolerance) &&
                        y in (region.top - tolerance)..(region.bottom + tolerance)) {
                        down.consume()
                        dragging = true
                        val maximumTop = (maxOf(documentBottom, region.bottom) - (region.bottom - region.top)).coerceAtLeast(0f)
                        try {
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                val delta = (change.position - down.position) / scale
                                currentMove(region.left + delta.x, (region.top + delta.y).coerceIn(0f, maximumTop))
                            }
                        } finally {
                            dragging = false
                            currentMoveEnd()
                        }
                    }
                }
            },
            state = state, contentPadding = PaddingValues(bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            document?.let { snapshot ->
                // Only visible bands allocate bitmaps, even for very long documents.
                val previewBottom = maxOf(snapshot.bottom, visibleRegion.bottom)
                items(count = ceil(previewBottom / Viewport.WIDTH).toInt(), key = { it }) { band ->
                    val top = band * Viewport.WIDTH
                    val bottom = minOf(top + Viewport.WIDTH, previewBottom)
                    PreviewBand(snapshot, top, bottom, visibleRegion)
                }
            }
        }
    }
}

@Composable
private fun PreviewBand(document: PreviewDocument, top: Float, bottom: Float, visibleRegion: StrokeIndex.Bounds,
    showViewport: Boolean = true) {
    val frameColor = MaterialTheme.colorScheme.primary
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val currentDocument by rememberUpdatedState(document)
    LaunchedEffect(top, bottom, retry) {
        snapshotFlow { currentDocument }.conflate().collect { snapshot ->
            failed = false
            try {
                bitmap = withContext(Dispatchers.Default) {
                    snapshot.render(RectF(0f, top, Viewport.WIDTH, bottom))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
    }
    Surface(Modifier.fillMaxWidth().aspectRatio(Viewport.WIDTH / (bottom - top))) {
        Box(Modifier.drawWithContent {
            drawContent()
            val scale = size.width / Viewport.WIDTH
            if (showViewport && visibleRegion.bottom > top && visibleRegion.top < bottom) {
                val inset = 1.5.dp.toPx()
                val origin = Offset(visibleRegion.left * scale + inset, (visibleRegion.top - top) * scale + inset)
                val area = Size(((visibleRegion.right - visibleRegion.left) * scale - 2f * inset).coerceAtLeast(0f),
                    ((visibleRegion.bottom - visibleRegion.top) * scale - 2f * inset).coerceAtLeast(0f))
                clipRect {
                    drawRect(frameColor.copy(alpha = 0.08f), origin, area)
                    drawRect(Color.White, origin, area, style = Stroke(3.dp.toPx()))
                    drawRect(frameColor, origin, area, style = Stroke(1.5.dp.toPx()))
                }
            }
        }, contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            if (failed) TextButton(onClick = { retry++ }) { Text(AppMessages.text(R.string.preview_retry)) }
            else if (bitmap == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}
