package jp.handwriting.note.storage

import java.io.*

/** Document organization only. No theme, pen preferences, or app defaults are exported. */
internal object NotebookFolders {
    fun write(file: File, value: LibraryPreferences) {
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(1)
            out.writeInt(value.folders.size)
            value.folders.forEach { out.writeUTF(it.id); out.writeUTF(it.name); out.writeUTF(it.parent.orEmpty()); out.writeInt(it.color) }
            out.writeInt(value.locations.size); value.locations.forEach { (id, folder) -> out.writeUTF(id); out.writeUTF(folder) }
            out.writeInt(value.sorts.size); value.sorts.forEach { (folder, sort) -> out.writeUTF(folder); out.writeInt(sort.ordinal) }
            out.writeInt(value.orders.size); value.orders.forEach { (folder, ids) -> out.writeUTF(folder); out.writeInt(ids.size); ids.forEach(out::writeUTF) }
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
    }
    fun read(file: File, settings: LibraryPreferences): LibraryPreferences = DataInputStream(ByteArrayInputStream(NoteFiles.readEnvelope(file))).use { input ->
        require(input.readInt() == 1)
        fun count(max: Int): Int = input.readInt().also { require(it in 0..max) }
        val folders = List(count(10000)) { NoteFolder(input.readUTF(), input.readUTF(), input.readUTF().ifEmpty { null }, input.readInt()) }
        val locations = List(count(100000)) { input.readUTF() to input.readUTF() }
        val sorts = List(count(10001)) { input.readUTF() to NoteSort.entries[input.readInt()] }
        var total = 0
        val orders = List(count(10001)) {
            val folder = input.readUTF(); val size = count(100000); total += size; require(total <= 100000)
            folder to List(size) { input.readUTF() }
        }
        require(input.available() == 0)
        require(listOf(locations.map { it.first }, sorts.map { it.first }, orders.map { it.first }).all { it.size == it.distinct().size })
        settings.copy(folders = folders, locations = locations.toMap(), sorts = sorts.toMap(), orders = orders.toMap())
    }
}
