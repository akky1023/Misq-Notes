package jp.handwriting.note.storage

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalSaveBenchmarkTest {
    @Test fun appendInSeparateSaves() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureLocalSave") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "save-benchmark-${UUID.randomUUID()}")
        try {
            val store = ImmutableNoteStore(directory)
            val library = Format3Library(store, File(directory, "notes"))
            var note = library.create("Save benchmark", NotePaper(), 1)
            val placement = StrokePlacement(100f, 100f, 110f)
            val encoded = ByteArray(1024) { it.toByte() }
            fun append(first: Long, count: Int) {
                val strokes = List(count) { index -> StrokeRef(first + index, UUID.randomUUID().toString()) }
                library.save(note.copy(info = note.info.copy(updatedAt = first), strokes = strokes,
                    nextId = first + count), strokes.associate { it.storageId to placement }, emptySet()) { encoded }
            }
            append(1, 3600)
            val objects = File(directory, "objects")
            val before = store.objectNames()
            val cpu = Process.getElapsedCpuTime()
            val start = SystemClock.elapsedRealtimeNanos()
            repeat(45) { append(3601L + it, 1) }
            val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
            val cpuMillis = Process.getElapsedCpuTime() - cpu
            val added = store.objectNames() - before
            val bytes = added.sumOf { File(objects, it).length() }
            val root = checkNotNull(ImmutableNoteStore(directory).current(note.info.id))
            check(root.count == 3645)
            store.verify(root)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nSAVE batches=45 newObjects=${added.size} objectBytes=$bytes elapsedMs=$elapsed cpuMs=$cpuMillis\n")
            })
        } finally {
            directory.deleteRecursively()
        }
    }
}
