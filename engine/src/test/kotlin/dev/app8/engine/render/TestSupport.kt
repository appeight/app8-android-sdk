package dev.app8.engine.render

import android.content.ContextWrapper
import dev.app8.engine.App8Config
import dev.app8.engine.App8DataSource
import dev.app8.engine.actions.AlertRequest
import dev.app8.engine.actions.HostEffects
import dev.app8.engine.actions.Navigator
import dev.app8.engine.core.model.Action
import dev.app8.engine.runtime.App8Runtime
import kotlinx.coroutines.runBlocking

/** An app held in memory: `app.json`, screens by id, one stylesheet. */
internal class MemoryDataSource(
    private val app: String,
    private val screens: Map<String, String>,
    private val styles: String = "[]",
    private val datasources: Map<String, String> = emptyMap(),
) : App8DataSource {
    override suspend fun getApp() = app.encodeToByteArray()
    override suspend fun getStyles() = listOf(styles.encodeToByteArray())
    override suspend fun getComponents() = emptyList<ByteArray>()
    override suspend fun getScreen(screenId: String) = screens.getValue(screenId).encodeToByteArray()
    override suspend fun getComponent(componentId: String) = error("none")
    override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? = null
    override suspend fun getDatasource(screenId: String, datasourceId: String) = datasources.getValue(datasourceId).encodeToByteArray()
}

internal class RecordingNavigator : Navigator {
    val calls = mutableListOf<String>()
    val params = mutableListOf<Map<String, Any?>>()
    override fun push(screenId: String, params: Map<String, Any?>, transition: dev.app8.engine.core.model.ScreenTransition?) { calls += "push:$screenId"; this.params += params }
    override fun pop(toRoot: Boolean) { calls += "pop:$toRoot" }
    override fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?, transition: dev.app8.engine.core.model.ScreenTransition?) {
        calls += "present:$screenId:$presentation:${detents?.size ?: 0}"
    }
    override fun dismiss() { calls += "dismiss" }
    override fun selectTab(index: Int?, id: String?) { calls += "tab:$index:$id" }
    override fun completeFlow(destination: String) { calls += "completeFlow:$destination" }
}

internal class RecordingHost : HostEffects {
    val calls = mutableListOf<String>()
    var lastAlert: AlertRequest? = null
    override fun openUrl(url: String, presentation: Action.URLPresentation) { calls += "url:$url:$presentation" }
    override fun haptic(style: Action.HapticStyle) { calls += "haptic:$style" }
    override fun alert(request: AlertRequest) { calls += "alert:${request.title}"; lastAlert = request }
}

internal const val APP_JSON = """{"title":"T","navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"s"}]},
  "variables":{"cartCount":{"type":"number","initialValue":2}}}"""

internal fun runtime(screens: Map<String, String>, app: String = APP_JSON, datasources: Map<String, String> = emptyMap()) =
    App8Runtime(ContextWrapper(null), MemoryDataSource(app, screens, datasources = datasources), App8Config())

internal fun session(rt: App8Runtime, screenId: String = "s", params: Map<String, Any?> = emptyMap()): ScreenSession = runBlocking {
    rt.ensureReady()
    ScreenSession.create(rt, Scope(rt.appStore, null), screenId, params)
}
