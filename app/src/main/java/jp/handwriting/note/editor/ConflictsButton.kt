package jp.handwriting.note.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConflictsButton(model: EditorViewModel, enabled: Boolean = true) {
    val state by model.conflicts.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val items = workspace.current?.id?.let { id -> state.items.filter { it.noteId == id } } ?: state.items
    val groups = remember(items) { items.groupBy { it.noteId } }
    var selectedNote by rememberSaveable { mutableStateOf<String?>(null) }
    val active = groups[selectedNote] ?: groups.values.firstOrNull().orEmpty()
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(workspace.current?.id) { visible = false }
    LaunchedEffect(items.isEmpty(), state.opening) { if (items.isEmpty() && !state.opening) visible = false }
    if (items.isNotEmpty()) IconButton(enabled = enabled && !state.opening, onClick = { visible = true; model.refreshConflicts() }) {
        BadgedBox(badge = { Badge { Text(items.size.toString()) } }) {
            Icon(ToolbarIcons.Conflicts, AppMessages.text(R.string.conflicts))
        }
    }
    if (visible) AlertDialog(
        onDismissRequest = { if (!state.opening) visible = false },
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(AppMessages.text(R.string.conflicts), Modifier.weight(1f))
                TextButton(enabled = enabled && !state.opening && active.isNotEmpty(), onClick = {
                    visible = false; model.beginMerge(active.first())
                }) { Text(AppMessages.text(R.string.merge_review)) }
            }
        },
        text = {
            Column {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (active.any { it.provisional }) Text(AppMessages.text(R.string.conflict_provisional_help),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                val date = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    groups.forEach { (note, versions) ->
                        if (groups.size > 1) item(key = "note:$note") {
                            Row(Modifier.fillMaxWidth().clickable(enabled = !state.opening) { selectedNote = note },
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = active.firstOrNull()?.noteId == note, enabled = !state.opening,
                                    onClick = { selectedNote = note })
                                Text(versions.first().title, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        items(versions, key = { it.key }) { conflict ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(conflict.title, style = MaterialTheme.typography.titleMedium)
                                    Text(conflict.filename, style = MaterialTheme.typography.bodySmall)
                                    Text(date.format(Date(conflict.updatedAt)), style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(enabled = enabled && !state.opening, onClick = { model.openConflict(conflict) }) {
                                    Icon(ToolbarIcons.OpenSeparate, AppMessages.text(R.string.open_conflict_copy))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !state.opening, onClick = { visible = false }) { Text(AppMessages.text(R.string.close)) } },
    )
}
