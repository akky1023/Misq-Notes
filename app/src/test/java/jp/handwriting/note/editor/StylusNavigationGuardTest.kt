package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class StylusNavigationGuardTest {
    @Test fun hoverExitBeforePenUpDoesNotReleaseTheNavigationGuard() {
        val guard = StylusNavigationGuard()
        guard.hover(true); assertTrue(guard.active)
        guard.contact(true); guard.hover(false); assertTrue(guard.active)
        guard.contact(false); assertFalse(guard.active)
    }
    @Test fun proximityContinuesAfterContactAndLifecycleResetAlwaysReleasesIt() {
        val guard = StylusNavigationGuard()
        guard.hover(true); guard.contact(true); guard.contact(false); assertTrue(guard.active)
        guard.reset(); assertFalse(guard.active)
        guard.contact(true); guard.reset(); assertFalse(guard.active)
        val back = BackPressGuard(); assertFalse(back.confirm(100))
        back.reset(); assertFalse(back.confirm(101))
    }
}
