package jp.handwriting.note.editor

import kotlin.math.floor

/** Main-thread index: vertical buckets avoid walking the entire notebook each frame. */
class StrokeIndex<T : Any> {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private class Item<T>(val id: Long, val value: T, val bounds: Bounds, val buckets: LongRange?) {
        var queryVersion = 0L
    }
    private val items = mutableMapOf<Long, Item<T>>()
    private val buckets = mutableMapOf<Long, MutableSet<Item<T>>>()
    private val longStrokes = mutableSetOf<Item<T>>()
    private val bottoms = java.util.TreeMap<Float, Int>()
    private var queryVersion = 0L
    val bottom get() = if (bottoms.isEmpty()) 0f else bottoms.lastKey()
    private fun bucket(y: Float) = floor(y.toDouble() / 512.0).toLong()
    fun add(id: Long, value: T, bounds: Bounds) {
        remove(id)
        val first = bucket(bounds.top); val last = bucket(bounds.bottom)
        val range = if (last - first <= 128) first..last else null
        val item = Item(id, value, bounds, range)
        items[id] = item
        bottoms[bounds.bottom] = (bottoms[bounds.bottom] ?: 0) + 1
        if (range == null) longStrokes.add(item)
        else range.forEach { buckets.getOrPut(it) { mutableSetOf() }.add(item) }
    }
    fun remove(id: Long) {
        val item = items.remove(id) ?: return
        val count = bottoms.getValue(item.bounds.bottom)
        if (count == 1) bottoms.remove(item.bounds.bottom) else bottoms[item.bounds.bottom] = count - 1
        longStrokes.remove(item)
        item.buckets?.forEach { bucket -> buckets[bucket]?.let { it.remove(item); if (it.isEmpty()) buckets.remove(bucket) } }
    }
    fun query(rect: Bounds): List<Pair<Long, T>> {
        if (queryVersion == Long.MAX_VALUE) {
            items.values.forEach { it.queryVersion = 0L }
            queryVersion = 0L
        }
        val version = ++queryVersion
        val ids = ArrayList<Long>()
        // A stroke can span several buckets. Visit it once without allocating a set per frame.
        fun collect(item: Item<T>) {
            if (item.queryVersion == version) return
            item.queryVersion = version
            val b = item.bounds
            if (b.left <= rect.right && b.right >= rect.left && b.top <= rect.bottom && b.bottom >= rect.top) ids.add(item.id)
        }
        val first = bucket(rect.top); val last = bucket(rect.bottom)
        if (last - first > 1024) items.values.forEach(::collect)
        else {
            longStrokes.forEach(::collect)
            (first..last).forEach { buckets[it]?.forEach(::collect) }
        }
        ids.sort()
        return ids.map { it to items.getValue(it).value }
    }
}
