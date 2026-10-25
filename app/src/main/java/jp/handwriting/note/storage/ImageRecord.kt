package jp.handwriting.note.storage

import java.io.*
import java.nio.ByteBuffer

/** Owned PNG bytes, never a URI or a path into the document provider. */
class ImageData(val png: ByteArray) {
    val width: Int
    val height: Int
    init {
        require(png.size in 33..MAX_BYTES)
        val header = ByteBuffer.wrap(png)
        require(header.long == 0x89504E470D0A1A0AuL.toLong() && header.int == 13 && header.int == 0x49484452)
        width = header.int; height = header.int
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 4_000_000)
    }
    companion object { const val MAX_BYTES = 20 * 1024 * 1024 }
}

data class ImageRecord(val data: ImageData, val left: Float, val top: Float, val width: Float, val height: Float) {
    init {
        require(listOf(left, top, width, height).all { it.isFinite() })
        require(width > 0 && height > 0 && (left + width).isFinite() && (top + height).isFinite())
    }
    fun translated(dx: Float, dy: Float) = copy(left = left + dx, top = top + dy)
    fun resized(factor: Float): ImageRecord {
        require(factor.isFinite() && factor > 0f)
        return copy(width = width * factor, height = height * factor)
    }
    fun resizedFromCenter(factor: Float, centerX: Float = left + width / 2f, centerY: Float = top + height / 2f): ImageRecord {
        val scaled = resized(factor)
        return scaled.copy(left = centerX - scaled.width / 2f, top = centerY - scaled.height / 2f)
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
        out.writeInt(MAGIC); out.writeInt(1)
        out.writeFloat(left); out.writeFloat(top); out.writeFloat(width); out.writeFloat(height)
        out.writeInt(data.png.size); out.write(data.png)
    } }.toByteArray()
    companion object {
        const val MAGIC = 0x484E4931 // HNI1, stored in the same immutable content store as ink.
        fun decode(bytes: ByteArray): ImageRecord = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val left = input.readFloat(); val top = input.readFloat(); val width = input.readFloat(); val height = input.readFloat()
            val size = input.readInt(); require(size in 33..ImageData.MAX_BYTES && size == input.available())
            val png = ByteArray(size); input.readFully(png)
            ImageRecord(ImageData(png), left, top, width, height)
        }
    }
}
