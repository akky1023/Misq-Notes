package jp.handwriting.note.editor

import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit repair of the four known probe fixtures; never changes a user's merge choices. */
class CombinedConflictRepairDeviceTest {
    @Test fun repairInstalledSampleAndVerifyMergeAndDeletion() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("repairCombinedConflictSample") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("net.neomenia.misqnotes.probe", context.packageName)
        val noteId = "11645a9a-6bf2-4326-84e1-897b7804de86"
        val ancestor = "1c2be12a-f9bd-4faa-abbd-f500b0eb68b6"
        val correctTime = 1793379081339L
        val oldTimes = listOf(4102444800000L, 1793379081442L, 1793379081478L, 1793379081501L)
        val notes = File(context.filesDir, "notes")
        val originals = (1..4).map { File(notes, "総合サンプル-別版$it-11645a9a.misq") }
        val before = originals.map(ContentHash::digest)
        val scratch = File(context.cacheDir, "combined-repair-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val backup = File(context.getExternalFilesDir(null), scratch.name).apply { check(mkdirs()) }
        try {
            val staged = originals.mapIndexed { index, file ->
                val reader = MisqFile(file, readOnlyAlias = true)
                reader.validate()
                val current = reader.index()
                assertEquals(noteId, current.manifest.info.id)
                assertEquals("コンフリクト総合サンプル（別版${index + 1}）", current.manifest.info.title)
                val event = current.events.single { it.version == ancestor }
                assertTrue(event.parents.isEmpty())
                assertTrue(event.at == oldTimes[index] || event.at == correctTime)
                assertTrue(current.events.count { it.parents.isEmpty() && it.at == correctTime } >= 20)
                val saved = File(backup, file.name)
                file.copyTo(saved)
                assertEquals(before[index], ContentHash.digest(saved))
                val staged = File(scratch, "branch-$index.tmp")
                file.copyTo(staged)
                val target = MisqFile(staged)
                target.rewrite(current.copy(events = current.events.map {
                    if (it.version == ancestor) it.copy(at = correctTime) else it
                }))
                target.validate()
                assertEquals(current.manifest, target.index().manifest)
                assertEquals(current.events.map { it.copy(at = 0) }, target.index().events.map { it.copy(at = 0) })
                staged
            }
            // Exercise the real final-save path on isolated copies, with no working original.
            val isolated = File(scratch, "notes").apply { check(mkdirs()) }
            staged.forEachIndexed { index, file -> file.copyTo(File(isolated, "version-$index.misq")) }
            val store = NoteConflicts(isolated, File(scratch, "conflicts"))
            val conflicts = store.scan()
            assertEquals(4, conflicts.size)
            assertTrue(conflicts.all { it.provisional })
            val sources = conflicts.map { it.file }
            MergeReviewSession.prepareAll(conflicts, sources, File(scratch, "reviews")).use { session ->
                val strength = MergeStrength.CAUTIOUS
                val decisions = session.multiPlan.steps(strength).associate { it.key to setOf(0) }
                val result = session.saveResolved(isolated, strength, decisions, session.multiPlan.title(strength, decisions))
                MisqFile(File(isolated, "$noteId.misq")).validate()
                assertEquals(noteId, result.info.id)
            }
            val repository = NoteRepository(NoteFiles.open(isolated))
            repository.delete(noteId)
            assertTrue(repository.list().isEmpty())
            assertTrue(store.scan().isEmpty())
            // Also exercise deleting the provisional display before any merge is saved.
            staged.forEachIndexed { index, file -> file.copyTo(File(isolated, "version-$index.misq")) }
            assertEquals(1, repository.list().size)
            assertTrue(repository.list().single().provisional)
            repository.delete(noteId)
            assertTrue(repository.list().isEmpty())
            assertTrue(store.scan().isEmpty())
            // Publish only after every staged file passed final-save/deletion checks.
            originals.forEachIndexed { index, file -> assertEquals(before[index], ContentHash.digest(file)) }
            staged.forEachIndexed { index, file ->
                val temporary = File(notes, ".sample-repair-${UUID.randomUUID()}.tmp")
                try {
                    file.inputStream().use { input -> java.io.FileOutputStream(temporary).use { output ->
                        input.copyTo(output, 65536); output.fd.sync()
                    } }
                    Files.move(temporary.toPath(), originals[index].toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
            assertEquals(4, NoteConflicts(notes, File(scratch, "verify")).scan().count { it.noteId == noteId })
            File(backup, "verified.txt").writeText("Repaired only ancestor $ancestor to $correctTime.\n" +
                originals.mapIndexed { index, file -> "${file.name}: ${before[index]} -> ${ContentHash.digest(file)}" }.joinToString("\n"))
        } finally { scratch.deleteRecursively() }
    }
}
