package jp.handwriting.note

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.editor.EditorViewModel

@Composable
internal fun StorageSettings(model: EditorViewModel) {
    val context = LocalContext.current
    val location by model.storageLocation.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                model.changeStorage(uri)
            }.onFailure { error = AppMessages.text(R.string.storage_select_failed, it.localizedMessage) }
        }
    }
    Text(AppMessages.text(R.string.storage_location), style = MaterialTheme.typography.titleMedium)
    Text(location.label)
    Row {
        TextButton(enabled = !workspace.working, onClick = { error = null; picker.launch(null) }) { Text(AppMessages.text(R.string.choose_folder)) }
        if (location.custom) TextButton(enabled = !workspace.working, onClick = { error = null; model.changeStorage(null) }) { Text(AppMessages.text(R.string.restore_default)) }
    }
    Text(AppMessages.text(R.string.storage_help), style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
