package jp.handwriting.note.storage

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import jp.handwriting.note.editor.EditorStroke
import jp.handwriting.note.editor.OrderedEdits
import jp.handwriting.note.editor.InkBrushes
import jp.handwriting.note.editor.InkStrokeRenderer
import jp.handwriting.note.editor.PressureCurve
import jp.handwriting.note.editor.PressureBasis
import java.io.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import jp.handwriting.note.editor.PaperRenderer
import jp.handwriting.note.editor.EmbeddedImages
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

data class NoteSnapshot(val manifest: NoteManifest, val entries: List<OrderedEdits.Entry<EditorStroke>>,
    val loadedIds: Set<Long>? = null, val addedSince: Long = Long.MAX_VALUE)
data class LoadedNote(val manifest: NoteManifest, val entries: List<OrderedEdits.Entry<EditorStroke>>, val recovered: Boolean,
    val partial: Boolean = false, val contentBottom: Float = 0f)

class NoteRepository internal constructor(private val files: NoteFiles, listCache: NoteListCache? = null,
    private val persist: (() -> Unit) -> Unit = { it() }) {
    private val listCache = listCache.takeIf { files.format3 == null }
    internal val usesImmutable get() = files.format3 != null
    @Synchronized internal fun prepareMergePrimary(id: String) {
        files.format3?.takeIf { it.store.current(id) != null }?.export(id, File(files.directory, "$id.misq"))
    }
    @Synchronized internal fun exportNotebook(id: String, target: File, checkpoint: () -> Unit) {
        check(!target.exists())
        files.format3?.let { library -> library.store.export(checkNotNull(library.store.current(id)), target, checkpoint); return }
        val source = files.document(id).file
        FileOutputStream(target).use { output -> source.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
        }; output.fd.sync() }
        MisqFile(target).validate(checkpoint)
    }
    private val thumbnailKeys = mutableMapOf<String, Pair<NotePaper, List<String>>>()
    // Retain Ink's outline cache between saves instead of rebuilding the entire preview per pen lift.
    private val thumbnailRenderer by lazy { InkStrokeRenderer.create() }
    private var recent: LoadedNote? = null
    private var spatialSource: SpatialNoteCache.Source? = null
    private val provisionalLoaded = mutableMapOf<String, NoteManifest>()
    private fun provisional(id: String) = if (files.hasWorkingCopy(id)) null else
        files.provisionalVersions()[id]?.first?.let { MisqFile(it, readOnlyAlias = true) }
    private fun content(note: NoteManifest) = note.copy(info = note.info.copy(updatedAt = 0,
        position = jp.handwriting.note.editor.ViewPosition(), color = 0, size = 0f))
    private val orphanSweeps = mutableMapOf<String, Int>()
    @Synchronized fun list(): List<NoteSummary> {
        val provisional = files.provisionalVersions().mapValues { it.value.second }
        return ((if (usesImmutable) files.list() else listCache?.list(files::list) ?: files.list()).filter { it.id !in provisional } + provisional.values)
            .sortedByDescending { it.updatedAt }.map { note ->
        // Error labels follow the current app language even when the index predates a switch.
        if (note.problem == null) note else note.copy(title = AppMessages.text(R.string.unreadable_note),
            problem = AppMessages.text(R.string.note_data_read_failed))
    }
    }
    private fun summary(note: NoteManifest) = NoteSummary(note.info.id, note.info.title, note.info.updatedAt,
        note.strokes.size, createdAt = note.info.createdAt, paper = note.info.paper,
        thumbnail = files.thumbnail(note.info.id).takeIf { it.exists() }?.path)
    @Synchronized fun create(title: String, paper: NotePaper = NotePaper()): NoteManifest {
        lateinit var created: NoteManifest
        persist {
            val clean = listCache?.changing()
            created = files.create(title, paper = paper)
            listCache?.saved(summary(created), clean == true)
        }
        thumbnail(NoteSnapshot(created, emptyList()))
        return created
    }
    @Synchronized internal fun copyConflict(store: NoteConflicts, conflict: NoteConflict, title: String): NoteManifest {
        lateinit var manifest: NoteManifest
        persist {
            val clean = listCache?.changing()
            manifest = store.copy(conflict, title)
            files.format3?.let { manifest = it.imported(File(files.directory, "${manifest.info.id}.misq")) }
            listCache?.saved(summary(manifest), clean == true)
        }
        return manifest
    }
    @Synchronized internal fun saveMerge(resolvedBranches: List<File> = emptyList(), write: (File) -> NoteManifest): NoteManifest {
        lateinit var manifest: NoteManifest
        persist {
            val clean = listCache?.changing()
            if (resolvedBranches.isNotEmpty()) files.format3?.requireCurrentExport(MisqFile(resolvedBranches.first(), readOnlyAlias = true).summary().id)
            manifest = write(files.directory)
            files.format3?.let { library ->
                val branches = resolvedBranches.mapNotNull(library::branchParent)
                manifest = library.imported(File(files.directory, "${manifest.info.id}.misq"), branches + listOfNotNull(library.store.current(manifest.info.id)?.id))
            }
            recent = null
            thumbnailKeys.remove(manifest.info.id)
            files.invalidateThumbnail(manifest.info.id)
            listCache?.saved(summary(manifest), clean == true)
        }
        return manifest
    }
    @Synchronized internal fun autoMergeConflict(store: NoteConflicts, conflict: NoteConflict): NoteManifest? {
        prepareMergePrimary(conflict.noteId)
        if (!store.canAutoMerge(conflict)) return null
        var manifest: NoteManifest? = null
        persist {
            val clean = listCache?.changing()
            files.format3?.requireCurrentExport(conflict.noteId)
            manifest = store.autoMerge(conflict) { note -> files.format3?.let { library ->
                val branch = library.branchParent(conflict.file)
                library.imported(File(files.directory, "${note.info.id}.misq"), listOfNotNull(library.store.current(note.info.id)?.id, branch))
            } }
            manifest?.let {
                recent = null
                files.invalidateThumbnail(it.info.id)
                listCache?.saved(summary(it), clean == true)
            }
        }
        return manifest
    }
    @Synchronized fun rename(id: String, title: String) {
        if (provisional(id) != null) {
            val loaded = load(id)
            save(NoteSnapshot(loaded.manifest.copy(info = loaded.manifest.info.copy(title = NoteFiles.validTitle(title),
                updatedAt = System.currentTimeMillis())), loaded.entries))
            return
        }
        persist {
        val clean = listCache?.changing()
        files.rename(id, title)
        listCache?.saved(summary(files.read(id).manifest), clean == true)
        if (recent?.manifest?.info?.id == id) recent = null
    }
    }
    @Synchronized fun delete(id: String) = persist {
        val clean = listCache?.changing()
        files.deleteNotebook(id)
        listCache?.deleted(id, clean == true)
        provisionalLoaded.remove(id)
        thumbnailKeys.remove(id)
        if (recent?.manifest?.info?.id == id) recent = null
    }
    fun preview(id: String): File {
        if (usesImmutable) {
            if (files.thumbnailIsCurrent(id)) return files.storedThumbnail(id) ?: files.thumbnail(id)
            return immutablePreview(id)
        }
        return synchronized(this) {
        if (provisional(id) != null) {
            val loaded = load(id)
            thumbnail(NoteSnapshot(loaded.manifest, loaded.entries), provisional = true)
            return files.thumbnail(id)
        }
        files.storedThumbnail(id)?.let { return it }
        val target = files.thumbnail(id)
        if (!files.thumbnailIsCurrent(id)) {
            val loaded = load(id)
            thumbnail(NoteSnapshot(loaded.manifest, loaded.entries))
        }
        return target
        }
    }
    private fun immutablePreview(id: String): File {
        val source = synchronized(this) { files.spatial(id) } ?: return files.thumbnail(id)
        // Provider reads and native outline generation never hold the editor's save lock.
        val entries = source.load(0f, 1000f, emptySet()) { ref, bytes -> OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes)) }
        synchronized(this) {
            if (files.format3?.store?.current(id)?.id == source.revisionId)
                thumbnail(NoteSnapshot(source.manifest, entries), alreadyLoaded = true, expectedRevision = source.revisionId)
        }
        return files.thumbnail(id)
    }
    fun updatePreview(snapshot: NoteSnapshot) {
        if (usesImmutable) {
            immutablePreview(snapshot.manifest.info.id)
            return
        }
        synchronized(this) {
        if (PartialNoteJournal.exists(files.directory, snapshot.manifest.info.id)) return
        // A delayed preview must not attach an older snapshot to a newer committed note.
        if (provisional(snapshot.manifest.info.id) == null && files.read(snapshot.manifest.info.id).manifest == snapshot.manifest) thumbnail(snapshot)
        }
    }
    @Synchronized fun save(snapshot: NoteSnapshot) {
        files.format3?.let { library ->
            val entries = snapshot.entries.associateBy { it.value.storageId }
            persist {
                library.save(snapshot.manifest, snapshot.entries.associate { it.value.storageId to it.value.placement },
                    snapshot.loadedIds, snapshot.addedSince) { encodeStroke(entries.getValue(it).value) }
                files.invalidateThumbnail(snapshot.manifest.info.id)
            }
            recent = null
            return
        }
        val strokes = snapshot.entries.associateBy { it.value.storageId }
        val id = snapshot.manifest.info.id
        val previous = recent?.takeIf { it.manifest.info.id == id }
        val provisional = provisional(id)
        if (provisional != null) {
            val current = provisional.readManifest().manifest
            check(provisionalLoaded[id]?.let { content(it) == content(current) } != false) { AppMessages.text(R.string.conflict_version_changed) }
            // Viewport and pen preferences do not recreate a missing working file.
            if (content(current) == content(snapshot.manifest)) {
                recent = LoadedNote(snapshot.manifest, snapshot.entries, false)
                return
            }
            publishProvisional(provisional, current, snapshot, strokes)
            return
        }
        check(id !in provisionalLoaded) { AppMessages.text(R.string.conflict_version_changed) }
        if (previous == null || previous.entries.any { it.value.storageId !in strokes }) orphanSweeps[id] = 2
        persist {
            val clean = listCache?.changing()
            if (!files.hasLocalBody(id) && (snapshot.entries.size < snapshot.manifest.strokes.size || PartialNoteJournal.exists(files.directory, id))) {
                PartialNoteJournal.write(files.directory, snapshot)
                listCache?.saved(summary(snapshot.manifest), clean == true)
                files.invalidateThumbnail(id)
                return@persist
            }
            files.save(snapshot.manifest, snapshot.entries.associate { it.value.storageId to it.value.placement }) { storageId -> encodeStroke(strokes.getValue(storageId).value) }
            if (PartialNoteJournal.exists(files.directory, id)) PartialNoteJournal.clear(files.directory, id)
            listCache?.saved(summary(snapshot.manifest), clean == true)
            files.invalidateThumbnail(snapshot.manifest.info.id)
            if (orphanSweeps.getOrDefault(id, 0) > 0) runCatching {
                files.collectOrphans(id)
                orphanSweeps[id] = orphanSweeps.getValue(id) - 1
            }
        }
        // Preview work belongs to the library's asynchronous preview request, never to closeNote.
        recent = if (snapshot.entries.size == snapshot.manifest.strokes.size) LoadedNote(snapshot.manifest, snapshot.entries, false) else null
    }
    private fun publishProvisional(source: MisqFile, expected: NoteManifest, snapshot: NoteSnapshot,
        strokes: Map<String, OrderedEdits.Entry<EditorStroke>>) {
        val id = expected.info.id
        val destination = files.document(id).file
        val temporary = File(files.directory, ".provisional-${java.util.UUID.randomUUID()}.tmp")
        try {
            source.file.inputStream().use { input -> FileOutputStream(temporary).use { output ->
                input.copyTo(output, 65536); output.fd.sync()
            } }
            val target = MisqFile(temporary)
            check(target.readManifest().manifest == expected && source.readManifest().manifest == expected) {
                AppMessages.text(R.string.conflict_version_changed)
            }
            target.validate()
            target.save(snapshot.manifest) { encodeStroke(strokes.getValue(it).value) }
            target.validate()
            persist {
                val clean = listCache?.changing()
                // Same-directory rename under the persistence lock; no REPLACE_EXISTING.
                check(!destination.exists()) { AppMessages.text(R.string.conflict_version_changed) }
                Files.move(temporary.toPath(), destination.toPath())
                files.invalidateThumbnail(id)
                listCache?.saved(summary(snapshot.manifest), clean == true)
            }
            provisionalLoaded.remove(id)
            recent = LoadedNote(snapshot.manifest, snapshot.entries, false)
        } finally { temporary.delete() }
    }
    @Synchronized internal fun loadVisible(id: String, aspect: Float, position: jp.handwriting.note.editor.ViewPosition? = null): LoadedNote {
        spatialSource = null
        fun fallback(): LoadedNote = load(id).also { if (!it.recovered && files.hasExternal(id)) persist {} }
        val original = runCatching { files.spatial(id) }.getOrNull() ?: return fallback()
        val journal = PartialNoteJournal.read(files.directory, id)
        val source = journal?.let { original.withManifest(it.manifest) } ?: original
        val manifest = source.manifest
        val viewport = position ?: manifest.info.position
        val top = viewport.top
        val height = (1000f * aspect / viewport.zoom).coerceAtLeast(1000f)
        return try {
            val current = journal?.manifest?.strokes?.associate { it.id to it.storageId }.orEmpty()
            val changed = journal?.placements?.keys.orEmpty()
            val entries = (source.load((top - 500f).coerceAtLeast(0f), top + height + 500f, emptySet()) { ref, bytes ->
                if (journal == null || (current[ref.id] == ref.storageId && ref.storageId !in changed))
                OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes))
                else null
            }.filterNotNull() + (if (journal == null) emptyList() else manifest.strokes.filter { it.storageId in changed }).map { ref ->
                OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, checkNotNull(journal).stroke(ref.storageId)))
            }).sortedBy { it.id }
            spatialSource = source
            LoadedNote(manifest, entries, source.recovered, partial = entries.size < manifest.strokes.size, contentBottom = source.bottom)
        } catch (_: Exception) { fallback() }
    }
    internal fun loadRegion(id: String, top: Float, bottom: Float, loaded: Set<Long>, checkpoint: () -> Unit = {}): List<OrderedEdits.Entry<EditorStroke>> {
        val source = synchronized(this) { checkNotNull(spatialSource?.takeIf { it.manifest.info.id == id }) }
        val records = source.load(top, bottom, loaded, checkpoint) { ref, bytes -> ref to bytes }
        return records.map { (ref, bytes) -> checkpoint(); OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes)) }.sortedBy { it.id }
    }
    @Synchronized internal fun regionPending(id: String, top: Float, bottom: Float, loaded: Set<Long>): Boolean =
        spatialSource?.takeIf { it.manifest.info.id == id }?.pending(top, bottom, loaded) == true
    internal fun loadNextRegion(id: String, top: Float, bottom: Float, loaded: Set<Long>,
        checkpoint: () -> Unit): List<OrderedEdits.Entry<EditorStroke>> {
        val source = synchronized(this) { checkNotNull(spatialSource?.takeIf { it.manifest.info.id == id }) }
        val records = source.next(top, bottom, loaded, checkpoint) { ref, bytes -> ref to bytes }
        // Native outline generation must not hold the repository lock while the user saves/navigates.
        return records.map { (ref, bytes) -> checkpoint(); OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes)) }.sortedBy { it.id }
    }

    @Synchronized fun load(id: String): LoadedNote {
        provisional(id)?.let { source ->
            val manifest = source.readManifest().manifest
            val loaded = LoadedNote(manifest, source.readStrokes(manifest.strokes.sortedBy { it.id }) { ref, bytes ->
                OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes))
            }, false)
            provisionalLoaded[id] = manifest
            recent = loaded
            return loaded
        }
        provisionalLoaded.remove(id)
        val current = files.read(id)
        recent?.takeIf { it.manifest == current.manifest }?.let { return it.copy(recovered = it.recovered || current.recovered) }
        val loaded = try {
            load(current.manifest, current.recovered)
        } catch (e: Exception) {
            if (usesImmutable) throw e // Never turn an unread/corrupt latest revision into an editable older snapshot.
            val previous = files.previous(id) ?: throw e
            load(previous, true)
        }
        recent = loaded
        val placements = loaded.entries.associate { it.value.storageId to it.value.placement }
        if (!loaded.recovered && !PartialNoteJournal.exists(files.directory, id) && files.needsPlacements(id, placements)) persist {
            files.save(loaded.manifest, placements) {
                storageId -> encodeStroke(loaded.entries.first { it.value.storageId == storageId }.value)
            }
        }
        orphanSweeps[id] = maxOf(orphanSweeps.getOrDefault(id, 0), 1)
        return loaded
    }
    private fun load(manifest: NoteManifest, recovered: Boolean) = LoadedNote(
        manifest, files.readStrokes(manifest.info.id, manifest.strokes.sortedBy { it.id }) { ref, bytes ->
            OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes))
        }, recovered,
    )
    private fun thumbnail(snapshot: NoteSnapshot, provisional: Boolean = false, alreadyLoaded: Boolean = false, expectedRevision: String? = null) {
        val info = snapshot.manifest.info
        val source = if (alreadyLoaded) null else (if (usesImmutable) files.spatial(info.id) else spatialSource)?.takeIf { it.manifest.info.id == info.id && snapshot.entries.size < snapshot.manifest.strokes.size }
        val refs = if (usesImmutable) null else snapshot.manifest.strokes.associate { it.id to it.storageId }
        val missing = source?.load(0f, 1000f, snapshot.entries.map { it.id }.toSet()) { ref, bytes ->
            OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes))
        }.orEmpty().filter { refs == null || refs[it.id] == it.value.storageId }
        val entries = (snapshot.entries + missing).filter { entry -> entry.value.bounds?.let { it.xMax >= 0 && it.yMax >= 0 && it.xMin <= 1000 && it.yMin <= 1000 } == true }
        val previewPaper = NotePaper(color = info.paper.color)
        val key = previewPaper to entries.map { it.value.storageId }
        val target = files.thumbnail(info.id)
        if (target.exists() && (thumbnailKeys[info.id] == key || (!provisional && info.id !in thumbnailKeys && files.thumbnailIsCurrent(info.id)))) {
            thumbnailKeys[info.id] = key
            if (!provisional) {
                if (!files.thumbnailIsCurrent(info.id)) persist { files.markThumbnailCurrent(info.id, expectedRevision) }
                else if (!usesImmutable) files.markThumbnailCurrent(info.id)
            }
            return
        }
        // A preview is a disposable cache; it must never prevent the note from being saved.
        runCatching {
            val bitmap = createBitmap(320, 320)
            try {
                val canvas = Canvas(bitmap); val transform = Matrix().apply { setScale(0.32f, 0.32f) }
                canvas.concat(transform)
                canvas.drawColor(previewPaper.color)
                entries.sortedBy { it.id }.forEach {
                    if (it.value.image != null) EmbeddedImages.draw(canvas, checkNotNull(it.value.image))
                    else thumbnailRenderer.draw(canvas, it.value.ink, transform)
                }
                val temporary = File(target.path + ".tmp")
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                FileOutputStream(temporary).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.fd.sync() }
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                if (!provisional) persist { files.markThumbnailCurrent(info.id, expectedRevision) }
                listCache?.thumbnail(info.id, target.path)
                thumbnailKeys[info.id] = key
            } finally { bitmap.recycle() }
        }
    }
    companion object {
        fun encodeStroke(stroke: EditorStroke): ByteArray {
            stroke.image?.let { return it.encode() }
            val ink = stroke.ink
            val curve = InkBrushes.curveFor(ink.brush.family)
            val kind = if (curve?.spline?.tangents != null) 8 else if (curve?.spline?.basis == PressureBasis.BEZIER_CONTROLS) 7 else if (curve?.spline != null) 6 else if (curve?.bezier != null) 5 else if (curve != null) 4 else when (ink.brush.family) {
                StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1) -> 1
                StockBrushes.marker(StockBrushes.MarkerVersion.V1) -> 2
                InkBrushes.highlighter -> 3
                else -> error("Unsupported brush family")
            }
            val inputs = ByteArrayOutputStream().also { ink.inputs.encode(it) }.toByteArray()
            return ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
                out.writeInt(0x484E5331); out.writeInt(when (kind) { 8 -> 6; 7 -> 5; 6 -> 4; 5 -> 3; 4 -> 2; else -> 1 }); out.writeInt(kind)
                curve?.let {
                    if (kind >= 5) PressureCurveCodec.write(out, it)
                    else { out.writeInt(it.minimumPercent); out.writeInt(it.exponentPercent) }
                }
                out.writeInt(ink.brush.colorIntArgb); out.writeFloat(ink.brush.size); out.writeFloat(ink.brush.epsilon)
                out.writeInt(inputs.size); out.write(inputs)
            } }.toByteArray()
        }
        fun decodeStroke(storageId: String, bytes: ByteArray): EditorStroke {
            if (bytes.size >= 4 && java.nio.ByteBuffer.wrap(bytes).int == ImageRecord.MAGIC)
                return EditorStroke(ImageRecord.decode(bytes), storageId)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == 0x484E5331) { "Unsupported stroke header" }
            val version = input.readInt(); val kind = input.readInt()
            require(version == 1 && kind in 1..3 || version == 2 && kind == 4 || version == 3 && kind == 5 || version == 4 && kind == 6 || version == 5 && kind == 7 || version == 6 && kind == 8) { "Unsupported stroke version" }
            val family = when (kind) {
                1 -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
                2 -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
                3 -> InkBrushes.highlighter
                4 -> InkBrushes.pressurePen(PressureCurve(input.readInt(), input.readInt()))
                5 -> InkBrushes.pressurePen(PressureCurveCodec.read(input).also { require(it.bezier != null) })
                6 -> InkBrushes.pressurePen(PressureCurveCodec.read(input).also { require(it.spline?.basis == PressureBasis.INTERPOLATED && it.spline.tangents == null) })
                7 -> InkBrushes.pressurePen(PressureCurveCodec.read(input).also { require(it.spline?.basis == PressureBasis.BEZIER_CONTROLS) })
                8 -> InkBrushes.pressurePen(PressureCurveCodec.read(input).also { require(it.spline?.tangents != null) })
                else -> throw IOException("Unsupported brush")
            }
            val color = input.readInt(); val size = input.readFloat(); val epsilon = input.readFloat()
            require(size.isFinite() && size in 0.1f..100f && epsilon.isFinite() && epsilon > 0 && epsilon <= size)
            val count = input.readInt(); require(count > 0 && count == input.available())
            val payload = ByteArray(count); input.readFully(payload)
            val inputs = StrokeInputBatch.decode(ByteArrayInputStream(payload))
            return EditorStroke(Stroke(Brush.createWithColorIntArgb(family, color, size, epsilon), inputs), storageId)
        }
    }
}
