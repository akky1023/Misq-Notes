package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class PenButtonStateTest {
    @Test fun outsideContactConsumesOnlyTheCurrentTemporaryLassoPress() {
        val state = PenButtonState()
        val actions = listOf(PenButtonAction.TEMPORARY_LASSO, PenButtonAction.TEMPORARY_ERASER, PenButtonAction.TEMPORARY_LASSO)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0, actions)
        state.handle(18, "Xiaomi Focus Pen Mouse", 193, true, 0, actions)
        state.beginLassoContact()
        state.handle(18, "Xiaomi Focus Pen Mouse", 92, true, 0, actions)
        state.beginEraserContact()
        state.dismissLasso()
        assertFalse(state.lassoHeld); assertTrue(state.held)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 1, actions)
        assertFalse(state.lassoHeld)
        state.endContact(); assertFalse(state.lassoHeld)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, false, 0, actions)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0, actions)
        assertTrue(state.lassoHeld)
    }

    @Test fun temporaryLassoWaitsForReleaseAndTheEndOfContact() {
        val state = PenButtonState(); val actions = List(3) { PenButtonAction.TEMPORARY_LASSO }
        val calls = mutableListOf<PenButtonAction>()
        state.handle(18, "Xiaomi Focus Pen Mouse", 92, true, 0, actions, calls::add)
        assertTrue(state.lassoHeld); assertFalse(state.held); assertTrue(calls.isEmpty())
        state.beginLassoContact()
        state.handle(18, "Xiaomi Focus Pen Mouse", 92, false, 0, actions)
        assertTrue(state.lassoHeld)
        state.endContact(); assertFalse(state.lassoHeld)
    }
    @Test fun temporaryLassoCanSpanMultipleContactsWhileTheButtonIsHeld() {
        val state = PenButtonState(); val actions = List(3) { PenButtonAction.TEMPORARY_LASSO }
        state.handle(18, "Xiaomi Focus Pen Mouse", 193, true, 0, actions)
        repeat(2) { state.beginLassoContact(); state.endContact(); assertTrue(state.lassoHeld) }
        state.handle(18, "Xiaomi Focus Pen Mouse", 193, false, 0, actions)
        assertFalse(state.lassoHeld)
    }
    @Test fun temporaryLassoReleaseMustMatchDeviceAndDisconnectClearsContactLatch() {
        val state = PenButtonState(); val actions = List(3) { PenButtonAction.TEMPORARY_LASSO }
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0, actions)
        state.beginLassoContact()
        state.handle(19, "Xiaomi Focus Pen Mouse", 93, false, 0, actions)
        state.endContact(); assertTrue(state.lassoHeld)
        state.beginLassoContact()
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, false, 0, actions)
        assertTrue(state.lassoHeld)
        state.deviceRemoved(18); assertFalse(state.lassoHeld)
    }
    @Test fun ignoresOtherKeyboardsAndUnrelatedKeys() {
        val state = PenButtonState()
        assertFalse(state.handle(1, "Keyboard", 93, true, 0))
        assertFalse(state.handle(1, "Xiaomi Focus Pen Mouse", 94, true, 0))
        assertFalse(state.held)
    }
    @Test fun releaseKeepsEraserUntilTheCurrentContactEnds() {
        val state = PenButtonState()
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0)
        state.beginEraserContact()
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, false, 0)
        assertTrue(state.held)
        state.endContact(); assertFalse(state.held)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0)
        state.beginEraserContact(); state.endContact(); assertTrue(state.held)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, false, 0); assertFalse(state.held)
    }
    @Test fun allThreeKeysUseIndependentActionsAndIgnoreRepeatedDowns() {
        val state = PenButtonState(); val calls = mutableListOf<PenButtonAction>()
        val actions = listOf(PenButtonAction.UNDO, PenButtonAction.REDO, PenButtonAction.LASSO)
        PenButton.entries.forEach { key ->
            state.handle(18, "Xiaomi Focus Pen Mouse", key.keyCode, true, 0, actions, calls::add)
            state.handle(18, "Xiaomi Focus Pen Mouse", key.keyCode, true, 1, actions, calls::add)
            state.handle(18, "Xiaomi Focus Pen Mouse", key.keyCode, false, 0, actions, calls::add)
        }
        assertEquals(actions, calls); assertFalse(state.held)
    }
    @Test fun multipleTemporaryButtonsAndDisconnectAfterReleaseDoNotLeaveALatch() {
        val state = PenButtonState(); val actions = List(3) { PenButtonAction.TEMPORARY_ERASER }
        for (key in listOf(92, 193)) state.handle(18, "Xiaomi Focus Pen Mouse", key, true, 0, actions)
        state.beginEraserContact()
        for (key in listOf(92, 193)) state.handle(18, "Xiaomi Focus Pen Mouse", key, false, 0, actions)
        assertTrue(state.held); state.deviceRemoved(18); assertFalse(state.held)
    }
    @Test fun repeatDoesNotToggleAndReleaseMustMatchDevice() {
        val state = PenButtonState()
        assertTrue(state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0))
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 9)
        state.handle(19, "Xiaomi Focus Pen Mouse", 93, false, 0)
        assertTrue(state.held)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, false, 0)
        assertFalse(state.held)
    }
    @Test fun resetAndDisconnectPreventStuckEraser() {
        val state = PenButtonState()
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0)
        state.deviceRemoved(18)
        assertFalse(state.held)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 3)
        assertFalse(state.held)
        state.handle(18, "Xiaomi Focus Pen Mouse", 93, true, 0)
        state.reset()
        assertFalse(state.held)
    }
}
