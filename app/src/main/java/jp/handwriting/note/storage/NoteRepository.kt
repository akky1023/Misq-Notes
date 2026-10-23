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
import jp.handwriting.note.editor.PressureCurve
import jp.handwriting.note.editor.PressureBasis
import java.io.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.core.graphics.createBitmap
import jp.handwriting.note.editor.PaperRenderer
import jp.handwriting.note.editor.EmbeddedImages
import jp.handwriting.note.AppMessages
import jp.handwriting.note.R
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

data class NoteSnapshot(val manifest: NoteManifest, val entries: List<OrderedEdits.Entry<EditorStroke>>)
data class LoadedNote(val manifest: NoteManifest, val entries: List<OrderedEdits.Entry<EditorStroke>>, val recovered: Boolean)

class NoteRepository internal constructor(private val files: NoteFiles, private val listCache: NoteListCache? = null,
    private val persist: (() -> Unit) -> Unit = { it() }) {
    private val thumbnailKeys = mutableMapOf<String, Pair<NotePaper, List<String>>>()
    // Retain Ink's outline cache between saves instead of rebuilding the entire preview per pen lift.
    private val thumbnailRenderer by lazy { CanvasStrokeRenderer.create() }
    private var recent: LoadedNote? = null
    private val orphanSweeps = mutableMapOf<String, Int>()
    @Synchronized fun list() = (listCache?.list(files::list) ?: files.list()).map { note ->
        // Error labels follow the current app language even when the index predates a switch.
        if (note.problem == null) note else note.copy(title = AppMessages.text(R.string.unreadable_note),
            problem = AppMessages.text(R.string.note_data_read_failed))
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
    @Synchronized fun rename(id: String, title: String) = persist {
        val clean = listCache?.changing()
        files.rename(id, title)
        listCache?.saved(summary(files.read(id).manifest), clean == true)
        if (recent?.manifest?.info?.id == id) recent = null
    }
    @Synchronized fun delete(id: String) = persist {
        val clean = listCache?.changing()
        files.delete(id)
        listCache?.deleted(id, clean == true)
        if (recent?.manifest?.info?.id == id) recent = null
    }
    @Synchronized fun preview(id: String): File {
        val target = files.thumbnail(id)
        if (!files.thumbnailIsCurrent(id)) {
            val loaded = load(id)
            thumbnail(NoteSnapshot(loaded.manifest, loaded.entries))
        }
        return target
    }
    @Synchronized fun save(snapshot: NoteSnapshot) {
        val strokes = snapshot.entries.associateBy { it.value.storageId }
        val id = snapshot.manifest.info.id
        val previous = recent?.takeIf { it.manifest.info.id == id }
        if (previous == null || previous.entries.any { it.value.storageId !in strokes }) orphanSweeps[id] = 2
        persist {
            val clean = listCache?.changing()
            files.save(snapshot.manifest) { storageId -> encodeStroke(strokes.getValue(storageId).value) }
            listCache?.saved(summary(snapshot.manifest), clean == true)
            files.invalidateThumbnail(snapshot.manifest.info.id)
            if (orphanSweeps.getOrDefault(id, 0) > 0) runCatching {
                files.collectOrphans(id)
                orphanSweeps[id] = orphanSweeps.getValue(id) - 1
            }
        }
        // Preview work belongs to the library's asynchronous preview request, never to closeNote.
        recent = LoadedNote(snapshot.manifest, snapshot.entries, false)
    }
    @Synchronized fun load(id: String): LoadedNote {
        val current = files.read(id)
        recent?.takeIf { it.manifest == current.manifest }?.let { return it.copy(recovered = it.recovered || current.recovered) }
        val loaded = try {
            load(current.manifest, current.recovered)
        } catch (e: Exception) {
            val previous = files.previous(id) ?: throw e
            load(previous, true)
        }
        recent = loaded
        orphanSweeps[id] = maxOf(orphanSweeps.getOrDefault(id, 0), 1)
        return loaded
    }
    private fun load(manifest: NoteManifest, recovered: Boolean) = LoadedNote(
        manifest, manifest.strokes.sortedBy { it.id }.map { ref ->
            OrderedEdits.Entry(ref.id, decodeStroke(ref.storageId, files.stroke(manifest.info.id, ref.storageId)))
        }, recovered,
    )
    private fun thumbnail(snapshot: NoteSnapshot) {
        val info = snapshot.manifest.info
        val entries = snapshot.entries.filter { entry -> entry.value.bounds?.let { it.xMax >= 0 && it.yMax >= 0 && it.xMin <= 1000 && it.yMin <= 1000 } == true }
        val previewPaper = NotePaper(color = info.paper.color)
        val key = previewPaper to entries.map { it.value.storageId }
        val target = files.thumbnail(info.id)
        if (target.exists() && (thumbnailKeys[info.id] == key || (info.id !in thumbnailKeys && files.thumbnailIsCurrent(info.id)))) {
            thumbnailKeys[info.id] = key; files.markThumbnailCurrent(info.id); return
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
                FileOutputStream(temporary).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.fd.sync() }
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                files.markThumbnailCurrent(info.id)
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
