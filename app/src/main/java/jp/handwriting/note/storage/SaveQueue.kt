package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

data class SaveState(val latest: Long = 0, val saved: Long = 0, val failed: Long = 0, val saving: Boolean = false, val error: String? = null)

/** Keep the latest queued snapshot, but never cancel a file write already in progress. */
class SaveQueue<T>(scope: CoroutineScope, private val batchMillis: Long = 0, private val debounce: Boolean = false, private val write: suspend (T) -> Unit) {
    init { require(batchMillis >= 0) }
    private data class Request<T>(val generation: Long, val value: T)
    private val queue = Channel<Request<T>>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(SaveState())
    private val flushWake = Channel<Unit>(Channel.CONFLATED)
    private val immediateThrough = MutableStateFlow(0L)
    private var lastValue: T? = null
    val state = mutableState.asStateFlow()
    init {
        scope.launch {
            for (first in queue) {
                // Content uses a fixed window to bound latency during continuous writing.
                // Derived previews can wait for idle; an in-flight write always finishes.
                var request = first
                if (batchMillis > 0) {
                    if (debounce) {
                        // Only derived previews use an idle window. Content saves keep bounded latency.
                        while (immediateThrough.value < request.generation) {
                            withTimeoutOrNull(batchMillis) {
                                select<Unit> {
                                    queue.onReceive { request = it }
                                    flushWake.onReceive { }
                                }
                            } ?: break
                        }
                    } else withTimeoutOrNull(batchMillis) { immediateThrough.first { it >= request.generation } }
                    while (true) request = queue.tryReceive().getOrNull() ?: break
                }
                try {
                    write(request.value)
                    mutableState.update { it.copy(saved = request.generation, saving = request.generation < it.latest, error = null) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutableState.update { it.copy(failed = request.generation,
                        saving = request.generation < it.latest, error = e.localizedMessage ?: AppMessages.text(R.string.save_unsuccessful)) }
                }
            }
        }
    }
    @Synchronized fun request(value: T): Long {
        lastValue = value
        val generation = mutableState.value.latest + 1
        mutableState.update { it.copy(latest = generation, saving = true, error = null) }
        check(queue.trySend(Request(generation, value)).isSuccess)
        return generation
    }
    @Synchronized fun retry() { lastValue?.let(::request) }
    /** Explicit navigation/save waits bypass batching, without cancelling an in-flight write. */
    suspend fun flush(generation: Long = state.value.latest): Boolean {
        immediateThrough.update { maxOf(it, generation) }
        flushWake.trySend(Unit)
        return await(generation)
    }
    suspend fun await(generation: Long): Boolean = state.first {
        it.saved >= generation || (it.failed >= generation && !it.saving)
    }.saved >= generation
}
