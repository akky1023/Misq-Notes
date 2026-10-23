package jp.handwriting.note.storage

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

enum class AppThemeMode(private val labelResource: Int) {
    SYSTEM(R.string.follow_device), LIGHT(R.string.theme_light), DARK(R.string.theme_dark);
    val label get() = AppMessages.text(labelResource)
    fun isDark(systemDark: Boolean) = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}
