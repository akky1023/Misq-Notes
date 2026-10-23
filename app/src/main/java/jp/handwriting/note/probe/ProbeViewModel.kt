package jp.handwriting.note.probe

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ProbeViewModel : ViewModel() {
    val recorder = ProbeRecorder()
    private val mutableSnapshot = MutableStateFlow(ProbeSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableExportStatus = MutableStateFlow("")
    val exportStatus = mutableExportStatus.asStateFlow()
    var pendingExport: Pair<ProbeSnapshot, JSONObject>? = null
        private set
    private var exporting = false
    private var dirty = false

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(100)
                if (dirty) {
                    mutableSnapshot.value = recorder.snapshot()
                    dirty = false
                }
            }
        }
    }

    fun record(record: ProbeRecord) { recorder.append(record); dirty = true }
    fun pause() { recorder.setPaused(!recorder.paused); publish() }
    fun clear() { recorder.clear(); publish() }
    fun prepareExport(metadata: JSONObject): Boolean {
        if (exporting || pendingExport != null) return false
        pendingExport = recorder.snapshot() to metadata
        mutableExportStatus.value = "保存先を選んでください"
        return true
    }
    fun export(resolver: ContentResolver, uri: Uri?) {
        val request = pendingExport ?: return
        pendingExport = null
        if (uri == null) { mutableExportStatus.value = "書き出しをキャンセルしました"; return }
        exporting = true
        mutableExportStatus.value = "書き出し中…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = ProbeExport.encode(request.first, request.second)
                    checkNotNull(resolver.openOutputStream(uri, "wt")) { "保存先を開けません" }
                        .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                mutableExportStatus.value = "JSONを書き出しました（${request.first.records.size}件）"
            } catch (e: Exception) {
                mutableExportStatus.value = "書き出し失敗：${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally { exporting = false }
        }
    }
    private fun publish() { mutableSnapshot.value = recorder.snapshot(); dirty = false }
}
