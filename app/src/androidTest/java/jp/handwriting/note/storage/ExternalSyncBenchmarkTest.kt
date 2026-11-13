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
