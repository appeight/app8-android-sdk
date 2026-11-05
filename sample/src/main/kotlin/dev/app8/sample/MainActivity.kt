package dev.app8.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.app8.engine.App8
import dev.app8.engine.App8Instance
import dev.app8.engine.events.App8AnalyticsEvent

/**
 * Runs the Facetheory demo app (shoppu-web `packages/app-dsl/demo/facetheory`) from the sample's
 * assets, or another bundle under `assets/` named by the `app` extra (`longlist`: the
 * benchmark's long list fixture).
 */
class MainActivity : ComponentActivity() {
    private var reportedDrawn = false

    private val instance: App8Instance by lazy {
        val app = intent.getStringExtra(EXTRA_APP) ?: "facetheory"
        App8.instance(this, AssetAppDataSource(assets, app)).also { i ->
            i.eventBus.subscribe { Log.i("App8Sample", "event ${it.name} ${it.payload}") }
            i.analyticsBus.observe {
                Log.i("App8Sample", "analytics ${it.name} ${it.properties}")
                // The first DSL screen is on screen: time to full display (startup benchmarks).
                if (it.name == App8AnalyticsEvent.Auto.SCREEN_APPEARED && !reportedDrawn) {
                    reportedDrawn = true
                    runOnUiThread { reportFullyDrawn() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // DSL ids are test tags; expose them as resource ids for UiAutomator (macrobenchmarks).
            instance.App(Modifier.semantics { testTagsAsResourceId = true }, onError = { Log.e("App8Sample", "app failed", it) })
        }
    }

    companion object {
        const val EXTRA_APP: String = "app"
    }
}
