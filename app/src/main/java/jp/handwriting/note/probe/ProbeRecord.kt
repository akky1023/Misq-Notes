package jp.handwriting.note.probe

data class PointerSample(
    val id: Int,
    val toolType: Int,
    val toolName: String,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float,
    val orientation: Float,
)

data class HistoricalSample(val eventTimeMs: Long, val pointers: List<PointerSample>)

/** Immutable copies only: Android recycles MotionEvent and KeyEvent after dispatch. */
data class ProbeRecord(
    val channel: String,
    val action: Int,
    val actionName: String,
    val eventTimeMs: Long,
    val receivedUptimeMs: Long,
    val deviceId: Int,
    val source: Int,
    val actionIndex: Int = 0,
    val buttonState: Int? = null,
    val actionButton: Int? = null,
    val flags: Int = 0,
    val pointers: List<PointerSample> = emptyList(),
    val history: List<HistoricalSample> = emptyList(),
    val omittedHistory: Int = 0,
    val keyCode: Int? = null,
    val keyName: String? = null,
    val scanCode: Int? = null,
    val repeatCount: Int? = null,
    val metaState: Int = 0,
    val pressedButtons: Int = 0,
    val releasedButtons: Int = 0,
    val firstButtonObservation: Boolean = false,
)

data class ProbeSnapshot(
    val records: List<ProbeRecord> = emptyList(),
    val total: Long = 0,
    val dropped: Long = 0,
    val buttonSignals: Long = 0,
    val keyEvents: Long = 0,
    val paused: Boolean = false,
)

/** Main-thread confined. A missing release is never invented after pause/focus loss. */
class ProbeRecorder(private val capacity: Int = 3000) {
    init { require(capacity > 0) }
    private val records = ArrayDeque<ProbeRecord>()
    private val buttons = mutableMapOf<Pair<Int, Int>, Int>()
    private var total = 0L
    private var dropped = 0L
    private var buttonSignals = 0L
    private var keyEvents = 0L
    var paused = false
        private set

    fun append(record: ProbeRecord) {
        if (paused) return
        val state = record.buttonState
        val key = record.deviceId to record.source
        val previous = if (state != null) buttons.put(key, state) else null
        val pressed = if (state != null && previous != null) state and previous.inv() else 0
        val released = if (state != null && previous != null) previous and state.inv() else 0
        val enriched = record.copy(
            pressedButtons = pressed,
            releasedButtons = released,
            firstButtonObservation = state != null && previous == null,
        )
        // Explicit press/release actions (11/12) count even if the state did not change.
        if (pressed != 0 || released != 0 || record.actionButton?.let { it != 0 } == true ||
            (state != null && previous == null && state != 0) ||
            (record.channel != "key" && record.action in 11..12)
        ) buttonSignals++
        if (record.channel == "key") keyEvents++
        if (records.size == capacity) { records.removeFirst(); dropped++ }
        records.addLast(enriched)
        total++
    }

    fun setPaused(value: Boolean) { paused = value; resetButtonBaseline() }
    fun resetButtonBaseline() { buttons.clear() }
    fun clear() {
        records.clear()
        buttons.clear()
        total = 0
        dropped = 0
        buttonSignals = 0
        keyEvents = 0
    }
    fun snapshot() = ProbeSnapshot(records.toList(), total, dropped, buttonSignals, keyEvents, paused)
}
