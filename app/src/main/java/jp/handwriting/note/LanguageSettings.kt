package jp.handwriting.note

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.handwriting.note.editor.ToolbarIcons

@Composable
internal fun LanguageSettings() {
    val context = LocalContext.current
    val selected = AppLanguages.current(context)
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun label(language: AppLanguage) = when (language) {
        AppLanguage.SYSTEM -> AppMessages.text(R.string.follow_device)
        AppLanguage.JAPANESE -> AppMessages.text(R.string.language_japanese)
        AppLanguage.ENGLISH -> "English"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(AppMessages.text(R.string.language), Modifier.weight(1f))
        Box {
            TextButton(onClick = { menu = true }) {
                Text(label(selected)); Icon(ToolbarIcons.Expand, null, Modifier.padding(start = 8.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                AppLanguage.entries.forEach { language -> DropdownMenuItem({ Text(label(language)) }, {
                    menu = false
                    runCatching { AppLanguages.select(context, language) }.onFailure { error = AppMessages.text(R.string.language_change_failed, it.localizedMessage) }
                }) }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
