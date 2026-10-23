package jp.handwriting.note

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.editor.EditorViewModel
import jp.handwriting.note.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AppAppearanceTest {
    @Test fun sortDirectionLivesInThePopupAndThemesApplyWithoutChangingPaper() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val root = File(instrumentation.targetContext.cacheDir, "appearance-${UUID.randomUUID()}")
        val paper = NotePaper()
        NoteFiles(File(root, "notes")).create("Theme note", paper = paper)
        LibraryPreferencesFile(File(root, "library.hwp")).write(LibraryPreferences(theme = AppThemeMode.LIGHT))
        val store = ViewModelStore()
        lateinit var model: EditorViewModel
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30)
            assertTrue(label, condition())
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), label) }
        }
        fun get(label: String): AccessibilityNodeInfo {
            var result: AccessibilityNodeInfo? = null
            waitFor(label) {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                result = find(automation.rootInActiveWindow, label)
                result != null
            }
            return result!!
        }
        fun click(label: String) {
            var node = get(label)
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue("Click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        fun bounds(label: String) = Rect().also(get(label)::getBoundsInScreen)
        fun selectTheme(mode: AppThemeMode) {
            click("テーマを選択"); click(mode.label)
            waitFor("Theme $mode") { model.library.value.value.theme == mode }
        }
        fun assertAmoledVisible(expected: Boolean) {
            waitFor("AMOLED visible: $expected") {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                expected == (find(automation.rootInActiveWindow, "AMOLED") != null)
            }
        }
        fun assertColorNear(expected: Int, actual: Int) {
            // Screenshot color conversion may differ from the source by one channel step.
            assertEquals(Color.alpha(expected), Color.alpha(actual))
            for (channel in listOf(Color::red, Color::green, Color::blue))
                assertTrue("Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", kotlin.math.abs(channel(expected) - channel(actual)) <= 1)
        }
        fun screenshot(name: String? = null): Bitmap {
            SystemClock.sleep(120)
            return automation.takeScreenshot()!!.also { image ->
                if (name != null) File(instrumentation.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    model = ViewModelProvider(object : ViewModelStoreOwner { override val viewModelStore = store })[EditorViewModel::class.java]
                    model.start(root)
                    activity.setContent { NotebookApp(model, {}, {}, model::closeNote) }
                }
                waitFor("Library ready") { !model.workspace.value.working && model.workspace.value.notes.size == 1 }
                click("並び順：更新日")
                assertTrue(bounds("降順").top < bounds("名前").top)
                click("降順")
                waitFor("Ascending selected") { model.library.value.value.sortFor(null) == NoteSort.UPDATED_ASC }
                assertTrue(bounds("昇順").top < bounds("名前").top)
                click("昇順")
                waitFor("Descending selected") { model.library.value.value.sortFor(null) == NoteSort.UPDATED_DESC }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click("総合設定"); click("外観")
                val settingsBounds = bounds("設定の内容")
                assertTrue(settingsBounds.width() <= 720 * instrumentation.targetContext.resources.displayMetrics.density + 2)
                assertAmoledVisible(false)
                selectTheme(AppThemeMode.SYSTEM)
                assertAmoledVisible(true)
                click("AMOLED")
                waitFor("AMOLED enabled") { model.library.value.value.amoled }
                selectTheme(AppThemeMode.LIGHT)
                assertAmoledVisible(false)
                assertTrue(model.library.value.value.amoled)
                selectTheme(AppThemeMode.SYSTEM)
                assertAmoledVisible(true)
                assertTrue(model.library.value.value.amoled)
                selectTheme(AppThemeMode.DARK)
                for (amoled in listOf(true, false, true)) {
                    if (model.library.value.value.amoled != amoled) click("AMOLED")
                    waitFor("AMOLED $amoled") { model.library.value.value.amoled == amoled }
                    val image = screenshot(if (amoled) "codex-theme-oled.png" else "codex-theme-dark.png")
                    try {
                        val background = image.getPixel(10, image.height / 2)
                        if (amoled) assertEquals(Color.BLACK, background)
                        else assertTrue(Color.red(background) < 40 && background != Color.BLACK)
                    } finally { image.recycle() }
                }
                selectTheme(AppThemeMode.LIGHT)
                val light = screenshot()
                try { assertColorNear(0xFFF1F4F5.toInt(), light.getPixel(10, light.height / 2)) }
                finally { light.recycle() }
                selectTheme(AppThemeMode.DARK)
                click("戻る"); click("ノート設定")
                val preview = bounds("用紙のプレビュー")
                val settingsImage = screenshot("codex-dark-paper-settings.png")
                try {
                    assertEquals(Color.BLACK, settingsImage.getPixel(10, settingsImage.height / 2))
                    assertEquals(Color.BLACK, settingsImage.getPixel(preview.centerX(), preview.bottom + 10))
                    assertColorNear(paper.color, settingsImage.getPixel(preview.centerX(), preview.centerY()))
                } finally { settingsImage.recycle() }
                click("戻る"); click("ペン設定")
                assertTrue(bounds("筆圧と強さを開く").left > settingsBounds.centerX())
                click("筆圧と強さ")
                get("筆圧と太さの通過点式ベジェ曲線。空白をタップして点を追加、点をドラッグして移動、選択点の四角いハンドルで曲がり方を調整、正方形から大きく離すと点を削除")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click("戻る"); click("戻る")
                click("Theme noteの先頭1000×1000")
                waitFor("Note ready") { model.workspace.value.current != null && !model.workspace.value.working }
                val toolbar = bounds("辺と四隅にスナップするツールバー")
                val image = screenshot()
                try {
                    assertColorNear(paper.color, image.getPixel(10, image.height / 2))
                    assertTrue(Color.red(image.getPixel(toolbar.centerX(), toolbar.top + 8)) < 100)
                } finally { image.recycle() }
                assertEquals(paper, model.workspace.value.current!!.paper)
                click("ノート一覧に戻る")
                waitFor("Saved") { model.workspace.value.current == null && !model.workspace.value.working && !model.library.value.saving }
                val saved = LibraryPreferencesFile(File(root, "library.hwp")).read()
                assertEquals(AppThemeMode.DARK, saved.theme)
                assertTrue(saved.amoled)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            root.deleteRecursively()
        }
    }
}
