package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CompactColorDialogTest {
    @Test fun compactPickerKeepsInputsBesideTheSquareAndAcceptsRgbaHex() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val result = AtomicReference<Int?>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var shown by remember { mutableStateOf(true) }
                MaterialTheme { CompositionLocalProvider(LocalColorPreviewBackground provides Color(0xFFFFEEDD)) {
                    if (shown) RgbColorDialog(0x8012ABEF.toInt(), PaletteUi(), true, { result.set(it) }, {}, {}, {}, allowAlpha = true) { shown = false }
                } }
            } }
            fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
                if (node == null || !node.refresh()) return null
                if (node.contentDescription?.toString() == label || (label !in listOf("R", "G", "B", "A", "HEX") && node.text?.toString() == label)) return node
                return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label) }
            }
            fun get(label: String): AccessibilityNodeInfo {
                val deadline = SystemClock.uptimeMillis() + 5_000
                do {
                    if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                    find(automation.rootInActiveWindow, label)?.let { return it }
                    SystemClock.sleep(20)
                } while (SystemClock.uptimeMillis() < deadline)
                error("Missing $label")
            }
            val square = Rect().also(get("彩度と明るさのカラーピッカー")::getBoundsInScreen)
            val preview = Rect().also(get("色のプレビュー")::getBoundsInScreen)
            val hue = Rect().also(get("色相")::getBoundsInScreen)
            assertTrue(kotlin.math.abs(square.width() - square.height()) <= 2)
            assertTrue(preview.left > square.right); assertTrue(hue.top >= square.bottom)
            assertTrue(get("HEX").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "#33669940")
            }))
            instrumentation.waitForIdleSync(); SystemClock.sleep(100)
            assertEquals("51", get("R").text.toString()); assertEquals("64", get("A").text.toString())
            automation.takeScreenshot()?.let { bitmap ->
                try { File(instrumentation.targetContext.cacheDir, "codex-compact-color.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            var apply = get("適用")
            while (!apply.isClickable && apply.parent != null) apply = apply.parent
            assertTrue(apply.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (result.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertEquals(0x40336699, result.get())
        }
    }
}
