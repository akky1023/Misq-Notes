package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import java.io.*

internal class ArchiveConflictException : IOException(AppMessages.text(R.string.storage_conflict))

/** Physical, immutable objects in a dedicated directory. Other files are never removed. */
internal interface PackedFiles {
    fun names(): Set<String>
    fun read(name: String): ByteArray
    fun openRead(name: String): InputStream = ByteArrayInputStream(read(name))
    fun writeStream(name: String, input: InputStream) = write(name, input.readBytes())
    fun digest(name: String): String = openRead(name).use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

