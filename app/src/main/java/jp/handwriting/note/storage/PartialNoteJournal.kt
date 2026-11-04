package jp.handwriting.note.storage

import java.io.*
import java.util.UUID

/** Durable edits before downloading an unopened part of the authoritative notebook. */
internal object PartialNoteJournal {
    internal data class Capture(val manifest: NoteManifest, val placements: Map<String, StrokePlacement>,
        val token: String, private val directory: File) {
        fun contains(storageId: String) = storageId in placements
        fun stroke(storageId: String): ByteArray = synchronized(PartialNoteJournal) {
            require(contains(storageId))
            val file = File(directory, storageId)
            if (file.exists()) NoteFiles.readEnvelope(file)
            else MisqFile(File(directory.parentFile, "${manifest.info.id}.misq")).stroke(storageId)
        }
        fun apply(target: MisqFile) = target.save(manifest, placements, ::stroke)
    }
    @Synchronized fun ids(root: File): List<String> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".partial-") }
        .map { it.name.removePrefix(".partial-") }
        .filter { runCatching { UUID.fromString(it).toString() == it && exists(root, it) }.getOrDefault(false) }

    @Synchronized fun read(root: File, id: String): Capture? {
        if (!exists(root, id)) return null
        val dir = folder(root, id)
        val target = File(dir, "manifest")
        val bytes = runCatching { NoteFiles.readEnvelope(target) }.getOrElse { NoteFiles.readEnvelope(File(target.path + ".bak")) }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val size = input.readInt(); require(size in 1..128 * 1024 * 1024)
            val manifest = NoteManifestCodec.decodeManifest(id, ByteArray(size).also(input::readFully))
            val count = input.readInt(); require(count in 0..1_000_000)
            val refs = manifest.strokes.map { it.storageId }.toSet()
            val placements = buildMap { repeat(count) {
                val storage = input.readUTF(); require(UUID.fromString(storage).toString() == storage && storage in refs && storage !in this)
                put(storage, StrokePlacement(input.readFloat(), input.readFloat(), input.readFloat()))
            } }
            require(input.read() == -1)
            Capture(manifest, placements, NotebookArchive.digest(bytes), dir)
        }
    }
    private fun folder(root: File, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, ".partial-$id")
    }
    @Synchronized fun exists(root: File, id: String) = File(folder(root, id), "manifest").exists() || File(folder(root, id), "manifest.bak").exists()
    fun write(root: File, snapshot: NoteSnapshot) {
        val entries = snapshot.entries.associateBy { it.value.storageId }
        write(root, snapshot.manifest, entries.mapValues { it.value.value.placement }) { NoteRepository.encodeStroke(entries.getValue(it).value) }
    }
    @Synchronized internal fun write(root: File, note: NoteManifest, placements: Map<String, StrokePlacement>, encode: (String) -> ByteArray) {
        val dir = folder(root, note.info.id).also { check(it.isDirectory || it.mkdirs()) }
        val references = note.strokes.map { it.storageId }.toSet()
        require(placements.keys.all { it in references })
        placements.keys.forEach { storageId ->
            require(UUID.fromString(storageId).toString() == storageId)
            val target = File(dir, storageId)
            if (!target.exists() || runCatching { NoteFiles.readEnvelope(target) }.isFailure)
                NoteFiles.atomicWrite(target, encode(storageId))
        }
        val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            val manifest = NoteManifestCodec.encodeManifest(note)
            output.writeInt(manifest.size); output.write(manifest)
            output.writeInt(placements.size)
            placements.forEach { (storageId, p) ->
                output.writeUTF(storageId)
                output.writeFloat(p.startY); output.writeFloat(p.top); output.writeFloat(p.bottom)
            }
        } }.toByteArray()
        NoteFiles.atomicWrite(File(dir, "manifest"), body)
    }
    @Synchronized fun clear(root: File, id: String) { val dir = folder(root, id); check(!dir.exists() || dir.deleteRecursively()) }
    fun recover(root: File, files: NoteFiles, deleted: (String) -> Boolean = { false }, ensure: (String) -> Unit) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".partial-") }.forEach { dir ->
            val id = dir.name.removePrefix(".partial-")
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && exists(root, id)) {
                if (deleted(id)) { clear(root, id); return@forEach }
                val capture = checkNotNull(read(root, id))
                ensure(id)
                files.save(capture.manifest, capture.placements, capture::stroke)
                clear(root, id)
            }
        }
    }
}
