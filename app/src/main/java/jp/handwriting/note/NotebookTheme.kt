package jp.handwriting.note

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import jp.handwriting.note.storage.AppThemeMode

internal val LocalNotebookDark = compositionLocalOf { false }

internal fun notebookColors(mode: AppThemeMode, systemDark: Boolean, amoled: Boolean = false): ColorScheme {
    if (!mode.isDark(systemDark)) return lightColorScheme(primary = Color(0xFF245E70),
        background = Color(0xFFF1F4F5))
    val dark = darkColorScheme(
        primary = Color(0xFF96D3E4), onPrimary = Color(0xFF003641), primaryContainer = Color(0xFF164D5B), onPrimaryContainer = Color(0xFFB5EBFB),
        secondary = Color(0xFFB3CBD2), onSecondary = Color(0xFF1E343A), secondaryContainer = Color(0xFF354B52), onSecondaryContainer = Color(0xFFCFE7EE),
        background = Color(0xFF111719), onBackground = Color(0xFFE0E3E5),
        surface = Color(0xFF111719), onSurface = Color(0xFFE0E3E5), surfaceVariant = Color(0xFF3F484C), onSurfaceVariant = Color(0xFFBEC8CD),
        surfaceContainerLowest = Color(0xFF0C1214), surfaceContainerLow = Color(0xFF191F21), surfaceContainer = Color(0xFF1D2325),
        surfaceContainerHigh = Color(0xFF272D2F), surfaceContainerHighest = Color(0xFF32383A),
        surfaceDim = Color(0xFF111719), surfaceBright = Color(0xFF363C3E), surfaceTint = Color(0xFF96D3E4),
        outline = Color(0xFF889398), outlineVariant = Color(0xFF3F484C),
    )
    return if (amoled) dark.copy(background = Color.Black, surface = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080C0E), surfaceContainer = Color(0xFF101618),
        surfaceDim = Color.Black) else dark
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun NotebookTheme(mode: AppThemeMode, amoled: Boolean = false, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = mode.isDark(systemDark)
    val view = LocalView.current
    SideEffect {
        view.context.activity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalNotebookDark provides dark) {
        MaterialTheme(colorScheme = notebookColors(mode, systemDark, amoled), content = content)
    }
}
