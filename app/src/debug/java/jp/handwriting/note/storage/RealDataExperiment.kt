package jp.handwriting.note.storage

import android.content.Context
import android.net.Uri
import java.io.File

/** All paths refer to explicit copies. There is no URI or path to the production notebook here. */
internal object RealDataExperiment {
    val tree: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMisqNotesExperiment-20261022")
    fun root(context: Context) = File(context.filesDir, "startup-experiment")
    fun working(context: Context) = File(root(context), "working")
    fun seed(context: Context) = File(root(context), "seed")
    fun files(directory: File): Map<String, File> = directory.walkTopDown().filter { file ->
        file.isFile && (file.name in listOf("folders.hwp", "presets.hwp") ||
            file.relativeTo(directory).invariantSeparatorsPath.matches(Regex("notes/[a-f0-9-]{36}/(manifest\\.hwn(\\.bak)?|[a-f0-9-]{36}\\.ink(\\.bak)?)")))
    }.associateBy { it.relativeTo(directory).invariantSeparatorsPath }

    fun prepare(context: Context): NotebookArchive.Revision {
        val source = File(root(context), "source")
        check(source.isDirectory) { "Copy fixture is missing" }
        val records = source.walkTopDown().filter { it.isFile && ArchiveLayout.isRecord(it.name) }.associateBy { it.name }
        val archive = NotebookArchive(object : ArchiveDirectory {
            override fun names() = records.keys
            override fun read(name: String) = records.getValue(name).readBytes()
            override fun write(name: String, bytes: ByteArray): Unit = error("Read-only fixture")
            override fun delete(name: String): Unit = error("Read-only fixture")
        })
        val revision = checkNotNull(archive.latest())
        val target = seed(context)
        // Never replace a working copy containing the user's experimental edits.
        if (!target.exists()) {
            check(target.mkdirs())
            archive.restore(revision, target)
            val presets = File(target, "presets.hwp").takeIf { it.exists() }?.let(NotebookPresets::read)
            val library = NotebookFolders.read(File(target, "folders.hwp"), LibraryPreferences())
            LibraryPreferencesFile(File(target, "library.hwp")).write(if (presets == null) library else library.copy(paper = presets.paper))
            presets?.let {
                SavedColors(File(target, "palette.hwp")).write(it.palettes)
                ToolPreferencesFile(File(target, "tools.hwp")).write(it.applyTo(ToolPreferences()))
            }
        }
        check(revision.files == files(target).mapValues { NotebookArchive.digest(it.value.readBytes()) })
        if (!working(context).exists()) check(target.copyRecursively(working(context)))
        return revision
    }

    fun verifyExternal(context: Context, phase: (String, Long) -> Unit = { _, _ -> }): NotebookArchive.Revision {
        check(context.getSharedPreferences("startup-experiment", Context.MODE_PRIVATE).getBoolean("externalGranted", false))
        val started = android.os.SystemClock.elapsedRealtime()
        val archive = NotebookArchive(DocumentArchiveDirectory(context.contentResolver, tree))
        phase("directoryConstructMs", android.os.SystemClock.elapsedRealtime() - started)
        val result = checkNotNull(archive.latest(files(seed(context))))
        phase("verifiedMs", android.os.SystemClock.elapsedRealtime() - started)
        return result
    }
}
