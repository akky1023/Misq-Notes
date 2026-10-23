package jp.handwriting.note.probe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbeExportTest {
    @Test fun exportsVersionRawEventsHistoryAndEscapedMetadata() {
        val p = PointerSample(7, 2, "STYLUS", 123.5f, 80f, 0.7f, 0f, 0f)
        val event = ProbeRecord("generic", 11, "ACTION_BUTTON_PRESS", 100, 102, 3, 0x4002,
            buttonState = 32, actionButton = 32, pointers = listOf(p),
            history = listOf(HistoricalSample(99, listOf(p.copy(pressure = Float.NaN)))))
        val json = JSONObject(ProbeExport.encode(ProbeSnapshot(listOf(event), total = 4, dropped = 3),
            JSONObject().put("name", "ペン\n\"test\"")))
        assertEquals(1, json.getInt("formatVersion"))
        assertEquals(3L, json.getLong("droppedEvents"))
        assertEquals("ペン\n\"test\"", json.getJSONObject("metadata").getString("name"))
        val record = json.getJSONArray("events").getJSONObject(0)
        assertEquals(32, record.getInt("buttonState"))
        assertEquals(7, record.getJSONArray("pointers").getJSONObject(0).getInt("id"))
        assertTrue(record.getJSONArray("history").getJSONObject(0)
            .getJSONArray("pointers").getJSONObject(0).isNull("pressure"))
        assertTrue(record.isNull("keyCode"))
    }
}
