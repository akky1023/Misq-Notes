package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import android.content.*
import android.net.Uri
import androidx.core.content.FileProvider
import jp.handwriting.note.storage.NotePaper
import java.io.File
import java.util.UUID

internal object NoteSharing {
    fun capture(context: Context, strokes: List<EditorStroke>, paper: NotePaper): Uri {
        val file = File(File(context.cacheDir, "exports").also { it.mkdirs() }, "${UUID.randomUUID()}.png")
        file.outputStream().use { NoteExport.png(strokes, paper, NoteExport.selectionBounds(strokes), it, highQuality = true) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    fun putClipboard(context: Context, uri: Uri) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData(AppMessages.text(R.string.capture_clip_label), arrayOf("image/png"), ClipData.Item(uri)))
    }
    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(AppMessages.text(R.string.selection), uri) }
        context.startActivity(Intent.createChooser(intent, AppMessages.text(R.string.share_capture)))
    }
}
