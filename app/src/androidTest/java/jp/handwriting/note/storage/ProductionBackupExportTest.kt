package jp.handwriting.note.storage

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

/** Explicitly invoked after production has verified its backup and copied it to app-owned external storage. */
class ProductionBackupExportTest {
    @Test fun receiveGrantedPreMigrationBackup() {
        val test = InstrumentationRegistry.getInstrumentation()
        val file = File(test.targetContext.filesDir, "production-backup-before-misq-v2.zip")
        // A fixed, read-only shell command avoids provider startup restrictions on some devices.
        test.uiAutomation.executeShellCommand("cat /storage/emulated/0/Android/data/net.neomenia.misqnotes/files/before-misq-v2.zip").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        var count = 0
        ZipFile(file).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.sha256")).bufferedReader().readLines()
            for (line in manifest) {
                val hash = line.substring(0, 64); val name = line.substring(66)
                assertEquals(hash, zip.getInputStream(zip.getEntry(name)).use { NotebookArchive.digest(it.readBytes()) }); count++
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        test.sendStatus(0, Bundle().apply { putString("stream", "\nPRIVATE_BACKUP files=$count bytes=${file.length()} sha256=$hash\n") })
    }
}
