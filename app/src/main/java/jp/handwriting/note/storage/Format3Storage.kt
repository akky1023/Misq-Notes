package jp.handwriting.note.storage

import java.io.*
import java.util.UUID
import org.json.JSONObject

/** The activation record is the last write. Every prior step is safe to repeat or abandon. */
internal class Format3Storage(private val directory: File) {
    private val marker = File(directory, ".format3-active")
    val active get() = marker.exists()
    fun open(fetch: ((String) -> InputStream)? = null): ImmutableNoteStore {
        val value = JSONObject(String(NoteFiles.readEnvelope(marker), Charsets.UTF_8))
        require(value.getInt("format") == 3 && value.getInt("minWriter") == 3)
        val folder = value.getString("directory"); require(folder.matches(Regex("format3-[a-f0-9-]{36}")))
        val root = File(directory, folder)
        check(root.isDirectory && File(root, "objects").isDirectory && File(root, "heads").isDirectory) { "The active notebook store is missing; it was not replaced with an empty library" }
        return ImmutableNoteStore(root, fetch)
    }
    fun create(): ImmutableNoteStore {
        check(!active) { "Notebook storage is already active" }
        val storeFolder = File(directory, "format3-${UUID.randomUUID()}")
        val store = ImmutableNoteStore(storeFolder)
        val activation = JSONObject().put("format", 3).put("minWriter", 3).put("directory", storeFolder.name)
        NoteFiles.atomicWrite(marker, activation.toString().toByteArray(Charsets.UTF_8))
        syncNoteDirectory(directory)
        return store
    }
    fun backup(store: ImmutableNoteStore, target: ImmutableNoteStore, checkpoint: () -> Unit = {}) {
        require(store.directory.canonicalFile != target.directory.canonicalFile)
        require(target.allRoots().isEmpty()) { "Backup destination must be empty" }
        val roots = store.allRoots()
        roots.forEach { root ->
            store.closure(root).forEach { id -> checkpoint(); store.openObject(id).use { target.importObject(id, it) } }
            target.verify(target.root(root.id)); target.install(target.root(root.id))
        }
        val inventory = roots.associate { it.info.id to it.id }
        NoteFiles.atomicWrite(File(target.directory, "backup-complete"), JSONObject(inventory as Map<*, *>).toString().toByteArray(Charsets.UTF_8))
        syncNoteDirectory(target.directory)
    }
    fun restoreBackup(backup: ImmutableNoteStore, target: ImmutableNoteStore, checkpoint: () -> Unit = {}) {
        val inventory = JSONObject(String(NoteFiles.readEnvelope(File(backup.directory, "backup-complete")), Charsets.UTF_8))
            .let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
        check(backup.allRoots().associate { it.info.id to it.id } == inventory) { "Backup inventory mismatch" }
        backup.allRoots().forEach { backup.verify(it) }
        this.backup(backup, target, checkpoint)
    }
}

