package jp.handwriting.note.editor

/** Immutable AVL tree: edits copy a logarithmic path; a save captures the list in constant time. */
internal class PersistentEntries<T : Any> private constructor(private val root: Node<T>?) : AbstractList<OrderedEdits.Entry<T>>() {
    constructor() : this(null)
    private class Node<T>(val entry: OrderedEdits.Entry<T>, val left: Node<T>? = null, val right: Node<T>? = null) {
        val height: Int = 1 + maxOf(left?.height ?: 0, right?.height ?: 0)
        val count: Int = 1 + (left?.count ?: 0) + (right?.count ?: 0)
    }
    override val size get() = root?.count ?: 0
    fun value(id: Long): T? {
        var node = root
        while (node != null) node = when {
            id < node.entry.id -> node.left
            id > node.entry.id -> node.right
            else -> return node.entry.value
        }
        return null
    }
    override fun get(index: Int): OrderedEdits.Entry<T> {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index=$index size=$size")
        var node = checkNotNull(root); var remaining = index
        while (true) {
            val before = node.left?.count ?: 0
            when {
                remaining < before -> node = checkNotNull(node.left)
                remaining > before -> { remaining -= before + 1; node = checkNotNull(node.right) }
                else -> return node.entry
            }
        }
    }
    override fun iterator(): Iterator<OrderedEdits.Entry<T>> = object : Iterator<OrderedEdits.Entry<T>> {
        private val stack = ArrayDeque<Node<T>>()
        init { push(root) }
        private fun push(start: Node<T>?) { var node = start; while (node != null) { stack.addLast(node); node = node.left } }
        override fun hasNext() = stack.isNotEmpty()
        override fun next(): OrderedEdits.Entry<T> {
            if (stack.isEmpty()) throw NoSuchElementException()
            val node = stack.removeLast(); push(node.right); return node.entry
        }
    }
    fun with(id: Long, value: T): PersistentEntries<T> {
        fun put(node: Node<T>?): Node<T> {
            if (node == null) return Node(OrderedEdits.Entry(id, value))
            if (id == node.entry.id) return if (node.entry.value === value) node else Node(OrderedEdits.Entry(id, value), node.left, node.right)
            return balance(if (id < node.entry.id) Node(node.entry, put(node.left), node.right) else Node(node.entry, node.left, put(node.right)))
        }
        return PersistentEntries(put(root))
    }
    fun without(id: Long): PersistentEntries<T> {
        fun remove(node: Node<T>?, key: Long): Node<T>? {
            node ?: return null
            return when {
                key < node.entry.id -> balance(Node(node.entry, remove(node.left, key), node.right))
                key > node.entry.id -> balance(Node(node.entry, node.left, remove(node.right, key)))
                node.left == null -> node.right
                node.right == null -> node.left
                else -> {
                    var successor = checkNotNull(node.right)
                    while (true) { val next = successor.left ?: break; successor = next }
                    balance(Node(successor.entry, node.left, remove(node.right, successor.entry.id)))
                }
            }
        }
        return PersistentEntries(remove(root, id))
    }
    companion object {
        fun <T : Any> from(entries: List<OrderedEdits.Entry<T>>): PersistentEntries<T> {
            val sorted = entries.sortedBy { it.id }
            fun build(first: Int, end: Int): Node<T>? {
                if (first == end) return null
                val middle = (first + end) ushr 1
                return Node(sorted[middle], build(first, middle), build(middle + 1, end))
            }
            return PersistentEntries(build(0, sorted.size))
        }
        private fun <T> left(node: Node<T>): Node<T> {
            val next = checkNotNull(node.right)
            return Node(next.entry, Node(node.entry, node.left, next.left), next.right)
        }
        private fun <T> right(node: Node<T>): Node<T> {
            val next = checkNotNull(node.left)
            return Node(next.entry, next.left, Node(node.entry, next.right, node.right))
        }
        private fun <T> balance(node: Node<T>): Node<T> {
            val delta = (node.left?.height ?: 0) - (node.right?.height ?: 0)
            if (delta > 1) {
                val child = checkNotNull(node.left)
                return right(if ((child.left?.height ?: 0) < (child.right?.height ?: 0)) Node(node.entry, left(child), node.right) else node)
            }
            if (delta < -1) {
                val child = checkNotNull(node.right)
                return left(if ((child.right?.height ?: 0) < (child.left?.height ?: 0)) Node(node.entry, node.left, right(child)) else node)
            }
            return node
        }
    }
}
