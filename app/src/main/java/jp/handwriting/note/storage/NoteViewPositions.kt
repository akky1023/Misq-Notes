package jp.handwriting.note.storage

import jp.handwriting.note.editor.ViewPosition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Device-local reading positions; these files never go through notebook synchronization. */
internal class NoteViewPositions(private val directory: File) {
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.hwp")
    }

    fun read(id: String): ViewPosition? {
        val file = file(id)
        for (candidate in listOf(file, File(file.path + ".bak"))) {
            val position = runCatching {
                DataInputStream(ByteArrayInputStream(NoteFiles.readEnvelope(candidate))).use { input ->
                    require(input.readInt() == 1)
                    val value = ViewPosition(zoom = input.readFloat(), left = input.readFloat(), top = input.readFloat())
                    require(input.available() == 0)
                    value
                }
            }.getOrNull()
            if (position != null) return position
        }
        return null
    }

    fun write(id: String, position: ViewPosition) {
        check(directory.isDirectory || directory.mkdirs())
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1)
                out.writeFloat(position.zoom); out.writeFloat(position.left); out.writeFloat(position.top)
            }
        }.toByteArray()
        NoteFiles.atomicWrite(file(id), bytes)
    }
}
