package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import java.io.*
import jp.handwriting.note.editor.StrokeCacheMode
import java.util.UUID

internal data class NoteFolder(val id: String, val name: String, val parent: String? = null, val color: Int = 0xFFE9B949.toInt()) {
    init { require(color ushr 24 == 255) }
}
internal enum class NoteSort(private val labelResource: Int) {
    NAME_ASC(R.string.sort_name_asc), NAME_DESC(R.string.sort_name_desc), CREATED_ASC(R.string.sort_created_asc), CREATED_DESC(R.string.sort_created_desc),
    UPDATED_ASC(R.string.sort_updated_asc), UPDATED_DESC(R.string.sort_updated_desc), MANUAL(R.string.sort_manual),
    ;
    val label get() = AppMessages.text(labelResource)
    val field get() = if (this == MANUAL) 3 else ordinal / 2
    val descending get() = this != MANUAL && ordinal % 2 == 1
    fun direction(descending: Boolean) = if (this == MANUAL) this else entries[field * 2 + if (descending) 1 else 0]
    val fieldLabel get() = listOf(AppMessages.text(R.string.sort_name), AppMessages.text(R.string.sort_created), AppMessages.text(R.string.sort_updated), AppMessages.text(R.string.sort_manual))[field]
}
internal data class LibraryPreferences(val folders: List<NoteFolder> = emptyList(), val locations: Map<String, String> = emptyMap(),
    val grid: Boolean = true, val paper: NotePaper = NotePaper(), val suppressPenGestures: Boolean = false,
    val sorts: Map<String, NoteSort> = emptyMap(), val orders: Map<String, List<String>> = emptyMap(),
    val theme: AppThemeMode = AppThemeMode.SYSTEM, val amoled: Boolean = false,
    val strokeCache: StrokeCacheMode = StrokeCacheMode.NEARBY) {
    init {
        require(folders.size <= 10000 && locations.size <= 100000)
        val ids = folders.map { it.id }.toSet()
        require(ids.size == folders.size)
        folders.forEach { folder ->
            require(UUID.fromString(folder.id).toString() == folder.id)
            NoteFiles.validTitle(folder.name)
            require(folder.parent == null || folder.parent in ids)
            val seen = mutableSetOf(folder.id); var parent = folder.parent
            while (parent != null) { require(seen.add(parent)); parent = folders.first { it.id == parent }.parent }
        }
        locations.forEach { (note, folder) -> require(UUID.fromString(note).toString() == note && folder in ids) }
        require(sorts.size <= 10001 && orders.size <= 10001)
        require((sorts.keys + orders.keys).all { it.isEmpty() || it in ids })
        require(orders.values.sumOf { it.size.toLong() } <= 100000)
        orders.forEach { (_, notes) -> require(notes.distinct().size == notes.size); notes.forEach { require(UUID.fromString(it).toString() == it) } }
    }
    fun path(id: String): String {
        val folder = folders.first { it.id == id }
        return folder.parent?.let { path(it) + " / " } .orEmpty() + folder.name
    }
    fun added(name: String, parent: String?): LibraryPreferences = copy(folders = folders + NoteFolder(UUID.randomUUID().toString(), NoteFiles.validTitle(name), parent))
    fun sortFor(folder: String?) = sorts[folder.orEmpty()] ?: NoteSort.UPDATED_DESC
    fun sorted(notes: List<NoteSummary>, folder: String?): List<NoteSummary> {
        val visible = notes.filter { locations[it.id] == folder }
        val comparator: Comparator<NoteSummary> = when (sortFor(folder)) {
            NoteSort.NAME_ASC -> compareBy<NoteSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.title }
            NoteSort.NAME_DESC -> compareBy<NoteSummary, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.title }.reversed()
            NoteSort.CREATED_ASC -> compareBy { it.createdAt }
            NoteSort.CREATED_DESC -> compareByDescending { it.createdAt }
            NoteSort.UPDATED_ASC -> compareBy { it.updatedAt }
            NoteSort.UPDATED_DESC -> compareByDescending { it.updatedAt }
            NoteSort.MANUAL -> {
                val ranks = orders[folder.orEmpty()].orEmpty().withIndex().associate { it.value to it.index }
                compareBy<NoteSummary> { ranks[it.id] ?: Int.MAX_VALUE }.thenByDescending { it.updatedAt }
            }
        }
        return visible.sortedWith(comparator.thenBy { it.id })
    }
    fun reordered(folder: String?, notes: List<String>) = copy(sorts = sorts + (folder.orEmpty() to NoteSort.MANUAL), orders = orders + (folder.orEmpty() to notes))
    fun moved(note: String, folder: String?): LibraryPreferences {
        if (locations[note] == folder) return this
        val cleaned = orders.mapValues { (_, ids) -> ids - note }
        val key = folder.orEmpty()
        return copy(locations = locations - note + if (folder == null) emptyMap() else mapOf(note to folder),
            orders = cleaned + (key to (cleaned[key].orEmpty() + note)))
    }
    fun removedFolder(id: String): LibraryPreferences {
        require(folders.any { it.id == id })
        require(folders.none { it.parent == id } && id !in locations.values) { AppMessages.text(R.string.folder_not_empty) }
        return copy(folders = folders.filterNot { it.id == id }, sorts = sorts - id, orders = orders - id)
    }
}

internal class LibraryPreferencesFile(private val file: File) {
    fun read(): LibraryPreferences {
        val backup = File(file.path + ".bak")
        val settings = if (!file.exists() && !backup.exists()) LibraryPreferences()
            else try { decode(NoteFiles.readEnvelope(file)) } catch (_: Exception) { decode(NoteFiles.readEnvelope(backup)) }
        val json = File(file.parentFile, "library.json")
        return if (json.exists() || File(json.path + ".bak").exists()) LibraryJson.read(json, settings) else settings
    }
    fun write(value: LibraryPreferences) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bytes = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { out ->
            out.writeInt(9); out.writeBoolean(value.grid)
            out.writeInt(value.paper.color); out.writeInt(value.paper.pattern.ordinal); out.writeInt(value.paper.lineColor); out.writeInt(value.paper.spacing)
            out.writeFloat(value.paper.lineWidth); out.writeBoolean(value.suppressPenGestures)
            out.writeInt(value.folders.size)
            value.folders.forEach { out.writeUTF(it.id); out.writeUTF(it.name); out.writeUTF(it.parent.orEmpty()); out.writeInt(it.color) }
            out.writeInt(value.locations.size); value.locations.forEach { (note, folder) -> out.writeUTF(note); out.writeUTF(folder) }
            out.writeInt(value.sorts.size); value.sorts.forEach { (folder, sort) -> out.writeUTF(folder); out.writeInt(sort.ordinal) }
            out.writeInt(value.orders.size); value.orders.forEach { (folder, notes) -> out.writeUTF(folder); out.writeInt(notes.size); notes.forEach(out::writeUTF) }
            out.writeInt(value.theme.ordinal)
            out.writeBoolean(value.amoled)
            out.writeInt(value.strokeCache.ordinal)
            value.paper.writeMargin(out)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        LibraryJson.write(File(file.parentFile, "library.json"), value)
    }
    private fun decode(bytes: ByteArray): LibraryPreferences = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        try {
            val version = input.readInt(); require(version in 1..9); val grid = input.readBoolean()
            var paper = NotePaper(input.readInt(), PaperPattern.entries[input.readInt()], input.readInt(), input.readInt(), if (version >= 2) input.readFloat() else 1f)
            val suppress = version >= 2 && input.readBoolean()
            val count = input.readInt(); require(count in 0..10000)
            val folders = List(count) { NoteFolder(input.readUTF(), input.readUTF(), input.readUTF().ifEmpty { null }, if (version >= 3) input.readInt() else 0xFFE9B949.toInt()) }
            val size = input.readInt(); require(size in 0..100000)
            val pairs = List(size) { input.readUTF() to input.readUTF() }; require(pairs.map { it.first }.distinct().size == size)
            val sorts = if (version >= 3) {
                val n = input.readInt(); require(n in 0..10001)
                List(n) { input.readUTF() to NoteSort.entries[input.readInt()] }.also { require(it.map { it.first }.distinct().size == n) }.toMap()
            } else emptyMap()
            val orders = if (version >= 3) {
                val n = input.readInt(); require(n in 0..10001); var total = 0
                List(n) {
                    val folder = input.readUTF(); val count = input.readInt(); require(count in 0..100000)
                    total += count; require(total <= 100000)
                    folder to List(count) { input.readUTF() }
                }.also { require(it.map { it.first }.distinct().size == n) }.toMap()
            } else emptyMap()
            val themeId = if (version >= 4) input.readInt() else 0
            val legacyOled = version == 4 && themeId == 3
            val theme = if (legacyOled) AppThemeMode.DARK else AppThemeMode.entries[themeId]
            val amoled = if (version >= 5) input.readBoolean() else legacyOled
            if (version == 6) input.readBoolean() // Removed capture-sharing preference.
            val strokeCache = if (version >= 8) StrokeCacheMode.entries[input.readInt()] else StrokeCacheMode.NEARBY
            if (version >= 9) paper = paper.readMargin(input)
            require(input.available() == 0)
            LibraryPreferences(folders, pairs.toMap(), grid, paper, suppress, sorts, orders, theme, amoled, strokeCache)
        } catch (e: RuntimeException) { throw IOException("Invalid library settings", e) }
    }
}
