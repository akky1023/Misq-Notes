package jp.handwriting.note.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import jp.handwriting.note.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun MergeStrength.label() = AppMessages.text(when (this) {
    MergeStrength.MANUAL -> R.string.merge_manual
    MergeStrength.CAUTIOUS -> R.string.merge_cautious
    MergeStrength.STANDARD -> R.string.merge_standard
    MergeStrength.AGGRESSIVE -> R.string.merge_aggressive
})
@Composable
internal fun MergeReviewDialog(model: EditorViewModel) {
    val state by model.mergeReview.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    if (state.conflict == null) return
    Dialog(onDismissRequest = model::dismissMerge, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            val session = state.session
            if (state.candidates.isNotEmpty()) MergeCandidatePicker(state.candidates, model::dismissMerge, model::selectMergeCandidates)
            else if (session == null) Box(Modifier.fillMaxSize()) {
                TextButton(enabled = !state.saving, onClick = model::dismissMerge, modifier = Modifier.align(Alignment.TopStart)) {
                    Text(AppMessages.text(R.string.close))
                }
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error?.let { Text(it, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error) }
            } else MergeSteps(session, state.saving, state.error, library.value, model::previewLayout, model::dismissMerge, model::saveResolvedMerge)
        }
    }
}

@Composable
private fun MergeCandidatePicker(candidates: List<NoteConflict>, onClose: () -> Unit, onStart: (Set<String>) -> Unit) {
    var selected by rememberSaveable(candidates.first().key) { mutableStateOf<List<String>>(arrayListOf()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(AppMessages.text(R.string.merge_pick_three), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text(AppMessages.text(R.string.close)) }
        }
        Text(AppMessages.text(R.string.merge_pick_count, selected.size), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(candidates, key = { it.key }) { version ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(version.key in selected, enabled = version.key in selected || selected.size < 3,
                        modifier = Modifier.semantics { contentDescription = AppMessages.text(R.string.merge_compare_version, version.filename) },
                        onCheckedChange = { checked -> selected = when {
                            !checked -> selected - version.key
                            selected.size < 3 -> (selected + version.key).distinct()
                            else -> selected
                        } })
                    Column(Modifier.weight(1f)) {
                        Text(version.title, style = MaterialTheme.typography.titleMedium)
                        Text(version.filename, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(enabled = selected.size == 3, onClick = { onStart(selected.toSet()) }, modifier = Modifier.align(Alignment.End)) {
            Text(AppMessages.text(R.string.merge_start_comparison))
        }
    }
}

@Composable
private fun MergeMetadataScreen(session: MergeReviewSession, steps: List<MultiMergeStep>, decisions: Map<String, Set<Int>>,
    final: Boolean, onClose: () -> Unit, onConfirm: (Map<String, Int>) -> Unit) {
    var selected by rememberSaveable(session.conflict.key) {
        mutableStateOf<Map<String, Int>>(HashMap(steps.mapNotNull { step -> decisions[step.key]?.singleOrNull()?.let { step.key to it } }.toMap()))
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(AppMessages.text(R.string.merge_metadata_review), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text(AppMessages.text(R.string.close)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            steps.forEach { step ->
                val isName = step.changes.any { it.kind == MergeKind.TITLE }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AppMessages.text(if (isName) R.string.merge_metadata_name else R.string.merge_metadata_paper), style = MaterialTheme.typography.titleMedium)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        @Composable fun Choice(source: Int, modifier: Modifier) {
                            val label = session.sourceLabel(source)
                            val info = session.multiPlan.indexes[source].manifest.info
                            fun choose() { selected = HashMap(selected).apply { put(step.key, source) } }
                            Card(onClick = ::choose, modifier = modifier, colors = CardDefaults.cardColors(
                                containerColor = if (selected[step.key] == source) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected[step.key] == source, onClick = ::choose,
                                            modifier = Modifier.semantics { contentDescription = AppMessages.text(
                                                if (isName) R.string.merge_metadata_name_choice else R.string.merge_metadata_paper_choice, label) })
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                    }
                                    if (isName) Text(info.title)
                                    else {
                                        val paper = info.paper
                                        Canvas(Modifier.fillMaxWidth().height(130.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                            val scale = size.width / 1000f
                                            drawContext.canvas.nativeCanvas.apply {
                                                save(); clipRect(0f, 0f, size.width, size.height); scale(scale, scale)
                                                PaperRenderer.draw(this, paper, android.graphics.RectF(0f, 0f, 1000f, size.height / scale), scale)
                                                restore()
                                            }
                                        }
                                        Text(AppMessages.text(when (paper.pattern) {
                                            PaperPattern.PLAIN -> R.string.paper_plain
                                            PaperPattern.RULED -> R.string.paper_ruled
                                            PaperPattern.GRID -> R.string.paper_grid
                                            PaperPattern.DOTS -> R.string.paper_dots
                                            PaperPattern.COLLEGE_RULED -> R.string.paper_college_ruled
                                        }), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (maxWidth >= 700.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            session.multiPlan.indexes.indices.forEach { Choice(it, Modifier.weight(1f)) }
                        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            session.multiPlan.indexes.indices.forEach { Choice(it, Modifier.fillMaxWidth()) }
                        }
                    }
                }
            }
        }
        Button(enabled = steps.all { selected[it.key] != null }, onClick = { onConfirm(selected) },
            modifier = Modifier.align(Alignment.End).padding(top = 12.dp)) {
            Text(AppMessages.text(if (final) R.string.merge_metadata_to_final else R.string.merge_metadata_continue))
        }
    }
}

@Composable
private fun MergeSteps(session: MergeReviewSession, saving: Boolean, error: String?, preferences: LibraryPreferences, onPreviewLayout: (Float, Float) -> Unit, onClose: () -> Unit,
    onSave: (MergeStrength, Map<String, Set<Int>>) -> Unit) {
    val context = LocalContext.current
    val mergePreferences = remember(context) { context.getSharedPreferences("merge-review", 0) }
    var strengthName by rememberSaveable(session.conflict.key) { mutableStateOf(mergePreferences.getString("strength", MergeStrength.CAUTIOUS.name)!!) }
    val strength = runCatching { MergeStrength.valueOf(strengthName) }.getOrDefault(MergeStrength.CAUTIOUS)
    var settings by remember { mutableStateOf(false) }
    var strengthPicker by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(false) }
    var confirmed by rememberSaveable(session.conflict.key) { mutableStateOf<Map<String, ArrayList<Int>>>(hashMapOf()) }
    val decisions = remember(confirmed) { confirmed.mapValues { it.value.toSet() } }
    val allSteps = remember(session, strength) { session.multiPlan.steps(strength) }
    val metadataSteps = remember(allSteps) { allSteps.filter { step -> step.changes.all { it.kind == MergeKind.TITLE || it.kind == MergeKind.PAPER } } }
    val steps = remember(allSteps, metadataSteps) { allSteps.filterNot { it in metadataSteps } }
    var metadataAccepted by rememberSaveable(session.conflict.key) { mutableStateOf(false) }
    val showMetadata = metadataSteps.isNotEmpty() && !metadataAccepted
    var position by rememberSaveable(session.conflict.key) { mutableIntStateOf(0) }
    val final = position >= steps.size
    val step = steps.getOrNull(position)
    var selection by rememberSaveable(session.conflict.key) { mutableStateOf<List<Int>>(arrayListOf()) }
    var selectionKey by rememberSaveable(session.conflict.key) { mutableStateOf("") }
    var candidate by rememberSaveable(session.conflict.key) { mutableIntStateOf(1) }
    var compare by rememberSaveable(session.conflict.key) { mutableStateOf(true) }
    var finalPreviewReady by remember { mutableStateOf(false) }
    var left by rememberSaveable(session.conflict.key) { mutableFloatStateOf(0f) }
    var top by rememberSaveable(session.conflict.key) { mutableFloatStateOf(0f) }
    var width by rememberSaveable(session.conflict.key) { mutableFloatStateOf(1000f) }
    var ratio by remember { mutableFloatStateOf(1f) }
    val bottom = remember(session) { maxOf(1000f, session.bounds.bottom + 40f) }
    val viewport = MergeViewport(left, top, width).constrained(ratio, bottom)
    fun setViewport(value: MergeViewport) {
        val bounded = value.constrained(ratio, bottom)
        left = bounded.left; top = bounded.top; width = bounded.width
    }
    LaunchedEffect(step?.key, strength, final, showMetadata) {
        if (showMetadata) return@LaunchedEffect
        selection = ArrayList(step?.let { decisions[it.key] ?: it.suggested }.orEmpty().sorted())
        selectionKey = step?.key.orEmpty()
        candidate = selection.lastOrNull { it > 0 } ?: 1
        if (final) setViewport(MergeViewport())
        else step?.area?.let { area ->
            val targetWidth = maxOf(200f, area.right - area.left + 60f, (area.bottom - area.top + 60f) * ratio).coerceAtMost(1000f)
            setViewport(MergeViewport((area.left + area.right - targetWidth) / 2,
                (area.top + area.bottom - targetWidth / ratio.coerceAtLeast(0.1f)) / 2, targetWidth))
        }
    }
    LaunchedEffect(ratio) { setViewport(viewport) }
    fun label(source: Int) = session.sourceLabel(source)
    fun toggle(source: Int) {
        if (saving || step == null || selectionKey != step.key) return
        val chosen = selection.toMutableSet()
        if (!chosen.remove(source)) {
            if (!step.accepts(chosen + source)) chosen.clear()
            chosen.add(source)
        }
        selection = ArrayList(chosen.sorted())
    }
    fun rememberSelection(): Boolean {
        val current = step ?: return false
        if (selectionKey != current.key || !current.accepts(selection.toSet())) return false
        confirmed = HashMap(confirmed).apply { put(current.key, ArrayList(selection)) }
        return true
    }
    val previewDecisions = remember(decisions, step, selection) {
        if (step != null && step.accepts(selection.toSet())) decisions + (step.key to selection.toSet()) else decisions
    }
    if (showMetadata) {
        MergeMetadataScreen(session, metadataSteps, decisions, final, onClose) { chosen ->
            confirmed = HashMap(confirmed).apply { chosen.forEach { (key, value) -> put(key, arrayListOf(value)) } }
            metadataAccepted = true
            finalPreviewReady = false
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        if (final) MergeFinalPaper(session, strength, decisions, viewport, bottom, !saving, preferences,
            onViewport = ::setViewport, onSize = { if (it.height > 0) ratio = it.width.toFloat() / it.height },
            onReady = { finalPreviewReady = it }, onLayout = onPreviewLayout)
        else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val sources: List<Int?> = if (!final && compare) {
                if (session.multiPlan.indexes.size <= 4) session.multiPlan.indexes.indices.toList() else listOf(0, candidate)
            } else listOf(null)
            sources.forEach { source ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    StepPaper(session, viewport, source, strength, previewDecisions, step, bottom, !saving, Modifier.fillMaxSize(),
                        onSize = { if (it.height > 0) ratio = it.width.toFloat() / it.height }, onViewport = ::setViewport,
                        onReady = { if (final) finalPreviewReady = it },
                        selected = source != null && source in selection,
                        onSelect = if (source != null && !saving && selectionKey == step?.key) ({ toggle(source) }) else null)
                    if (source != null) Surface(Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 6.dp, end = 6.dp).widthIn(max = 340.dp),
                        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shadowElevation = 2.dp) {
                        Text(label(source), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 2.dp) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !saving, onClick = {
                    if (position > 0) {
                        rememberSelection(); finalPreviewReady = false; position--
                    } else if (metadataSteps.isNotEmpty()) {
                        rememberSelection(); metadataAccepted = false; finalPreviewReady = false
                    } else onClose()
                }) {
                    Icon(ToolbarIcons.ChevronLeft, AppMessages.text(when {
                        final && position > 0 -> R.string.merge_back_to_steps
                        position > 0 -> R.string.merge_previous
                        else -> R.string.back
                    }))
                }
                Text(AppMessages.text(if (final) R.string.merge_final_review else R.string.merge_step_number, position + 1, steps.size),
                    Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
                if (final) TextButton(enabled = !saving && finalPreviewReady && session.multiPlan.complete(strength, decisions),
                    onClick = { onSave(strength, decisions) }) {
                    if (saving || !finalPreviewReady) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(ToolbarIcons.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(AppMessages.text(R.string.merge_finalize))
                } else IconButton(enabled = !saving && step != null && selectionKey == step.key && step.accepts(selection.toSet()),
                    onClick = { if (rememberSelection()) { finalPreviewReady = false; position++ } }) {
                    Icon(ToolbarIcons.ChevronRight, AppMessages.text(R.string.merge_confirm_step))
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            FilledTonalIconButton(enabled = !saving, onClick = { settings = true }) {
                Icon(ToolbarIcons.Settings, AppMessages.text(R.string.merge_options))
            }
            DropdownMenu(settings, { settings = false }) {
                DropdownMenuItem({ Text(AppMessages.text(R.string.merge_strength, strength.label())) }, {
                    settings = false; strengthPicker = true
                }, enabled = steps.none { it.key in confirmed } && !final)
                if (metadataSteps.isNotEmpty()) DropdownMenuItem({ Text(AppMessages.text(R.string.merge_metadata_revisit)) }, {
                    settings = false; metadataAccepted = false
                })
                DropdownMenuItem({ Text(AppMessages.text(R.string.merge_fit)) }, { setViewport(MergeViewport()); settings = false })
            }
        }
        if (error != null) Surface(Modifier.align(Alignment.TopCenter).padding(top = 60.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Text(error, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!final) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallFloatingActionButton(onClick = { if (!saving) compare = !compare },
                        modifier = Modifier.semantics { contentDescription = AppMessages.text(R.string.merge_view_mode) }) {
                        Icon(ToolbarIcons.Paper, AppMessages.text(if (compare) R.string.merge_selected_preview else R.string.merge_side_by_side))
                    }
                    if (session.multiPlan.indexes.size > 4) Box {
                        FloatingActionButton(onClick = { if (!saving) candidates = true },
                            modifier = Modifier.semantics { contentDescription = AppMessages.text(R.string.merge_choose_versions) }) {
                            Text(label(candidate), Modifier.padding(horizontal = 14.dp))
                        }
                        DropdownMenu(candidates, { candidates = false }) {
                            Column(Modifier.widthIn(max = 360.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                                Text(AppMessages.text(R.string.merge_selection_count, selection.size), Modifier.padding(12.dp))
                                session.multiPlan.indexes.indices.drop(1).forEach { source ->
                                    Column(Modifier.fillMaxWidth().clickable(enabled = !saving) { candidate = source; candidates = false }.padding(12.dp)) {
                                        Text(label(source))
                                        Text(session.conflicts[source - 1].filename, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                        if (step?.changes?.any { it.kind == MergeKind.TITLE } == true)
                                            Text(session.multiPlan.indexes[source].manifest.info.title)
                                    }
                                }
                                Text(AppMessages.text(if (step?.changes?.all { it.combines } == true) R.string.merge_multi_allowed else R.string.merge_single_required),
                                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { candidates = false }, modifier = Modifier.align(Alignment.End)) { Text(AppMessages.text(R.string.close)) }
                            }
                        }
                    }
                }
            }

        }
    }
    if (strengthPicker) AlertDialog(onDismissRequest = { strengthPicker = false },
        title = { Text(AppMessages.text(R.string.merge_strength, strength.label())) },
        text = { Column { MergeStrength.entries.forEach { option -> DropdownMenuItem({ Text(option.label()) }, {
            strengthName = option.name; mergePreferences.edit().putString("strength", option.name).apply(); strengthPicker = false
        }) } } }, confirmButton = { TextButton(onClick = { strengthPicker = false }) { Text(AppMessages.text(R.string.close)) } })
}

@Composable
internal fun StepPaper(session: MergeReviewSession, viewport: MergeViewport, source: Int?, strength: MergeStrength,
    decisions: Map<String, Set<Int>>, step: MultiMergeStep?, paperBottom: Float, enabled: Boolean, modifier: Modifier,
    onSize: (IntSize) -> Unit, onViewport: (MergeViewport) -> Unit, onReady: (Boolean) -> Unit,
    selected: Boolean = false, onSelect: (() -> Unit)? = null) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rendering by remember { mutableStateOf(false) }
    val latestViewport by rememberUpdatedState(viewport)
    val latestCallback by rememberUpdatedState(onViewport)
    val latestReady by rememberUpdatedState(onReady)
    val latestSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(session, size, viewport, source, strength, decisions, step?.key) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        var pending: Bitmap? = null; rendering = true; latestReady(false)
        try {
            error = null
            withContext(Dispatchers.IO) { pending = session.renderStep(size.width, size.height, viewport, source, strength, decisions, step) }
            bitmap = pending; pending = null; latestReady(true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.localizedMessage ?: AppMessages.text(R.string.operation_failed) }
        finally { pending?.recycle(); rendering = false }
    }
    Box(modifier.onSizeChanged { size = it; onSize(it) }.semantics {
        contentDescription = AppMessages.text(R.string.merge_canvas, source?.toString() ?: "result")
        if (onSelect != null && source != null) {
            this.selected = selected
            onClick(label = AppMessages.text(R.string.merge_select_version, session.sourceLabel(source))) {
                latestSelect?.invoke(); true
            }
        }
    }.pointerInput(enabled, onSelect != null, source, step?.key) {
        if (enabled && onSelect != null) detectTapGestures(onLongPress = {}, onTap = { latestSelect?.invoke() })
    }.pointerInput(enabled, size) {
        if (enabled) detectTransformGestures { centroid, pan, zoom, _ ->
            latestCallback(latestViewport.transform(size.width, centroid.x, centroid.y, pan.x, pan.y, zoom, size.height, paperBottom))
        }
    }, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        if (selected && onSelect != null) Canvas(Modifier.fillMaxSize()) {
            val length = minOf(size.width * 0.6f, size.height * 0.6f, 180.dp.toPx())
            val left = (size.width - length) / 2f; val top = (size.height - length) / 2f
            val check = android.graphics.Path().apply {
                moveTo(left + length * 5f / 24f, top + length * 12f / 24f)
                lineTo(left + length * 10f / 24f, top + length * 17f / 24f)
                lineTo(left + length * 19f / 24f, top + length * 7f / 24f)
            }
            val cutout = android.graphics.Path()
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = length * 1.8f / 24f
                strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            }.getFillPath(check, cutout)
            val mask = android.graphics.Path().apply {
                addRect(0f, 0f, size.width.toFloat(), size.height.toFloat(), android.graphics.Path.Direction.CW)
                op(cutout, android.graphics.Path.Op.DIFFERENCE)
            }
            drawContext.canvas.nativeCanvas.drawPath(mask, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(42, 35, 165, 75)
            })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (rendering) CircularProgressIndicator(Modifier.size(if (bitmap == null) 36.dp else 20.dp))
    }
}
