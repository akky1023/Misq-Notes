package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val LocalEditorPopoverAnchor = compositionLocalOf<IntRect?> { null }
internal val LocalPopoverExit = compositionLocalOf<((() -> Unit) -> Unit)> { { action -> action() } }
internal fun Modifier.editorPopoverAnchor(onAnchor: (IntRect) -> Unit) = onGloballyPositioned { coordinates ->
    val b = coordinates.boundsInWindow()
    onAnchor(IntRect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
}

internal class EditorPopoverPosition(private val anchor: IntRect?, private val gap: Int, private val keyboard: Int = 0) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val availableHeight = (windowSize.height - keyboard).coerceAtLeast(popupContentSize.height)
        val selected = anchor
        if (selected == null) return IntOffset((windowSize.width - popupContentSize.width).coerceAtLeast(0) / 2, (availableHeight - popupContentSize.height) / 2)
        val x = (selected.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val below = selected.bottom + gap
        val above = selected.top - gap - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= availableHeight -> below
            above >= 0 -> above
            else -> selected.center.y - popupContentSize.height / 2
        }.coerceIn(0, maxOf(0, availableHeight - popupContentSize.height))
        val sideX = if (below + popupContentSize.height > availableHeight && above < 0) {
            if (selected.left >= popupContentSize.width + gap) selected.left - gap - popupContentSize.width
            else if (selected.right + gap + popupContentSize.width <= windowSize.width) selected.right + gap else x
        } else x
        return IntOffset(sideX, y)
    }
}

@Composable
internal fun CompactPopover(onDismiss: () -> Unit, width: Dp = 320.dp, dismissEnabled: Boolean = true, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val progress by animateFloatAsState(if (visible) 1f else 0f, tween(140), label = AppMessages.text(R.string.popover_animation))
    val close: (() -> Unit) -> Unit = { action ->
        if (!closing) {
            closing = true; visible = false
            scope.launch { delay(140); action() }
        }
    }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val keyboard = WindowInsets.ime.getBottom(density)
    val maxWidth = with(density) { window.width.toDp() - 24.dp }.coerceAtLeast(100.dp)
    val maxHeight = with(density) { (window.height - keyboard).coerceAtLeast(1).toDp() - 24.dp }.coerceAtLeast(96.dp)
    val position = EditorPopoverPosition(LocalEditorPopoverAnchor.current, with(density) { 2.dp.roundToPx() }, keyboard)
    Popup(popupPositionProvider = position, onDismissRequest = { if (dismissEnabled) close(onDismiss) },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        Box(Modifier.padding(12.dp)) {
            Surface(Modifier.width(minOf(width, maxWidth)).heightIn(max = maxHeight).graphicsLayer {
                alpha = progress; scaleX = 0.94f + 0.06f * progress; scaleY = scaleX
            }, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
                CompositionLocalProvider(LocalPopoverExit provides close) { content() }
            }
        }
    }
}

@Composable
internal fun EditorPopover(title: String?, onDismiss: () -> Unit, dismissEnabled: Boolean = true, actions: @Composable RowScope.() -> Unit = {
    val close = LocalPopoverExit.current
    TextButton(onClick = { close(onDismiss) }) { Text(AppMessages.text(R.string.close)) }
}, content: @Composable ColumnScope.() -> Unit) {
    CompactPopover(onDismiss, dismissEnabled = dismissEnabled) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        }
    }
}
