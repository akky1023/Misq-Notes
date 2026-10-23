package jp.handwriting.note.editor

internal class StylusNavigationGuard {
    private var hovering = false
    private var contacting = false
    val active get() = hovering || contacting
    fun hover(near: Boolean) { hovering = near }
    fun contact(down: Boolean) { contacting = down }
    fun reset() { hovering = false; contacting = false }
}
