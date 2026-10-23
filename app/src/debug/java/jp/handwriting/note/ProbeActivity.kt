package jp.handwriting.note

import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import jp.handwriting.note.probe.EventDecoder
import jp.handwriting.note.probe.ProbeScreen
import jp.handwriting.note.probe.ProbeViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Diagnostic Activity, packaged only in debug builds. */
class ProbeActivity : ComponentActivity() {
    private lateinit var model: ProbeViewModel
    private val devices = mutableMapOf<Int, InputDevice>()
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        model.export(applicationContext.contentResolver, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ProbeViewModel::class.java]
        // Use the public Window callback: AndroidX restricts its own key-dispatch override.
        val originalCallback = window.callback
        window.callback = object : Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (!model.recorder.paused) {
                    rememberDevice(event.deviceId)
                    model.record(EventDecoder.key(event))
                }
                return originalCallback.dispatchKeyEvent(event)
            }
        }
        enableEdgeToEdge()
        setContent {
            ProbeScreen(model) {
                if (model.prepareExport(metadata())) {
                    createDocument.launch("focus-pen-${System.currentTimeMillis()}.json")
                }
            }
        }
    }

    // Observe before Compose/View dispatch, without consuming keys or system navigation.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::model.isInitialized && !model.recorder.paused) {
            rememberDevice(event.deviceId)
            model.record(EventDecoder.motion(event, "touch"))
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::model.isInitialized && !model.recorder.paused) {
            rememberDevice(event.deviceId)
            model.record(EventDecoder.motion(event, "generic"))
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::model.isInitialized && !hasFocus) model.recorder.resetButtonBaseline()
    }

    private fun rememberDevice(id: Int) {
        if (id !in devices) InputDevice.getDevice(id)?.let { devices[id] = it }
    }

    private fun metadata() = JSONObject().apply {
        InputDevice.getDeviceIds().forEach(::rememberDevice)
        put("appVersion", BuildConfig.VERSION_NAME)
        put("applicationId", packageName)
        put("exportedAt", Instant.now().toString())
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("androidRelease", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("buildFingerprint", Build.FINGERPRINT)
        put("displayDensity", resources.displayMetrics.density.toDouble())
        put("orientation", resources.configuration.orientation)
        put("scope", "Activity-dispatched input including toolbar; system-consumed input is not observable")
        put("devices", JSONArray().apply {
            devices.values.forEach { d -> put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("descriptor", d.descriptor)
                put("vendorId", d.vendorId)
                put("productId", d.productId)
                put("sources", d.sources)
                put("isExternal", d.isExternal)
                put("motionRanges", JSONArray().apply {
                    d.motionRanges.forEach { r -> put(JSONObject().apply {
                        put("axis", r.axis)
                        put("source", r.source)
                        put("min", r.min.toDouble())
                        put("max", r.max.toDouble())
                        put("flat", r.flat.toDouble())
                        put("fuzz", r.fuzz.toDouble())
                        put("resolution", r.resolution.toDouble())
                    }) }
                })
            }) }
        })
    }
}
