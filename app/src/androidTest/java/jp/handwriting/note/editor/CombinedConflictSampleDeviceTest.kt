package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in probe data maintenance. Preparation and deletion are separate, with a verified backup. */
class CombinedConflictSampleDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notes get() = File(context.filesDir, "notes").canonicalFile
    private val planFile get() = File(context.filesDir, "conflict-sample-replacement-plan.json")
    private val report get() = File(context.getExternalFilesDir(null), "conflict-sample-replacement-plan.json")
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count)
        } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun prefix(title: String) = title.startsWith("競合サンプル") || title.startsWith("コンフリクト確認用") || title.startsWith("コンフリクト総合サンプル")
    private fun confined(file: File): File = file.canonicalFile.also { check(it.toPath().startsWith(notes.toPath()) && it != notes) }
    private fun snapshot(file: File) = JSONObject().put("path", confined(file).relativeTo(notes).invariantSeparatorsPath).put("sha256", digest(file))
    private fun readOpened(): JSONObject = File(context.filesDir, "conflicts/opened.hwp").let {
        if (it.exists()) JSONObject(String(NoteFiles.readEnvelope(it))) else JSONObject()
    }
    private fun savePlan(plan: JSONObject) { planFile.writeText(plan.toString(2)); report.writeText(plan.toString(2)) }

    @Test fun replaceSamples() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("replaceConflictSamples") == "true")
        assertEquals("net.neomenia.misqnotes.probe", context.packageName)
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "prepare" -> prepare()
            "apply" -> applyPrepared()
            else -> error("Explicit prepare/apply phase required")
        }
    }

    private suspend fun prepare() {
        if (planFile.exists()) {
            val previous = JSONObject(planFile.readText())
            if (previous.optString("phase") == "prepared") { report.writeText(previous.toString(2)); return }
        }
        val files = notes.listFiles().orEmpty().filter { it.isFile && it.extension == "misq" }
        val rows = files.map { file -> file to MisqFile(file, readOnlyAlias = true).readManifest().manifest }
        val ids = knownSampleIds.toMutableSet()
        rows.filter { prefix(it.second.info.title) }.forEach { ids += it.second.info.id }
        val opened = readOpened()
        do {
            val count = ids.size
            opened.keys().asSequence().toList().forEach { key -> if (key.substringBefore(':') in ids) ids += opened.getString(key) }
        } while (ids.size != count)
        val oldRows = rows.filter { it.second.info.id in ids }
        val folders = ids.map { confined(File(notes, it)) }.filter { it.isDirectory }
        val targets = (oldRows.map { it.first } + folders.flatMap { it.walkTopDown().filter(File::isFile).toList() }).distinctBy { it.canonicalPath }
        val protected = rows.filter { it.second.info.id !in ids }.map { it.first }
        check(targets.isNotEmpty()) { "No existing conflict samples found" }
        val run = UUID.randomUUID().toString()
        val stage = File(context.cacheDir, "combined-conflict-$run").apply { check(mkdirs()) }
        val generated = generate(stage)
        val backup = File(context.getExternalFilesDir(null), "conflict-tests-before-cleanup-$run.zip")
        val expected = targets.associate { "notes/${it.relativeTo(notes).invariantSeparatorsPath}" to digest(it) }
        ZipOutputStream(backup.outputStream().buffered()).use { zip ->
            targets.forEach { file ->
                zip.putNextEntry(ZipEntry("notes/${file.relativeTo(notes).invariantSeparatorsPath}"))
                file.inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry()
            }
            for (name in listOf("conflicts/opened.hwp", "conflicts/opened.hwp.bak", "library.hwp", "library.hwp.bak", "library.json", "library.json.bak")) {
                val file = File(context.filesDir, name)
                if (file.isFile) { zip.putNextEntry(ZipEntry("restore-info/$name")); file.inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry() }
            }
        }
        ZipFile(backup).use { zip -> expected.forEach { (path, hash) ->
            val entry = checkNotNull(zip.getEntry(path)); val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input -> val buffer = ByteArray(65536); while (true) {
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hash)
        } }
        val plan = JSONObject().put("phase", "prepared").put("backup", backup.path).put("backupSha256", digest(backup))
            .put("sampleIds", JSONArray(ids.toList())).put("folders", JSONArray(folders.map { it.name }))
            .put("deleteFiles", JSONArray(targets.map(::snapshot))).put("preserveFiles", JSONArray(protected.map(::snapshot)))
            .put("deleteNotes", JSONArray(oldRows.map { JSONObject().put("id", it.second.info.id).put("title", it.second.info.title).put("file", it.first.name) }))
            .put("preserveNotes", JSONArray(rows.filter { it.second.info.id !in ids }.map { JSONObject().put("id", it.second.info.id).put("title", it.second.info.title) }))
            .put("stage", stage.path).put("newId", generated.first).put("newFiles", JSONArray(generated.second.map { JSONObject().put("file", it.name).put("sha256", digest(it)) }))
        savePlan(plan)
    }

    private fun applyPrepared() {
        val plan = JSONObject(planFile.readText()); check(plan.getString("phase") == "prepared")
        val backup = File(plan.getString("backup")); check(digest(backup) == plan.getString("backupSha256"))
        val stage = File(plan.getString("stage")).canonicalFile
        check(stage.parentFile == context.cacheDir.canonicalFile && stage.name.startsWith("combined-conflict-"))
        fun verify(array: JSONArray) { for (i in 0 until array.length()) {
            val item = array.getJSONObject(i); check(digest(confined(File(notes, item.getString("path")))) == item.getString("sha256"))
        } }
        verify(plan.getJSONArray("deleteFiles")); verify(plan.getJSONArray("preserveFiles"))
        val newFiles = plan.getJSONArray("newFiles")
        for (i in 0 until newFiles.length()) {
            val item = newFiles.getJSONObject(i); val file = File(stage, item.getString("file")).canonicalFile
            check(file.parentFile == stage && digest(file) == item.getString("sha256"))
            check(!confined(File(notes, file.name)).exists())
        }
        NoteListCache(notes).changing()
        val ids = plan.getJSONArray("sampleIds").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        // Every recursively removed directory is an exact, previously inspected notebook UUID.
        val files = NoteFiles(notes)
        for (id in ids) { confined(File(notes, id)); files.delete(id) }
        val delete = plan.getJSONArray("deleteFiles")
        for (i in 0 until delete.length()) confined(File(notes, delete.getJSONObject(i).getString("path"))).let { if (it.exists()) check(it.delete()) }
        val opened = readOpened()
        opened.keys().asSequence().toList().filter { it.substringBefore(':') in ids || opened.optString(it) in ids }.forEach(opened::remove)
        val openedFile = File(context.filesDir, "conflicts/opened.hwp")
        if (openedFile.exists()) {
            NoteFiles.atomicWrite(openedFile, opened.toString().toByteArray())
            File(context.filesDir, "conflicts/opened.hwp.bak").takeIf { it.isFile }?.writeBytes(openedFile.readBytes())
        }
        for (i in 0 until newFiles.length()) {
            val name = newFiles.getJSONObject(i).getString("file")
            File(stage, name).copyTo(confined(File(notes, name)))
        }
        verify(plan.getJSONArray("preserveFiles"))
        assertFalse(NoteFiles(notes).list().any { it.id in ids })
        val conflicts = NoteConflicts(notes, File(context.filesDir, "conflicts")).scan()
        assertEquals(4, conflicts.count { it.noteId == plan.getString("newId") })
        assertEquals(1, NoteFiles(notes).list().count { it.id == plan.getString("newId") })
        plan.put("phase", "applied"); savePlan(plan)
        stage.deleteRecursively()
    }

    private suspend fun generate(stage: File): Pair<String, List<File>> {
        val title = "コンフリクト総合サンプル"
        val files = NoteFiles(stage); val empty = files.create(title)
        val payloads = mutableMapOf<String, ByteArray>(); val baseline = linkedMapOf<Long, EditorStroke>()
        var nextId = 1L
        val gray = 0xff616b73.toInt()
        val colors = listOf(0xff4285f4.toInt(), 0xffea4335.toInt(), 0xff109d58.toInt(), 0xffff9800.toInt(), 0xffa142f4.toInt())
        fun owned(stroke: EditorStroke) = stroke.also { payloads[it.storageId] = NoteRepository.encodeStroke(it) }
        fun line(color: Int, x: Float, y: Float, size: Float = 6f): EditorStroke {
            val inputs = MutableStrokeInputBatch().add(InputToolType.STYLUS, x, y, 0).add(InputToolType.STYLUS, x + 120f, y + 120f, 30)
            return owned(EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), color, size, 0.01f), inputs)))
        }
        fun heading(y: Float, lines: List<String>, height: Int = 120): EditorStroke {
            val bitmap = Bitmap.createBitmap(1000, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55, 65, 75) }
            lines.forEachIndexed { i, text -> paint.textSize = if (i == 0) 30f else 21f; canvas.drawText(text, 40f, 40f + i * 34f, paint) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
            return owned(EditorStroke(ImageRecord(ImageData(bytes), 0f, y, 1000f, height.toFloat())))
        }
        baseline[nextId++] = heading(0f, listOf(title, "1冊に12種類の筆跡差分と、ノート名・用紙の違いを収録。", "元ノート＋別版4件。マージ開始時に比較する別版を3つ選びます。", "青が元ノート、赤・緑・橙・紫が独立した別版の変更です。", "同じ変更は確認対象から外れます。各見出しの下で線を比べてください。"), 220)
        val labels = listOf("片側だけの追記", "離れた場所への追記", "重なる追記・複数採用", "近接した追記・強さの比較", "一方だけの削除", "削除と移動の衝突", "同じ線を別々の場所へ移動", "同じ変更・確認不要", "削除と離れた追記", "時計ずれ・削除と移動", "画像の移動・削除", "色と太さの変更")
        val targetIds = mutableMapOf<Int, Long>()
        for (number in 1..12) {
            val y = 280f + (number - 1) * 650f
            baseline[nextId++] = heading(y, listOf("%02d  %s".format(number, labels[number - 1]), if (number == 8) "同じ筆跡を同じ内容に変更したため、マージの確認対象には出ません。" else "色・位置・残す線を比べ、紙面下のチェックで採用する版を選びます。"))
            if (number in setOf(5, 6, 7, 8, 9, 10, 12)) { targetIds[number] = nextId; baseline[nextId++] = line(gray, if (number == 9) 650f else 400f, y + 210f) }
            if (number == 11) { targetIds[number] = nextId; baseline[nextId++] = heading(y + 200f, listOf("画像", "移動・削除・拡大"), 110).let {
                owned(EditorStroke(it.image!!.copy(left = 400f, width = 240f, height = 110f)))
            } }
        }
        val base = empty.copy(strokes = baseline.map { StrokeRef(it.key, it.value.storageId) }, nextId = 1000)
        files.save(base) { payloads.getValue(it) }
        val branchFiles = (1..4).map { index -> File(stage, "branch-$index").apply { mkdirs() }.let { directory ->
            File(directory, "${empty.info.id}.misq").also { files.document(empty.info.id).file.copyTo(it) }
        } }
        val sameEdit = line(gray, 500f, 280f + 7 * 650f + 320f)
        val outputs = mutableListOf<File>()
        val papers = listOf(PaperPattern.PLAIN, PaperPattern.RULED, PaperPattern.GRID, PaperPattern.DOTS, PaperPattern.COLLEGE_RULED)
        for (version in 0..4) {
            val strokes = LinkedHashMap(baseline); var addedId = 1000L
            fun add(stroke: EditorStroke) { strokes[addedId++] = stroke }
            for (number in 1..12) {
                val y = 280f + (number - 1) * 650f; val id = targetIds[number]
                when (number) {
                    1 -> if (version == 1) add(line(colors[version], 500f, y + 220f))
                    2 -> if (version in 0..2) add(line(colors[version], 100f + version * 300f, y + 220f))
                    3 -> add(line(colors[version], 400f + version * 8f, y + 220f))
                    4 -> if (version in 0..1) add(line(colors[version], 100f + version * 140f, y + 220f))
                    5 -> if (version == 0) strokes.remove(id)
                    6, 10 -> when (version) { 0 -> strokes.remove(id); 1, 2 -> strokes[id!!] = line(colors[version], if (version == 1) 650f else 100f, y + 330f) }
                    7 -> strokes[id!!] = line(colors[version], 100f + (version % 3) * 280f, y + 220f + (version / 3) * 170f)
                    8 -> strokes[id!!] = owned(EditorStroke(sameEdit.ink))
                    9 -> if (version == 0) strokes.remove(id) else if (version == 1) add(line(colors[version], 100f, y + 330f))
                    11 -> when (version) {
                        0 -> strokes[id!!] = owned(baseline.getValue(id).translated(-250f, 100f))
                        1 -> strokes.remove(id)
                        2 -> strokes[id!!] = owned(baseline.getValue(id).translated(200f, 110f))
                        4 -> strokes[id!!] = owned(EditorStroke(baseline.getValue(id).image!!.resized(1.3f)))
                    }
                    12 -> strokes[id!!] = line(colors[version], 400f, y + 210f, 4f + version * 2f)
                }
            }
            val note = base.copy(info = base.info.copy(title = if (version == 0) title else "$title（別版$version）", paper = NotePaper(pattern = papers[version])),
                strokes = strokes.map { StrokeRef(it.key, it.value.storageId) }, nextId = 2000)
            val writer = if (version == 0) files.document(base.info.id) else MisqFile(branchFiles[version - 1])
            writer.save(note) { payloads.getValue(it) }
            val clockEntity = baseline.getValue(targetIds.getValue(10)).storageId
            writer.preserveEventTimes(mapOf(clockEntity to if (version == 0) 1L else if (version == 1) 4102444800000L else System.currentTimeMillis()))
            outputs += if (version == 0) writer.file else File(stage, "総合サンプル-別版$version-${base.info.id.take(8)}.misq").also { writer.file.copyTo(it) }
        }
        val checker = NoteConflicts(stage, File(stage, "check"))
        val aliases = checker.scan().filter { it.noteId == base.info.id && it.file.parentFile == stage }
        assertEquals(4, aliases.size)
        MergeReviewSession.prepareAll(aliases.take(3), listOf(outputs.first()) + aliases.take(3).map { it.file }, File(stage, "reviews")).use { session ->
            assertEquals(4, session.multiPlan.indexes.size)
            assertTrue(session.multiPlan.changes.any { it.kind == MergeKind.TITLE })
            assertTrue(session.multiPlan.changes.any { it.kind == MergeKind.PAPER })
            assertTrue(session.multiPlan.changes.any { it.kind == MergeKind.DELETE })
            assertTrue(session.multiPlan.changes.any { it.kind == MergeKind.EDIT })
            assertTrue(session.multiPlan.changes.any { it.kind == MergeKind.ADD })
            val sameEntity = baseline.getValue(targetIds.getValue(8)).storageId
            assertFalse(session.multiPlan.changes.any { it.key == sameEntity })
        }
        val readme = "$title\n\n元ノート1冊と別版4件です。比較する別版を3件選びます。\n" + labels.mapIndexed { i, label -> "%02d %s".format(i + 1, label) }.joinToString("\n") +
            "\n\n冒頭でノート名と用紙を選択します。各見出し画像は全版で共通です。\n08は同じ変更なのでマージ処理には現れません。\n元ノートID: ${base.info.id}\n"
        File(context.getExternalFilesDir(null), "combined-conflict-sample-20261031.txt").writeText(readme)
        ZipOutputStream(File(context.getExternalFilesDir(null), "combined-conflict-sample-20261031.zip").outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt")); zip.write(readme.toByteArray()); zip.closeEntry()
            outputs.forEach { file -> zip.putNextEntry(ZipEntry("notes/${file.name}")); file.inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry() }
        }
        return base.info.id to outputs
    }

    private val knownSampleIds = setOf(
        "f8531945-602e-42d8-9cdc-9440596653c9",
        "ac53e233-18b6-4955-b625-8518ecb7ee4e",
        "99bb671c-1e61-4305-89f5-f4637e3d6062",
        "deb49fbb-8fc8-4df7-9642-cebe0eab155b",
        "45d84f63-668d-45fa-8852-a4a4b53f8bf2",
        "2acba084-c4af-4e3a-8c0d-1dd26719e10f",
        "44ba4b39-a7f7-4f75-a304-5f52bce35841",
        "c018fcd0-ef22-454f-9095-7c99a28d881d",
        "68ba3f75-58c0-4a67-87ff-3c97156eb120",
        "9ce3756c-da91-4e90-8d8a-fc953cb376d6",
        "8cb053b8-9399-406f-92f3-0534fee9829c",
        "afc11b3d-183a-45a3-9420-5ab39292d701",
        "213f950f-246c-4233-98c0-779d045a51ce",
        "788aa455-c046-4e8e-be83-7726d0ad9b0b",
        "7a28ba7e-d068-48a6-b1a6-c4e47f1ea2d4"
    )
}
