package jp.handwriting.note

import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Window
import android.view.MotionEvent
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import jp.handwriting.note.storage.PreMigrationBackup
import jp.handwriting.note.editor.EditorViewModel
import jp.handwriting.note.editor.InkCanvasView
import jp.handwriting.note.editor.StylusNavigationGuard

open class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLanguages.wrap(newBase)) }
    private lateinit var model: EditorViewModel
    private var canvas: InkCanvasView? = null
    private val inputManager by lazy { getSystemService(InputManager::class.java) }
    private val penNavigation = StylusNavigationGuard()
    private var penY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppMessages.configure(this)
        model = ViewModelProvider(this)[EditorViewModel::class.java]
        startNotebook(model)
        val delegate = window.callback
        window.callback = object : Window.Callback by delegate {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                trackPenNavigation(event)
                return delegate.dispatchGenericMotionEvent(event)
            }
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                trackPenNavigation(event)
                if (canvas?.finishSpaceBeforeTouch(event) { delegate.dispatchTouchEvent(it) } == true) return true
                return delegate.dispatchTouchEvent(event)
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    val handled = model.handlePenButton(
                        event.deviceId, InputDevice.getDevice(event.deviceId)?.name, event.keyCode,
                        event.action == KeyEvent.ACTION_DOWN, event.repeatCount,
                    )
                    if (handled) { model.buttonChanged(); return true }
                }
                return delegate.dispatchKeyEvent(event)
            }
        }
        enableEdgeToEdge()
        setContent {
            val workspace by model.workspace.collectAsStateWithLifecycle()
            val library by model.library.collectAsStateWithLifecycle()
            DisposableEffect(workspace.current != null, library.value.suppressPenGestures) {
                if (workspace.current == null) resetPenNavigation()
                updatePenGestureExclusion()
                onDispose { window.decorView.systemGestureExclusionRects = emptyList() }
            }
            DisposableEffect(workspace.current != null) {
                updateStatusBar()
                onDispose { WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.statusBars()) }
            }
            NotebookApp(model, onCanvas = { canvas = it }, onCanvasDisposed = { if (canvas === it) canvas = null }, onNotes = {
                canvas?.settleForPause()
                model.closeNote()
            })
        }
    }
    protected open fun startNotebook(model: EditorViewModel) {
        if (intent.action == "net.neomenia.misqnotes.BACKUP_BEFORE_MISQ" && packageName == "net.neomenia.misqnotes") {
            // This explicit maintenance launch must not open, migrate, or synchronize the library.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    PreMigrationBackup.ensure(this@MainActivity, "before-misq-v2.zip")
                    PreMigrationBackup.copyForAdb(this@MainActivity)
                }
                finish()
            }
            return
        }
        model.configureStorage(this)
        model.start(filesDir)
    }
    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(this, null)
    }
    override fun onPause() {
        canvas?.settleForPause()
        if (::model.isInitialized) model.resetButton()
        resetPenNavigation()
        super.onPause()
    }
    override fun onStop() {
        inputManager.unregisterInputDeviceListener(this)
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::model.isInitialized && hasFocus) updateStatusBar()
        if (::model.isInitialized && !hasFocus) {
            // A focusable canvas popup takes focus from this window. Settle input
            // without dismissing that popup; onPause still closes it when leaving.
            canvas?.settleForPause(dismissMenu = false)
            model.resetButton()
            resetPenNavigation()
        }
    }
    private fun updateStatusBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (model.workspace.value.current != null) controller.hide(WindowInsetsCompat.Type.statusBars())
        else controller.show(WindowInsetsCompat.Type.statusBars())
    }
    private fun trackPenNavigation(event: MotionEvent) {
        val index = (0 until event.pointerCount).firstOrNull {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        } ?: return
        penY = event.getRawY(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> penNavigation.hover(true)
            MotionEvent.ACTION_HOVER_EXIT -> penNavigation.hover(false)
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> penNavigation.contact(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> penNavigation.contact(false)
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == index) penNavigation.contact(false)
        }
        model.penNavigationActive(penNavigation.active)
        updatePenGestureExclusion()
    }
    private fun updatePenGestureExclusion() {
        val view = window.decorView
        if (!::model.isInitialized || !penNavigation.active || !model.library.value.value.suppressPenGestures || model.workspace.value.current == null) {
            view.systemGestureExclusionRects = emptyList(); return
        }
        val density = resources.displayMetrics.density
        val height = (160 * density).toInt().coerceAtMost(view.height)
        val top = (penY.toInt() - height / 2).coerceIn(0, (view.height - height).coerceAtLeast(0))
        val edge = (32 * density).toInt().coerceAtMost(view.width)
        view.systemGestureExclusionRects = listOf(Rect(0, top, edge, top + height), Rect(view.width - edge, top, view.width, top + height))
    }
    private fun resetPenNavigation() {
        penNavigation.reset()
        if (::model.isInitialized) model.penNavigationActive(false)
        window.decorView.systemGestureExclusionRects = emptyList()
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        resetPenNavigation()
        canvas?.cancelGesture()
        model.penButton.deviceRemoved(deviceId)
        model.buttonChanged()
    }
    override fun onInputDeviceChanged(deviceId: Int) {
        onInputDeviceRemoved(deviceId)
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
}
