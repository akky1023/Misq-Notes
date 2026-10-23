package jp.handwriting.note

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.editor.*
import jp.handwriting.note.storage.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val noteDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@Stable
private class NoteDrag {
    var note by mutableStateOf<NoteSummary?>(null)
    var position by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var width by mutableFloatStateOf(0f)
    var order by mutableStateOf<List<String>?>(null)
    var destination by mutableStateOf<String?>(null)
    var initialOrder = emptyList<String>()
    fun clear() { note = null; order = null; destination = null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteList(notes: List<NoteSummary>, working: Boolean, model: EditorViewModel, folder: String?, onFolder: (String?) -> Unit, onSettings: () -> Unit) {
    val library by model.library.collectAsStateWithLifecycle()
    val data = library.value
    var create by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<NoteSummary?>(null) }
    var renameFolder by remember { mutableStateOf<NoteFolder?>(null) }
    var colorFolder by remember { mutableStateOf<NoteFolder?>(null) }
    var delete by remember { mutableStateOf<NoteSummary?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val current = data.folders.firstOrNull { it.id == folder }
    LaunchedEffect(data.folders, working, folder) { if (!working && folder != null && current == null) onFolder(null) }
    BackHandler(folder != null) { onFolder(current?.parent) }
    val folders = data.folders.filter { it.parent == folder }.sortedBy { it.name }
    val sorted = data.sorted(notes, folder)
    val drag = remember(folder) { NoteDrag() }
    val visible = drag.order?.let { order ->
        val ranks = order.withIndex().associate { it.value to it.index }
        sorted.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
    } ?: sorted
    val latestVisible by rememberUpdatedState(visible)
    val noteBounds = remember(folder) { mutableMapOf<String, Rect>() }
    val folderBounds = remember(folder) { mutableMapOf<String, Rect>() }
    var rootPosition by remember { mutableStateOf(Offset.Zero) }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    val state = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scrollEdge = with(density) { 64.dp.toPx() }
    val latestWorking by rememberUpdatedState(working)
    LaunchedEffect(folder) { state.scrollToItem(0) }
    LaunchedEffect(drag.note) {
        if (drag.note == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (drag.note != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val y = drag.position.y + rootPosition.y
            val amount = when {
                y < gridBounds.top + scrollEdge -> -((gridBounds.top + scrollEdge - y) / scrollEdge).coerceIn(0f, 1f)
                y > gridBounds.bottom - scrollEdge -> ((y - gridBounds.bottom + scrollEdge) / scrollEdge).coerceIn(0f, 1f)
                else -> 0f
            }
            state.scrollBy(amount * 900f * density.density * seconds)
        }
    }
    fun finishDrag() {
        val note = drag.note
        if (note != null && !latestWorking) {
            val destination = folderBounds.entries.firstOrNull { it.value.contains(drag.position + rootPosition) }?.key
            if (destination != null) model.moveNote(note.id, destination.ifEmpty { null })
            else if (drag.order != drag.initialOrder) drag.order?.let { model.reorderNotes(folder, it) }
        }
        drag.clear()
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned { rootPosition = it.positionInRoot() }
        .pointerInput(folder) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    if (!latestWorking) {
                        val hit = noteBounds.entries.firstOrNull { it.value.contains(point + rootPosition) }
                        val note = latestVisible.firstOrNull { it.id == hit?.key }
                        if (note != null && hit != null) {
                            drag.note = note; drag.position = point; drag.grab = point + rootPosition - hit.value.topLeft
                            drag.width = hit.value.width; drag.initialOrder = latestVisible.map { it.id }; drag.order = drag.initialOrder
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                }, onDragEnd = ::finishDrag, onDragCancel = { drag.clear() },
            ) { change, delta ->
                if (drag.note == null) return@detectDragGesturesAfterLongPress
                change.consume(); drag.position += delta
                val point = drag.position + rootPosition
                drag.destination = folderBounds.entries.firstOrNull { it.value.contains(point) }?.key
                if (drag.destination == null) {
                    val target = noteBounds.entries.firstOrNull { it.key != drag.note!!.id && it.value.contains(point) }?.key
                    val order = drag.order.orEmpty().toMutableList()
                    val from = order.indexOf(drag.note!!.id); val to = order.indexOf(target)
                    if (from >= 0 && to >= 0 && from != to) { order.add(to, order.removeAt(from)); drag.order = order }
                }
            }
        }) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (folder != null) {
                    val parent = current?.parent.orEmpty()
                    DisposableEffect(parent) { onDispose { folderBounds.remove(parent) } }
                    Surface(Modifier.onGloballyPositioned { folderBounds[parent] = it.boundsInRoot() }, shape = CircleShape,
                        color = if (drag.destination == parent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                        ToolAction(AppMessages.text(R.string.parent_folder_drop), ToolbarIcons.Back, !working) { onFolder(current?.parent) }
                    }
                }
                Text(current?.name ?: AppMessages.text(R.string.notes), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                Box {
                    ToolAction(AppMessages.text(R.string.sort_order, data.sortFor(folder).fieldLabel), ToolbarIcons.Sort, !working) { sortMenu = true }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        val currentSort = data.sortFor(folder)
                        DropdownMenuItem(
                            text = { Text(if (currentSort == NoteSort.MANUAL) AppMessages.text(R.string.manual_order) else if (currentSort.descending) AppMessages.text(R.string.descending) else AppMessages.text(R.string.ascending)) },
                            onClick = { model.sortNotes(folder, currentSort.direction(!currentSort.descending)) },
                            enabled = !working && currentSort != NoteSort.MANUAL,
                            leadingIcon = { Icon(if (currentSort == NoteSort.MANUAL) ToolbarIcons.Sort else if (currentSort.descending) ToolbarIcons.ArrowDown else ToolbarIcons.ArrowUp, null) },
                        )
                        HorizontalDivider()
                        listOf(NoteSort.NAME_ASC, NoteSort.CREATED_ASC, NoteSort.UPDATED_ASC, NoteSort.MANUAL).forEach { sort ->
                            DropdownMenuItem({ Text(sort.fieldLabel) }, { sortMenu = false; model.sortNotes(folder, sort.direction(data.sortFor(folder).descending)) },
                                trailingIcon = { if (data.sortFor(folder).field == sort.field) Icon(ToolbarIcons.Check, null) })
                        }
                    }
                }
                ToolAction(if (data.grid) AppMessages.text(R.string.list_view) else AppMessages.text(R.string.grid_view), if (data.grid) ToolbarIcons.List else ToolbarIcons.Grid, !working) { model.listGrid(!data.grid) }
                ToolAction(AppMessages.text(R.string.settings), ToolbarIcons.Settings, !working, onClick = onSettings)
            }
            if (current != null) Text(data.path(current.id), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            library.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = model::retryLibrary) { Text(AppMessages.text(R.string.retry)) } }
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { gridBounds = it.boundsInRoot() }, contentAlignment = Alignment.TopCenter) {
                LazyVerticalGrid(columns = if (data.grid) GridCells.Adaptive(240.dp) else GridCells.Fixed(1), state = state,
                    userScrollEnabled = drag.note == null,
                    modifier = Modifier.widthIn(max = if (data.grid) 1600.dp else 840.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp, end = 8.dp)) {
                    items(folders, key = { "folder:${it.id}" }) { entry ->
                        DisposableEffect(entry.id) { onDispose { folderBounds.remove(entry.id) } }
                        FolderTile(entry, data.grid, working, drag.destination == entry.id,
                            Modifier.onGloballyPositioned { folderBounds[entry.id] = it.boundsInRoot() },
                            onOpen = { onFolder(entry.id) }, onRename = { renameFolder = entry }, onColor = { colorFolder = entry },
                            onDelete = { model.deleteFolder(entry.id) }, canDelete = data.folders.none { it.parent == entry.id } && entry.id !in data.locations.values)
                    }
                    items(visible, key = { it.id }) { note ->
                        DisposableEffect(note.id) { onDispose { noteBounds.remove(note.id) } }
                        NoteTile(note, data.grid, working, model,
                            Modifier.onGloballyPositioned { noteBounds[note.id] = it.boundsInRoot() }
                                .graphicsLayer { alpha = if (drag.note?.id == note.id) 0.25f else 1f },
                            onRename = { rename = note }, onDelete = { delete = note }, onExport = { exportId = note.id })
                    }
                    if (folders.isEmpty() && visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(AppMessages.text(R.string.empty_library), Modifier.padding(12.dp))
                    }
                }
                GridScrollIndicator(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp))
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
            Surface(Modifier.size(60.dp).shadow(6.dp, CircleShape, clip = false).clip(CircleShape).combinedClickable(enabled = !working, onClick = { create = "note" },
                onLongClickLabel = AppMessages.text(R.string.create_folder), onLongClick = { createMenu = true }).semantics { contentDescription = AppMessages.text(R.string.create_note_description) },
                shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(ToolbarIcons.Add, null, Modifier.size(28.dp)) }
            }
            DropdownMenu(createMenu, { createMenu = false }) {
                DropdownMenuItem({ Text(AppMessages.text(R.string.new_folder)) }, { createMenu = false; create = "folder" }, leadingIcon = { Icon(ToolbarIcons.Folder, null) })
            }
        }
        drag.note?.let { note ->
            val width = with(density) { drag.width.toDp() }
            Surface(Modifier.offset { IntOffset((drag.position.x - drag.grab.x).roundToInt(), (drag.position.y - drag.grab.y).roundToInt()) }
                .width(width).semantics { contentDescription = AppMessages.text(R.string.dragging_note) }.graphicsLayer { alpha = 0.7f }, shape = RoundedCornerShape(12.dp), shadowElevation = 12.dp) {
                if (data.grid) Column { Thumbnail(note, model, Modifier.fillMaxWidth().aspectRatio(1f)); Text(note.title, Modifier.padding(12.dp)) }
                else Row(verticalAlignment = Alignment.CenterVertically) { Thumbnail(note, model, Modifier.size(112.dp)); Text(note.title, Modifier.padding(12.dp)) }
            }
        }
    }
    create?.let { kind -> NameDialog(if (kind == "folder") AppMessages.text(R.string.new_folder) else AppMessages.text(R.string.new_note), "", folder = kind == "folder", onDismiss = { create = null }) {
        create = null; if (kind == "folder") model.createFolder(it, folder) else model.createNote(it, folder)
    } }
    rename?.let { note -> NameDialog(AppMessages.text(R.string.rename_note), "", { rename = null }) { rename = null; model.renameNote(note.id, it) } }
    renameFolder?.let { entry -> NameDialog(AppMessages.text(R.string.rename_folder), "", folder = true, onDismiss = { renameFolder = null }) { renameFolder = null; model.renameFolder(entry.id, it) } }
    notes.firstOrNull { it.id == exportId }?.let { note -> NoteOptionsDialog(model, note.id, note.title, onDismiss = { exportId = null }) }
    colorFolder?.let { entry -> FolderColorDialog(entry.color, !working, onSelect = { model.folderColor(entry.id, it); colorFolder = null }) { colorFolder = null } }
    delete?.let { note -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(AppMessages.text(R.string.confirm_delete, note.title)) },
        text = { Text(AppMessages.text(R.string.delete_note_help)) }, confirmButton = { TextButton(onClick = { delete = null; model.deleteNote(note.id) }) { Text(AppMessages.text(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text(AppMessages.text(R.string.discard)) } }) }
}

private val folderShape = GenericShape { size, _ ->
    val w = size.width; val h = size.height; val r = w * 0.04f
    moveTo(r, h * 0.06f); lineTo(w * 0.35f, h * 0.06f)
    quadraticTo(w * 0.39f, h * 0.06f, w * 0.43f, h * 0.15f)
    lineTo(w - r, h * 0.15f); quadraticTo(w, h * 0.15f, w, h * 0.15f + r)
    lineTo(w, h - r); quadraticTo(w, h, w - r, h)
    lineTo(r, h); quadraticTo(0f, h, 0f, h - r)
    lineTo(0f, h * 0.06f + r); quadraticTo(0f, h * 0.06f, r, h * 0.06f); close()
}

@Composable
private fun FolderTile(entry: NoteFolder, grid: Boolean, working: Boolean, drop: Boolean, modifier: Modifier,
    onOpen: () -> Unit, onRename: () -> Unit, onColor: () -> Unit, onDelete: () -> Unit, canDelete: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val color = Color(entry.color)
    val text = if (((entry.color ushr 16) and 255) * 299 + ((entry.color ushr 8) and 255) * 587 + (entry.color and 255) * 114 > 150000) Color(0xFF202B33) else Color.White
    Surface(onClick = onOpen, enabled = !working, modifier = modifier.fillMaxWidth().semantics { contentDescription = AppMessages.text(R.string.folder_description, entry.name) }.then(if (grid) Modifier.aspectRatio(1.15f) else Modifier),
        shape = if (grid) folderShape else RoundedCornerShape(12.dp), color = if (grid) color else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (grid) text else MaterialTheme.colorScheme.onSurface,
        border = if (drop) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null) {
        @Composable fun Menu() {
            Box {
                ToolAction(AppMessages.text(R.string.folder_actions), ToolbarIcons.More, !working) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(AppMessages.text(R.string.rename)) }, { menu = false; onRename() })
                    DropdownMenuItem({ Text(AppMessages.text(R.string.change_color)) }, { menu = false; onColor() })
                    DropdownMenuItem({ Text(AppMessages.text(R.string.delete)) }, { menu = false; onDelete() }, enabled = canDelete)
                }
            }
        }
        if (grid) Box {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.65f).align(Alignment.BottomCenter).background(text.copy(alpha = 0.06f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)))
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Menu()
            }
            if (drop) Text(AppMessages.text(R.string.move_here), Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
        } else Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ToolbarIcons.Folder, null, Modifier.size(36.dp), tint = color)
            Text(entry.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium); Menu()
        }
    }
}

@Composable
private fun NoteTile(note: NoteSummary, grid: Boolean, working: Boolean, model: EditorViewModel, modifier: Modifier, onRename: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = { model.openNote(note.id) }, enabled = !working, modifier = modifier.fillMaxWidth()) {
        @Composable fun Details(modifier: Modifier) {
            Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(note.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Box {
                        ToolAction(AppMessages.text(R.string.note_actions), ToolbarIcons.More, !working) { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(AppMessages.text(R.string.rename)) }, { menu = false; onRename() }, enabled = note.problem == null)
                            DropdownMenuItem({ Text(AppMessages.text(R.string.export)) }, { menu = false; onExport() }, enabled = note.problem == null)
                            DropdownMenuItem({ Text(AppMessages.text(R.string.delete)) }, { menu = false; onDelete() })
                        }
                    }
                }
                Timestamp(note.createdAt, ToolbarIcons.Created, AppMessages.text(R.string.created_time))
                Timestamp(note.updatedAt, ToolbarIcons.Updated, AppMessages.text(R.string.updated_time))
                note.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (grid) { Thumbnail(note, model, Modifier.fillMaxWidth().aspectRatio(1f)); Details(Modifier.fillMaxWidth()) }
        else Row(verticalAlignment = Alignment.CenterVertically) { Thumbnail(note, model, Modifier.size(112.dp)); Details(Modifier.weight(1f)) }
    }
}

@Composable
private fun Timestamp(time: Long, icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(noteDate.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun Thumbnail(note: NoteSummary, model: EditorViewModel, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, note.id, note.updatedAt) { value = model.thumbnail(note.id) }
    Box(modifier.background(Color(note.paper.color))) {
        bitmap?.let { Image(it.asImageBitmap(), AppMessages.text(R.string.note_thumbnail, note.title), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, folder: Boolean = false, onConfirm: (String) -> Unit) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it.take(120) }, singleLine = true,
            placeholder = { Text(if (folder) AppMessages.text(R.string.new_folder) else AppMessages.text(R.string.untitled)) }) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim().ifEmpty { if (folder) AppMessages.text(R.string.new_folder) else AppMessages.text(R.string.untitled) }) }) { Text(AppMessages.text(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppMessages.text(R.string.discard)) } })
}
