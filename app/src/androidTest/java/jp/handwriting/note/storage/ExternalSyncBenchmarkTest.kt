package jp.handwriting.note.storage

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Test

class ExternalSyncBenchmarkTest {
    /** Reproduces the production listing path without touching selected folders or creating 150k files. */
    @Test fun repeatedLargeInventoryWithSmallNote() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("measureLargeInventory") == "true")
        val count = (args.getString("backgroundObjects") ?: "75000").toInt().also { require(it in 0..150000) }
        val seconds = (args.getString("durationSeconds") ?: "0").toLong().also { require(it in 0..86400) }
        val idleSeconds = (args.getString("idleSeconds") ?: "0").toLong().also { require(it in 0..3600) }
        val rounds = (args.getString("inventoryRounds") ?: if (seconds > 0) "10000" else "6")
            .toInt().also { require(it in 1..10000) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "net.neomenia.misqnotes.probe")
        val name = "inventory-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, "working-set-provider/$name")
        val remote = File(directory, "misq-v3").also { check(it.mkdirs()) }
        File(remote, ".virtual-object-count").writeText(count.toString())
        val local = File(context.cacheDir, "inventory-store-$name")
        try {
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri(
                "net.neomenia.misqnotes.probe.tests.working-set", name)
            val packed = PackedDocumentFiles(context.contentResolver, tree, "misq-v3")
            val store = ImmutableNoteStore(local)
            val transport = ImmutableNoteTransport(store, packed)
            var root = store.create(NoteInfo(UUID.randomUUID().toString(), "Small note, large remote history", 0, 0))
            val deadline = SystemClock.elapsedRealtime() + seconds * 1000
            for (pass in 0 until rounds) {
                if (pass > 0 && seconds > 0 && SystemClock.elapsedRealtime() >= deadline) break
                val start = SystemClock.elapsedRealtime()
                val cpu = Process.getElapsedCpuTime()
                val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                root = store.saveThumbnail(root, byteArrayOf(pass.toByte()))
                transport.withInventorySnapshot { transport.publish(root) }
                val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "\nINVENTORY pass=$pass backgroundFiles=${count * 2} elapsedMs=${SystemClock.elapsedRealtime() - start} cpuMs=${Process.getElapsedCpuTime() - cpu} javaBefore=$before javaAfter=$after max=${Runtime.getRuntime().maxMemory()}\n") })
                if (idleSeconds > 0) SystemClock.sleep(minOf(idleSeconds * 1000,
                    if (seconds > 0) (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) else Long.MAX_VALUE))
            }
            val receiver = ImmutableNoteStore(File(local, "receiver"))
            val download = ImmutableNoteTransport(receiver, packed)
            val reopened = download.withInventorySnapshot { download.heads(root.info.id).single() }
            check(reopened.id == root.id)
            receiver.install(reopened)
            download.verifyRemote(reopened)
        } finally { directory.deleteRecursively(); local.deleteRecursively() }
    }

    @Test fun compareFreshAndReusedInventory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureExternalSync") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "working-set-provider/$name")
        val remote = File(directory, "misq-v3")
        check(remote.mkdirs())
        val local = File(context.cacheDir, "battery-sync-$name")
        try {
            val count = 4000
            repeat(count) { index ->
                val hash = index.toString(16).padStart(64, '0')
                val suffix = "$hash-00000000-0000-0000-0000-000000000000"
                check(File(remote, "object-$suffix").createNewFile())
                File(remote, "seal-$suffix").writeText(hash)
            }
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri(
                "net.neomenia.misqnotes.probe.tests.working-set", name)
            val packed = PackedDocumentFiles(context.contentResolver, tree, "misq-v3")
            val transport = ImmutableNoteTransport(ImmutableNoteStore(local), packed)
            fun measure(label: String, block: () -> Unit) {
                val wall = SystemClock.elapsedRealtime()
                val cpu = Process.getElapsedCpuTime()
                block()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "\nSYNC $label files=${count * 2} elapsedMs=${SystemClock.elapsedRealtime() - wall} cpuMs=${Process.getElapsedCpuTime() - cpu}\n")
                })
            }
            measure("listing") { check(packed.names().size == count * 2) }
            measure("cold") { transport.withInventorySnapshot { check(transport.noteIds().isEmpty()) } }
            measure("warm") { transport.withInventorySnapshot { check(transport.noteIds().isEmpty()) } }

            val source = ImmutableNoteStore(local)
            var root = source.create(NoteInfo(UUID.randomUUID().toString(), "Sync benchmark", 0, 0))
            val publisher = ImmutableNoteTransport(source, packed)
            publisher.withInventorySnapshot {
                repeat(20) {
                    root = source.saveThumbnail(root, byteArrayOf(it.toByte()))
                    publisher.publish(root)
                }
            }
            val receiver = ImmutableNoteTransport(ImmutableNoteStore(File(local, "receiver")), packed)
            repeat(2) { pass ->
                measure(if (pass == 0) "cold-inventory" else "warm-inventory") {
                    receiver.withInventorySnapshot {
                        measure(if (pass == 0) "cold-history" else "warm-history") {
                            check(receiver.heads(root.info.id).single().id == root.id)
                        }
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
            local.deleteRecursively()
        }
    }
}
