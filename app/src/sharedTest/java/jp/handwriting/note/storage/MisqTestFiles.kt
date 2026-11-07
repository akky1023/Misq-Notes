package jp.handwriting.note.storage

import java.io.File
import java.util.UUID

/** Standalone MISQ0002 exchange-file fixtures, never part of an application APK. */
internal class MisqTestFiles(internal val directory: File) {
    init { check(directory.isDirectory || directory.mkdirs()) }
    fun document(id: String) = MisqFile(File(directory, "$id.misq"))
    fun create(title: String, now: Long = System.currentTimeMillis(), paper: NotePaper = NotePaper()): NoteManifest {
        val note = NoteManifest(NoteInfo(UUID.randomUUID().toString(), title, now, now, paper = paper), emptyList())
        document(note.info.id).save(note) { error("Empty note") }
        return note
    }
    fun save(note: NoteManifest, placements: Map<String, StrokePlacement> = emptyMap(), encode: (String) -> ByteArray) = document(note.info.id).save(note, placements, encode)
    fun read(id: String) = document(id).readManifest()
    fun stroke(id: String, storageId: String) = document(id).stroke(storageId)
    fun rename(id: String, title: String) { val note = read(id).manifest; save(note.copy(info = note.info.copy(title = title, updatedAt = System.currentTimeMillis()))) { error("Existing stroke") } }
    fun previous(id: String) = document(id).previous()
    fun collectOrphans(id: String) = document(id).compactIfNeeded()
    fun delete(id: String) { check(document(id).file.delete()) }
    fun list() = directory.listFiles().orEmpty().filter { it.extension == "misq" }.mapNotNull { file ->
        runCatching { MisqFile(file, readOnlyAlias = true).summary().takeIf { file.nameWithoutExtension == it.id } }.getOrNull()
    }
    fun thumbnail(id: String) = File(directory, "$id/thumbnail-v2.png")
    fun encodeManifest(note: NoteManifest) = NoteManifestCodec.encodeManifest(note)
}

/** Explicit conversion of synthetic exchange-file fixtures before exercising the current editor. */
internal fun prepareModernReview(root: File): File {
    require(root.name.startsWith("conflict-fixture-"))
    val notes = File(root, "notes")
    val storage = Format3Storage(notes)
    if (!storage.active) {
        val sources = notes.listFiles().orEmpty().filter { file ->
            file.extension == "misq" && runCatching { UUID.fromString(file.nameWithoutExtension).toString() == file.nameWithoutExtension }.getOrDefault(false)
        }.filter { file -> MisqFile(file, readOnlyAlias = true).summary().id == file.nameWithoutExtension }
        val library = Format3Library(storage.create(), notes)
        sources.forEach { library.imported(it) }
    }
    return root
}
