package jp.handwriting.note.storage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A one-time, verified local backup before the first packed-format connection. */
internal object PreMigrationBackup {
    fun ensure(context: Context) {
        val backup = File(context.noBackupFilesDir, "before-packed-v1.zip")
        if (!backup.exists()) {
            val source = buildMap<String, File> {
                context.filesDir.walkTopDown().filter { it.isFile }.forEach { put("files/" + it.relativeTo(context.filesDir).invariantSeparatorsPath, it) }
                val prefs = File(context.applicationInfo.dataDir, "shared_prefs")
                prefs.walkTopDown().filter { it.isFile }.forEach { put("shared_prefs/" + it.relativeTo(prefs).invariantSeparatorsPath, it) }
            }
            val temporary = File(backup.path + ".tmp")
            val hashes = mutableMapOf<String, String>()
            FileOutputStream(temporary).use { file ->
                val zip = ZipOutputStream(file)
                source.forEach { (name, input) ->
                    val bytes = input.readBytes(); hashes[name] = NotebookArchive.digest(bytes)
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("manifest.sha256"))
                zip.write(hashes.entries.joinToString("\n") { (name, hash) -> "$hash  $name" }.toByteArray())
                zip.closeEntry(); zip.finish(); zip.flush(); file.fd.sync(); zip.close()
            }
            ZipFile(temporary).use { zip -> hashes.forEach { (name, hash) ->
                check(zip.getInputStream(zip.getEntry(name)).use { NotebookArchive.digest(it.readBytes()) } == hash)
            } }
            check(temporary.renameTo(backup))
        }
        // A scoped grant lets the user's installed probe copy this backup to the PC over ADB.
        val trustedProbe = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo("net.neomenia.misqnotes.probe", android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners.orEmpty().any {
                NotebookArchive.digest(it.toByteArray()) == "18c354f35c28b925935e06601bb6c71f3e9bf1495b0a21ff0ce0d111eeac16bb"
            }
        }.onFailure { android.util.Log.w("MisqBackup", "Probe signing check failed", it) }.getOrDefault(false)
        android.util.Log.i("MisqBackup", "backupReady=true trustedProbe=$trustedProbe")
        if (context.packageName == "net.neomenia.misqnotes" && trustedProbe) {
            val exported = File(context.cacheDir, "exports/before-packed-v1.zip").also { it.parentFile!!.mkdirs() }
            if (!exported.exists()) backup.copyTo(exported)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", exported)
            runCatching { context.grantUriPermission("net.neomenia.misqnotes.probe", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { android.util.Log.w("MisqBackup", "Backup grant failed", it) }
        }
    }
}
