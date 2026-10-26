package jp.handwriting.note.storage

import android.net.Uri
import androidx.core.net.toUri

/** Fixed disposable copy, never the user's akky/.Database/Misq notes directory. */
internal object MisqTestCopy {
    val tree: Uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMisqStorageTest-20261026".toUri()
}
