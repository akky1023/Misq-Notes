package jp.handwriting.note.probe

import org.junit.Assert.*
import org.junit.Test

class ProbeRecorderTest {
    private fun motion(state: Int, device: Int = 1, source: Int = 0x4002, action: Int = 2) = ProbeRecord(
        channel = "touch", action = action, actionName = "test", eventTimeMs = 100,
        receivedUptimeMs = 101, deviceId = device, source = source, buttonState = state,
    )

    @Test fun pressAndReleaseAreDetectedAcrossTouchAndGenericChannels() {
        val recorder = ProbeRecorder()
        recorder.append(motion(0))
        recorder.append(motion(32).copy(channel = "generic", action = 11, actionButton = 32))
        recorder.append(motion(32))
        recorder.append(motion(0).copy(channel = "generic", action = 12, actionButton = 32))
        val s = recorder.snapshot()
        assertEquals(32, s.records[1].pressedButtons)
        assertEquals(0, s.records[2].pressedButtons)
        assertEquals(32, s.records[3].releasedButtons)
        assertEquals(2L, s.buttonSignals)
    }

    @Test fun devicesAndSourcesHaveIndependentBaselines() {
        val recorder = ProbeRecorder()
        recorder.append(motion(32))
        recorder.append(motion(0, device = 2))
        recorder.append(motion(0, source = 0x2002))
        assertTrue(recorder.snapshot().records.all { it.firstButtonObservation })
        assertTrue(recorder.snapshot().records.all { it.releasedButtons == 0 })
    }

    @Test fun pauseDoesNotFabricateAnUnobservedRelease() {
        val recorder = ProbeRecorder()
        recorder.append(motion(32))
        recorder.setPaused(true)
        recorder.append(motion(0))
        recorder.setPaused(false)
        recorder.append(motion(0))
        val s = recorder.snapshot()
        assertEquals(2L, s.total)
        assertTrue(s.records.last().firstButtonObservation)
        assertEquals(0, s.records.last().releasedButtons)
    }

    @Test fun ringDropsOldestAndClearResetsSessionCounters() {
        val recorder = ProbeRecorder(capacity = 2)
        repeat(5) { recorder.append(motion(0).copy(eventTimeMs = it.toLong())) }
        val s = recorder.snapshot()
        assertEquals(listOf(3L, 4L), s.records.map { it.eventTimeMs })
        assertEquals(5L, s.total)
        assertEquals(3L, s.dropped)
        recorder.clear()
        assertEquals(ProbeSnapshot(), recorder.snapshot())
        // Previously returned snapshots must survive a clear/export during further input.
        assertEquals(2, s.records.size)
    }

    @Test fun keyActionsAreNotInterpretedAsMotionButtonChanges() {
        val recorder = ProbeRecorder()
        recorder.append(motion(32))
        recorder.append(motion(0).copy(channel = "key", buttonState = null, keyCode = 280))
        recorder.append(motion(0))
        assertEquals(1L, recorder.snapshot().keyEvents)
        assertEquals(32, recorder.snapshot().records.last().releasedButtons)
    }

    @Test fun focusLossResetsBaselineWithoutDiscardingRecords() {
        val recorder = ProbeRecorder()
        recorder.append(motion(32))
        recorder.resetButtonBaseline()
        recorder.append(motion(0))
        assertEquals(0, recorder.snapshot().records.last().releasedButtons)
        assertEquals(2L, recorder.snapshot().total)
    }
}
