package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

enum class PenButton(private val labelResource: Int, val keyCode: Int) {
    LOWER(R.string.lower_button, 93), UPPER(R.string.upper_button, 92), INDEPENDENT(R.string.independent_button, 193);
    val label get() = AppMessages.text(labelResource)
}
enum class PenButtonAction(private val labelResource: Int) {
    NONE(R.string.action_none), TEMPORARY_ERASER(R.string.temporary_eraser), PEN(R.string.pen), HIGHLIGHTER(R.string.highlighter),
    ERASER(R.string.eraser), LASSO(R.string.lasso), UNDO(R.string.undo), REDO(R.string.redo), FIT_WIDTH(R.string.reset_zoom),
    TEMPORARY_LASSO(R.string.temporary_lasso);
    val label get() = AppMessages.text(labelResource)
}

/** Measured Focus Pen keys. A temporary eraser stays latched until this contact ends. */
class PenButtonState {
    companion object {
        val defaults = listOf(PenButtonAction.TEMPORARY_ERASER, PenButtonAction.NONE, PenButtonAction.NONE)
    }
    private data class Press(val device: Int, val action: PenButtonAction)
    private val pressed = mutableMapOf<PenButton, Press>()
    private var contactEraser = false
    private var contactDevice: Int? = null
    private var contactLasso = false
    private var lassoDevice: Int? = null
    val held get() = contactEraser || pressed.values.any { it.action == PenButtonAction.TEMPORARY_ERASER }
    val lassoHeld get() = contactLasso || pressed.values.any { it.action == PenButtonAction.TEMPORARY_LASSO }
    fun handle(device: Int, deviceName: String?, keyCode: Int, down: Boolean, repeat: Int,
        actions: List<PenButtonAction> = defaults, onAction: (PenButtonAction) -> Unit = {}): Boolean {
        if (deviceName != "Xiaomi Focus Pen Mouse") return false
        val button = PenButton.entries.firstOrNull { it.keyCode == keyCode } ?: return false
        if (down && repeat == 0 && button !in pressed) {
            val action = actions[button.ordinal]
            pressed[button] = Press(device, action)
            if (action !in listOf(PenButtonAction.NONE, PenButtonAction.TEMPORARY_ERASER, PenButtonAction.TEMPORARY_LASSO)) onAction(action)
        }
        if (!down && pressed[button]?.device == device) pressed.remove(button)
        return true
    }
    fun beginEraserContact() {
        if (held) { contactEraser = true; contactDevice = pressed.values.firstOrNull { it.action == PenButtonAction.TEMPORARY_ERASER }?.device }
    }
    fun beginLassoContact() {
        if (lassoHeld) { contactLasso = true; lassoDevice = pressed.values.firstOrNull { it.action == PenButtonAction.TEMPORARY_LASSO }?.device }
    }
    /** An outside contact resumes the chosen tool, even if the shortcut is still pressed. */
    fun dismissLasso() {
        contactLasso = false; lassoDevice = null
        pressed.replaceAll { _, press ->
            if (press.action == PenButtonAction.TEMPORARY_LASSO) press.copy(action = PenButtonAction.NONE) else press
        }
    }
    fun endContact() { contactEraser = false; contactDevice = null; contactLasso = false; lassoDevice = null }
    fun deviceRemoved(id: Int) {
        if (contactDevice == id || lassoDevice == id || pressed.values.any { it.device == id }) reset()
    }
    fun reset() { pressed.clear(); endContact() }
}
