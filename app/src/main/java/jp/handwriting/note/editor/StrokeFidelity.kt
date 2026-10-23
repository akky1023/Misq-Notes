package jp.handwriting.note.editor

import androidx.ink.strokes.Stroke

/** Geometry tolerance in document units, also bounded relative to thin brush tips. */
internal object StrokeFidelity {
    fun displayEpsilon(size: Float) = minOf(0.01f, size / 128f)
    fun exportEpsilon(size: Float) = minOf(0.001f, size / 1024f)
}

/** Refine only strokes actually rendered; keep the original document and inputs immutable. */
internal class StrokeFidelityCache(private val forExport: Boolean = false) {
    private val strokes = object : LinkedHashMap<Stroke, Stroke>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stroke, Stroke>?) = forExport && size > 128
    }

    private var retained: Set<Stroke>? = null

    /** Keep the selected working set, including off-screen strokes when requested. */
    @Synchronized fun retainVisible(visible: Collection<Stroke>) {
        retained = visible.toSet()
        strokes.keys.retainAll(retained!!)
    }

    @Synchronized private fun cached(stroke: Stroke) = strokes[stroke]

    @Synchronized private fun store(stroke: Stroke, refined: Stroke): Stroke {
        if (retained?.contains(stroke) == false) return refined
        return strokes.getOrPut(stroke) { refined }
    }

    /** May run on a worker; stale work must not repopulate a changed retention set. */
    fun prefetch(stroke: Stroke) {
        if (synchronized(this) { retained?.contains(stroke) == true }) get(stroke)
    }

    fun get(stroke: Stroke): Stroke {
        val epsilon = if (forExport) StrokeFidelity.exportEpsilon(stroke.brush.size)
            else StrokeFidelity.displayEpsilon(stroke.brush.size)
        if (stroke.brush.epsilon <= epsilon) return stroke
        return cached(stroke) ?: store(stroke, stroke.copy(stroke.brush.copy(epsilon = epsilon)))
    }

    @Synchronized fun clear() { retained = emptySet(); strokes.clear() }
}
