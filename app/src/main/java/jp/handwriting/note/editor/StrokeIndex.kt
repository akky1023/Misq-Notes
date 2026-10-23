package jp.handwriting.note.editor

import kotlin.math.floor

/** Vertical buckets select visible candidates without walking the entire notebook each frame. */
class StrokeIndex<T : Any> {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private data class Item<T>(val value: T, val bounds: Bounds, val buckets: LongRange?)
    private val items = mutableMapOf<Long, Item<T>>()
    private val buckets = mutableMapOf<Long, MutableSet<Long>>()
    private val longStrokes = mutableSetOf<Long>()
    private val bottoms = java.util.TreeMap<Float, Int>()
    val bottom get() = if (bottoms.isEmpty()) 0f else bottoms.lastKey()
    private fun bucket(y: Float) = floor(y.toDouble() / 512.0).toLong()
    fun add(id: Long, value: T, bounds: Bounds) {
        remove(id)
        val first = bucket(bounds.top); val last = bucket(bounds.bottom)
        val range = if (last - first <= 128) first..last else null
        items[id] = Item(value, bounds, range)
        bottoms[bounds.bottom] = (bottoms[bounds.bottom] ?: 0) + 1
        if (range == null) longStrokes.add(id)
        else range.forEach { buckets.getOrPut(it) { mutableSetOf() }.add(id) }
    }
    fun remove(id: Long) {
        val item = items.remove(id) ?: return
        val count = bottoms.getValue(item.bounds.bottom)
        if (count == 1) bottoms.remove(item.bounds.bottom) else bottoms[item.bounds.bottom] = count - 1
        longStrokes.remove(id)
        item.buckets?.forEach { bucket -> buckets[bucket]?.let { it.remove(id); if (it.isEmpty()) buckets.remove(bucket) } }
    }
    fun query(rect: Bounds): List<Pair<Long, T>> {
        val ids = longStrokes.toMutableSet()
        val first = bucket(rect.top); val last = bucket(rect.bottom)
        if (last - first > 1024) ids.addAll(items.keys)
        else (first..last).forEach { buckets[it]?.let(ids::addAll) }
        return ids.asSequence().filter { id ->
            val b = items.getValue(id).bounds
            b.left <= rect.right && b.right >= rect.left && b.top <= rect.bottom && b.bottom >= rect.top
        }.sorted().map { it to items.getValue(it).value }.toList()
    }
}
