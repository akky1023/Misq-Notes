package jp.handwriting.note.editor

/** A deliberate second system-back within the interval confirms leaving the note. */
internal class BackPressGuard(private val intervalMillis: Long = 2000) {
    private var firstPress: Long? = null
    init { require(intervalMillis > 0) }
    fun reset() { firstPress = null }
    fun confirm(nowMillis: Long): Boolean {
        val first = firstPress
        if (first != null && nowMillis >= first && nowMillis - first <= intervalMillis) {
            firstPress = null
            return true
        }
        firstPress = nowMillis
        return false
    }
}
