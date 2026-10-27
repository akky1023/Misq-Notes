package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.RectF
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import jp.handwriting.note.storage.NotePaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

private data class PreviewDocument(val entries: List<OrderedEdits.Entry<EditorStroke>>, val paper: NotePaper,
    val bottom: Float, val starIds: Set<String>, val rows: List<StarPreviewRows.Row>)

@Composable
internal fun NotePreview(model: EditorViewModel, modifier: Modifier = Modifier) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val visibleRegion by model.visibleRegion.collectAsStateWithLifecycle()
    val note = workspace.current ?: return
    var document by remember(note.id) { mutableStateOf<PreviewDocument?>(null) }
    val starCache = remember(note.id) { mutableMapOf<String, StrokeIndex.Bounds?>() }
    LaunchedEffect(note.id, note.paper, ui.contentRevision, ui.busy) {
        if (ui.busy) return@LaunchedEffect
        delay(300)
        val entries = model.edits.snapshot()
        val bottom = maxOf(1000f, model.contentBottom + 24f)
        document = withContext(Dispatchers.Default) {
            starCache.keys.retainAll(entries.map { it.value.storageId }.toSet())
            val stars = entries.mapNotNull { entry ->
                currentCoroutineContext().ensureActive()
                val stroke = entry.value
                if (!starCache.containsKey(stroke.storageId)) {
                    val ink = stroke.inkOrNull
                    val bounds = stroke.bounds
                    starCache[stroke.storageId] = if (ink != null && bounds != null &&
                        SingleStrokeStar.matches(List(ink.inputs.size) { i ->
                            val p = ink.inputs[i]; SingleStrokeStar.Point(p.x, p.y)
                        })) StrokeIndex.Bounds(bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax) else null
                }
                starCache[stroke.storageId]
            }
            val starIds = entries.filter { starCache[it.value.storageId] != null }.mapTo(mutableSetOf()) { it.value.storageId }
            PreviewDocument(entries, note.paper, bottom, starIds, StarPreviewRows.group(stars))
        }
    }
    key(note.id) {
        var starsOnly by remember { mutableStateOf(false) }
        val state = rememberLazyListState()
        val starState = rememberLazyListState()
        Column(modifier) {
            TabRow(selectedTabIndex = if (starsOnly) 1 else 0) {
                Tab(selected = !starsOnly, onClick = { starsOnly = false },
                    text = { Text(AppMessages.text(R.string.preview_tab_all), maxLines = 1) })
                Tab(selected = starsOnly, onClick = { starsOnly = true },
                    text = { Text(AppMessages.text(R.string.preview_tab_stars), maxLines = 1) })
            }
            val contentModifier = Modifier.fillMaxWidth().weight(1f)
            if (starsOnly) StarRowsPreview(model, document, visibleRegion, ui.busy || workspace.working, starState, contentModifier)
            else WholeNotePreview(model, document, visibleRegion, ui.busy || workspace.working, state, contentModifier)
        }
    }
}

@Composable
private fun StarRowsPreview(model: EditorViewModel, document: PreviewDocument?, visibleRegion: StrokeIndex.Bounds,
    busy: Boolean, state: LazyListState, modifier: Modifier) {
    if (document == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
    } else if (document.rows.isEmpty()) {
        Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Text(AppMessages.text(R.string.preview_no_stars), style = MaterialTheme.typography.bodySmall)
        }
    } else LazyColumn(modifier.semantics { contentDescription = AppMessages.text(R.string.preview_star_rows) },
        state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(document.rows.size) { index ->
            val row = document.rows[index]
            Box(Modifier.fillMaxWidth().semantics {
                contentDescription = AppMessages.text(R.string.preview_star_row, index + 1)
            }.clickable(enabled = !busy) {
                val height = visibleRegion.bottom - visibleRegion.top
                model.moveViewportFromPreview(visibleRegion.left, ((row.top + row.bottom - height) / 2f).coerceAtLeast(0f))
                model.viewportChanged()
            }) {
                PreviewBand(document, row.top, row.bottom, visibleRegion)
            }
        }
    }
}

@Composable
private fun WholeNotePreview(model: EditorViewModel, document: PreviewDocument?, visibleRegion: StrokeIndex.Bounds,
    busy: Boolean, state: LazyListState, modifier: Modifier) {
    var dragging by remember { mutableStateOf(false) }
    val currentRegion by rememberUpdatedState(visibleRegion)
    val currentBusy by rememberUpdatedState(busy)
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
                                model.moveViewportFromPreview(region.left + delta.x, (region.top + delta.y).coerceIn(0f, maximumTop))
                            }
                        } finally {
                            dragging = false
                            model.viewportChanged()
                        }
                    }
                }
            },
            state = state, horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun PreviewBand(document: PreviewDocument, top: Float, bottom: Float, visibleRegion: StrokeIndex.Bounds) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(document, top, bottom, retry) {
        failed = false
        try {
            bitmap = withContext(Dispatchers.Default) {
                NoteExport.preview(document.entries.map { it.value }, document.paper, RectF(0f, top, Viewport.WIDTH, bottom),
                    enlargedStrokes = document.starIds)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Surface(Modifier.fillMaxWidth().aspectRatio(Viewport.WIDTH / (bottom - top))) {
        Box(Modifier.drawWithContent {
            drawContent()
            val scale = size.width / Viewport.WIDTH
            if (visibleRegion.bottom > top && visibleRegion.top < bottom) {
                val inset = 1.5.dp.toPx()
                val origin = Offset(visibleRegion.left * scale + inset, (visibleRegion.top - top) * scale + inset)
                val area = Size(((visibleRegion.right - visibleRegion.left) * scale - 2f * inset).coerceAtLeast(0f),
                    ((visibleRegion.bottom - visibleRegion.top) * scale - 2f * inset).coerceAtLeast(0f))
                clipRect {
                    drawRect(Color(0xFFFF9800).copy(alpha = 0.08f), origin, area)
                    drawRect(Color.White, origin, area, style = Stroke(3.dp.toPx()))
                    drawRect(Color(0xFFFF9800), origin, area, style = Stroke(1.5.dp.toPx()))
                }
            }
        }, contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            if (failed) TextButton(onClick = { retry++ }) { Text(AppMessages.text(R.string.preview_retry)) }
            else if (bitmap == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}
