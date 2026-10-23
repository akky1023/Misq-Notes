package jp.handwriting.note

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.handwriting.note.editor.*
import jp.handwriting.note.storage.AppThemeMode
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun GeneralSettingsScreen(model: EditorViewModel, onBack: () -> Unit) {
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var pressure by rememberSaveable { mutableStateOf(false) }
    val back = { if (section == null) onBack() else section = null }
    BackHandler(onBack = back)
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().semantics { contentDescription = AppMessages.text(R.string.settings_content) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolAction(AppMessages.text(R.string.back), ToolbarIcons.Back, onClick = back)
            Text(when (section) { "appearance" -> AppMessages.text(R.string.appearance); "pen" -> AppMessages.text(R.string.pen_settings); "notes" -> AppMessages.text(R.string.note_defaults); "language" -> AppMessages.text(R.string.language); else -> AppMessages.text(R.string.settings) }, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            if (preferences.saving || library.saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 24.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (section) {
                    "appearance" -> {
                        var menu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(AppMessages.text(R.string.theme), Modifier.weight(1f))
                            Box {
                                TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = AppMessages.text(R.string.choose_theme) }) {
                                    Text(library.value.theme.label)
                                    Icon(ToolbarIcons.Expand, null, Modifier.padding(start = 8.dp))
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    AppThemeMode.entries.forEach { mode ->
                                        DropdownMenuItem({ Text(mode.label) }, { menu = false; model.theme(mode) })
                                    }
                                }
                            }
                        }
                        if (library.value.theme != AppThemeMode.LIGHT) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .toggleable(library.value.amoled, role = Role.Switch, onValueChange = model::amoled)
                                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("AMOLED", Modifier.weight(1f))
                                Switch(library.value.amoled, onCheckedChange = null)
                            }
                        }
                    }
                    "pen" -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(AppMessages.text(R.string.suppress_gestures), Modifier.weight(1f))
                            Switch(library.value.suppressPenGestures, model::suppressPenGestures)
                        }
                        Text(AppMessages.text(R.string.suppress_gestures_help), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { pressure = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(AppMessages.text(R.string.pressure_strength), Modifier.weight(1f))
                            Icon(ToolbarIcons.ChevronRight, AppMessages.text(R.string.open_pressure_strength), Modifier.padding(end = 12.dp))
                        }
                        PenButton.entries.forEach { button ->
                            var menu by remember { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(button.label, Modifier.weight(1f))
                                Box {
                                    TextButton(enabled = !workspace.working, onClick = { menu = true }) { Text(preferences.value.buttonActions[button.ordinal].label) }
                                    DropdownMenu(menu, { menu = false }) {
                                        PenButtonAction.entries.forEach { action ->
                                            DropdownMenuItem({ Text(action.label) }, { menu = false; model.buttonAction(button, action) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "notes" -> {
                        StrokeCacheSettings(library.value.strokeCache, model::strokeCache)
                        HorizontalDivider()
                        StorageSettings(model)
                        HorizontalDivider()
                        Text(AppMessages.text(R.string.new_note_background), style = MaterialTheme.typography.titleLarge)
                        PaperSettings(library.value.paper, model::defaultPaper)
                    }
                    "language" -> LanguageSettings()
                    else -> listOf(Triple("appearance", AppMessages.text(R.string.appearance), ToolbarIcons.Palette), Triple("pen", AppMessages.text(R.string.pen_settings), ToolbarIcons.Pen),
                        Triple("notes", AppMessages.text(R.string.note_defaults), ToolbarIcons.Paper), Triple("language", AppMessages.text(R.string.language), ToolbarIcons.Language)).forEach { (id, title, icon) ->
                        Card(Modifier.fillMaxWidth().clip(CardDefaults.shape).clickable { section = id }) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(icon, null); Text(title, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
                preferences.error?.let { Text(it, color = MaterialTheme.colorScheme.error); ToolAction(AppMessages.text(R.string.retry_save), ToolbarIcons.Redo, !preferences.saving, onClick = model::retryPreferences) }
                library.error?.let { Text(it, color = MaterialTheme.colorScheme.error); ToolAction(AppMessages.text(R.string.retry_save), ToolbarIcons.Redo, !library.saving, onClick = model::retryLibrary) }
            }
            ColumnScrollIndicator(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp))
        }
    }
    }
    if (pressure) {
        val windowSize = LocalWindowInfo.current.containerSize
        val side = with(LocalDensity.current) { minOf(420.dp, windowSize.width.toDp() - 64.dp, windowSize.height.toDp() - 120.dp).coerceAtLeast(120.dp) }
        Dialog(onDismissRequest = { pressure = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.width(side + 32.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AppMessages.text(R.string.pressure_strength), style = MaterialTheme.typography.titleLarge)
                    PressureCurveSettings(preferences.value.pressureCurve, !workspace.working, model::pressureCurve, graphOnly = true)
                }
            }
        }
    }
}

@Composable
private fun StrokeCacheSettings(current: StrokeCacheMode, onChange: (StrokeCacheMode) -> Unit) {
    Text(AppMessages.text(R.string.stroke_cache), style = MaterialTheme.typography.titleLarge)
    val labels = listOf(R.string.stroke_cache_limited, R.string.stroke_cache_visible,
        R.string.stroke_cache_nearby, R.string.stroke_cache_all)
    StrokeCacheMode.entries.forEach { mode ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton) { onChange(mode) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(current == mode, onClick = null)
            Text(AppMessages.text(labels[mode.ordinal]), Modifier.padding(start = 8.dp))
        }
    }
    Text(AppMessages.text(R.string.stroke_cache_help), style = MaterialTheme.typography.bodySmall)
}
