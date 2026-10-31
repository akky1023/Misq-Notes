package jp.handwriting.note

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import jp.handwriting.note.editor.EditorViewModel
import java.io.File

/** Isolated instrumentation fixture; never configures the real notebook storage. */
class ConflictFixtureActivity : ComponentActivity() {
    lateinit var model: EditorViewModel
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMessages.configure(this)
        val root = File(checkNotNull(intent.getStringExtra("fixture"))).canonicalFile
        check(root.path.startsWith(cacheDir.canonicalPath + File.separator + "conflict-fixture-"))
        model = ViewModelProvider(this)[EditorViewModel::class.java]
        model.start(root)
        setContent { NotebookApp(model, {}, {}, model::closeNote) }
    }
}
