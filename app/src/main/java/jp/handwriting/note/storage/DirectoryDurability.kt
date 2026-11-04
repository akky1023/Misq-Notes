package jp.handwriting.note.storage

import java.io.File

/** fsyncing a new file does not by itself persist the directory entry that names it. */
@android.annotation.SuppressLint("ObsoleteSdkInt")
internal fun syncNoteDirectory(directory: File) {
    // JVM unit tests have no Android filesystem; real durability is exercised on-device.
    if (android.os.Build.VERSION.SDK_INT < 29) return
    require(directory.isDirectory)
    val descriptor = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
    try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
}
