package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.abs

@Composable
internal fun FloatingEditorToolbar(
    ui: EditorUi, palette: PaletteUi, preferences: ToolPreferencesUi,
    onTool: (EditorTool) -> Unit, onColor: (Int) -> Unit, onFullColor: (Int) -> Unit, onSize: (Int) -> Unit, onTransparency: (Int) -> Unit,
    onSaveColor: (Int) -> Unit, onRemoveColor: (Int) -> Unit, onRetryPalette: () -> Unit,
    onSaveWidth: (Int, Int) -> Unit, onRetryPreferences: () -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit, onToolOrder: (List<EditorTool>) -> Unit, onDockChange: (ToolbarDock) -> Unit,
    onLassoMode: (LassoMode) -> Unit, onImport: () -> Unit, onLassoIncludeMedia: (Boolean) -> Unit,
) {
    var horizontal by rememberSaveable { mutableFloatStateOf(0.5f) }
    var vertical by rememberSaveable { mutableFloatStateOf(0f) }
    var edge by rememberSaveable { mutableStateOf(DockEdge.TOP) }
    var lastExpandedEdge by rememberSaveable { mutableStateOf(DockEdge.TOP) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var window by remember { mutableStateOf(IntSize.Zero) }
    var draggingTool by remember { mutableStateOf<EditorTool?>(null) }
    var dragGhostPosition by remember { mutableStateOf(Offset.Zero) }
    var toolbarRoot by remember { mutableStateOf(Offset.Zero) }
    var popoverAnchor by remember { mutableStateOf<IntRect?>(null) }
    val toolPositions = remember { mutableMapOf<EditorTool, Offset>() }
    var draggingWindow by remember { mutableStateOf(false) }
    var placementReady by remember { mutableStateOf(false) }
    val toolVerticalScroll = rememberScrollState()
    val toolHorizontalScroll = rememberScrollState()
    val colorVerticalScroll = rememberScrollState()
    val verticalLayout = edge == DockEdge.LEFT || edge == DockEdge.RIGHT
    val minimized = edge == DockEdge.CORNER
    val density = LocalDensity.current
    val inset = with(density) { (if (minimized) 12.dp else 8.dp).toPx().roundToInt() }
    val headerWidth = with(density) { 112.dp.toPx().roundToInt() }
    val headerHeight = with(density) { 56.dp.toPx().roundToInt() }
    val travelX = (area.width - window.width - inset * 2).coerceAtLeast(0)
    val travelY = (area.height - window.height - inset * 2).coerceAtLeast(0)
    var targetX = inset + (horizontal * travelX).roundToInt()
    var targetY = inset + (vertical * travelY).roundToInt()
    if (!draggingWindow && targetX < headerWidth && targetY < headerHeight) {
        if (edge == DockEdge.TOP && area.width >= window.width + headerWidth) targetX = headerWidth
        else targetY = headerHeight + inset
    }
    val animatedOffset by animateIntOffsetAsState(IntOffset(targetX, targetY),
        animationSpec = if (draggingWindow || !placementReady) snap() else spring(dampingRatio = 1f, stiffness = 450f), label = AppMessages.text(R.string.toolbar_animation))
    val displayedOffset by rememberUpdatedState(animatedOffset)
    LaunchedEffect(area, window) { if (area != IntSize.Zero && window != IntSize.Zero) placementReady = true }
    val currentOrder by rememberUpdatedState(preferences.value.toolOrder)
    val saveOrder by rememberUpdatedState(onToolOrder)
    val currentBusy by rememberUpdatedState(ui.busy)
    LaunchedEffect(horizontal, vertical, edge) { onDockChange(ToolbarDock(horizontal, vertical, edge)) }
    fun finishDrag() {
        val dock = ToolbarDock.snap(horizontal, vertical, 0.12f, 0.10f)
        if (edge != DockEdge.CORNER) lastExpandedEdge = edge
        if (dock.edge != DockEdge.CORNER) lastExpandedEdge = dock.edge
        draggingWindow = false
        horizontal = dock.x; vertical = dock.y; edge = dock.edge
    }
    fun reopen() {
        val dock = ToolbarDock(horizontal, vertical, edge).reopened(lastExpandedEdge)
        horizontal = dock.x; vertical = dock.y; edge = dock.edge; lastExpandedEdge = dock.edge
    }
    @Composable fun ToolButtons() {
        ToolAction(AppMessages.text(R.string.undo), ToolbarIcons.Undo, ui.canUndo, onClick = onUndo)
        ToolAction(AppMessages.text(R.string.redo), ToolbarIcons.Redo, ui.canRedo, onClick = onRedo)
        if (verticalLayout) HorizontalDivider(Modifier.width(28.dp).padding(vertical = 3.dp))
        else VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp))
        preferences.value.toolOrder.forEach { tool ->
            key(tool) {
                val label = when (tool) { EditorTool.PEN -> AppMessages.text(R.string.pen); EditorTool.HIGHLIGHTER -> AppMessages.text(R.string.highlighter); EditorTool.ERASER -> AppMessages.text(R.string.eraser); EditorTool.LASSO -> AppMessages.text(R.string.lasso) }
                val icon = when (tool) { EditorTool.PEN -> ToolbarIcons.Pen; EditorTool.HIGHLIGHTER -> ToolbarIcons.Highlighter; EditorTool.ERASER -> ToolbarIcons.Eraser; EditorTool.LASSO -> ToolbarIcons.Lasso }
                Box(Modifier.onGloballyPositioned { toolPositions[tool] = it.positionInRoot() }.pointerInput(tool, verticalLayout) {
                    var distance = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { point -> if (!currentBusy) {
                            draggingTool = tool; distance = 0f
                            dragGhostPosition = (toolPositions[tool] ?: toolbarRoot) + point - toolbarRoot
                        } },
                        onDragEnd = { draggingTool = null }, onDragCancel = { draggingTool = null },
                    ) { change, delta ->
                        change.consume()
                        if (draggingTool != tool) return@detectDragGesturesAfterLongPress
                        dragGhostPosition += delta
                        distance += if (verticalLayout) delta.y else delta.x
                        val step = 36.dp.toPx()
                        val order = currentOrder.toMutableList()
                        while (abs(distance) >= step * 0.65f) {
                            val from = order.indexOf(tool)
                            val to = (from + if (distance > 0) 1 else -1).coerceIn(0, order.lastIndex)
                            if (to == from) { distance = 0f; break }
                            order.add(to, order.removeAt(from))
                            distance -= if (distance > 0) step else -step
                        }
                        if (order != currentOrder) saveOrder(order)
                    }
                }.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(AppMessages.text(R.string.reorder_before)) {
                            val list = currentOrder.toMutableList(); val i = list.indexOf(tool)
                            if (!currentBusy && i > 0) { list.add(i - 1, list.removeAt(i)); saveOrder(list); true } else false
                        },
                        CustomAccessibilityAction(AppMessages.text(R.string.reorder_after)) {
                            val list = currentOrder.toMutableList(); val i = list.indexOf(tool)
                            if (!currentBusy && i < list.lastIndex) { list.add(i + 1, list.removeAt(i)); saveOrder(list); true } else false
                        },
                    )
                }.graphicsLayer { alpha = if (draggingTool == tool) 0.3f else 1f }) {
                    ToolAction(label, icon, !ui.busy, draggingTool == tool || (ui.tool == tool && !ui.temporaryEraser) || (tool == EditorTool.ERASER && ui.temporaryEraser)) {
                        if (ui.tool == tool && !ui.temporaryEraser && tool != EditorTool.LASSO) dialog = "details" else onTool(tool)
                    }
                }
            }
        }
        ToolAction(AppMessages.text(R.string.import_document), ToolbarIcons.Image, !ui.busy, onClick = onImport)
    }
    @Composable fun CollapseButton() {
        val rotation = when (edge) {
            DockEdge.LEFT -> if (expanded) 270f else 90f
            DockEdge.RIGHT -> if (expanded) 90f else 270f
            DockEdge.BOTTOM -> if (expanded) 180f else 0f
            else -> if (expanded) 0f else 180f
        }
        Surface(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp).semantics {
            contentDescription = if (expanded) AppMessages.text(R.string.collapse_styles) else AppMessages.text(R.string.expand_styles)
        }, shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = Color.Transparent) {
            Box(contentAlignment = Alignment.Center) { Icon(ToolbarIcons.Collapse, null, Modifier.size(22.dp).rotate(rotation)) }
        }
    }
    @Composable fun Colors() {
        if (ui.tool in listOf(EditorTool.PEN, EditorTool.HIGHLIGHTER)) {
            palette.colors.forEach { rgb -> ColorSwatch(rgb, PenStyle.rgb(ui.color) == rgb, !ui.busy) { onColor(rgb) } }
            PaletteAction(!ui.busy) { dialog = "color" }
        }
    }
    @Composable fun Widths() {
        preferences.value.widthsFor(ui.tool).forEachIndexed { slot, width ->
            WidthPreset(slot, width, ui.size.toInt() == width, !ui.busy && ui.tool != EditorTool.LASSO,
                { onSize(width) }, { dialog = "preset:$slot" }, compact = true, eraser = ui.tool == EditorTool.ERASER)
        }
    }
    @Composable fun LassoModes() {
        LassoMode.entries.forEach { mode ->
            val active = preferences.value.lassoMode == mode
            val label = AppMessages.text(if (mode == LassoMode.FREE) R.string.lasso_free else R.string.lasso_rectangle)
            ToolAction(label,
                if (mode == LassoMode.FREE) ToolbarIcons.FreeLasso else ToolbarIcons.RectangleLasso,
                !ui.busy, active) { onLassoMode(mode) }
        }
        if (verticalLayout) HorizontalDivider(Modifier.width(28.dp).padding(vertical = 3.dp))
        else VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp))
        ToolAction(AppMessages.text(R.string.lasso_include_media), ToolbarIcons.Image, !ui.busy,
            preferences.value.lassoIncludeMedia, activeTint = Color(0xFF2196F3)) {
            onLassoIncludeMedia(!preferences.value.lassoIncludeMedia)
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { area = it }.onGloballyPositioned { toolbarRoot = it.positionInRoot() }) {
        Surface(modifier = Modifier
            .offset { animatedOffset }
            .editorPopoverAnchor { popoverAnchor = it }
            .onSizeChanged { window = it }
            .shadow(8.dp, if (minimized) CircleShape else RoundedCornerShape(12.dp), clip = false)
            .animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 450f))
            .then(if (minimized) Modifier.size(96.dp) else if (verticalLayout) Modifier.widthIn(max = 84.dp).heightIn(max = 400.dp) else Modifier.widthIn(max = 340.dp).fillMaxWidth())
            .clip(if (minimized) CircleShape else RoundedCornerShape(12.dp))
            .semantics { contentDescription = AppMessages.text(R.string.toolbar_description) }
            .systemGestureExclusion()
            .pointerInput(travelX, travelY) {
                detectDragGestures(onDragStart = {
                    horizontal = if (travelX > 0) ((displayedOffset.x - inset).toFloat() / travelX).coerceIn(0f, 1f) else horizontal
                    vertical = if (travelY > 0) ((displayedOffset.y - inset).toFloat() / travelY).coerceIn(0f, 1f) else vertical
                    draggingWindow = true
                }, onDragEnd = { finishDrag() }, onDragCancel = { finishDrag() }) { change, delta ->
                    change.consume()
                    horizontal = (horizontal + delta.x / maxOf(1, travelX, area.width / 2)).coerceIn(0f, 1f)
                    vertical = (vertical + delta.y / maxOf(1, travelY, area.height / 2)).coerceIn(0f, 1f)
                }
            }.then(if (minimized) Modifier.clickable { reopen() } else Modifier),
            shape = if (minimized) CircleShape else RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 0.dp) {
            if (minimized) Box(contentAlignment = Alignment.Center) {
                Icon(ToolbarIcons.Pen, AppMessages.text(R.string.open_toolbar), Modifier.size(40.dp))
            } else if (verticalLayout) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.width(36.dp).height(320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.weight(1f).verticalScroll(toolVerticalScroll, enabled = toolVerticalScroll.maxValue > 0), horizontalAlignment = Alignment.CenterHorizontally) { ToolButtons() }
                        Spacer(Modifier.height(12.dp)); CollapseButton()
                    }
                    if (expanded) Column(Modifier.width(36.dp).height(320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (ui.tool == EditorTool.ERASER || ui.tool == EditorTool.LASSO) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                if (ui.tool == EditorTool.LASSO) LassoModes() else Widths()
                            }
                        } else {
                            Column(Modifier.weight(1f).verticalScroll(colorVerticalScroll, enabled = colorVerticalScroll.maxValue > 0), horizontalAlignment = Alignment.CenterHorizontally) { Colors() }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Column { Widths() }
                        }
                    }
                }
            } else Column(Modifier.padding(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(toolHorizontalScroll, enabled = toolHorizontalScroll.maxValue > 0), verticalAlignment = Alignment.CenterVertically) { ToolButtons() }
                    Spacer(Modifier.width(16.dp).height(36.dp))
                    CollapseButton()
                }
                if (expanded) {
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (ui.tool == EditorTool.ERASER || ui.tool == EditorTool.LASSO) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (ui.tool == EditorTool.LASSO) LassoModes() else Widths()
                            }
                        } else {
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { Colors() }
                            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { Widths() }
                        }
                    }
                }
            }
        }
        draggingTool?.let { tool ->
            val icon = when (tool) { EditorTool.PEN -> ToolbarIcons.Pen; EditorTool.HIGHLIGHTER -> ToolbarIcons.Highlighter; EditorTool.ERASER -> ToolbarIcons.Eraser; EditorTool.LASSO -> ToolbarIcons.Lasso }
            val halfSize = with(density) { 22.dp.toPx() }
            Surface(Modifier.offset { IntOffset((dragGhostPosition.x - halfSize).roundToInt(), (dragGhostPosition.y - halfSize).roundToInt()) }
                .size(44.dp).graphicsLayer { alpha = 0.65f }, shape = RoundedCornerShape(10.dp), shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, AppMessages.text(R.string.dragging_tool), Modifier.size(28.dp)) }
            }
        }
    }
    CompositionLocalProvider(LocalEditorPopoverAnchor provides popoverAnchor) {
    when (dialog) {
        "color" -> RgbColorDialog(ui.color, palette, !ui.busy, onFullColor, onSaveColor, onRemoveColor, onRetryPalette,
            title = if (ui.tool == EditorTool.HIGHLIGHTER) AppMessages.text(R.string.highlighter_color) else AppMessages.text(R.string.pen_color), allowAlpha = true) { dialog = null }
        "details" -> PenDetailsDialog(ui, preferences, onSize, onTransparency, onRetryPreferences, onColor = { dialog = "detailsColor" }) { dialog = null }
        "detailsColor" -> RgbColorDialog(ui.color, palette, !ui.busy, onFullColor, onSaveColor, onRemoveColor, onRetryPalette,
            title = if (ui.tool == EditorTool.HIGHLIGHTER) AppMessages.text(R.string.highlighter_color) else AppMessages.text(R.string.pen_color), allowAlpha = true) { dialog = "details" }
    }
    dialog?.removePrefix("preset:")?.toIntOrNull()?.takeIf { it in 0..2 }?.let { slot ->
        WidthTemplateDialog(ui, slot, preferences.value.widthsFor(ui.tool)[slot], onSave = { onSaveWidth(slot, it); onSize(it) }, onDismiss = { dialog = null })
    }
    }
}

@Composable
internal fun ToolAction(label: String, icon: ImageVector, enabled: Boolean = true, active: Boolean = false,
    activeTint: Color? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(36.dp).semantics { selected = active; contentDescription = label },
        shape = CircleShape,
        color = if (active) activeTint?.copy(alpha = 0.15f) ?: MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            active -> activeTint ?: MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) } }
}

