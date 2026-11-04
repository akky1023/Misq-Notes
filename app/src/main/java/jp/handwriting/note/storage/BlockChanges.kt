package jp.handwriting.note.storage

import java.io.*
import java.util.UUID
import org.json.JSONObject

/** Durable per-band generations. An older upload can never acknowledge a newer edit. */
internal object BlockChanges {
    data class Capture(val generation: Long, val signatures: Map<Long, String>, val dirty: Map<Long, Long>,
        val metadata: String, val metadataDirty: Long, val baseline: String?, val complete: Boolean, val stamp: String,
        val acknowledgedGeneration: Long = 0)
    private fun stateFile(file: File) = File(File(file.parentFile, ".block-state"), file.nameWithoutExtension.also {
        require(UUID.fromString(it).toString() == it)
    })
    private fun stamp(file: File): String = file.noteStamp().let { "${it.identity}|${it.modified}|${it.size}" }
    private fun read(file: File): Capture? = runCatching {
        val json = JSONObject(String(NoteFiles.readEnvelope(stateFile(file)), Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val signatures = json.getJSONObject("signatures").let { value -> value.keys().asSequence().associate { it.toLong() to value.getString(it) } }
        val dirty = json.getJSONObject("dirty").let { value -> value.keys().asSequence().associate { it.toLong() to value.getLong(it) } }
        Capture(json.getLong("generation"), signatures, dirty, json.getString("metadata"), json.getLong("metadataDirty"),
            json.optString("baseline").takeIf { it.isNotEmpty() }, json.getBoolean("complete"), json.getString("stamp"), json.optLong("acknowledgedGeneration", 0))
    }.getOrNull()
    private fun write(file: File, state: Capture) {
        val signatures = JSONObject(); state.signatures.forEach { (band, signature) -> signatures.put(band.toString(), signature) }
        val dirty = JSONObject(); state.dirty.forEach { (band, generation) -> dirty.put(band.toString(), generation) }
        val json = JSONObject().put("version", 1).put("generation", state.generation).put("signatures", signatures)
            .put("dirty", dirty).put("metadata", state.metadata).put("metadataDirty", state.metadataDirty)
            .put("baseline", state.baseline.orEmpty()).put("complete", state.complete).put("stamp", state.stamp)
            .put("acknowledgedGeneration", state.acknowledgedGeneration)
        val target = stateFile(file)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        NoteFiles.atomicWrite(target, json.toString().toByteArray())
    }
    fun update(file: File, index: MisqFile.Index): Capture {
        val previous = read(file)
        val complete = index.manifest.strokes.all { it.storageId in index.placements }
        val signatures = index.manifest.strokes.groupBy { index.placements[it.storageId]?.band ?: Long.MIN_VALUE }.mapValues { (_, refs) ->
            val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
                refs.sortedBy { it.id }.forEach { ref ->
                    output.writeLong(ref.id); output.writeUTF(ref.storageId); output.writeUTF(index.records.getValue(ref.storageId).hash)
                    index.placements[ref.storageId]?.let { output.writeFloat(it.startY); output.writeFloat(it.top); output.writeFloat(it.bottom) }
                }
            } }.toByteArray()
            NotebookArchive.digest(bytes)
        }
        val metadata = NotebookArchive.digest(NoteManifestCodec.encodeManifest(index.manifest.copy(strokes = emptyList())))
        val changed = (previous?.signatures.orEmpty().keys + signatures.keys).filter { previous?.signatures?.get(it) != signatures[it] }
        val metadataChanged = previous == null || previous.metadata != metadata
        val currentStamp = stamp(file)
        val generation = (previous?.generation ?: 0) + if (changed.isNotEmpty() || metadataChanged || previous?.complete != complete || previous.stamp != currentStamp) 1 else 0
        val dirty = previous?.dirty.orEmpty() + changed.associateWith { generation }
        val state = Capture(generation, signatures, dirty, metadata, if (metadataChanged) generation else previous!!.metadataDirty,
            previous?.baseline, complete, currentStamp, previous?.acknowledgedGeneration ?: 0)
        if (state != previous) write(file, state)
        return state
    }
    fun capture(file: File): Capture? = runCatching {
        read(file)?.takeIf { it.stamp == stamp(file) } ?: update(file, MisqFile(file, readOnlyAlias = true).index())
    }.getOrNull()
    fun acknowledge(file: File, captured: Capture, hash: String) {
        val current = read(file) ?: return
        if (captured.generation < current.acknowledgedGeneration) return
        val remaining = current.dirty.filter { (band, generation) ->
            generation > captured.generation || current.signatures[band] != captured.signatures[band]
        }
        val metadataDirty = if (current.metadataDirty <= captured.generation && current.metadata == captured.metadata) 0 else current.metadataDirty
        val next = current.copy(dirty = remaining, metadataDirty = metadataDirty, baseline = hash, acknowledgedGeneration = captured.generation)
        if (next != current) write(file, next)
    }
    fun forget(file: File) {
        val target = stateFile(file)
        listOf(target, File(target.path + ".bak")).forEach { check(!it.exists() || it.delete()) }
    }
}
