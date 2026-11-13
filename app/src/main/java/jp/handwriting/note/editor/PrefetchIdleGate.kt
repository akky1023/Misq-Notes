package jp.handwriting.note.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Shared by the editor and its workers. Updates come from the main thread. */
internal class PrefetchIdleGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Activity(val contact: Boolean, val changedAt: Long)
    private val activity = MutableStateFlow(Activity(false, clock()))

    fun interaction() { activity.value = activity.value.copy(changedAt = clock()) }

    fun contact(active: Boolean) {
        if (activity.value.contact != active) activity.value = Activity(active, clock())
    }

    private fun remaining(state: Activity) = (1_000L - (clock() - state.changedAt)).coerceAtLeast(0)

    fun isIdle(): Boolean = activity.value.let { !it.contact && remaining(it) == 0L }

    suspend fun awaitIdle() {
        while (true) {
            val state = activity.value
            if (state.contact) {
                activity.first { !it.contact }
            } else {
                val wait = remaining(state)
                if (wait == 0L) return
                withTimeoutOrNull(wait) { activity.first { it != state } }
            }
        }
    }
}
