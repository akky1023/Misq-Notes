package jp.handwriting.note.storage

import java.io.*
import java.util.UUID
import org.json.JSONObject

/** The activation record is the last write. Every prior step is safe to repeat or abandon. */
@android.annotation.SuppressLint("UsableSpace") // Reserve physical free space; do not count potentially clearable caches.
internal class Format3Migration(private val directory: File, private val availableBytes: () -> Long = { directory.usableSpace }) {
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
    fun migrate(sources: List<MisqFile>, metadata: Map<String, File>, checkpoint: () -> Unit = {},
        placements: (MisqFile) -> Map<String, StrokePlacement>): ImmutableNoteStore {
        check(!active) { "Storage migration is already active" }
        val expectedIds = sources.map { it.index().manifest.info.id }.toSet()
        require(expectedIds.size == sources.size)
        require(sources.none { it.index().manifest.info.id == Format3Library.SETTINGS_ID }) { "Reserved notebook identity" }
        val bytes = sources.sumOf { it.file.length() } + metadata.values.sumOf { it.length() }
        val required = Math.addExact(Math.multiplyExact(bytes, 3L), 64L * 1024 * 1024)
        check(availableBytes() >= required) { "Migration needs $required bytes of free space; originals have been retained" }
        val backup = File(directory, "format3-rollback-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val storeFolder = File(directory, "format3-${UUID.randomUUID()}")
        val store = ImmutableNoteStore(storeFolder)
        val originalHashes = linkedMapOf<String, String>()
        val roots = mutableListOf<ImmutableNoteStore.Root>()
        fun copy(source: File, target: File): String {
            checkpoint(); val expected = NotebookArchive.digest(source)
            FileOutputStream(target).use { output -> source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            }; output.fd.sync() }
            check(NotebookArchive.digest(target) == expected && NotebookArchive.digest(source) == expected) { "Source changed during migration" }
            return expected
        }
        // This rollback copy is independent of all shared objects, including historical payloads.
        sources.forEach { source ->
            val id = source.index().manifest.info.id
            val target = File(backup, "$id.misq")
            originalHashes[target.name] = copy(source.file, target)
            MisqFile(target).validate()
        }
        metadata.forEach { (name, file) -> require(name.matches(Regex("[a-z-]+\\.hwp"))); if (file.exists()) originalHashes[name] = copy(file, File(backup, name)) }
        NoteFiles.atomicWrite(File(backup, "inventory"), JSONObject(originalHashes as Map<*, *>).toString().toByteArray(Charsets.UTF_8))
        syncNoteDirectory(backup)
        sources.forEach { source ->
            checkpoint(); val id = source.index().manifest.info.id
            val original = MisqFile(File(backup, "$id.misq"))
            val work = File(backup, "$id.tmp")
            try {
                original.file.copyTo(work)
                val ready = MisqFile(work)
                val manifest = ready.index().manifest
                val values = if (manifest.strokes.all { it.storageId in ready.index().placements }) ready.index().placements else placements(ready)
                ready.save(manifest, values, ready::stroke)
                val root = store.importNote(ready, checkpoint = checkpoint)
                val exported = File(backup, "$id.check.tmp")
                try {
                    store.export(root, exported)
                    val a = ready.index(); val b = MisqFile(exported).index()
                    check(a.manifest.copy(strokes = a.manifest.strokes.sortedBy { it.id }) == b.manifest && a.events == b.events && a.entities == b.entities && a.placements == b.placements &&
                        a.records.mapValues { it.value.hash } == b.records.mapValues { it.value.hash }) { "Migration round-trip mismatch" }
                } finally { exported.delete() }
                roots += root
            } finally { work.delete() }
        }
        roots.forEach { checkpoint(); store.install(it) }
        requireMigrationInventory(expectedIds, store.ids())
        sources.forEach { source -> check(NotebookArchive.digest(source.file) == originalHashes.getValue("${source.index().manifest.info.id}.misq")) { "Source changed before activation" } }
        metadata.forEach { (name, file) -> if (file.exists()) check(NotebookArchive.digest(file) == originalHashes[name]) }
        checkpoint()
        val activation = JSONObject().put("format", 3).put("minWriter", 3).put("directory", storeFolder.name).put("rollback", backup.name)
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

/** Exact identity comparison prevents a partially enumerated library from becoming authoritative. */
internal fun requireMigrationInventory(expected: Set<String>, actual: Set<String>) {
    check(expected == actual) { "Migration notebook inventory mismatch; originals have been retained (${expected.size} expected, ${actual.size} found)" }
}
