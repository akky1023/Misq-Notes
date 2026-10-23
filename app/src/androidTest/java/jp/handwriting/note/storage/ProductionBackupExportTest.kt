package jp.handwriting.note.storage

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

/** Explicitly invoked only after production has made and granted its pre-migration backup. */
class ProductionBackupExportTest {
    @Test fun receiveGrantedPreMigrationBackup() {
        val test = InstrumentationRegistry.getInstrumentation()
        @Suppress("DEPRECATION")
        val signers = test.targetContext.packageManager.getPackageInfo(test.targetContext.packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
        test.sendStatus(0, Bundle().apply { putString("stream", "\nPROBE_SIGNER ${signers.joinToString { NotebookArchive.digest(it.toByteArray()) }}\n") })
        // Starting instrumentation force-stops its target and clears transient URI grants.
        // Restart the donor only after this receiver is running, then wait for its backup grant.
        test.uiAutomation.executeShellCommand("am start -S -W -n net.neomenia.misqnotes/jp.handwriting.note.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val file = File(test.targetContext.filesDir, "production-backup-before-packed.zip")
        val uri = Uri.parse("content://net.neomenia.misqnotes.files/exports/before-packed-v1.zip")
        val deadline = android.os.SystemClock.elapsedRealtime() + 20000
        var received = false
        while (!received) {
            try {
                checkNotNull(test.targetContext.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { input.copyTo(it) } }
                received = true
            } catch (error: Exception) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw error
                Thread.sleep(200)
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
        test.sendStatus(0, Bundle().apply { putString("stream", "\nPRIVATE_BACKUP files=$count bytes=${file.length()} sha256=${NotebookArchive.digest(file.readBytes())}\n") })
    }
}
