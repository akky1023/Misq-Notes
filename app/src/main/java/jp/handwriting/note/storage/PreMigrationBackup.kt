package jp.handwriting.note.storage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** A verified, separately named backup before each storage-format migration. */
internal object PreMigrationBackup {
    /** Explicit maintenance export to app-owned external storage; no provider handoff is needed. */
    fun copyForAdb(context: Context) {
        val name = "before-misq-v2.zip"
        val source = File(context.noBackupFilesDir, name)
        val target = File(checkNotNull(context.getExternalFilesDir(null)), name)
        fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            digest.digest()
        }
        if (target.exists() && hash(source).contentEquals(hash(target))) return
        val temporary = File(target.path + ".tmp")
        FileOutputStream(temporary).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
        check(hash(source).contentEquals(hash(temporary)))
        Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        android.util.Log.i("MisqBackup", "adbBackupReady=true bytes=${target.length()}")
    }
    fun ensure(context: Context, name: String = "before-packed-v1.zip") {
        require(name in setOf("before-packed-v1.zip", "before-misq-v2.zip"))
        val backup = File(context.noBackupFilesDir, name)
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
            val exported = File(context.cacheDir, "exports/$name").also { it.parentFile!!.mkdirs() }
            if (!exported.exists()) backup.copyTo(exported)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", exported)
            runCatching { context.grantUriPermission("net.neomenia.misqnotes.probe", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { android.util.Log.w("MisqBackup", "Backup grant failed", it) }
        }
    }
}
