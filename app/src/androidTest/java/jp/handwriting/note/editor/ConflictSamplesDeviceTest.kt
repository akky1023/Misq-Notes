package jp.handwriting.note.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly requested sample data only; ordinary test runs never write the probe library. */
class ConflictSamplesDeviceTest {
    private val descriptions = listOf(
        "右のみ追記・自動取り込み", "左のみ追記・解決済み", "離れた場所に両側追記", "重なる場所に両側追記",
        "近接した両側追記・強さ比較", "左だけ削除", "左が削除・右が移動", "同じ線を左右で別々に移動",
        "同じ変更・確認不要", "左が移動・右が離れた場所に追記", "ノート名が異なる", "用紙が異なる",
        "3端末の別版・2件比較", "時計ずれ・削除と移動")
    private val expectations = listOf(
        "右の赤い追記を確認なしで元ノートへ取り込み、一覧から外れる。",
        "左にだけ青い追記があり、すでに取り込み済みとして一覧から外れる。",
        "慎重でも左右の追記を自動選択。結果を確認して別ノートに保存できる。",
        "慎重・標準では要確認。積極的では両方を自動選択し、重なり順を比較できる。",
        "慎重では要確認、標準では両側の追記を自動選択する。",
        "削除の履歴があるため一覧に残る。慎重では左の削除を自動選択し、線を復活させない。",
        "同じ線の削除対移動。すべての強さで要確認、両方残すは無効。",
        "同じ線を別の場所へ移動。すべての強さで要確認、両方残すも選択できる。",
        "保存IDが異なっても内容は同じ。選択を求めず1本だけ残し、一覧から外れる。",
        "移動と追記が離れている。慎重でも両側の変更を自動選択する。",
        "左と右でノート名が異なる。名前を手動選択する。",
        "左は罫線、右は方眼と背景色変更。用紙を手動選択する。",
        "元ノートと別版2件を候補としてまとめ、各変更で採用する版を単独または複数選択する。",
        "右の時計が進んでいても削除対移動を自動決定しない。")

    @Test fun createConflictSamples() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedConflictSamples") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("net.neomenia.misqnotes.probe", context.packageName)
        val stage = File(context.cacheDir, "conflict-fixture-samples-${UUID.randomUUID()}").apply { mkdirs() }
        val publish = File(stage, "publish").apply { mkdirs() }
        val files = MisqTestFiles(publish)
        val payloads = mutableMapOf<String, ByteArray>()
        val published = mutableListOf<File>()
        val readme = StringBuilder("コンフリクトの比較用サンプル\n\n灰色：共通の筆跡、青：左側の変更、赤：右側の変更。\n自動解消されるケースは一覧ボタンが出ない。名前の番号で探せます。\n比較画面の自動マージの強さを切り替えて挙動を比べられます。\n13だけ別版が2件、それ以外は1件です。\n\n")
        fun stroke(color: Int, points: List<Pair<Float, Float>>): EditorStroke {
            val inputs = MutableStrokeInputBatch()
            points.forEachIndexed { i, (x, y) -> inputs.add(InputToolType.STYLUS, x, y, i * 30L) }
            return EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), color, 6f, 0.01f), inputs)).also {
                payloads[it.storageId] = NoteRepository.encodeStroke(it)
            }
        }
        val gray = 0xff616b73.toInt(); val blue = 0xff4285f4.toInt(); val red = 0xffea4335.toInt()
        fun diagonal(color: Int, x: Float, y: Float, reverse: Boolean = false) = stroke(color,
            if (reverse) listOf(x to y + 100, x + 100 to y) else listOf(x to y, x + 100 to y + 100))
        fun ref(id: Long, stroke: EditorStroke) = StrokeRef(id, stroke.storageId)
        try {
            for (number in 1..14) {
                val title = "競合サンプル%02d %s".format(number, descriptions[number - 1])
                val empty = files.create(title)
                val common = stroke(gray, listOf(100f to 100f, 250f to 100f, 250f to 250f, 100f to 250f, 100f to 100f))
                val target = stroke(gray, listOf(400f to 280f, 500f to 100f, 600f to 280f, 400f to 280f))
                val base = empty.copy(strokes = listOf(ref(1, common), ref(2, target)), nextId = 10)
                files.save(base) { payloads.getValue(it) }
                val branchDir = File(stage, "branch-$number").apply { mkdirs() }
                val rightFile = File(branchDir, "${base.info.id}.misq").also { files.document(base.info.id).file.copyTo(it) }
                var left = base; var right = base
                fun leftAdd(x: Float, y: Float) { left = left.copy(strokes = left.strokes + ref(10, diagonal(blue, x, y)), nextId = 20) }
                fun rightAdd(x: Float, y: Float) { right = right.copy(strokes = right.strokes + ref(10, diagonal(red, x, y, true)), nextId = 20) }
                fun leftMove(x: Float, y: Float) { left = left.copy(strokes = listOf(ref(1, common), ref(2, diagonal(blue, x, y)))) }
                fun rightMove(x: Float, y: Float) { right = right.copy(strokes = listOf(ref(1, common), ref(2, diagonal(red, x, y, true)))) }
                when (number) {
                    1 -> rightAdd(600f, 450f)
                    2 -> leftAdd(100f, 450f)
                    3 -> { leftAdd(100f, 450f); rightAdd(650f, 450f) }
                    4 -> { leftAdd(350f, 450f); rightAdd(350f, 450f) }
                    5 -> { leftAdd(100f, 450f); rightAdd(220f, 450f) }
                    6 -> left = left.copy(strokes = listOf(ref(1, common)))
                    7 -> { left = left.copy(strokes = listOf(ref(1, common))); rightMove(550f, 450f) }
                    8 -> { leftMove(100f, 450f); rightMove(650f, 450f) }
                    9 -> {
                        val changed = diagonal(blue, 350f, 450f)
                        val cloneId = UUID.randomUUID().toString().also { payloads[it] = payloads.getValue(changed.storageId) }
                        left = left.copy(strokes = listOf(ref(1, common), ref(2, changed)))
                        right = right.copy(strokes = listOf(ref(1, common), StrokeRef(2, cloneId)))
                    }
                    10 -> { leftMove(550f, 450f); rightAdd(100f, 450f) }
                    11 -> {
                        left = left.copy(info = left.info.copy(title = "$title・左の名前"))
                        right = right.copy(info = right.info.copy(title = "$title・右の名前"))
                    }
                    12 -> {
                        left = left.copy(info = left.info.copy(paper = NotePaper(pattern = PaperPattern.RULED)))
                        right = right.copy(info = right.info.copy(paper = NotePaper(color = 0xfff0f7ff.toInt(), pattern = PaperPattern.GRID)))
                    }
                    13 -> { leftAdd(100f, 450f); rightAdd(650f, 450f) }
                    14 -> {
                        left = left.copy(strokes = listOf(ref(1, common)), info = left.info.copy(updatedAt = left.info.updatedAt - 86400000L))
                        rightMove(550f, 450f)
                        right = right.copy(info = right.info.copy(updatedAt = right.info.updatedAt + 86400000L))
                    }
                }
                files.save(left) { payloads.getValue(it) }
                MisqFile(rightFile).save(right) { payloads.getValue(it) }
                if (number == 14) {
                    val entity = MisqFile(rightFile).index().entities.getValue(2)
                    files.document(base.info.id).preserveEventTimes(mapOf(entity to 1L))
                    MisqFile(rightFile).preserveEventTimes(mapOf(entity to 4102444800000L))
                }
                val original = files.document(base.info.id).file
                val alias = File(publish, "sample-%02d-right-%s.misq".format(number, base.info.id.take(8))).also { rightFile.copyTo(it) }
                val checker = NoteConflicts(publish, File(stage, "check-$number"))
                val conflict = checker.scan().single { it.file == alias }
                assertEquals("$number auto fast-forward", number in setOf(1, 2, 9), checker.canAutoMerge(conflict))
                MergeReviewSession.prepareAll(listOf(conflict), listOf(original, alias), File(stage, "reviews")).use { session ->
                    val cautious = session.multiPlan.steps(MergeStrength.CAUTIOUS)
                    val standard = session.multiPlan.steps(MergeStrength.STANDARD)
                    val aggressive = session.multiPlan.steps(MergeStrength.AGGRESSIVE)
                    when (number) {
                        3, 6, 10 -> assertTrue(cautious.all { it.suggested.isNotEmpty() })
                        4 -> { assertTrue(cautious.any { it.suggested.isEmpty() }); assertTrue(aggressive.all { it.suggested.isNotEmpty() }) }
                        5 -> { assertTrue(cautious.any { it.suggested.isEmpty() }); assertTrue(standard.all { it.suggested.isNotEmpty() }) }
                        7, 8, 11, 12, 14 -> assertTrue(aggressive.any { it.suggested.isEmpty() })
                        9 -> assertTrue(session.multiPlan.changes.isEmpty())
                    }
                }
                if (number == 13) {
                    val thirdDir = File(stage, "third").apply { mkdirs() }
                    val third = MisqFile(File(thirdDir, "${base.info.id}.misq").also { rightFile.copyTo(it) })
                    third.save(base.copy(strokes = base.strokes + ref(11, diagonal(0xffa142f4.toInt(), 350f, 700f)), nextId = 20)) { payloads.getValue(it) }
                    published += File(publish, "sample-13-third-${base.info.id.take(8)}.misq").also { third.file.copyTo(it) }
                }
                published += listOf(original, alias)
                readme.append("%02d %s\n%s\n元ノートID: %s\n\n".format(number, descriptions[number - 1], expectations[number - 1], base.info.id))
                println("SAMPLE %02d %s id=%s".format(number, descriptions[number - 1], base.info.id))
            }
            val export = File(context.getExternalFilesDir(null), "conflict-samples-20261031.zip")
            check(!export.exists()) { "Sample archive already exists; do not duplicate the sample batch." }
            ZipOutputStream(export.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("README.txt")); zip.write(readme.toString().toByteArray()); zip.closeEntry()
                for (file in published) { zip.putNextEntry(ZipEntry("notes/${file.name}")); file.inputStream().use { it.copyTo(zip, 65536) }; zip.closeEntry() }
            }
            val notes = File(context.filesDir, "notes").apply { mkdirs() }
            for (file in published) {
                val target = File(notes, file.name)
                check(!target.exists())
                file.copyTo(target)
                MisqFile(target, readOnlyAlias = true).validate()
            }
            File(context.getExternalFilesDir(null), "conflict-samples-20261031.txt").writeText(readme.toString())
            println("Created 14 notes and 15 aliases in probe library; pristine archive: ${export.path}")
        } finally { stage.deleteRecursively() }
    }
}
