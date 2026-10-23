package jp.handwriting.note

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.editor.*
import jp.handwriting.note.storage.NoteSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun NotebookApp(model: EditorViewModel, onCanvas: (InkCanvasView) -> Unit, onCanvasDisposed: (InkCanvasView) -> Unit, onNotes: () -> Unit) {
    val state by model.workspace.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    NotebookTheme(library.value.theme, library.value.amoled) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box {
                if (state.current == null) {
                    if (settings) GeneralSettingsScreen(model) { settings = false }
                    else NoteList(state.notes, state.working, model, folder, { folder = it }, onSettings = { settings = true })
                }
                else key(state.current!!.id) { EditorScreen(model, onCanvas, onCanvasDisposed, onNotes) }
                // Overlay notices so an IO error cannot resize the canvas midway through a stroke.
                    if (state.error != null || state.syncError != null || state.recovered) {
                        val error = state.error ?: state.syncError
                        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding(), color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(error ?: AppMessages.text(R.string.recovered_notice), Modifier.weight(1f))
                                if (state.current != null) {
                                    if (error != null) ToolAction(AppMessages.text(R.string.retry), ToolbarIcons.Redo, onClick = model::retry)
                                    if (!state.saveFailed && state.syncError == null) ToolAction(AppMessages.text(R.string.close), ToolbarIcons.Close, onClick = model::clearError)
                                } else {
                                    if (error != null) TextButton(onClick = model::retry) { Text(AppMessages.text(R.string.retry)) }
                                    if (!state.saveFailed && state.syncError == null) TextButton(onClick = model::clearError) { Text(AppMessages.text(R.string.close)) }
                                }
                            }
                        }
                    }
                if (state.working) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)).pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    }, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

