package jp.handwriting.note.editor

import android.graphics.Canvas
import android.graphics.RenderNode

/** View-sized display lists keep unchanged Ink meshes out of the UI-thread drawing loop. */
internal class StrokeRenderCache(private val appendLimit: Int = APPEND_SIZE) {
    init { require(appendLimit in 1..GROUP_SIZE) }
    companion object { internal const val GROUP_SIZE = 64; internal const val APPEND_SIZE = 8 }
    private data class Item(val stroke: EditorStroke, val selected: Boolean)
    private data class RecordedNode(val node: RenderNode, val position: ViewPosition) {
        fun discard() = node.discardDisplayList()
    }
    private data class Group(val nodes: List<RecordedNode>, val items: List<Item>, val offset: WorldPoint, val tail: List<Item> = emptyList())
    private val groups = mutableMapOf<Long, Group>()
    private var viewport: ViewPosition? = null
    private var width = 0
    private var height = 0
    internal var recordedGroups = 0
        private set
    internal val pendingCompaction get() = groups.values.any { it.nodes.size > 1 }

    fun clear() {
        groups.values.forEach { group -> group.nodes.forEach { it.discard() } }
        groups.clear()
    }

    fun draw(canvas: Canvas, entries: List<Pair<Long, EditorStroke>>, position: ViewPosition,
        viewWidth: Int, viewHeight: Int, selectionOffset: WorldPoint,
        selected: (EditorStroke) -> Boolean, compact: Boolean = false, retainedStrokes: Set<EditorStroke>? = null, drawStroke: (Canvas, EditorStroke, Boolean) -> Unit) {
        // Bitmap exports and previews continue to use Ink's software renderer.
        if (!canvas.isHardwareAccelerated) {
            entries.forEach { drawStroke(canvas, it.second, selected(it.second)) }
            return
        }
        if (viewport?.zoom != position.zoom || width != viewWidth || height != viewHeight) {
            clear(); viewport = position; width = viewWidth; height = viewHeight
        }
        // Operation IDs are stable under erase/undo; deleting a stroke never shifts later groups.
        val visible = entries.groupBy { it.first / GROUP_SIZE }
        val unused = groups.keys.filter { id -> id !in visible &&
            (retainedStrokes == null || groups.getValue(id).items.none { it.stroke in retainedStrokes }) }
        unused.forEach { groups.remove(it)?.nodes?.forEach(RecordedNode::discard) }
        var compacted = false
        fun record(id: Long, items: List<Item>, reuse: RecordedNode? = null): RecordedNode {
            val node = reuse?.node ?: RenderNode("note-strokes-$id")
            // A stroke crossing the screen edge must remain complete when panned into view.
            node.setClipToBounds(false)
            node.setPosition(0, 0, viewWidth, viewHeight)
            val recording = node.beginRecording(viewWidth, viewHeight)
            try { items.forEach { drawStroke(recording, it.stroke, it.selected) } }
            finally { node.endRecording() }
            recordedGroups++
            return RecordedNode(node, position)
        }
        for ((id, strokes) in visible) {
            val items = strokes.map { Item(it.second, selected(it.second)) }
            val offset = if (items.any { it.selected }) selectionOffset else WorldPoint(0f, 0f)
            var group = groups[id]
            if (group == null || group.items != items || group.offset != offset) {
                if (group != null && appendLimit < GROUP_SIZE && group.offset == offset && items.size > group.items.size &&
                    group.items.indices.all { group.items[it] == items[it] }) {
                    // Retain the large base display list; re-record only the appended tail.
                    val tail = group.tail + items.drop(group.items.size)
                    val nodes = group.nodes.toMutableList()
                    val reusable = if (group.tail.isNotEmpty()) nodes.removeAt(nodes.lastIndex) else null
                    val chunks = tail.chunked(appendLimit)
                    chunks.forEachIndexed { index, chunk -> nodes += record(id, chunk, if (index == 0) reusable else null) }
                    group = Group(nodes, items, offset, chunks.last().takeIf { it.size < appendLimit }.orEmpty())
                } else {
                    group?.nodes?.forEach(RecordedNode::discard)
                    group = Group(listOf(record(id, items)), items, offset)
                }
                groups[id] = group
            }
            if (compact && !compacted && group.nodes.size > 1) {
                group.nodes.forEach(RecordedNode::discard)
                group = Group(listOf(record(id, items)), items, offset)
                groups[id] = group; compacted = true
            }
            val scale = viewWidth / Viewport.WIDTH * position.zoom
            group.nodes.forEach { recorded ->
                canvas.save()
                canvas.translate((recorded.position.left - position.left) * scale,
                    (recorded.position.top - position.top) * scale)
                canvas.drawRenderNode(recorded.node)
                canvas.restore()
            }
        }
    }
}
