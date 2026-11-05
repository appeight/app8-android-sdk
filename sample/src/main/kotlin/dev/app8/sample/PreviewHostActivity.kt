package dev.app8.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.lifecycleScope
import dev.app8.engine.App8
import dev.app8.engine.App8Instance
import dev.app8.engine.style.App8TextScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The Android twin of app8-ios `app8-preview-host`: watches `files/jobs/` (external app
 * storage) for `<jobId>.json` envelopes, renders each with a fresh [App8Instance], waits for
 * layout and images to settle, then writes `output/<jobId>.ready` (and `<jobId>.diag.json`
 * with the engine's warnings). `scripts/render.sh` drives it and takes the screenshot.
 *
 * `adb shell am start -n dev.app8.sample/.PreviewHostActivity`
 */
class PreviewHostActivity : ComponentActivity() {
    private var current by mutableStateOf<Pair<String, App8Instance>?>(null)
    private val jobsDir by lazy { File(getExternalFilesDir(null), "jobs").apply { mkdirs() } }
    private val outputDir by lazy { File(getExternalFilesDir(null), "output").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val job = current
            if (job == null) Box(Modifier.fillMaxSize()) else key(job.first) {
                val (_, instance) = job
                if (pendingScreen != null) instance.Screen(pendingScreen!!, pendingParams) else instance.App(onError = { Log.e(TAG, "render failed", it) })
            }
        }
        // Created by the app (not adb) so it owns them; render.sh pushes assets under assets/.
        listOf(jobsDir, outputDir, File(getExternalFilesDir(null), "assets")).forEach { it.mkdirs() }
        Log.i(TAG, "watching ${jobsDir.absolutePath}")
        lifecycleScope.launch { pollLoop() }
    }

    private var pendingScreen: String? = null
    private var pendingParams: Map<String, Any?> = emptyMap()

    private suspend fun pollLoop() {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                jobsDir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".lock.json") }?.minByOrNull { it.lastModified() }
            }
            if (next == null) {
                delay(150)
                continue
            }
            process(next)
        }
    }

    private suspend fun process(file: File) {
        val jobId = file.name.removeSuffix(".json")
        val locked = File(jobsDir, "$jobId.lock.json")
        if (!file.renameTo(locked)) return
        val started = System.currentTimeMillis()
        try {
            val envelope = PreviewEnvelope.parse(withContext(Dispatchers.IO) { locked.readText() })
            val instance = App8.instance(this, InMemoryPreviewDataSource(envelope))
            // iOS parity for the conformance report: DSL text ignores the font-size setting.
            instance.textScaling = App8TextScaling.Fixed
            // Like iOS startApp(): the envelope's app.json already starts at the target screen.
            pendingScreen = null
            pendingParams = envelope.params
            current = jobId to instance
            awaitIdle(instance)
            // Like the iOS host's fixed settle: let variable-driven animations (≤ 1 s) finish.
            delay((MIN_SETTLE_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            val warnings = instance.diagnostics()
            write("$jobId.diag.json", JsonObject(mapOf("warnings" to JsonArray(warnings.map { JsonPrimitive(it) }))).toString())
            write("$jobId.ready", JsonObject(mapOf("jobId" to JsonPrimitive(jobId), "screenId" to JsonPrimitive(envelope.screenId))).toString())
            waitConsumed(File(outputDir, "$jobId.ready"))
            Log.i(TAG, "rendered '$jobId' (${envelope.screenId}) in ${System.currentTimeMillis() - started}ms")
        } catch (e: Exception) {
            Log.e(TAG, "job '$jobId' failed", e)
            write("$jobId.error", (e.message ?: e.toString()).take(500))
        } finally {
            locked.delete()
        }
    }

    /** Two frames, then no outstanding loads for 300 ms (10 s cap). */
    private suspend fun awaitIdle(instance: App8Instance) {
        withTimeoutOrNull(10_000) { withContext(AndroidUiDispatcher.Main) {
            withFrameNanos { }
            withFrameNanos { }
            var quietSince = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (instance.pendingWork.value == 0) {
                    if (quietSince == 0L) quietSince = now
                    if (now - quietSince >= 300) break
                } else {
                    quietSince = 0L
                }
                withFrameNanos { }
            }
            // Let the last image fade/draw.
            delay(120)
        } }
    }

    private suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        val tmp = File(outputDir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(outputDir, name))
    }

    private suspend fun waitConsumed(ready: File) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && ready.exists()) delay(50)
    }

    private companion object {
        const val TAG = "App8Preview"
        const val MIN_SETTLE_MS = 1500L
    }
}
