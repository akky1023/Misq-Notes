package jp.handwriting.note.storage

import java.io.*
import java.util.UUID
import jp.handwriting.note.editor.ViewPosition

internal object NoteManifestCodec {
    private fun uuid(id: String): String { require(UUID.fromString(id).toString() == id); return id }
    private fun validTitle(title: String) = NoteFiles.validTitle(title)
    internal fun decodeManifest(id: String, bytes: ByteArray): NoteManifest {
        // Share the parser without altering either live generation.
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x484E4D31)
        val version = input.readInt(); require(version in 1..4)
        val storedId = uuid(input.readUTF()); require(storedId == id)
        val title = validTitle(input.readUTF()); val created = input.readLong(); val updated = input.readLong()
        val view = ViewPosition(input.readFloat(), input.readFloat(), input.readFloat())
        val color = input.readInt(); val size = input.readFloat(); require(size.isFinite() && size in 0.1f..100f)
        val paper = if (version >= 2) NotePaper(input.readInt(), PaperPattern.entries[input.readInt()], input.readInt(), input.readInt(),
            if (version >= 3) input.readFloat() else 1f).let { if (version >= 4) it.readMargin(input) else it } else NotePaper()
        val next = input.readLong(); val count = input.readInt(); require(next > 0 && count in 0..1_000_000)
        val refs = List(count) { StrokeRef(input.readLong().also { require(it > 0 && it < next) }, uuid(input.readUTF())) }
        require(refs.map { it.id }.distinct().size == count && refs.map { it.storageId }.distinct().size == count && input.available() == 0)
        return NoteManifest(NoteInfo(id, title, created, updated, view, color, size, paper), refs, next)
    }
    internal fun encodeManifest(note: NoteManifest): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x484E4D31); out.writeInt(4)
            out.writeUTF(uuid(note.info.id)); out.writeUTF(validTitle(note.info.title))
            out.writeLong(note.info.createdAt); out.writeLong(note.info.updatedAt)
            out.writeFloat(note.info.position.zoom); out.writeFloat(note.info.position.left); out.writeFloat(note.info.position.top)
            out.writeInt(note.info.color); out.writeFloat(note.info.size)
            out.writeInt(note.info.paper.color); out.writeInt(note.info.paper.pattern.ordinal); out.writeInt(note.info.paper.lineColor); out.writeInt(note.info.paper.spacing)
            out.writeFloat(note.info.paper.lineWidth); note.info.paper.writeMargin(out)
            out.writeLong(note.nextId)
            out.writeInt(note.strokes.size)
            // New IDs were validated by strokeFile; loaded IDs by stroke(). Revalidating every
            // unchanged UUID and constructing its File on each pen lift adds no integrity check.
            note.strokes.forEach { out.writeLong(it.id); out.writeUTF(it.storageId) }
        } }.toByteArray()
}
