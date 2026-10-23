package jp.handwriting.note

import android.content.Intent
import android.provider.DocumentsContract
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import jp.handwriting.note.editor.EditorViewModel
import jp.handwriting.note.storage.RealDataExperiment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Usable local-first UI on a copy. External checking is read-only; no import or sync writes. */
class ExperimentActivity : MainActivity() {
    internal lateinit var experimentModel: EditorViewModel
    internal var verification: Job? = null
    internal var verificationSucceeded = false
    internal var verificationFailure: Exception? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri == RealDataExperiment.tree) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            getSharedPreferences("startup-experiment", MODE_PRIVATE).edit().putBoolean("externalGranted", true).apply()
        }
        finish()
    }
    override fun startNotebook(model: EditorViewModel) {
        experimentModel = model
        if (intent.action == "jp.handwriting.note.CHOOSE_EXPERIMENT_COPY") {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUriUsingTree(RealDataExperiment.tree, DocumentsContract.getTreeDocumentId(RealDataExperiment.tree))))
            return
        }
        val started = SystemClock.elapsedRealtime()
        check(RealDataExperiment.working(this).isDirectory) { "Prepare the copied dataset first" }
        // Only this app can change its own launcher on devices that prohibit shell overrides.
        val launcher = android.content.ComponentName(this, "jp.handwriting.note.ExperimentLauncher")
        if (packageManager.getComponentEnabledSetting(launcher) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            packageManager.setComponentEnabledSetting(launcher, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
            packageManager.setComponentEnabledSetting(android.content.ComponentName(this, MainActivity::class.java),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        }
        val waitForExternal = intent.getBooleanExtra("waitForExternal", false)
        if (!waitForExternal) model.start(RealDataExperiment.working(this))
        lifecycleScope.launch {
            model.workspace.first { !it.working }
            Log.i("MisqExperiment", "listReadyMs=${SystemClock.elapsedRealtime() - started}")
        }
        verification = lifecycleScope.launch {
            try {
                val revision = withContext(Dispatchers.IO) { RealDataExperiment.verifyExternal(this@ExperimentActivity) { phase, ms ->
                    Log.i("MisqExperiment", "$phase=$ms")
                } }
                verificationSucceeded = true
                if (waitForExternal) model.start(RealDataExperiment.working(this@ExperimentActivity))
                Log.i("MisqExperiment", "externalVerifiedMs=${SystemClock.elapsedRealtime() - started} files=${revision.files.size}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                verificationFailure = error
                if (waitForExternal) model.start(RealDataExperiment.working(this@ExperimentActivity))
                Log.w("MisqExperiment", "External copy check failed; local copy remains usable", error)
            }
        }
    }
}
