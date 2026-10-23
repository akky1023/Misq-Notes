package jp.handwriting.note.storage

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SaveQueueTest {
    @Test fun batchesRapidRequestsAndAwaitWaitsForTheLatestSnapshot() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val writes = mutableListOf<Int>()
            val queue = SaveQueue<Int>(scope, batchMillis = 50) { writes += it }
            val first = queue.request(1)
            yield() // The consumer has received the first request and started its window.
            queue.request(2); val last = queue.request(3)
            assertTrue(writes.isEmpty())
            assertTrue(withTimeout(5000) { queue.await(last) })
            assertTrue(queue.await(first))
            assertEquals(listOf(3), writes)
            assertFalse(queue.state.value.saving)
        } finally { scope.cancel() }
    }
    @Test fun continuousRequestsDoNotPostponeTheBatchIndefinitely() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val written = CompletableDeferred<Int>()
            val queue = SaveQueue<Int>(scope, batchMillis = 50) { written.complete(it) }
            val producer = launch { repeat(1000) { queue.request(it); delay(5) } }
            withTimeout(5000) { written.await() }
            assertTrue(producer.isActive)
            producer.cancelAndJoin()
            assertTrue(withTimeout(5000) { queue.await(queue.state.value.latest) })
        } finally { scope.cancel() }
    }
    @Test fun retryReplaysTheFailedSnapshotAfterStorageBecomesAvailable() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var available = false; val values = mutableListOf<Int>()
            val queue = SaveQueue<Int>(scope) { check(available); values += it }
            val first = queue.request(7)
            assertFalse(withTimeout(5000) { queue.await(first) })
            available = true; queue.retry()
            assertTrue(withTimeout(5000) { queue.await(queue.state.value.latest) })
            assertEquals(listOf(7), values)
            assertNull(queue.state.value.error)
        } finally { scope.cancel() }
    }
    @Test fun keepsInFlightWriteAndConflatesOnlyWaitingSnapshots() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val queue = SaveQueue<Int>(scope) {
                if (it == 1) { started.complete(Unit); finish.await() }
                writes.add(it)
            }
            queue.request(1); started.await()
            queue.request(2); val last = queue.request(3)
            finish.complete(Unit)
            assertTrue(withTimeout(5000) { queue.await(last) })
            assertEquals(listOf(1, 3), writes)
            assertFalse(queue.state.value.saving)
        } finally { scope.cancel() }
    }
    @Test fun failureCanBeRetriedAndFlushReportsItsResult() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val queue = SaveQueue<Int>(scope) { if (it == 1) throw IOException("full") }
            val first = queue.request(1)
            assertFalse(withTimeout(5000) { queue.await(first) })
            assertNotNull(queue.state.value.error)
            val second = queue.request(2)
            assertTrue(withTimeout(5000) { queue.await(second) })
            assertNull(queue.state.value.error)
        } finally { scope.cancel() }
    }
}
