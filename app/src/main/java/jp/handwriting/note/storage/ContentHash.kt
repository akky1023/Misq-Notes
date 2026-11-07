package jp.handwriting.note.storage

import java.io.File
import java.security.MessageDigest

internal object ContentHash {
    private const val HEX = "0123456789abcdef"
    fun digest(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        hex(digest.digest())
    }
    fun digest(bytes: ByteArray): String {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
    internal fun hex(hash: ByteArray): String {
        return CharArray(hash.size * 2).also { chars -> hash.forEachIndexed { i, byte ->
            val value = byte.toInt() and 255
            chars[i * 2] = HEX[value ushr 4]; chars[i * 2 + 1] = HEX[value and 15]
        } }.concatToString()
    }
}
