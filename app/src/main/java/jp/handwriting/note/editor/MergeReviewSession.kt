package jp.handwriting.note.editor

import android.graphics.*
import androidx.ink.geometry.MutableVec
import jp.handwriting.note.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive




/** Frozen input files, compact geometry bounds, and a bounded cache of decoded visible strokes. */
internal class MergeReviewSession private constructor(val conflicts: List<NoteConflict>, private val folder: File,
    private val readers: List<MisqFile>) : AutoCloseable {
    val conflict get() = conflicts.first()
    val provisional get() = conflict.provisional
    fun sourceLabel(source: Int): String {
        val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss.SSS", java.util.Locale.ROOT)
            .format(java.time.Instant.ofEpochMilli(multiPlan.indexes[source].manifest.info.updatedAt)
                .atZone(java.time.ZoneId.systemDefault()))
        return when {
            source > 0 -> jp.handwriting.note.AppMessages.text(jp.handwriting.note.R.string.merge_other_version, timestamp)
            provisional -> jp.handwriting.note.AppMessages.text(jp.handwriting.note.R.string.merge_provisional_version, timestamp)
            else -> jp.handwriting.note.AppMessages.text(jp.handwriting.note.R.string.merge_original_version)
        }
    }
    private val a get() = readers[0]
    lateinit var multiPlan: MultiMergePlan
        private set
    private val decoded = object : LinkedHashMap<String, EditorStroke>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EditorStroke>?) = size > 128
    }
    private val boxes = mutableMapOf<String, StrokeIndex.Bounds>()
    private val spatial = StrokeIndex<MergeValue>()
    private var closed = false
    val bounds get() = boxes.values.reduceOrNull(::mergeBounds) ?: StrokeIndex.Bounds(0f, 0f, 1000f, 1000f)
    private fun reader(value: MergeValue) = readers[value.source]
    @Synchronized private fun stroke(value: MergeValue): EditorStroke {
        check(!closed)
        val result = decoded.getOrPut(value.key) {
            val source = NoteRepository.decodeStroke(value.blob, reader(value).stroke(value.blob))
            if (source.image != null) source else {
                val epsilon = StrokeFidelity.displayEpsilon(source.ink.brush.size)
                if (source.ink.brush.epsilon <= epsilon) source else EditorStroke(source.ink.copy(source.ink.brush.copy(epsilon = epsilon)), source.storageId)
            }
        }
        while (decoded.size > 1 && decoded.values.sumOf { it.image?.data?.png?.size?.toLong() ?: (it.ink.inputs.size * 128L + 4096L) } > 24L * 1024 * 1024)
            decoded.remove(decoded.keys.first())
        return result
    }
    private fun box(value: MergeValue): StrokeIndex.Bounds? = boxes[value.key] ?: stroke(value).bounds?.let {
        StrokeIndex.Bounds(it.xMin, it.yMin, it.xMax, it.yMax).also { box -> boxes[value.key] = box }
    }
    suspend fun renderStep(width: Int, height: Int, viewport: MergeViewport, source: Int?,
        strength: MergeStrength, decisions: Map<String, Set<Int>>, step: MultiMergeStep?): Bitmap {
        val factor = minOf(1f, 1200f / maxOf(width, height).coerceAtLeast(1))
        val bitmap = Bitmap.createBitmap((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); val scale = bitmap.width / viewport.width
            val visible = RectF(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + bitmap.height / scale)
            val matrix = Matrix().apply { setValues(floatArrayOf(scale, 0f, -visible.left * scale, 0f, scale, -visible.top * scale, 0f, 0f, 1f)) }
            canvas.concat(matrix)
            PaperRenderer.draw(canvas, source?.let { multiPlan.indexes[it].manifest.info.paper } ?: multiPlan.paper(strength, decisions), visible, scale)
            step?.area?.let { area -> canvas.drawRect(area.left - 8f, area.top - 8f, area.right + 8f, area.bottom + 8f,
                Paint().apply { color = Color.argb(60, 100, 175, 255) }) }
            val visibleKeys = spatial.query(StrokeIndex.Bounds(visible.left, visible.top, visible.right, visible.bottom)).map { it.second.key }.toSet()
            val renderer = InkStrokeRenderer.create(); val job = currentCoroutineContext()
            fun outline(value: MergeValue, color: Int, dashed: Boolean) {
                if (value.key !in visibleKeys) return
                job.ensureActive()
                val ink = stroke(value)
                val path = Path()
                if (ink.image != null) boxes[value.key]?.let { path.addRect(it.left, it.top, it.right, it.bottom, Path.Direction.CW) }
                else {
                    val mesh = ink.ink.shape; val point = MutableVec()
                    for (group in 0 until mesh.getRenderGroupCount()) for (line in 0 until mesh.getOutlineCount(group)) {
                        val count = mesh.getOutlineVertexCount(group, line)
                        for (i in 0 until count) {
                            mesh.populateOutlinePosition(group, line, i, point)
                            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        }
                        if (count > 0) path.close()
                    }
                }
                val outer = Path()
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 8f / scale
                    strokeJoin = Paint.Join.ROUND
                    if (dashed) pathEffect = DashPathEffect(floatArrayOf(6f / scale, 4f / scale), 0f)
                }.getFillPath(path, outer)
                val gap = Path()
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 3f / scale; strokeJoin = Paint.Join.ROUND
                }.getFillPath(path, gap)
                // Keep the filled stroke and its antialiased edge clear, including translucent ink.
                val excluded = Path(path).apply { op(gap, Path.Op.UNION) }
                if (outer.op(excluded, Path.Op.DIFFERENCE)) canvas.drawPath(outer, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; alpha = 115; style = Paint.Style.FILL
                })
            }
            val values = source?.let { multiPlan.live[it] } ?: multiPlan.result(strength, decisions, provisional = true)
            for (value in values) {
                if (value.key !in visibleKeys) continue
                job.ensureActive(); val ink = stroke(value)
                if (ink.image != null) EmbeddedImages.draw(canvas, ink.image) else renderer.draw(canvas, ink.ink, matrix)
            }
            step?.changes?.forEach { row ->
                val selected = source?.let { listOfNotNull(row.versions[it]) }
                    ?: (decisions[step.key] ?: step.suggested.ifEmpty { setOf(0) }).mapNotNull { row.versions[it] }.distinctBy { it.key }
                val old = row.base ?: row.versions.firstOrNull { it != null }.takeIf { row.kind == MergeKind.DELETE }
                if (old != null && selected.none { it.hash == old.hash }) outline(old, Color.rgb(210, 45, 45), true)
                selected.filter { it.hash != row.base?.hash }.forEach { outline(it, Color.rgb(30, 165, 75), false) }
            }
            job.ensureActive(); return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    suspend fun previewDocument(strength: MergeStrength, decisions: Map<String, Set<Int>>): PreviewDocument {
        val values = multiPlan.result(strength, decisions)
        val stars = mutableListOf<StrokeIndex.Bounds>()
        val starIds = mutableSetOf<String>()
        var bottom = 1000f
        for (value in values) {
            currentCoroutineContext().ensureActive()
            val bounds = box(value) ?: continue
            bottom = maxOf(bottom, bounds.bottom + 24f)
            val ink = stroke(value).inkOrNull ?: continue
            if (SingleStrokeStar.matches(List(ink.inputs.size) { index ->
                val point = ink.inputs[index]; SingleStrokeStar.Point(point.x, point.y)
            })) {
                stars += bounds; starIds += value.blob
            }
        }
        val paper = multiPlan.paper(strength, decisions)
        return PreviewDocument(bottom, StarPreviewRows.group(stars)) { band ->
            val visible = spatial.query(StrokeIndex.Bounds(band.left, band.top, band.right, band.bottom)).mapTo(hashSetOf()) { it.second.key }
            val strokes = values.asSequence().filter { it.key in visible || it.blob in starIds }.map { value ->
                stroke(value)
            }.asIterable()
            NoteExport.preview(strokes, paper, band, enlargedStrokes = starIds)
        }
    }
    fun saveResolved(notes: File, strength: MergeStrength, decisions: Map<String, Set<Int>>, title: String): NoteManifest =
        writeResult(notes, multiPlan.result(strength, decisions), title, multiPlan.paper(strength, decisions))
    private fun writeResult(notes: File, values: List<MergeValue>, title: String, paper: NotePaper): NoteManifest {
        check(!closed)
        val expected = multiPlan.indexes.first()
        val original = expected.manifest
        val id = original.info.id
        val destination = File(notes, "$id.misq")
        fun unchanged() = check(if (provisional) !destination.exists() else destination.isFile && runCatching {
            val current = MisqFile(destination).index()
            current.manifest == expected.manifest && current.events == expected.events &&
                current.records.keys == expected.records.keys && expected.records.all { (id, record) -> current.records[id]?.hash == record.hash }
        }.getOrDefault(false)) {
            jp.handwriting.note.AppMessages.text(jp.handwriting.note.R.string.conflict_version_changed)
        }
        unchanged()
        val used = mutableSetOf<String>()
        val entities = mutableMapOf<Long, String>()
        val originalIds = expected.entities.entries.associate { it.value to it.key }
        var nextId = original.nextId
        val refs = values.map { value ->
            // Choosing two edits of one stroke intentionally creates a second independent stroke.
            val entity = if (used.add(value.entity)) value.entity else UUID.randomUUID().toString()
            val refId = originalIds[entity] ?: nextId++
            entities[refId] = entity
            StrokeRef(refId, value.blob)
        }
        val manifest = original.copy(info = original.info.copy(title = NoteFiles.validTitle(title.take(120)),
            updatedAt = System.currentTimeMillis(), paper = paper), strokes = refs, nextId = nextId)
        val temporary = File(notes, ".merge-${UUID.randomUUID()}.tmp")
        try {
            a.file.copyTo(temporary, overwrite = true)
            val target = MisqFile(temporary)
            target.resolve(manifest, readers, entities)
            target.validate()
            unchanged()
            if (provisional) java.nio.file.Files.move(temporary.toPath(), destination.toPath())
            else java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return manifest
        } finally { temporary.delete() }
    }
    @Synchronized override fun close() { closed = true; decoded.clear(); folder.deleteRecursively() }

    companion object {
        suspend fun prepareAll(conflicts: List<NoteConflict>, sources: List<File>, cache: File): MergeReviewSession {
            require(sources.size == if (conflicts.first().provisional) maxOf(2, conflicts.size) else conflicts.size + 1)
            val folder = File(cache, "merge-review-${UUID.randomUUID()}").apply { check(mkdirs()) }
            suspend fun snapshot(source: File, name: String): File {
                val target = File(folder, name)
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); digest.update(buffer, 0, count) }
                } }
                val verify = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                    currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; verify.update(buffer, 0, count)
                } }
                check(digest.digest().contentEquals(verify.digest())) { jp.handwriting.note.AppMessages.text(jp.handwriting.note.R.string.conflict_version_changed) }
                return target
            }
            try {
                val readers = sources.mapIndexed { i, file -> MisqFile(snapshot(file, "$i.misq"), readOnlyAlias = true) }
                val session = MergeReviewSession(conflicts, folder, readers)
                val job = currentCoroutineContext()
                session.multiPlan = MultiMergePlan.build(readers.map { it.index() }) { value ->
                    job.ensureActive(); session.box(value)
                }
                session.multiPlan.values.forEachIndexed { index, value ->
                    currentCoroutineContext().ensureActive()
                    session.box(value)?.let { session.spatial.add(index.toLong(), value, it) }
                }
                return session
            } catch (error: Throwable) { folder.deleteRecursively(); throw error }
        }
    }
}
