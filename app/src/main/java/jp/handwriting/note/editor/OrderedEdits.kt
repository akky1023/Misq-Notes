package jp.handwriting.note.editor

import java.util.TreeMap

/** Main-thread document operations. An asynchronous Ink handoff never reorders edits. */
class OrderedEdits<T : Any>(private val historyLimit: Int = 256) {
    init { require(historyLimit > 0) }
    data class Entry<T>(val id: Long, val value: T)
    private data class Edit<T>(val before: List<Entry<T>>, val after: List<Entry<T>>)
    private sealed interface Pending<T>
    private class Add<T>(var value: T? = null, var canceled: Boolean = false) : Pending<T>
    private class Erase<T> : Pending<T> {
        val queries = ArrayDeque<Query<T>>()
        val removed = mutableListOf<Entry<T>>()
        var ended = false
        var canceled = false
    }
    private data class Query<T>(val hit: (T) -> Boolean, val candidates: (() -> Collection<Long>)?)
    private var content = PersistentEntries<T>()
    private val pending = TreeMap<Long, Pending<T>>()
    private val undo = ArrayDeque<Edit<T>>()
    private val redo = ArrayDeque<Edit<T>>()
    private var nextId = 1L
    val nextEntryId get() = nextId
    var revision = 0L
        private set
    val entries: Collection<T> get() {
        val captured = content
        return object : AbstractCollection<T>() {
            override val size get() = captured.size
            override fun iterator() = captured.iterator().let { entries -> object : Iterator<T> {
                override fun hasNext() = entries.hasNext()
                override fun next() = entries.next().value
            } }
        }
    }
    val busy: Boolean get() = pending.isNotEmpty()
    val canUndo: Boolean get() = !busy && undo.isNotEmpty()
    val canRedo: Boolean get() = !busy && redo.isNotEmpty()
    var onChange: () -> Unit = {}
    var onContentChange: (List<Entry<T>>, List<Entry<T>>) -> Unit = { _, _ -> }
    fun snapshot(): List<Entry<T>> = content
    fun restore(entries: List<Entry<T>>, next: Long = (entries.maxOfOrNull { it.id } ?: 0) + 1) {
        check(!busy)
        require(entries.all { it.id > 0 && it.id < next } && entries.map { it.id }.distinct().size == entries.size)
        val removed = snapshot()
        content = PersistentEntries.from(entries)
        undo.clear(); redo.clear(); nextId = next
        revision++
        onContentChange(entries, removed)
        onChange()
    }

    fun reserveStroke(): Long = nextId++.also { pending[it] = Add(); onChange() }
    fun resolveStroke(id: Long, value: T) {
        val add = pending[id] as? Add<T> ?: return
        add.value = value
        drain()
    }
    fun cancelStroke(id: Long) {
        (pending[id] as? Add<T>)?.canceled = true
        drain()
    }
    fun beginErase(): Long = nextId++.also { pending[it] = Erase(); onChange() }
    fun erase(id: Long, candidates: (() -> Collection<Long>)? = null, hit: (T) -> Boolean) {
        (pending[id] as? Erase<T>)?.queries?.addLast(Query(hit, candidates))
        drain()
    }
    fun endErase(id: Long, canceled: Boolean = false) {
        (pending[id] as? Erase<T>)?.let { it.ended = true; it.canceled = canceled }
        drain()
    }
    private fun commit(edit: Edit<T>) {
        if (edit.before.isEmpty() && edit.after.isEmpty()) return
        revision++
        undo.addLast(edit)
        if (undo.size > historyLimit) undo.removeFirst()
        redo.clear()
    }
    private fun drain() {
        while (pending.isNotEmpty()) {
            val (id, op) = checkNotNull(pending.firstEntry())
            when (op) {
                is Add -> {
                    if (op.value == null && !op.canceled) break
                    if (!op.canceled) {
                        val value = checkNotNull(op.value)
                        content = content.with(id, value)
                        onContentChange(listOf(Entry(id, value)), emptyList())
                        commit(Edit(emptyList(), listOf(Entry(id, value))))
                    }
                    pending.remove(id)
                }
                is Erase -> {
                    while (op.queries.isNotEmpty()) {
                        val query = op.queries.removeFirst()
                        val removed = mutableListOf<Entry<T>>()
                        for (key in query.candidates?.invoke() ?: content.map { it.id }) {
                            val value = content.value(key) ?: continue
                            if (query.hit(value)) {
                                val entry = Entry(key, value)
                                op.removed.add(entry); removed.add(entry)
                                content = content.without(key)
                            }
                        }
                        if (removed.isNotEmpty()) onContentChange(emptyList(), removed)
                    }
                    if (!op.ended) break
                    if (op.canceled) {
                        op.removed.forEach { content = content.with(it.id, it.value) }
                        onContentChange(op.removed, emptyList())
                    }
                    else commit(Edit(op.removed.toList(), emptyList()))
                    pending.remove(id)
                }
            }
        }
        onChange()
    }
    fun undo() {
        if (!canUndo) return
        val edit = undo.removeLast()
        apply(edit.after, edit.before)
        redo.addLast(edit)
        revision++
        onChange()
    }
    fun redo() {
        if (!canRedo) return
        val edit = redo.removeLast()
        apply(edit.before, edit.after)
        undo.addLast(edit)
        revision++
        onChange()
    }
    /** Replace in place so a lasso move preserves IDs, drawing order and one-step undo. */
    fun replace(values: Map<Long, T>) {
        check(!busy)
        require(values.keys.all { content.value(it) != null })
        val before = values.keys.sorted().map { Entry(it, checkNotNull(content.value(it))) }
        val after = before.map { Entry(it.id, values.getValue(it.id)) }
        if (before == after) return
        apply(before, after); commit(Edit(before, after)); onChange()
    }
    fun delete(ids: Set<Long>) {
        check(!busy)
        val before = ids.sorted().mapNotNull { id -> content.value(id)?.let { Entry(id, it) } }
        if (before.isEmpty()) return
        apply(before, emptyList()); commit(Edit(before, emptyList())); onChange()
    }
    /** A pasted group is a single undo step, preserving relative order. */
    fun addAll(values: List<T>): List<Long> {
        check(!busy)
        val added = values.map { Entry(nextId++, it) }
        if (added.isEmpty()) return emptyList()
        apply(emptyList(), added); commit(Edit(emptyList(), added)); onChange()
        return added.map { it.id }
    }
    private fun apply(before: List<Entry<T>>, after: List<Entry<T>>) {
        before.forEach { content = content.without(it.id) }
        after.forEach { content = content.with(it.id, it.value) }
        onContentChange(after, before)
    }
}
