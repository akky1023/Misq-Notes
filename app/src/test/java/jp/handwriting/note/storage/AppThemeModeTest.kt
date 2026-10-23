package jp.handwriting.note.storage

import org.junit.Assert.*
import org.junit.Test

class AppThemeModeTest {
    @Test fun onlySystemModeFollowsTheDeviceAndOledIsAlwaysDark() {
        for (dark in listOf(false, true)) {
            assertEquals(dark, AppThemeMode.SYSTEM.isDark(dark))
            assertFalse(AppThemeMode.LIGHT.isDark(dark))
            assertTrue(AppThemeMode.DARK.isDark(dark))
        }
    }
}
