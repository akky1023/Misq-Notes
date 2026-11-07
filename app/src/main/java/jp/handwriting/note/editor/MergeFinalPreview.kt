package jp.handwriting.note.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import jp.handwriting.note.storage.LibraryPreferences
import jp.handwriting.note.storage.MergeStrength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The final paper remains interactive; the shared editor preview only navigates this review. */
@Composable
internal fun MergeFinalPaper(session: MergeReviewSession, strength: MergeStrength, decisions: Map<String, Set<Int>>,
    viewport: MergeViewport, paperBottom: Float, enabled: Boolean, preferences: LibraryPreferences,
    onViewport: (MergeViewport) -> Unit, onSize: (IntSize) -> Unit, onReady: (Boolean) -> Unit,
    onLayout: (Float, Float) -> Unit) {
    var visible by rememberSaveable(session.conflict.key) { mutableStateOf(true) }
    var preferredWidth by rememberSaveable(session.conflict.key) { mutableFloatStateOf(preferences.previewWidthDp) }
    var handlePosition by rememberSaveable(session.conflict.key) { mutableFloatStateOf(preferences.previewHandlePosition) }
    var swipe by remember { mutableStateOf<Float?>(null) }
    val animated by animateFloatAsState(swipe ?: if (visible) 1f else 0f,
        if (swipe != null) snap() else tween(220), label = "mergePreviewVisibility")
    val fraction = swipe ?: animated
    var mainSize by remember { mutableStateOf(IntSize.Zero) }
    var document by remember(session, strength, decisions) { mutableStateOf<PreviewDocument?>(null) }
    var failed by remember(session, strength, decisions) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val show = visible || fraction > 0f
    LaunchedEffect(session, strength, decisions, show, retry) {
        if (!show || document != null) return@LaunchedEffect
        failed = false
        try { document = withContext(Dispatchers.IO) { session.previewDocument(strength, decisions) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
    }
    val region = StrokeIndex.Bounds(viewport.left, viewport.top, viewport.left + viewport.width,
        viewport.top + viewport.width * mainSize.height.coerceAtLeast(1) / mainSize.width.coerceAtLeast(1))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val minimum = minOf(80.dp, maxWidth * 0.25f)
        val maximum = maxOf(minimum, minOf(maxWidth * 0.65f, maxWidth - 200.dp))
        val fullWidth = (preferredWidth.takeIf { it > 0f }?.dp ?: (maxWidth * 0.22f).coerceAtMost(200.dp))
            .coerceIn(minimum, maximum)
        val previewWidth = fullWidth * fraction
        Box(Modifier.fillMaxSize().padding(start = if (preferences.previewOnRight) 0.dp else previewWidth,
            end = if (preferences.previewOnRight) previewWidth else 0.dp)) {
            StepPaper(session, viewport, null, strength, decisions, null, paperBottom, enabled, Modifier.fillMaxSize(),
                onSize = { mainSize = it; onSize(it) }, onViewport = onViewport, onReady = onReady)
        }
        if (show) Box(Modifier.align(if (preferences.previewOnRight) Alignment.TopEnd else Alignment.TopStart)
            .width(previewWidth).fillMaxHeight().clipToBounds().graphicsLayer { alpha = fraction }) {
            Surface(Modifier.align(if (preferences.previewOnRight) Alignment.TopStart else Alignment.TopEnd)
                .wrapContentWidth(if (preferences.previewOnRight) Alignment.Start else Alignment.End, unbounded = true)
                .requiredWidth(fullWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Box {
                    if (failed) TextButton(onClick = { retry++ }, modifier = Modifier.align(Alignment.Center)) {
                        Text(AppMessages.text(R.string.preview_retry))
                    } else NotePreview(document, region, !enabled, Modifier.fillMaxSize(), preferences.previewOnRight,
                        renderContent = swipe == null && fraction == 1f,
                        onMove = { left, top -> onViewport(viewport.copy(left = left, top = top)) })
                    VerticalDivider(Modifier.align(if (preferences.previewOnRight) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
        val travel = (maxHeight - 80.dp).coerceAtLeast(0.dp)
        PreviewHandle(visible, preferences.previewOnRight, fraction, onToggle = { visible = !visible },
            onSwipeStart = { swipe = fraction },
            onSwipe = { delta ->
                val width = with(density) { fullWidth.toPx() }
                if (width > 0f) swipe = ((swipe ?: fraction) + delta / width).coerceIn(0f, 1f)
            },
            onSwipeEnd = { target -> if (target != null) visible = target; swipe = null },
            onResize = { delta -> preferredWidth = (fullWidth.value + with(density) { delta.toDp().value }).coerceIn(minimum.value, maximum.value) },
            onMove = { delta ->
                val height = with(density) { travel.toPx() }
                if (height > 0f) handlePosition = (handlePosition + delta / height).coerceIn(0f, 1f)
            }, onAdjustmentEnd = { onLayout(preferredWidth, handlePosition) },
            modifier = Modifier.align(Alignment.TopStart).offset {
                val x = (if (preferences.previewOnRight) maxWidth - previewWidth - 48.dp + 24.dp * fraction else previewWidth - 24.dp * fraction)
                    .coerceIn(0.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp))
                IntOffset(x.roundToPx(), (travel * handlePosition).roundToPx())
            }.size(48.dp, 80.dp))
    }
}
