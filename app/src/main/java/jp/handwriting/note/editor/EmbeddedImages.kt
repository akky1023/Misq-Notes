package jp.handwriting.note.editor

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import jp.handwriting.note.storage.ImageData
import jp.handwriting.note.storage.ImageRecord
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.core.graphics.createBitmap
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object EmbeddedImages {
    private val bitmaps = object : LruCache<ImageData, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: ImageData, value: Bitmap) = value.allocationByteCount
    }
    private val paint = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    fun draw(canvas: Canvas, image: ImageRecord) {
        val bitmap = bitmaps.get(image.data) ?: checkNotNull(BitmapFactory.decodeByteArray(image.data.png, 0, image.data.png.size)) {
            "Invalid imported image"
        }.also { bitmaps.put(image.data, it) }
        canvas.drawBitmap(bitmap, null, RectF(image.left, image.top, image.left + image.width, image.top + image.height), paint.get())
    }
    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val factor = minOf(1.0, 2048.0 / maxOf(width, height), sqrt(4_000_000.0 / (width.toDouble() * height)))
        return maxOf(1, (width * factor).toInt()) to maxOf(1, (height * factor).toInt())
    }
    private fun encode(bitmap: Bitmap): ImageData {
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
        return ImageData(bytes.toByteArray())
    }
    /** Staging gives PdfRenderer a seekable descriptor and releases all provider access before commit. */
    suspend fun read(context: Context, uri: Uri, selectPages: suspend (Int) -> List<Int> = { (0 until it).toList() }): List<ImageData> {
        val file = File.createTempFile("note-import-", ".source", context.cacheDir)
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= 256L * 1024 * 1024) { "File exceeds 256 MB" }
                    out.write(buffer, 0, count)
                }
                require(total > 0) { "Empty file" }
            } }
            val pdf = java.io.RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(minOf(file.length(), 1024).toInt()); input.readFully(header)
                String(header, Charsets.ISO_8859_1).contains("%PDF-")
            }
            if (!pdf) {
                val source = ImageDecoder.createSource(file)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (w, h) = dimensions(info.size.width, info.size.height)
                    decoder.setTargetSize(w, h)
                    decoder.setOnPartialImageListener { false }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    return listOf(encode(bitmap)).also { currentCoroutineContext().ensureActive() }
                } finally { bitmap.recycle() }
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { document ->
                    require(document.pageCount in 1..200) { "PDF must have between 1 and 200 pages" }
                    val pages = selectPages(document.pageCount).distinct().sorted()
                    require(pages.isNotEmpty() && pages.all { it in 0 until document.pageCount }) { "Select valid PDF pages" }
                    var total = 0L
                    return pages.map { index ->
                        currentCoroutineContext().ensureActive()
                        document.openPage(index).use { page ->
                            // Render at twice the document's logical width, with a fixed allocation ceiling.
                            val factor = minOf(2000.0 / page.width, 8192.0 / page.height,
                                sqrt(4_000_000.0 / (page.width.toDouble() * page.height)))
                            val w = maxOf(1, (page.width * factor).toInt()); val h = maxOf(1, (page.height * factor).toInt())
                            val bitmap = createBitmap(w, h)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                encode(bitmap).also {
                                    currentCoroutineContext().ensureActive()
                                    total += it.png.size
                                    require(total <= 64L * 1024 * 1024) { "Imported pages exceed 64 MB" }
                                }
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        } finally { file.delete() }
    }
}
