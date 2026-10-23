package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.math.abs

@Composable
internal fun SelectionClipboard(model: EditorViewModel, selection: SelectionOverlay?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember { SnackbarHostState() }
    var notification by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun notify(message: String, share: android.net.Uri? = null) {
        notification?.cancel()
        notification = scope.launch {
            val timeout = launch { delay(3000); host.currentSnackbarData?.dismiss() }
            try {
                val result = host.showSnackbar(message, actionLabel = if (share != null) AppMessages.text(R.string.share) else null, duration = SnackbarDuration.Indefinite)
                if (result == SnackbarResult.ActionPerformed && share != null) NoteSharing.share(context, share)
            } finally { timeout.cancel() }
        }
    }
    fun capture() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val strokes = model.selectedStrokes()
                val paper = checkNotNull(model.workspace.value.current).paper
                val uri = withContext(Dispatchers.IO) { NoteSharing.capture(context, strokes, paper) }
                NoteSharing.putClipboard(context, uri)
                notify(AppMessages.text(R.string.copied_to_clipboard), uri)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.localizedMessage ?: AppMessages.text(R.string.capture_failed)) }
            finally { busy = false }
        }
    }
    Box(Modifier.fillMaxSize()) {
        SelectionActionsOverlay(selection?.copy(enabled = selection.enabled && !busy), model::deleteSelection, model::clearSelection,
            onCopy = model::duplicateSelection, onCapture = ::capture)
        val threshold = with(LocalDensity.current) { 40.dp.toPx() }
        SnackbarHost(host, Modifier.align(Alignment.TopEnd).padding(12.dp).widthIn(max = 420.dp).fillMaxWidth()) { data ->
            var offset by remember(data) { mutableFloatStateOf(0f) }
            Snackbar(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.pointerInput(data) {
                detectHorizontalDragGestures(onDragCancel = { offset = 0f }, onDragEnd = {
                    if (abs(offset) >= threshold) data.dismiss() else offset = 0f
                }) { change, delta -> change.consume(); offset += delta }
            }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
                actionContentColor = MaterialTheme.colorScheme.primary, action = if (data.visuals.actionLabel != null) ({
                IconButton(onClick = data::performAction) { Icon(ToolbarIcons.Share, AppMessages.text(R.string.share), tint = MaterialTheme.colorScheme.primary) }
            }) else null) { Text(data.visuals.message) }
        }
    }
}
