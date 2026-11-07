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

class NoteRepository internal constructor(private val files: NoteFiles,
    private val persist: (() -> Unit) -> Unit = { it() }) {
    internal val usesImmutable get() = true
    @Synchronized internal fun prepareMergePrimary(id: String) {
        files.format3.takeIf { it.store.current(id) != null }?.export(id, File(files.directory, "$id.misq"))
    }
    @Synchronized internal fun exportNotebook(id: String, target: File, checkpoint: () -> Unit) {
        check(!target.exists())
        files.format3.store.export(checkNotNull(files.format3.store.current(id)), target, checkpoint)
    }
    private val thumbnailKeys = mutableMapOf<String, Pair<NotePaper, List<String>>>()
    // Retain Ink's outline cache between saves instead of rebuilding the entire preview per pen lift.
    private val thumbnailRenderer by lazy { InkStrokeRenderer.create() }
    private var recent: LoadedNote? = null
    private var spatialSource: SpatialNoteSource? = null
    @Synchronized fun list(): List<NoteSummary> = files.list().map { note ->
        // Error labels follow the current app language even when the index predates a switch.
        if (note.problem == null) note else note.copy(title = AppMessages.text(R.string.unreadable_note),
            problem = AppMessages.text(R.string.note_data_read_failed))
    }
    @Synchronized fun create(title: String, paper: NotePaper = NotePaper()): NoteManifest {
        lateinit var created: NoteManifest
        persist {
            created = files.create(title, paper = paper)
        }
        thumbnail(NoteSnapshot(created, emptyList()))
        return created
    }
    @Synchronized internal fun copyConflict(store: NoteConflicts, conflict: NoteConflict, title: String): NoteManifest {
        lateinit var manifest: NoteManifest
        persist {
            manifest = store.copy(conflict, title)
            files.format3.let { manifest = it.imported(File(files.directory, "${manifest.info.id}.misq")) }
        }
        return manifest
    }
    @Synchronized internal fun saveMerge(resolvedBranches: List<File> = emptyList(), write: (File) -> NoteManifest): NoteManifest {
        lateinit var manifest: NoteManifest
        persist {
            if (resolvedBranches.isNotEmpty()) files.format3.requireCurrentExport(MisqFile(resolvedBranches.first(), readOnlyAlias = true).summary().id)
            manifest = write(files.directory)
            files.format3.let { library ->
                val branches = resolvedBranches.mapNotNull(library::branchParent)
                manifest = library.imported(File(files.directory, "${manifest.info.id}.misq"), branches + listOfNotNull(library.store.current(manifest.info.id)?.id))
            }
            recent = null
            thumbnailKeys.remove(manifest.info.id)
            files.invalidateThumbnail(manifest.info.id)
        }
        return manifest
    }
    @Synchronized internal fun autoMergeConflict(store: NoteConflicts, conflict: NoteConflict): NoteManifest? {
        prepareMergePrimary(conflict.noteId)
        if (!store.canAutoMerge(conflict)) return null
        var manifest: NoteManifest? = null
        persist {
            files.format3.requireCurrentExport(conflict.noteId)
            manifest = store.autoMerge(conflict) { note -> files.format3.let { library ->
                val branch = library.branchParent(conflict.file)
                library.imported(File(files.directory, "${note.info.id}.misq"), listOfNotNull(library.store.current(note.info.id)?.id, branch))
            } }
            manifest?.let {
                recent = null
                files.invalidateThumbnail(it.info.id)
            }
        }
        return manifest
    }
    @Synchronized fun rename(id: String, title: String) = persist {
        files.rename(id, title)
        if (recent?.manifest?.info?.id == id) recent = null
    }
    @Synchronized fun delete(id: String) = persist {
        files.deleteNotebook(id)
        thumbnailKeys.remove(id)
        if (recent?.manifest?.info?.id == id) recent = null
    }
    fun preview(id: String): File {
        if (files.thumbnailIsCurrent(id)) return files.storedThumbnail(id) ?: files.thumbnail(id)
        return immutablePreview(id)
    }
    private fun immutablePreview(id: String): File {
        val source = synchronized(this) { files.spatial(id) } ?: return files.thumbnail(id)
        // Provider reads and native outline generation never hold the editor's save lock.
        val entries = source.load(0f, 1000f, emptySet()) { ref, bytes -> OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes)) }
        synchronized(this) {
            if (files.format3.store.current(id)?.id == source.revisionId)
                thumbnail(NoteSnapshot(source.manifest, entries), alreadyLoaded = true, expectedRevision = source.revisionId)
        }
        return files.thumbnail(id)
    }
    fun updatePreview(snapshot: NoteSnapshot) { immutablePreview(snapshot.manifest.info.id) }
    @Synchronized fun save(snapshot: NoteSnapshot) {
        val entries = snapshot.entries.associateBy { it.value.storageId }
        persist {
            files.format3.save(snapshot.manifest, snapshot.entries.associate { it.value.storageId to it.value.placement },
                snapshot.loadedIds, snapshot.addedSince) { encodeStroke(entries.getValue(it).value) }
            files.invalidateThumbnail(snapshot.manifest.info.id)
        }
        recent = null
    }

    @Synchronized internal fun loadVisible(id: String, aspect: Float, position: jp.handwriting.note.editor.ViewPosition? = null): LoadedNote {
        spatialSource = null
        val source = files.spatial(id) ?: return load(id)
        val manifest = source.manifest
        val viewport = position ?: manifest.info.position
        val top = viewport.top
        val height = (1000f * aspect / viewport.zoom).coerceAtLeast(1000f)
        val entries = source.load((top - 500f).coerceAtLeast(0f), top + height + 500f, emptySet()) { ref, bytes ->
            OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes))
        }.sortedBy { it.id }
        spatialSource = source
        return LoadedNote(manifest, entries, source.recovered, partial = entries.size < manifest.strokes.size, contentBottom = source.bottom)
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
        val current = files.read(id)
        recent?.takeIf { it.manifest == current.manifest }?.let { return it.copy(recovered = it.recovered || current.recovered) }
        // Corruption of the latest revision must not become an editable older snapshot.
        return load(current.manifest, current.recovered).also { recent = it }
    }
    private fun load(manifest: NoteManifest, recovered: Boolean) = LoadedNote(
        manifest, files.readStrokes(manifest.info.id, manifest.strokes.sortedBy { it.id }) { ref, bytes ->
            OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes))
        }, recovered,
    )
    private fun thumbnail(snapshot: NoteSnapshot, alreadyLoaded: Boolean = false, expectedRevision: String? = null) {
        val info = snapshot.manifest.info
        val source = if (alreadyLoaded) null else files.spatial(info.id)?.takeIf { it.manifest.info.id == info.id && snapshot.entries.size < snapshot.manifest.strokes.size }
        val missing = source?.load(0f, 1000f, snapshot.entries.map { it.id }.toSet()) { ref, bytes ->
            OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, bytes))
        }.orEmpty()
        val entries = (snapshot.entries + missing).filter { entry -> entry.value.bounds?.let { it.xMax >= 0 && it.yMax >= 0 && it.xMin <= 1000 && it.yMin <= 1000 } == true }
        val previewPaper = NotePaper(color = info.paper.color)
        val key = previewPaper to entries.map { it.value.storageId }
        val target = files.thumbnail(info.id)
        if (target.exists() && (thumbnailKeys[info.id] == key || (info.id !in thumbnailKeys && files.thumbnailIsCurrent(info.id)))) {
            thumbnailKeys[info.id] = key
            if (!files.thumbnailIsCurrent(info.id)) persist { files.markThumbnailCurrent(info.id, expectedRevision) }
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
                persist { files.markThumbnailCurrent(info.id, expectedRevision) }
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
