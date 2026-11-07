package jp.handwriting.note.storage

import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Stable logical hashes, unique physical uploads. Incomplete files are never overwritten. */
internal class ImmutableNoteTransport(private val store: ImmutableNoteStore, private val remote: PackedFiles) {
    @Volatile private var inventory = emptySet<String>()
    private val sealed = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val checked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val acknowledged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val snapshotDepth = ThreadLocal<Int>()
    init { store.fetch = ::openLogical }

    /** One synchronous sync pass reuses its checked inventory; later passes see new writers. */
    fun <T> withInventorySnapshot(block: () -> T): T {
        val depth = snapshotDepth.get() ?: 0
        if (depth == 0) refresh()
        snapshotDepth.set(depth + 1)
        try { return block() }
        finally {
            if (depth == 0) snapshotDepth.remove() else snapshotDepth.set(depth)
        }
    }
    @Synchronized private fun remember(vararg names: String) { inventory = inventory + names }
    @Synchronized private fun rememberObject(id: String, name: String, seal: String) {
        inventory = inventory + name + seal
        sealed[id] = name
        checked += seal
    }
    @Synchronized private fun refresh() {
        if ((snapshotDepth.get() ?: 0) > 0) return
        inventory = remote.names()
        sealed.entries.removeAll { (_, name) -> name !in inventory || "seal-${name.removePrefix("object-")}" !in inventory }
        inventory.filter { it.matches(SEAL) && it !in checked }.forEach { name ->
            val hash = name.substring(5, 69); val body = "object-${name.removePrefix("seal-")}"
            if (body in inventory && runCatching { remote.read(name).contentEquals(hash.toByteArray(Charsets.US_ASCII)) }.getOrDefault(false)) {
                sealed["o-$hash"] = body; checked += name
            }
        }
    }
    private fun openLogical(id: String): InputStream {
        if (sealed[id] == null) refresh()
        return remote.openRead(sealed[id] ?: throw IOException("Uncommitted or missing external object: $id"))
    }
    fun publish(root: ImmutableNoteStore.Root, checkpoint: () -> Unit = {}) {
        refresh()
        val boundary = if (acknowledged.all { sealed.containsKey(it) }) acknowledged else emptySet()
        val closure = store.closure(root, boundary)
        closure.forEach { id ->
            checkpoint()
            val existing = sealed[id]
            if (existing != null) return@forEach
            val suffix = "${id.removePrefix("o-")}-${UUID.randomUUID()}"
            val name = "object-$suffix"
            store.openObject(id).use { remote.writeStream(name, it) }
            check(remote.digest(name) == id.removePrefix("o-")) { "External object verification failed" }
            val seal = "seal-$suffix"; val bytes = id.removePrefix("o-").toByteArray(Charsets.US_ASCII)
            remote.write(seal, bytes); check(remote.read(seal).contentEquals(bytes))
            rememberObject(id, name, seal)
        }
        val prefix = "commit-${root.info.id}-${root.id.removePrefix("o-")}-"
        val bytes = store.objectBytes(root.id)
        if (inventory.none { it.startsWith(prefix) && runCatching { remote.read(it).contentEquals(bytes) }.getOrDefault(false) }) {
            val name = prefix + UUID.randomUUID()
            remote.write(name, bytes); check(remote.read(name).contentEquals(bytes)) { "External commit verification failed" }
            remember(name)
        }
        acknowledged += closure
    }
    fun receive(name: String, checkpoint: () -> Unit = {}): ImmutableNoteStore.Root {
        require(name.matches(COMMIT)); refresh(); require(name in inventory)
        val hash = "o-${name.substring(44, 108)}"
        remote.openRead(name).use { store.importObject(hash, it) }
        val root = store.root(hash); require(root.info.id == name.substring(7, 43))
        val closure = store.closure(root); checkpoint()
        if (closure.any { !sealed.containsKey(it) }) throw IOException("Incomplete incoming state; previous state has been retained")
        store.verify(root, bodies = false)
        return root
    }
    fun noteIds(): Set<String> { refresh(); return inventory.filter { it.matches(COMMIT) }.map { it.substring(7, 43) }.toSet() }
    fun verifyRemote(root: ImmutableNoteStore.Root, checkpoint: () -> Unit = {}) {
        refresh()
        store.closure(root).forEach { id ->
            checkpoint()
            val name = sealed[id] ?: throw IOException("Missing committed object: $id")
            check(remote.digest(name) == id.removePrefix("o-")) { "Damaged committed object: $id" }
        }
    }
    fun heads(noteId: String): List<ImmutableNoteStore.Root> {
        refresh()
        val names = inventory.filter { it.matches(COMMIT) && it.substring(7, 43) == noteId }
        // Torn advertisements were never committed. Valid advertisements with missing
        // dependencies cause an error, preserving the previously installed healthy state.
        val complete = names.filter { name -> runCatching { ContentHash.digest(remote.read(name)) == name.substring(44, 108) }.getOrDefault(false) }
        val records = complete.groupBy { it.substring(44, 108) }.mapValues { it.value.first() }
        val roots = records.map { (hash, name) ->
            val id = "o-$hash"; remote.openRead(name).use { store.importObject(id, it) }; store.root(id)
        }
        val ancestors = mutableSetOf<String>(); val pending = java.util.ArrayDeque<String>()
        roots.flatMap { it.parents }.forEach(pending::add)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (ancestors.add(id)) store.root(id).parents.forEach(pending::add)
        }
        return roots.filter { it.id !in ancestors }.map { receive(records.getValue(it.id.removePrefix("o-"))) }
    }
    companion object {
        private val SEAL = Regex("seal-[a-f0-9]{64}-[a-f0-9-]{36}")
        private val COMMIT = Regex("commit-[a-f0-9-]{36}-[a-f0-9]{64}-[a-f0-9-]{36}")
    }
}
