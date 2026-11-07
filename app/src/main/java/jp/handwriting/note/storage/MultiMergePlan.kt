package jp.handwriting.note.storage

import jp.handwriting.note.editor.StrokeIndex

internal enum class MergeStrength { MANUAL, CAUTIOUS, STANDARD, AGGRESSIVE }
internal enum class MergeKind { ADD, DELETE, EDIT, TITLE, PAPER }
internal data class MergeValue(val entity: String, val blob: String, val hash: String,
    val order: Long, val source: Int) { val key get() = "$entity:$hash" }
internal fun mergeBounds(a: StrokeIndex.Bounds, b: StrokeIndex.Bounds) = StrokeIndex.Bounds(
    minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

internal data class MultiMergeChange(val key: String, val kind: MergeKind, val base: MergeValue?,
    val versions: List<MergeValue?>, val changed: Set<Int>, val area: StrokeIndex.Bounds?, val uncertain: Boolean) {
    val combines get() = kind !in listOf(MergeKind.TITLE, MergeKind.PAPER) &&
        !(kind == MergeKind.DELETE && versions.any { it == null } && versions.any { it != null })
}
internal data class MultiMergeStep(val changes: List<MultiMergeChange>, val suggested: Set<Int>) {
    val key = changes.joinToString("|") { it.key }
    val area = changes.mapNotNull { it.area }.reduceOrNull(::mergeBounds)
    fun accepts(sources: Set<Int>) = sources.isNotEmpty() && (sources.size == 1 || changes.all { it.combines })
}

/** One common ancestor across all versions; a decision selects whole versions within each region. */
internal class MultiMergePlan private constructor(val indexes: List<MisqFile.Index>, val live: List<List<MergeValue>>,
    val unchanged: List<MergeValue>, val changes: List<MultiMergeChange>, val values: List<MergeValue>) {
    private val cache = java.util.EnumMap<MergeStrength, List<MultiMergeStep>>(MergeStrength::class.java)
    @Synchronized fun steps(strength: MergeStrength): List<MultiMergeStep> = cache.getOrPut(strength) {
        val parents = IntArray(changes.size) { it }
        fun root(start: Int): Int { var i = start; while (parents[i] != i) { parents[i] = parents[parents[i]]; i = parents[i] }; return i }
        val spatial = StrokeIndex<Int>(); val margin = if (strength == MergeStrength.CAUTIOUS) 8f else 1f
        changes.forEachIndexed { i, row -> row.area?.let { b ->
            val padded = StrokeIndex.Bounds(b.left - margin, b.top - margin, b.right + margin, b.bottom + margin)
            spatial.query(padded).forEach { (_, j) -> parents[root(i)] = root(j) }
            spatial.add(i.toLong(), i, padded)
        } }
        changes.indices.groupBy(::root).values.map { ids ->
            val rows = ids.map(changes::get); val changed = rows.flatMap { it.changed }.toSet()
            val suggestion = when {
                strength == MergeStrength.MANUAL || rows.any { it.uncertain || it.kind in listOf(MergeKind.TITLE, MergeKind.PAPER) } -> emptySet()
                changed.size == 1 -> changed
                strength == MergeStrength.AGGRESSIVE && rows.all {
                    it.kind == MergeKind.ADD && it.versions.filterNotNull().map { value -> value.hash }.distinct().size == 1
                } -> changed
                else -> emptySet()
            }
            MultiMergeStep(rows, suggestion)
        }.sortedWith(compareBy<MultiMergeStep> { it.area?.top ?: Float.NEGATIVE_INFINITY }.thenBy { it.area?.left ?: 0f })
    }
    fun complete(strength: MergeStrength, decisions: Map<String, Set<Int>>) = steps(strength).all { step ->
        decisions[step.key]?.let { chosen -> step.accepts(chosen) && chosen.all { it in indexes.indices } } == true
    }
    fun result(strength: MergeStrength, decisions: Map<String, Set<Int>>, provisional: Boolean = false): List<MergeValue> {
        if (!provisional) check(complete(strength, decisions)) { "Unconfirmed merge steps" }
        val result = unchanged.toMutableList()
        steps(strength).forEach { step ->
            val selected = decisions[step.key] ?: if (step.suggested.isNotEmpty()) step.suggested else setOf(0)
            check(step.accepts(selected) && selected.all { it in indexes.indices })
            step.changes.filter { it.kind !in listOf(MergeKind.TITLE, MergeKind.PAPER) }.forEach { row ->
                result += selected.sorted().mapNotNull { row.versions[it] }.distinctBy { it.key }
            }
        }
        val added = changes.filter { it.base == null }.map { it.key }.toSet()
        return result.distinctBy { it.key }.sortedWith(compareBy<MergeValue> { it.entity in added }
            .thenBy { if (it.entity in added) it.source.toLong() else it.order }.thenBy { it.order }.thenBy { it.entity }.thenBy { it.hash })
    }
    fun metadataSource(key: String, strength: MergeStrength, decisions: Map<String, Set<Int>>): Int =
        steps(strength).firstOrNull { step -> step.changes.any { it.key == key } }?.let { decisions[it.key]?.singleOrNull() } ?: 0
    fun title(strength: MergeStrength, decisions: Map<String, Set<Int>>) = indexes[metadataSource("@title", strength, decisions)].manifest.info.title
    fun paper(strength: MergeStrength, decisions: Map<String, Set<Int>>) = indexes[metadataSource("@paper", strength, decisions)].manifest.info.paper

    companion object {
        fun build(indexes: List<MisqFile.Index>, bounds: (MergeValue) -> StrokeIndex.Bounds?): MultiMergePlan {
            require(indexes.size >= 2 && indexes.map { it.manifest.info.id }.distinct().size == 1)
            val histories = indexes.map { it.events.groupBy { event -> event.entity } }
            val live = indexes.mapIndexed { source, index -> index.manifest.strokes.map { ref ->
                val entity = index.entities.getValue(ref.id)
                val event = histories[source][entity].orEmpty().lastOrNull { it.blob == ref.storageId }
                MergeValue(entity, ref.storageId, index.records.getValue(ref.storageId).hash,
                    event?.order ?: ref.id, source)
            } }
            val maps = live.map { it.associateBy { value -> value.entity } }
            val unchanged = mutableListOf<MergeValue>(); val changes = mutableListOf<MultiMergeChange>()
            val values = linkedMapOf<String, MergeValue>()
            for (entity in (histories.flatMap { it.keys } + maps.flatMap { it.keys }).toSortedSet()) {
                val versions = maps.map { it[entity] }; versions.filterNotNull().forEach { values.putIfAbsent(it.key, it) }
                if (versions.map { it?.hash }.distinct().size == 1) { versions.firstOrNull()?.let(unchanged::add); continue }
                val logs = histories.map { it[entity].orEmpty() }
                val commonIds = logs.map { it.map { event -> event.version }.toSet() }.reduce { a, b -> a.intersect(b) }
                val common = MisqFile.frontier(logs.first().filter { it.version in commonIds })
                val ancestor = common.singleOrNull()
                val base = ancestor?.blob?.let { blob -> indexes.indexOfFirst { blob in it.records }.takeIf { it >= 0 }?.let { source ->
                    MergeValue(entity, blob, indexes[source].records.getValue(blob).hash,
                        ancestor.order, source)
                } }
                base?.let { values.putIfAbsent(it.key, it) }
                val deleted = versions.indices.any { versions[it] == null && logs[it].isNotEmpty() }
                val affected = (listOfNotNull(base) + versions.filterNotNull()).distinctBy { it.key }
                val boxes = affected.map(bounds)
                val uncertain = common.size > 1 || (ancestor?.blob != null && base == null) ||
                    logs.any { it.isNotEmpty() && MisqFile.frontier(it).size != 1 } || boxes.any { it == null } ||
                    (commonIds.isEmpty() && deleted) || versions.indices.any { versions[it] != null && logs[it].isEmpty() } ||
                    commonIds.any { id -> logs.map { log -> log.first { it.version == id } }.distinct().size != 1 }
                val kind = if ((base != null || deleted) && versions.any { it == null }) MergeKind.DELETE else if (base == null) MergeKind.ADD else MergeKind.EDIT
                changes += MultiMergeChange(entity, kind, base, versions, versions.indices.filter { versions[it]?.hash != base?.hash }.toSet(),
                    boxes.filterNotNull().reduceOrNull(::mergeBounds), uncertain)
            }
            for ((key, kind, different) in listOf(Triple("@title", MergeKind.TITLE, indexes.map { it.manifest.info.title }.distinct().size > 1),
                Triple("@paper", MergeKind.PAPER, indexes.map { it.manifest.info.paper }.distinct().size > 1))) {
                if (different) changes += MultiMergeChange(key, kind, null, indexes.map { null }, indexes.indices.toSet(), null, false)
            }
            return MultiMergePlan(indexes, live, unchanged, changes, values.values.toList())
        }
    }
}
