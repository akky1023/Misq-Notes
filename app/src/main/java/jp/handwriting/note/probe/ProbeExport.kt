package jp.handwriting.note.probe

import org.json.JSONArray
import org.json.JSONObject

object ProbeExport {
    fun encode(snapshot: ProbeSnapshot, metadata: JSONObject): String = JSONObject().apply {
        put("formatVersion", 1)
        put("metadata", metadata)
        put("coordinateSpace", "activity-window-pixels")
        put("eventTimeClock", "SystemClock.uptimeMillis")
        put("totalEvents", snapshot.total)
        put("droppedEvents", snapshot.dropped)
        put("buttonSignalEvents", snapshot.buttonSignals)
        put("keyEvents", snapshot.keyEvents)
        put("paused", snapshot.paused)
        put("events", JSONArray().apply { snapshot.records.forEach { put(record(it)) } })
    }.toString(2)

    private fun record(r: ProbeRecord) = JSONObject().apply {
        put("channel", r.channel)
        put("action", r.action)
        put("actionName", r.actionName)
        put("actionIndex", r.actionIndex)
        put("eventTimeMs", r.eventTimeMs)
        put("receivedUptimeMs", r.receivedUptimeMs)
        put("deviceId", r.deviceId)
        put("source", r.source)
        put("buttonState", r.buttonState ?: JSONObject.NULL)
        put("actionButton", r.actionButton ?: JSONObject.NULL)
        put("pressedButtons", r.pressedButtons)
        put("releasedButtons", r.releasedButtons)
        put("firstButtonObservation", r.firstButtonObservation)
        put("flags", r.flags)
        put("metaState", r.metaState)
        put("keyCode", r.keyCode ?: JSONObject.NULL)
        put("keyName", r.keyName ?: JSONObject.NULL)
        put("scanCode", r.scanCode ?: JSONObject.NULL)
        put("repeatCount", r.repeatCount ?: JSONObject.NULL)
        put("pointers", pointers(r.pointers))
        put("omittedHistory", r.omittedHistory)
        put("history", JSONArray().apply {
            r.history.forEach { h -> put(JSONObject().apply {
                put("eventTimeMs", h.eventTimeMs)
                put("pointers", pointers(h.pointers))
            }) }
        })
    }

    private fun pointers(samples: List<PointerSample>) = JSONArray().apply {
        samples.forEach { p -> put(JSONObject().apply {
            put("id", p.id)
            put("toolType", p.toolType)
            put("toolName", p.toolName)
            put("x", finite(p.x))
            put("y", finite(p.y))
            put("pressure", finite(p.pressure))
            put("tilt", finite(p.tilt))
            put("orientation", finite(p.orientation))
        }) }
    }

    private fun finite(value: Float): Any = if (value.isFinite()) value.toDouble() else JSONObject.NULL
}
