package jp.handwriting.note.storage

import jp.handwriting.note.editor.StrokeIndex

internal enum class MergeStrength { MANUAL, CAUTIOUS, STANDARD, AGGRESSIVE }
internal enum class MergeChoice { A, B, BOTH }
internal enum class MergeKind { ADD, DELETE, EDIT, TITLE, PAPER }
internal data class MergeValue(val entity: String, val blob: String, val hash: String, val side: MergeChoice,
    val order: Long, val at: Long, val source: Int = if (side == MergeChoice.A) 0 else 1) { val key get() = "$entity:$hash" }
internal data class MergeChange(val key: String, val kind: MergeKind, val base: MergeValue?, val a: MergeValue?, val b: MergeValue?,
    val aChanged: Boolean, val bChanged: Boolean, val area: StrokeIndex.Bounds?, val uncertain: Boolean = false) {
    val sameResult get() = a?.hash == b?.hash
    val bothAllowed get() = kind != MergeKind.TITLE && kind != MergeKind.PAPER && !(base != null && (a == null || b == null))
}
internal data class MergeGroup(val changes: List<MergeChange>, val suggested: MergeChoice?, val overlaps: Boolean) {
    val key = changes.joinToString("|") { it.key }
    val bothAllowed get() = changes.all { it.bothAllowed }
    val area get() = changes.mapNotNull { it.area }.reduceOrNull(::mergeBounds)
}
internal fun mergeBounds(a: StrokeIndex.Bounds, b: StrokeIndex.Bounds) = StrokeIndex.Bounds(
    minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

/** Decisions use ancestry and content hashes, never device clock ordering. */
internal class StrokeMergePlan private constructor(val a: MisqFile.Index, val b: MisqFile.Index,
    val unchanged: List<MergeValue>, val changes: List<MergeChange>, val values: List<MergeValue>) {
    private val groupCache = java.util.EnumMap<MergeStrength, List<MergeGroup>>(MergeStrength::class.java)
    @Synchronized fun groups(strength: MergeStrength): List<MergeGroup> = groupCache.getOrPut(strength) { buildGroups(strength) }
    private fun buildGroups(strength: MergeStrength): List<MergeGroup> {
        val parents = IntArray(changes.size) { it }
        fun root(start: Int): Int { var i = start; while (parents[i] != i) { parents[i] = parents[parents[i]]; i = parents[i] }; return i }
        val index = StrokeIndex<Int>()
        val margin = if (strength == MergeStrength.CAUTIOUS) 8f else 1f
        val overlaps = mutableSetOf<Int>()
        changes.forEachIndexed { i, change ->
            change.area?.let { area ->
                val padded = StrokeIndex.Bounds(area.left - margin, area.top - margin, area.right + margin, area.bottom + margin)
                index.query(padded).forEach { (_, j) ->
                    val other = changes[j]
                    parents[root(i)] = root(j)
                    if ((change.aChanged && other.bChanged) || (change.bChanged && other.aChanged)) {
                        overlaps += i; overlaps += j
                    }
                }
                index.add(i.toLong(), i, padded)
            }
        }
        return changes.indices.groupBy(::root).values.map { ids ->
            val rows = ids.map(changes::get)
            val overlap = ids.any { it in overlaps }
            val suggested = when {
                strength == MergeStrength.MANUAL -> null
                rows.any { it.uncertain || it.kind == MergeKind.TITLE || it.kind == MergeKind.PAPER } -> null
                rows.all { it.sameResult } -> MergeChoice.A
                rows.any { it.area == null } -> null
                strength == MergeStrength.AGGRESSIVE && rows.all { it.kind == MergeKind.ADD && !(it.aChanged && it.bChanged) } -> MergeChoice.BOTH
                overlap || rows.any { it.aChanged && it.bChanged } -> null
                rows.all { it.aChanged && !it.bChanged } -> MergeChoice.A
                rows.all { it.bChanged && !it.aChanged } -> MergeChoice.B
                else -> null
            }
            MergeGroup(rows, suggested, overlap)
        }.sortedWith(compareBy<MergeGroup> { it.area?.top ?: Float.NEGATIVE_INFINITY }.thenBy { it.area?.left ?: 0f })
    }
    fun decisions(strength: MergeStrength, overrides: Map<String, MergeChoice>): Map<String, MergeChoice?> =
        groups(strength).flatMap { group -> group.changes.map { it.key to (overrides[it.key] ?: group.suggested) } }.toMap()

    fun result(strength: MergeStrength, overrides: Map<String, MergeChoice>, aOnTop: Boolean): List<MergeValue> {
        val decisions = decisions(strength, overrides)
        check(changes.all { decisions[it.key] != null }) { "Unresolved merge changes" }
        check(changes.all { decisions[it.key] != MergeChoice.BOTH || it.bothAllowed })
        val result = unchanged.toMutableList()
        changes.filter { it.kind != MergeKind.TITLE && it.kind != MergeKind.PAPER }.forEach { change ->
            val choice = checkNotNull(decisions[change.key])
            check(choice != MergeChoice.BOTH || change.bothAllowed)
            result += when (choice) {
                MergeChoice.A -> listOfNotNull(change.a)
                MergeChoice.B -> listOfNotNull(change.b)
                MergeChoice.BOTH -> listOfNotNull(change.a, change.b).distinctBy { it.hash }
            }
        }
        val added = changes.filter { it.base == null }.map { it.key }.toSet()
        return result.distinctBy { it.key }.sortedWith(compareBy<MergeValue> { it.entity in added }
            .thenBy { if (it.entity in added) if ((it.side == MergeChoice.A) == aOnTop) 1L else 0L else it.order }
            .thenBy { it.order }.thenBy { it.entity }.thenBy { it.hash })
    }
    fun paper(decisions: Map<String, MergeChoice?>) = if (decisions["@paper"] == MergeChoice.B) b.manifest.info.paper else a.manifest.info.paper
    fun title(decisions: Map<String, MergeChoice?>) = if (decisions["@title"] == MergeChoice.B) b.manifest.info.title else a.manifest.info.title

    companion object {
        fun build(a: MisqFile.Index, b: MisqFile.Index, bounds: (MergeValue) -> StrokeIndex.Bounds?): StrokeMergePlan {
            require(a.manifest.info.id == b.manifest.info.id)
            val leftEvents = a.events.groupBy { it.entity }; val rightEvents = b.events.groupBy { it.entity }
            fun live(index: MisqFile.Index, side: MergeChoice, events: Map<String, List<MisqFile.Event>>) = index.manifest.strokes.associate { ref ->
                val entity = index.entities.getValue(ref.id)
                val head = events[entity].orEmpty().lastOrNull { it.blob == ref.storageId }
                entity to MergeValue(entity, ref.storageId, index.records.getValue(ref.storageId).hash, side, head?.order ?: ref.id, head?.at ?: index.manifest.info.updatedAt)
            }
            val av = live(a, MergeChoice.A, leftEvents); val bv = live(b, MergeChoice.B, rightEvents)
            val unchanged = mutableListOf<MergeValue>(); val changes = mutableListOf<MergeChange>()
            val all = linkedMapOf<String, MergeValue>()
            fun remember(value: MergeValue?) { if (value != null) all.putIfAbsent(value.key, value) }
            for (entity in (leftEvents.keys + rightEvents.keys + av.keys + bv.keys).sorted()) {
                val ae = leftEvents[entity].orEmpty(); val be = rightEvents[entity].orEmpty()
                val commonIds = ae.map { it.version }.toSet().intersect(be.map { it.version }.toSet())
                val common = MisqFile.frontier(ae.filter { it.version in commonIds })
                val baseEvent = common.singleOrNull()
                val base = baseEvent?.blob?.let { blob ->
                    val index = if (blob in a.records) a else b
                    index.records[blob]?.let { MergeValue(entity, blob, it.hash, if (index === a) MergeChoice.A else MergeChoice.B, baseEvent.order, baseEvent.at) }
                }
                val x = av[entity]; val y = bv[entity]
                remember(x); remember(y); remember(base)
                // Equal current contents need no decision, even when their histories diverge.
                if (x?.hash == y?.hash) { x?.let(unchanged::add); continue }
                val ax = x?.hash != base?.hash; val by = y?.hash != base?.hash
                if (!ax && !by) { x?.let(unchanged::add); continue }
                val inconsistent = common.size > 1 || (baseEvent?.blob != null && base == null) || (x != null && ae.isEmpty()) || (y != null && be.isEmpty()) ||
                    (ae.isNotEmpty() && MisqFile.frontier(ae).size != 1) || (be.isNotEmpty() && MisqFile.frontier(be).size != 1) ||
                    commonIds.any { id ->
                        val l = ae.first { it.version == id }; val r = be.first { it.version == id }
                        l.entity != r.entity || l.parents != r.parents ||
                            (l.blob?.let { a.records[it]?.hash } != r.blob?.let { b.records[it]?.hash })
                    }
                val affected = listOfNotNull(base, x.takeIf { ax }, y.takeIf { by })
                val boxes = affected.map(bounds)
                val area = boxes.filterNotNull().reduceOrNull(::mergeBounds)
                val kind = if (base == null) MergeKind.ADD else if (x == null || y == null) MergeKind.DELETE else MergeKind.EDIT
                changes += MergeChange(entity, kind, base, x, y, ax, by, area, inconsistent || boxes.any { it == null })
            }
            if (a.manifest.info.title != b.manifest.info.title) changes += MergeChange("@title", MergeKind.TITLE, null, null, null, true, true, null)
            if (a.manifest.info.paper != b.manifest.info.paper) changes += MergeChange("@paper", MergeKind.PAPER, null, null, null, true, true, null)
            return StrokeMergePlan(a, b, unchanged, changes, all.values.toList())
        }
    }
}
