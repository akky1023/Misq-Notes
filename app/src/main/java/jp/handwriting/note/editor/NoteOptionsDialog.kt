package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
internal fun NoteOptionsDialog(model: EditorViewModel, id: String, title: String, onDismiss: () -> Unit, onBackground: (() -> Unit)? = null) {
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var importSuccess by remember { mutableStateOf(false) }
    val complete: (android.net.Uri?) -> Unit = { uri ->
        val source = pending?.let(::File)
        if (uri == null) { source?.delete(); pending = null; busy = false }
        else scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(source).inputStream().use { input ->
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { input.copyTo(it) }
                    }
                }
                success = true
            } catch (e: Exception) { error = AppMessages.text(R.string.export_failed, e.localizedMessage) }
            finally { source?.delete(); pending = null; busy = false }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null; success = false; importSuccess = false
            try { model.importDocument(context, uri, id); importSuccess = true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = AppMessages.text(R.string.import_failed, e.localizedMessage ?: "") }
            finally { busy = false }
        }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), complete)
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), complete)
    fun export(format: NoteExportFormat) {
        scope.launch {
            busy = true; error = null; success = false; importSuccess = false
            try {
                val snapshot = model.exportSnapshot(id)
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "export-${UUID.randomUUID()}.${format.extension}").also { file ->
                        try { file.outputStream().use { NoteExport.write(format, snapshot.entries.map { it.value }, snapshot.manifest.info.paper, it) } }
                        catch (e: Exception) { file.delete(); throw e }
                    }
                }
                pending = file.path
                val name = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100) + ".${format.extension}"
                if (format == NoteExportFormat.PDF) pdf.launch(name) else png.launch(name)
            } catch (e: Exception) { busy = false; error = AppMessages.text(R.string.export_failed, e.localizedMessage) }
        }
    }
    EditorPopover(onDismiss = onDismiss, dismissEnabled = !busy, title = null, content = {
        HideDialogStatusBar()
        val close = LocalPopoverExit.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            onBackground?.let { open ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy, onClick = { close(open) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(AppMessages.text(R.string.note_background), Modifier.weight(1f))
                    Icon(ToolbarIcons.ChevronRight, AppMessages.text(R.string.open_note_background))
                }
            }
            if (workspace.current?.id == id) {
                TextButton(enabled = !busy, onClick = { importFile.launch(arrayOf("image/*")) }) {
                    Icon(ToolbarIcons.Image, null)
                    Spacer(Modifier.width(8.dp))
                    Text(AppMessages.text(R.string.import_photo))
                }
                TextButton(enabled = !busy, onClick = { importFile.launch(arrayOf("application/pdf")) }) {
                    Icon(ToolbarIcons.Pdf, null)
                    Spacer(Modifier.width(8.dp))
                    Text(AppMessages.text(R.string.import_pdf))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(enabled = !busy, onClick = { export(NoteExportFormat.PDF) }, modifier = Modifier.size(56.dp)) {
                    Icon(ToolbarIcons.Pdf, AppMessages.text(R.string.export_pdf), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.error)
                }
                IconButton(enabled = !busy, onClick = { export(NoteExportFormat.PNG) }, modifier = Modifier.size(56.dp)) {
                    Icon(ToolbarIcons.Image, AppMessages.text(R.string.export_png), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            if (importSuccess) Text(AppMessages.text(R.string.import_complete))
            if (success) Text(AppMessages.text(R.string.export_complete))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, actions = {
        val close = LocalPopoverExit.current
        TextButton(enabled = !busy, onClick = { close(onDismiss) }) { Text(AppMessages.text(R.string.close)) }
    })
}
