package jp.handwriting.note.storage

import java.io.*
import java.util.UUID

/** Durable edits before downloading an unopened part of the authoritative notebook. */
internal object PartialNoteJournal {
    private fun folder(root: File, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, ".partial-$id")
    }
    fun exists(root: File, id: String) = File(folder(root, id), "manifest").exists() || File(folder(root, id), "manifest.bak").exists()
    fun write(root: File, snapshot: NoteSnapshot) {
        val entries = snapshot.entries.associateBy { it.value.storageId }
        write(root, snapshot.manifest, entries.mapValues { it.value.value.placement }) { NoteRepository.encodeStroke(entries.getValue(it).value) }
    }
    internal fun write(root: File, note: NoteManifest, placements: Map<String, StrokePlacement>, encode: (String) -> ByteArray) {
        val dir = folder(root, note.info.id).also { check(it.isDirectory || it.mkdirs()) }
        placements.keys.forEach { storageId ->
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
    fun clear(root: File, id: String) { val dir = folder(root, id); check(!dir.exists() || dir.deleteRecursively()) }
    fun recover(root: File, files: NoteFiles, deleted: (String) -> Boolean = { false }, ensure: (String) -> Unit) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".partial-") }.forEach { dir ->
            val id = dir.name.removePrefix(".partial-")
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && exists(root, id)) {
                if (deleted(id)) { clear(root, id); return@forEach }
                val target = File(dir, "manifest")
                val bytes = runCatching { NoteFiles.readEnvelope(target) }.getOrElse { NoteFiles.readEnvelope(File(target.path + ".bak")) }
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val size = input.readInt(); require(size in 1..128 * 1024 * 1024)
                    val manifest = NoteManifestCodec.decodeManifest(id, ByteArray(size).also(input::readFully))
                    val count = input.readInt(); require(count in 0..1_000_000)
                    val placements = buildMap { repeat(count) {
                        val storage = input.readUTF(); require(UUID.fromString(storage).toString() == storage)
                        put(storage, StrokePlacement(input.readFloat(), input.readFloat(), input.readFloat()))
                    } }
                    require(input.read() == -1)
                    ensure(id)
                    files.save(manifest, placements) { NoteFiles.readEnvelope(File(dir, it)) }
                }
                clear(root, id)
            }
        }
    }
}
