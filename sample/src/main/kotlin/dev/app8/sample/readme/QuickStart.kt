// The README "Quick Start" code, compiled with the sample so the README cannot drift from the
// public API. Keep the two in sync: edit here first, then paste into README.md.
package dev.app8.sample.readme

import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.app8.engine.App8
import dev.app8.engine.App8DataSource
import dev.app8.engine.App8Instance
import dev.app8.engine.App8View

// region 1. Provide content
/**
 * Serves DSL JSON bundled in the app's assets (`assets/app8/app.json`, `screens/<id>.json`, ...).
 * Swap the asset reads for network calls to update the UI without an app release.
 */
class AssetsDataSource(
    private val assets: AssetManager,
    private val root: String = "app8",
) : App8DataSource {
    private fun read(path: String): ByteArray = assets.open("$root/$path").use { it.readBytes() }

    override suspend fun getApp(): ByteArray = read("app.json")
    override suspend fun getStyles(): List<ByteArray> = listOf(read("styles.json"))
    override suspend fun getComponents(): List<ByteArray> = emptyList()
    override suspend fun getScreen(screenId: String): ByteArray = read("screens/$screenId.json")
    override suspend fun getComponent(componentId: String): ByteArray = read("components/$componentId.json")
    override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? = null

    // getDatasource, getAllScreenIds, getTranslations and the stream* methods have default
    // implementations: override them only when you need those features.
}
// endregion

// region 2. Render with Compose
class MainActivity : ComponentActivity() {
    private val app8: App8Instance by lazy { App8.instance(this, AssetsDataSource(assets)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            app8.App(onError = { error -> Log.e("App8", "App8 failed to load", error) })
        }
    }
}
// endregion

// region 2b. Render in a View hierarchy
class ViewHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val view = App8View(this).apply {
            instance = App8.instance(context, AssetsDataSource(assets))
            onError = { error -> Log.e("App8", "App8 failed to load", error) }
        }
        setContentView(view)
    }
}
// endregion

// region 3. Receive events and analytics
/** The host app's own code, stubbed so the README snippet compiles. */
interface Host {
    fun startStripeFlow()
    fun showProfile(name: String?)
}

interface Analytics {
    fun track(name: String, properties: Map<String, Any?>)
}

fun Host.wireEvents(app8: App8Instance, analytics: Analytics) {
    // Host action events from `emit` actions in the DSL.
    app8.eventBus.subscribe { event ->
        when (event.name) {
            "connect.tapped" -> startStripeFlow()
            "user.selected" -> showProfile(event.payload["name"] as? String)
        }
    }
    // Analytics events, author-declared and auto `app8.*`. `properties` is the canonical,
    // fully merged payload: forward it as is.
    app8.analyticsBus.observe { event ->
        analytics.track(event.name, event.properties)
    }
}
// endregion
