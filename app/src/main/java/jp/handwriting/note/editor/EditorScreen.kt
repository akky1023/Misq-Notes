package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import jp.handwriting.note.NotebookTheme

@Composable
fun EditorScreen(model: EditorViewModel, onCanvas: (InkCanvasView) -> Unit, onCanvasDisposed: (InkCanvasView) -> Unit, onNotes: () -> Unit) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val palette by model.palette.collectAsStateWithLifecycle()
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val selection by model.selectionOverlay.collectAsStateWithLifecycle()
    val zoomPercent by model.zoomPercent.collectAsStateWithLifecycle()
    val zoomInteraction by model.zoomInteraction.collectAsStateWithLifecycle()
    val penNavigationActive by model.penNavigationActive.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    var paperDialog by rememberSaveable { mutableStateOf(false) }
    var optionsDialog by rememberSaveable { mutableStateOf(false) }
    var previewVisible by rememberSaveable(workspace.current?.id) { mutableStateOf(false) }
    val previewFraction by animateFloatAsState(if (previewVisible) 1f else 0f, tween(220), label = "notePreviewVisibility")
    var optionsAnchor by remember { mutableStateOf<IntRect?>(null) }
    var toolbarDock by remember { mutableStateOf(ToolbarDock(0.5f, 0f, DockEdge.TOP)) }
    val context = LocalContext.current
    val backGuard = remember { BackPressGuard() }
    var backNotice by remember { mutableStateOf<Toast?>(null) }
    var seenZoomInteraction by remember { mutableLongStateOf(zoomInteraction) }
    var zoomNotice by remember { mutableStateOf(false) }
    LaunchedEffect(zoomInteraction) {
        if (seenZoomInteraction != zoomInteraction) {
            seenZoomInteraction = zoomInteraction; zoomNotice = true
            delay(500); zoomNotice = false
        }
    }
    DisposableEffect(Unit) { onDispose { backNotice?.cancel() } }
    BackHandler(enabled = !paperDialog && !optionsDialog) {
        if (library.value.suppressPenGestures && penNavigationActive) { backNotice?.cancel(); backGuard.reset() }
        else if (backGuard.confirm(SystemClock.elapsedRealtime())) { backNotice?.cancel(); onNotes() }
        else {
            backNotice?.cancel()
            backNotice = Toast.makeText(context, AppMessages.text(R.string.back_again), Toast.LENGTH_SHORT).also { it.show() }
        }
    }
    CompositionLocalProvider(LocalColorPreviewBackground provides workspace.current?.paper?.color?.let { Color(it) }) {
    NotebookTheme(library.value.theme, library.value.amoled) {
        val importDocument = rememberDocumentImporter(model)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val fullPreviewWidth = (maxWidth * 0.22f).coerceAtMost(200.dp)
                val previewWidth = fullPreviewWidth * previewFraction
                Box(Modifier.fillMaxSize().padding(
                    start = if (library.value.previewOnRight) 0.dp else previewWidth,
                    end = if (library.value.previewOnRight) previewWidth else 0.dp,
                )) {
                    AndroidView(
                        factory = { InkCanvasView(it, model).also(onCanvas) },
                        modifier = Modifier.fillMaxSize(),
                        onReset = null,
                        onRelease = onCanvasDisposed,
                    )
                    Box(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                        FloatingEditorToolbar(
                            ui = if (ui.temporaryLasso && !ui.temporaryEraser) ui.copy(tool = EditorTool.LASSO) else ui,
                            palette = palette,
                            preferences = preferences,
                            onTool = model::chooseTool,
                            onColor = model::chooseRgb,
                            onFullColor = { model.style(color = it) },
                            onSize = { model.style(size = it.toFloat()) },
                            onTransparency = model::transparency,
                            onSaveColor = model::saveColor,
                            onRemoveColor = model::removeColor,
                            onRetryPalette = model::retryPalette,
                            onSaveWidth = model::saveWidth,
                            onRetryPreferences = model::retryPreferences,
                            onUndo = model.edits::undo,
                            onRedo = model.edits::redo,
                            onToolOrder = model::toolOrder,
                            onLassoMode = model::chooseLassoMode,
                            onDockChange = { toolbarDock = it },
                            onImport = importDocument,
                            onLassoIncludeMedia = model::lassoIncludeMedia,
                        )
                        }
                    }
                    SelectionClipboard(model, selection)
                if (zoomPercent != 100) Surface(
                    Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = when {
                        toolbarDock.edge == DockEdge.CORNER && toolbarDock.x == 0f && toolbarDock.y == 1f -> 120.dp
                        toolbarDock.edge == DockEdge.BOTTOM -> 112.dp
                        else -> 12.dp
                    })
                        .semantics { contentDescription = AppMessages.text(R.string.zoom_level, zoomPercent) },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Text("$zoomPercent%", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
                AnimatedVisibility(zoomNotice, Modifier.align(Alignment.Center), enter = fadeIn(tween(80)), exit = fadeOut(tween(200))) {
                Surface(Modifier.semantics { contentDescription = AppMessages.text(R.string.zoom_notice, zoomPercent) }, shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), shadowElevation = 4.dp) {
                    Text("$zoomPercent%", Modifier.padding(horizontal = 28.dp, vertical = 18.dp), style = MaterialTheme.typography.displayMedium)
                }
                }
                if (workspace.saving || workspace.syncing || palette.saving || preferences.saving || library.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(22.dp)
                            .semantics { contentDescription = AppMessages.text(R.string.saving) },
                        strokeWidth = 2.dp,
                    )
                }
                }
                if (previewVisible || previewFraction > 0f) {
                    Box(Modifier.align(if (library.value.previewOnRight) Alignment.TopEnd else Alignment.TopStart)
                        .width(previewWidth).fillMaxHeight().clipToBounds().graphicsLayer { alpha = previewFraction }) {
                    Surface(Modifier.align(if (library.value.previewOnRight) Alignment.TopStart else Alignment.TopEnd)
                        .wrapContentWidth(if (library.value.previewOnRight) Alignment.Start else Alignment.End, unbounded = true)
                        .requiredWidth(fullPreviewWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Box {
                            NotePreview(model, Modifier.fillMaxSize().padding(top = 72.dp, start = 8.dp, end = 8.dp, bottom = 16.dp))
                            VerticalDivider(Modifier.align(if (library.value.previewOnRight) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                    }
                }
                Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(28.dp), shadowElevation = 3.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onNotes, modifier = Modifier.semantics { contentDescription = AppMessages.text(R.string.back_to_notes) }) {
                            Icon(ToolbarIcons.Back, null)
                        }
                        IconButton(onClick = { optionsDialog = true }, modifier = Modifier.editorPopoverAnchor { optionsAnchor = it }.semantics { contentDescription = AppMessages.text(R.string.note_settings) }) {
                            Icon(ToolbarIcons.Settings, null)
                        }
                        IconToggleButton(checked = previewVisible, onCheckedChange = { previewVisible = it },
                            modifier = Modifier.semantics { contentDescription = AppMessages.text(R.string.note_preview) }) {
                            Icon(ToolbarIcons.Preview, null)
                        }
                    }
                }
            }
        }
    CompositionLocalProvider(LocalEditorPopoverAnchor provides optionsAnchor) {
    if (optionsDialog && !paperDialog) workspace.current?.let { note ->
        NoteOptionsDialog(model, note.id, note.title, onDismiss = { optionsDialog = false }, onBackground = { paperDialog = true })
    }
    if (paperDialog) AlertDialog(onDismissRequest = { paperDialog = false }, title = { Text(AppMessages.text(R.string.note_background)) }, text = {
        HideDialogStatusBar()
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompositionLocalProvider(LocalEditorPopoverAnchor provides null) {
                workspace.current?.paper?.let { PaperSettings(it, model::notePaper) }
            }
        }
    }, confirmButton = { TextButton(onClick = { paperDialog = false }) { Text(AppMessages.text(R.string.close)) } })
    }
    }
    }
}
