package jp.handwriting.note.probe

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent

object EventDecoder {
    private const val MAX_HISTORY = 64

    fun motion(event: MotionEvent, channel: String): ProbeRecord {
        fun pointers(historyIndex: Int? = null) = List(event.pointerCount) { i ->
            PointerSample(
                id = event.getPointerId(i),
                toolType = event.getToolType(i),
                toolName = toolName(event.getToolType(i)),
                x = historyIndex?.let { event.getHistoricalX(i, it) } ?: event.getX(i),
                y = historyIndex?.let { event.getHistoricalY(i, it) } ?: event.getY(i),
                pressure = historyIndex?.let { event.getHistoricalPressure(i, it) } ?: event.getPressure(i),
                tilt = historyIndex?.let { event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, it) }
                    ?: event.getAxisValue(MotionEvent.AXIS_TILT, i),
                orientation = historyIndex?.let { event.getHistoricalOrientation(i, it) } ?: event.getOrientation(i),
            )
        }
        val start = (event.historySize - MAX_HISTORY).coerceAtLeast(0)
        return ProbeRecord(
            channel = channel,
            action = event.action,
            actionName = MotionEvent.actionToString(event.action),
            actionIndex = event.actionIndex,
            eventTimeMs = event.eventTime,
            receivedUptimeMs = SystemClock.uptimeMillis(),
            deviceId = event.deviceId,
            source = event.source,
            buttonState = event.buttonState,
            actionButton = event.actionButton,
            flags = event.flags,
            metaState = event.metaState,
            pointers = pointers(),
            history = (start until event.historySize).map {
                HistoricalSample(event.getHistoricalEventTime(it), pointers(it))
            },
            omittedHistory = start,
        )
    }

    fun key(event: KeyEvent) = ProbeRecord(
        channel = "key",
        action = event.action,
        actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
            KeyEvent.ACTION_UP -> "ACTION_UP"
            @Suppress("DEPRECATION")
            KeyEvent.ACTION_MULTIPLE -> "ACTION_MULTIPLE"
            else -> "ACTION_${event.action}"
        },
        eventTimeMs = event.eventTime,
        receivedUptimeMs = SystemClock.uptimeMillis(),
        deviceId = event.deviceId,
        source = event.source,
        flags = event.flags,
        keyCode = event.keyCode,
        keyName = KeyEvent.keyCodeToString(event.keyCode),
        scanCode = event.scanCode,
        repeatCount = event.repeatCount,
        metaState = event.metaState,
    )

    private fun toolName(tool: Int) = when (tool) {
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TOOL_$tool"
    }
}
