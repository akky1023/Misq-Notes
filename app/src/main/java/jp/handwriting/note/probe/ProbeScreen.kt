package jp.handwriting.note.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun ProbeScreen(model: ProbeViewModel, onExport: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val exportStatus by model.exportStatus.collectAsStateWithLifecycle()
    var showClear by remember { mutableStateOf(false) }
    var signalOnly by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ProbeCanvasView?>(null) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF245E70))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF1F4F5)) {
            Column(Modifier.safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Focus Pen 入力検証", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::pause) { Text(if (snapshot.paused) "記録再開" else "記録を一時停止") }
                    OutlinedButton(onClick = onExport) { Text("JSON保存") }
                    TextButton(onClick = { showClear = true }) { Text("クリア") }
                }
                Text(
                    "${if (snapshot.paused) "停止中" else "記録中"} · 計${snapshot.total}件 · 保持${snapshot.records.size}件 · 破棄${snapshot.dropped}件",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (exportStatus.isNotEmpty()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.weight(1f)) {
                    if (maxWidth >= 840.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PreviewPane(Modifier.weight(1.2f).fillMaxHeight()) { preview = it }
                            Diagnostics(snapshot, signalOnly, { signalOnly = it }, Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PreviewPane(Modifier.weight(1f).fillMaxWidth()) { preview = it }
                            Diagnostics(snapshot, signalOnly, { signalOnly = it }, Modifier.weight(1.2f).fillMaxWidth())
                        }
                    }
                }
                Text("ログはメモリ内のみ。終了前にJSON保存してください。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showClear) AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("ログと試し書きを消去") },
            text = { Text("未保存の記録を消去して、新しい検証を始めます。") },
            confirmButton = { TextButton(onClick = {
                model.clear(); preview?.clear(); showClear = false
            }) { Text("消去") } },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun PreviewPane(modifier: Modifier, onView: (ProbeCanvasView) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ここで試し書き・ホバー・横ボタン操作", style = MaterialTheme.typography.titleSmall)
        Text("指は描画対象外。線は入力確認用です。", style = MaterialTheme.typography.bodySmall)
        AndroidView(
            factory = { ProbeCanvasView(it).also(onView) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun Diagnostics(s: ProbeSnapshot, signalOnly: Boolean, onFilter: (Boolean) -> Unit, modifier: Modifier) {
    val latest = s.records.lastOrNull { r -> r.pointers.any { it.toolType == 2 || it.toolType == 4 } }
        ?: s.records.lastOrNull()
    val visible = s.records.asReversed().asSequence().filter {
        !signalOnly || it.channel == "key" || it.pressedButtons != 0 || it.releasedButtons != 0 ||
            it.actionButton?.let { b -> b != 0 } == true || it.buttonState?.let { b -> b != 0 } == true ||
            (it.channel != "key" && it.action in 11..12)
    }.take(60).toList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ボタン信号 ${s.buttonSignals}件 / キー ${s.keyEvents}件", style = MaterialTheme.typography.titleSmall)
        Text("信号の有無だけでFocus Pen対応の可否は確定しません。", style = MaterialTheme.typography.bodySmall)
        Row {
            Checkbox(checked = signalOnly, onCheckedChange = onFilter)
            TextButton(onClick = { onFilter(!signalOnly) }) { Text("ボタン・キーのみ表示") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("最新のペン入力（未取得時は最新イベント）", style = MaterialTheme.typography.labelMedium)
                Text(latest?.let(::details) ?: "まだ入力がありません。ペンを近づけるか、試し書きしてください。",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            item { Text("イベント履歴 · 新しい順（最大60件）", style = MaterialTheme.typography.labelMedium) }
            items(visible) { r ->
                Text(details(r), Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

private fun hex(value: Int?) = value?.let { "0x${it.toUInt().toString(16)}" } ?: "—"
private fun number(value: Float) = String.format(Locale.US, "%.3f", value)
private fun details(r: ProbeRecord) = buildString {
    append("${r.channel} ${r.actionName} (${r.action}) index=${r.actionIndex}\n")
    append("t=${r.eventTimeMs}ms recv=${r.receivedUptimeMs}ms\n")
    append("device=${r.deviceId} source=${hex(r.source)}\n")
    if (r.channel == "key") {
        append("${r.keyName} code=${r.keyCode} scan=${r.scanCode} repeat=${r.repeatCount}")
    } else {
        append("buttons=${hex(r.buttonState)} actionButton=${hex(r.actionButton)}\n")
        append("押下=${hex(r.pressedButtons)} 解放=${hex(r.releasedButtons)}")
        if (r.firstButtonObservation) append(" 初回観測")
        r.pointers.forEach {
            append("\nid=${it.id} ${it.toolName}(${it.toolType}) x=${number(it.x)} y=${number(it.y)} p=${number(it.pressure)}")
        }
        append("\nhistory=${r.history.size} omitted=${r.omittedHistory} flags=${hex(r.flags)}")
    }
}
