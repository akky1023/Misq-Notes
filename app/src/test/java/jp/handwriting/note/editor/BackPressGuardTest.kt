package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class BackPressGuardTest {
    @Test fun onlyTheSecondConsecutiveBackWithinTwoSecondsLeavesTheNote() {
        val guard = BackPressGuard()
        assertFalse(guard.confirm(0))
        assertTrue(guard.confirm(1000))
        assertFalse(guard.confirm(1100))
        assertTrue(guard.confirm(3100))
    }
    @Test fun aLateSecondBackStartsANewConfirmationWindow() {
        val guard = BackPressGuard()
        assertFalse(guard.confirm(1000))
        assertFalse(guard.confirm(3001))
        assertTrue(guard.confirm(4000))
    }
    @Test fun clockResetAndInvalidIntervalsCannotCauseAnUnexpectedExit() {
        val guard = BackPressGuard()
        assertFalse(guard.confirm(10000))
        assertFalse(guard.confirm(500))
        assertTrue(guard.confirm(501))
        assertThrows(IllegalArgumentException::class.java) { BackPressGuard(0) }
    }
}
