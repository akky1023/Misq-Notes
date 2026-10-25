package jp.handwriting.note.editor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import kotlinx.coroutines.*

private class PdfPageRequest(val count: Int) {
    val result = CompletableDeferred<List<Int>>()
}

@Composable
internal fun rememberDocumentImporter(model: EditorViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteId by rememberSaveable { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var committing by remember { mutableStateOf(false) }
    var canceling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<PdfPageRequest?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = noteId
        noteId = null
        if (uri != null && target != null) {
            committing = false; canceling = false; error = null
            job = scope.launch {
                try {
                    model.importDocument(context, uri, target, selectPages = { count ->
                        withContext(Dispatchers.Main) {
                            val request = PdfPageRequest(count)
                            pages = request
                            try { request.result.await() }
                            finally { pages = null; request.result.cancel() }
                        }
                    }, onCommit = { committing = true })
                    Toast.makeText(context, AppMessages.text(R.string.import_complete), Toast.LENGTH_LONG).show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = AppMessages.text(R.string.import_failed, e.localizedMessage ?: "") }
                finally { pages = null; job = null; committing = false; canceling = false }
            }
        }
    }
    fun cancel() {
        if (!committing) { canceling = true; job?.cancel() }
    }
    pages?.let { request ->
        var selected by remember(request) { mutableStateOf((0 until request.count).toSet()) }
        AlertDialog(onDismissRequest = ::cancel,
            title = { Text(AppMessages.text(R.string.import_select_pages)) },
            text = {
                Column {
                    Row {
                        TextButton(onClick = { selected = (0 until request.count).toSet() }) { Text(AppMessages.text(R.string.import_select_all)) }
                        TextButton(onClick = { selected = emptySet() }) { Text(AppMessages.text(R.string.import_clear_pages)) }
                    }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items((0 until request.count).toList(), key = { it }) { page ->
                            Row(Modifier.fillMaxWidth().toggleable(page in selected, role = Role.Checkbox) {
                                selected = if (it) selected + page else selected - page
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = page in selected, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(AppMessages.text(R.string.import_page, page + 1))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty() && !request.result.isCompleted,
                    onClick = { request.result.complete(selected.sorted()) }) { Text(AppMessages.text(R.string.import_document)) }
            },
            dismissButton = { TextButton(onClick = ::cancel) { Text(AppMessages.text(R.string.import_cancel)) } })
    }
    if (job != null && pages == null) AlertDialog(onDismissRequest = ::cancel,
        title = { Text(AppMessages.text(if (committing) R.string.saving else R.string.import_document)) },
        text = { CircularProgressIndicator() },
        confirmButton = {}, dismissButton = {
            if (!committing) TextButton(enabled = !canceling, onClick = ::cancel) { Text(AppMessages.text(R.string.import_cancel)) }
        })
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(AppMessages.text(R.string.close)) } })
    }
    return {
        model.workspace.value.current?.takeIf { job == null && !model.workspace.value.working && !model.ui.value.busy }?.let {
            noteId = it.id
            picker.launch(arrayOf("image/*", "application/pdf"))
        }
    }
}
