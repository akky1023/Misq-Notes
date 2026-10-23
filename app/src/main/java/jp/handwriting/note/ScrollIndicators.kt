package jp.handwriting.note

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size

@Composable
private fun ScrollIndicator(metrics: () -> Pair<Float, Float>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(modifier) {
        val (fraction, progress) = metrics()
        if (fraction < 1f) {
            val height = (size.height * fraction).coerceAtLeast(28f).coerceAtMost(size.height)
            drawRoundRect(color, Offset(0f, (size.height - height) * progress.coerceIn(0f, 1f)), Size(size.width, height), CornerRadius(size.width / 2))
        }
    }
}

@Composable
internal fun GridScrollIndicator(state: LazyGridState, modifier: Modifier) {
    ScrollIndicator({
    val info = state.layoutInfo; val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    val scrollable = state.canScrollForward || state.canScrollBackward
    val fraction = if (!scrollable || total == 0) 1f else (visible.size.toFloat() / total).coerceAtMost(0.9f)
    val progress = if (!state.canScrollForward && state.canScrollBackward) 1f else if (total <= visible.size) 0f else state.firstVisibleItemIndex.toFloat() / (total - visible.size)
        fraction to progress
    }, modifier)
}

internal fun Modifier.withScrollBar(state: ScrollState): Modifier = composed {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    drawWithContent {
        drawContent()
        if (state.maxValue > 0) {
            val height = (size.height * state.viewportSize / (state.viewportSize + state.maxValue)).coerceAtLeast(28f).coerceAtMost(size.height)
            val width = 3.dp.toPx()
            drawRoundRect(color, Offset(size.width - width, (size.height - height) * state.value / state.maxValue), Size(width, height), CornerRadius(width / 2))
        }
    }
}

@Composable
internal fun ColumnScrollIndicator(state: ScrollState, modifier: Modifier) {
    ScrollIndicator({
        (if (state.maxValue <= 0) 1f else state.viewportSize.toFloat() / (state.maxValue + state.viewportSize)) to
            (if (state.maxValue <= 0) 0f else state.value.toFloat() / state.maxValue)
    }, modifier)
}
