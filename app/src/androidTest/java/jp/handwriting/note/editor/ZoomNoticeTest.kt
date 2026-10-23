package jp.handwriting.note.editor

import android.os.SystemClock
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.MainActivity
import org.junit.Assert.*
import org.junit.Test

class ZoomNoticeTest {
    @Test fun centerNoticeExpiresAfterZoomAndOneHundredPercentHasNoCornerLabel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        lateinit var model: EditorViewModel
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // An isolated editor has no repository and never changes the user's notes.
                model = EditorViewModel()
                activity.setContent { EditorScreen(model, {}, {}, {}) }
            }
            instrumentation.waitForIdleSync()
            fun matches(node: AccessibilityNodeInfo?, label: String): Boolean {
                if (node == null) return false
                if (!node.refresh()) return false
                if (node.contentDescription?.contains(label) == true) return true
                return (0 until node.childCount).any { matches(node.getChild(it), label) }
            }
            fun hasLabel(label: String): Boolean {
                // A removed Compose semantics node can remain in UiAutomation's cache.
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                return matches(automation.rootInActiveWindow, label)
            }
            val readyDeadline = SystemClock.uptimeMillis() + 5_000
            while (!hasLabel("ノート一覧に戻る") && SystemClock.uptimeMillis() < readyDeadline) SystemClock.sleep(20)
            assertTrue("Editor should finish its initial composition: ${automation.rootInActiveWindow}", hasLabel("ノート一覧に戻る"))
            fun noticeVisible() = hasLabel("拡大率を一時表示")
            assertFalse(noticeVisible())
            assertFalse(hasLabel("拡大率 100%"))
            scenario.onActivity { model.transformViewport(200f, 200f, 200f, 200f, 2f); model.updateViewportOverlay() }
            val deadline = SystemClock.uptimeMillis() + 400
            while (!noticeVisible() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("Zoom notice should appear", noticeVisible())
            SystemClock.sleep(500)
            val expiryDeadline = SystemClock.uptimeMillis() + 500
            while (noticeVisible() && SystemClock.uptimeMillis() < expiryDeadline) SystemClock.sleep(20)
            assertFalse("Zoom notice should expire after 500ms, fade-out and accessibility delivery", noticeVisible())
            assertEquals(200, model.zoomPercent.value)
            scenario.onActivity { model.fitWidth() }
            SystemClock.sleep(1_000)
            assertFalse(noticeVisible())
            assertFalse(hasLabel("拡大率 100%"))
        }
    }
}
