package jp.handwriting.note.editor

import android.app.Activity
import android.os.*
import android.view.FrameMetrics
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/** Opt-in test instrumentation only. Fixed-size frame ring and two rotating 2 MiB logs. */
internal class EnduranceTelemetry(activity: Activity) : AutoCloseable {
    private val thread = HandlerThread("endurance-metrics").apply { start() }
    private val window = activity.window
    private val frames = LongArray(4096)
    private var count = 0
    private var cursor = 0
    private var dropped = 0
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "endurance.jsonl")
    private val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
        synchronized(frames) {
            frames[cursor] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            cursor = (cursor + 1) % frames.size
            count = (count + 1).coerceAtMost(frames.size)
            dropped += lost
        }
    }

    init { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }

    /** Call on the test thread, after a timed UI round trip; PSS is intentionally sampled rarely. */
    fun sample(phase: String, uiDelayMs: Long, loaded: Int) {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val row = JSONObject().put("epochMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("phase", phase)
            .put("uiDelayMs", uiDelayMs).put("loaded", loaded)
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaMaxBytes", runtime.maxMemory()).put("nativeBytes", Debug.getNativeHeapAllocatedSize())
            .put("pssKb", info.totalPss).put("cpuMs", Process.getElapsedCpuTime())
            .put("gc", JSONObject(Debug.getRuntimeStats()))
            .put("thermalStatus", context.getSystemService(PowerManager::class.java).currentThermalStatus)
        synchronized(frames) {
            val sorted = frames.take(count).sorted()
            row.put("frameSamples", count).put("droppedMetrics", dropped)
            if (sorted.isNotEmpty()) for (p in listOf(50, 95, 99))
                row.put("frameP${p}Ms", sorted[(sorted.size * p / 100).coerceAtMost(sorted.lastIndex)] / 1e6)
            count = 0; cursor = 0; dropped = 0
        }
        if (file.length() >= 2 * 1024 * 1024) {
            val previous = File(file.parentFile, "endurance.previous.jsonl")
            check(!previous.exists() || previous.delete())
            check(file.renameTo(previous))
        }
        file.appendText("$row\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nENDURANCE $row\n")
        })
    }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        thread.join(2000)
    }
}
